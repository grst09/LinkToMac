//! Spawns and talks to the Node "bridge" process (`desktop-app/whatsapp-bridge/index.js`)
//! that runs Baileys — the open-source client for WhatsApp's multi-device "linked device"
//! protocol (the same one web.whatsapp.com and WhatsApp Desktop speak). Communication is
//! line-delimited JSON over the child's stdio: events flow out on stdout, commands go in on
//! stdin, and each command gets a matching `{"type":"ack","id":...}` reply correlated via
//! `pending` below.
//!
//! Dev-only path resolution: the bridge script is found via `CARGO_MANIFEST_DIR` (baked in at
//! compile time), which only makes sense because this app isn't distributed to other machines
//! today — see the plan's "Node process strategy" note. A real distributable build would need
//! a proper Tauri sidecar (a compiled binary bundled via `externalBin`) instead.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use serde_json::{json, Value};
use tauri::{AppHandle, Emitter};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;
use tokio::sync::{oneshot, Mutex};

use super::{LinkStatus, WaChat, WaMediaResult, WaMessage, WaReceiptUpdate};
use crate::net::server::AppState;

pub struct BridgeHandle {
    child: Mutex<CommandChild>,
    pending: Arc<Mutex<HashMap<String, oneshot::Sender<Value>>>>,
}

impl BridgeHandle {
    /// Writes one JSON command to the bridge's stdin and awaits its `ack`. Bounded by a
    /// generous timeout so a bridge that's hung or has silently died (without the OS
    /// reporting `Terminated`, e.g. blocked on network I/O) can't hang a Tauri command
    /// forever — the frontend's `invoke(...)` would otherwise just spin.
    pub async fn send_command(&self, cmd_type: &str, mut payload: serde_json::Map<String, Value>) -> anyhow::Result<Value> {
        let id = uuid::Uuid::new_v4().to_string();
        payload.insert("cmd".to_string(), json!(cmd_type));
        payload.insert("id".to_string(), json!(id));

        let (tx, rx) = oneshot::channel();
        self.pending.lock().await.insert(id.clone(), tx);

        let mut line = serde_json::to_vec(&payload)?;
        line.push(b'\n');
        if let Err(e) = self.child.lock().await.write(&line) {
            self.pending.lock().await.remove(&id);
            return Err(e.into());
        }

        match tokio::time::timeout(Duration::from_secs(30), rx).await {
            Ok(Ok(value)) => Ok(value),
            Ok(Err(_)) => anyhow::bail!("bridge closed before responding"),
            Err(_) => {
                self.pending.lock().await.remove(&id);
                anyhow::bail!("bridge command timed out")
            }
        }
    }

    pub fn kill(self) -> anyhow::Result<()> {
        self.child.into_inner().kill()?;
        Ok(())
    }
}

fn bridge_script_path() -> PathBuf {
    PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/../whatsapp-bridge/index.js"))
}

/// Starts the bridge process and its stdout-reading task. `session_dir` is passed straight
/// through to Baileys' `useMultiFileAuthState`, so it must be stable across app restarts for
/// the linked-device session to survive them — see `lib.rs`'s `.setup()`.
pub async fn start(app_handle: AppHandle, state: Arc<AppState>, session_dir: PathBuf) -> anyhow::Result<Arc<BridgeHandle>> {
    let script = bridge_script_path();
    let (mut rx, child) = app_handle
        .shell()
        .command("node")
        .args([
            script.to_string_lossy().to_string(),
            "--session-dir".to_string(),
            session_dir.to_string_lossy().to_string(),
        ])
        .spawn()?;

    let pending: Arc<Mutex<HashMap<String, oneshot::Sender<Value>>>> = Arc::new(Mutex::new(HashMap::new()));
    let handle = Arc::new(BridgeHandle {
        child: Mutex::new(child),
        pending: Arc::clone(&pending),
    });

    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stdout(bytes) => {
                    let line = String::from_utf8_lossy(&bytes);
                    for segment in line.split('\n') {
                        let segment = segment.trim();
                        if segment.is_empty() {
                            continue;
                        }
                        match serde_json::from_str::<Value>(segment) {
                            Ok(value) => handle_event(&state, &pending, value).await,
                            Err(e) => tracing::warn!("whatsapp bridge: unparseable stdout line: {e}: {segment}"),
                        }
                    }
                }
                CommandEvent::Stderr(bytes) => {
                    tracing::warn!("whatsapp bridge stderr: {}", String::from_utf8_lossy(&bytes));
                }
                CommandEvent::Error(err) => {
                    tracing::error!("whatsapp bridge error: {err}");
                }
                CommandEvent::Terminated(payload) => {
                    tracing::warn!("whatsapp bridge terminated: {payload:?}");
                    state.whatsapp.lock().await.link_status = LinkStatus::Unlinked;
                    let _ = state.app_handle.emit("whatsapp-connection", json!({ "status": "terminated" }));
                    for (_, tx) in pending.lock().await.drain() {
                        let _ = tx.send(json!({ "ok": false, "error": "bridge terminated" }));
                    }
                }
                _ => {}
            }
        }
    });

    Ok(handle)
}

async fn handle_event(state: &Arc<AppState>, pending: &Arc<Mutex<HashMap<String, oneshot::Sender<Value>>>>, value: Value) {
    let Some(event_type) = value.get("type").and_then(Value::as_str) else {
        return;
    };
    let payload = value.get("payload").cloned().unwrap_or(Value::Null);

    match event_type {
        "ready" => {
            tracing::info!("whatsapp bridge ready");
        }
        "qr" => {
            let qr = payload.get("qr").and_then(Value::as_str).map(str::to_string);
            let mut wa = state.whatsapp.lock().await;
            wa.qr = qr.clone();
            wa.link_status = LinkStatus::Qr;
            drop(wa);
            let _ = state.app_handle.emit("whatsapp-qr", qr);
        }
        "connection" => {
            let status = payload.get("status").and_then(Value::as_str).unwrap_or("");
            let logged_out = payload.get("loggedOut").and_then(Value::as_bool).unwrap_or(false);
            let mut wa = state.whatsapp.lock().await;
            wa.link_status = match status {
                "connecting" => LinkStatus::Connecting,
                "open" => {
                    wa.qr = None;
                    LinkStatus::Open
                }
                "close" if logged_out => LinkStatus::LoggedOut,
                "close" => LinkStatus::Connecting, // bridge auto-reconnects unless logged out
                _ => wa.link_status,
            };
            drop(wa);
            let _ = state.app_handle.emit("whatsapp-connection", payload);
        }
        "chats" => {
            if let Some(chats) = parse_field::<Vec<WaChat>>(&payload, "chats") {
                let mut wa = state.whatsapp.lock().await;
                wa.upsert_chats(chats);
                let snapshot = wa.chats.clone();
                drop(wa);
                let _ = state.app_handle.emit("whatsapp-chats-updated", snapshot);
            }
        }
        "contacts" => {
            apply_contacts(state, &payload).await;
        }
        "history" => {
            if let Some(chats) = parse_field::<Vec<WaChat>>(&payload, "chats") {
                state.whatsapp.lock().await.upsert_chats(chats);
            }
            apply_contacts(state, &payload).await;
            if let Some(messages) = parse_field::<Vec<WaMessage>>(&payload, "messages") {
                state.whatsapp.lock().await.append_messages(messages);
            }
            let wa = state.whatsapp.lock().await;
            let chats = wa.chats.clone();
            drop(wa);
            let _ = state.app_handle.emit("whatsapp-chats-updated", chats);
        }
        "messages" => {
            if let Some(messages) = parse_field::<Vec<WaMessage>>(&payload, "messages") {
                state.whatsapp.lock().await.append_messages(messages.clone());
                let _ = state.app_handle.emit("whatsapp-message", messages);
            }
        }
        "receipt" => {
            if let Ok(updates) = serde_json::from_value::<Vec<WaReceiptUpdate>>(payload.clone()) {
                state.whatsapp.lock().await.apply_receipts(updates.clone());
                let _ = state.app_handle.emit("whatsapp-receipt", updates);
            }
        }
        "presence" => {
            // Ephemeral — not stored, just relayed for a live typing indicator.
            let _ = state.app_handle.emit("whatsapp-typing", payload);
        }
        "ack" => {
            if let Some(id) = value.get("id").and_then(Value::as_str) {
                if let Some(tx) = pending.lock().await.remove(id) {
                    let _ = tx.send(value);
                }
            }
        }
        "log" => {
            let message = payload.get("message").and_then(Value::as_str).unwrap_or("");
            tracing::warn!("whatsapp bridge log: {message}");
        }
        other => {
            tracing::debug!("whatsapp bridge: unhandled event type {other}");
        }
    }
}

/// Deserializes `payload[field]`, logging (rather than silently dropping) anything the bridge
/// sent that doesn't match what `WaChat`/`WaMessage`/etc. expect — e.g. a WhatsApp message
/// shape Baileys started emitting differently across a version bump. Without this, a mismatch
/// would just make messages vanish with no trace of why.
fn parse_field<T: serde::de::DeserializeOwned>(payload: &Value, field: &str) -> Option<T> {
    let raw = payload.get(field).cloned().unwrap_or(Value::Null);
    match serde_json::from_value(raw) {
        Ok(value) => Some(value),
        Err(e) => {
            tracing::warn!("whatsapp bridge: failed to parse '{field}': {e}");
            None
        }
    }
}

async fn apply_contacts(state: &Arc<AppState>, payload: &Value) {
    let Some(contacts) = payload.get("contacts").and_then(Value::as_array) else {
        return;
    };
    let pairs: Vec<(String, Option<String>)> = contacts
        .iter()
        .filter_map(|c| {
            let id = c.get("id")?.as_str()?.to_string();
            let name = c.get("name").and_then(Value::as_str).map(str::to_string);
            Some((id, name))
        })
        .collect();
    if !pairs.is_empty() {
        let mut wa = state.whatsapp.lock().await;
        wa.upsert_contacts(pairs);
        let names = wa.contact_names.clone();
        drop(wa);
        let _ = state.app_handle.emit("whatsapp-contacts-updated", names);
    }
}

pub fn parse_media_result(ack: &Value) -> Option<WaMediaResult> {
    serde_json::from_value(ack.clone()).ok()
}
