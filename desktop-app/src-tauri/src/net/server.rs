//! WebSocket server + Bonjour/mDNS advertisement + handshake, ported from
//! `mac-app/Sources/LinkToMac/Networking/ConnectionServer.swift`. See docs/PROTOCOL.md.
//!
//! Phase A scope: prove an already-paired Android phone reconnects to this server exactly as
//! it did to the old Swift app (same identity key, same pin, no forced re-pair). Feature
//! message dispatch (notifications, calls, files, etc.) is later-phase work — this module logs
//! and otherwise ignores anything past the handshake for now, except `ping`/`pong` keepalive.

use std::net::SocketAddr;
use std::sync::Arc;

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use futures_util::{SinkExt, StreamExt};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use serde::Serialize;
use tauri::Emitter;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::WebSocketStream;

use crate::crypto::secure_channel::{self, SecureChannelError};
use crate::net::local_network;
use crate::protocol::envelope::{
    EmptyPayload, EncryptedFrame, HelloAckPayload, HelloPayload, Message, PairingQrPayload,
    ServerHelloPayload,
};
use crate::protocol::envelope::SyncSettingsPayload;
use crate::store::identity::IdentityStore;
use crate::store::local_contacts::LocalContactsStore;
use crate::store::local_notes::LocalNotesStore;
use crate::store::paired_devices::PairedDeviceStore;
use crate::store::pending_messages::PendingMessagesStore;

pub const PORT: u16 = 53821;
const SERVICE_TYPE: &str = "_linktomac._tcp.local.";

/// A handle to the currently-live connection, if any — lets code outside `handle_connection`
/// (the clipboard poll loop, the `dismiss_notification`/etc. Tauri commands) send outbound
/// messages without needing direct access to the WebSocket itself. Only one connection is
/// live at a time (matching `ConnectionServer.swift`'s single-active-connection model), so a
/// single slot is enough — no per-connection routing needed.
struct ActiveConnection {
    sender: tokio::sync::mpsc::UnboundedSender<WsMessage>,
    session_key: [u8; 32],
}

pub struct AppState {
    pub identity: IdentityStore,
    pub paired_devices: Mutex<PairedDeviceStore>,
    pub settings: Mutex<crate::store::settings::SettingsStore>,
    /// Single pending pairing session at a time, matching the old app's behavior — see
    /// `beginNewPairingSession`/`activePairingToken` in `ConnectionServer.swift`.
    pub active_pairing_token: Mutex<Option<Vec<u8>>>,
    /// The paired device id currently holding the live connection, if any — mirrors
    /// `activeDeviceIdIfConnected` in `ConnectionServer.swift`, so the UI can mark the right
    /// row in the paired-devices list without guessing from connection state alone.
    pub active_device_id: Mutex<Option<String>>,
    active_connection: Mutex<Option<ActiveConnection>>,
    /// Newest-first, capped at 200, deduped by id — matches `NotificationStore.swift` exactly.
    pub notifications: Mutex<Vec<crate::protocol::envelope::NotificationPostedPayload>>,
    pub device_status: Mutex<Option<crate::protocol::envelope::DeviceStatusPayload>>,
    /// The last clipboard text synced in *either* direction — shared between the poll loop
    /// (local → phone) and the receive handler (phone → local) so neither echoes a value that
    /// just arrived from the other side. Matches `ClipboardSyncManager.swift`'s single
    /// `lastSyncedText` exactly (not separate sent/received trackers).
    pub clipboard_last_synced: Mutex<Option<String>>,
    /// Newest-first, capped at 100 — every accepted copy from either device, for the Clipboard
    /// section's history view. See `clipboard::record_history`.
    pub clipboard_history: Mutex<Vec<crate::clipboard::ClipboardEntry>>,
    pub calls: Mutex<Vec<crate::protocol::envelope::CallLogEntry>>,
    pub messages: Mutex<crate::messages::MessageState>,
    pub contacts: Mutex<Vec<crate::protocol::envelope::ContactEntry>>,
    pub notes: Mutex<Vec<crate::protocol::envelope::NoteEntry>>,
    pub photos: Mutex<crate::photos::PhotoState>,
    pub files: Mutex<crate::files::FileState>,
    pub mirror: Mutex<crate::mirror::MirrorState>,
    /// The phone's last-pushed per-category sync toggles — see `SyncSettingsPayload`'s doc
    /// comment. Defaults to all-enabled until the first `sync.settings` push arrives.
    pub sync_settings: Mutex<SyncSettingsPayload>,
    pub local_notes: Mutex<LocalNotesStore>,
    pub local_contacts: Mutex<LocalContactsStore>,
    pub pending_messages: Mutex<PendingMessagesStore>,
    pub app_handle: tauri::AppHandle,
    _mdns: ServiceDaemon,
}

impl AppState {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        identity: IdentityStore,
        paired_devices: PairedDeviceStore,
        settings: crate::store::settings::SettingsStore,
        local_notes: LocalNotesStore,
        local_contacts: LocalContactsStore,
        pending_messages: PendingMessagesStore,
        app_handle: tauri::AppHandle,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = start_mdns(&identity)?;
        Ok(Self {
            identity,
            paired_devices: Mutex::new(paired_devices),
            settings: Mutex::new(settings),
            active_pairing_token: Mutex::new(None),
            active_device_id: Mutex::new(None),
            active_connection: Mutex::new(None),
            notifications: Mutex::new(Vec::new()),
            device_status: Mutex::new(None),
            clipboard_last_synced: Mutex::new(None),
            clipboard_history: Mutex::new(Vec::new()),
            calls: Mutex::new(Vec::new()),
            messages: Mutex::new(crate::messages::MessageState::default()),
            contacts: Mutex::new(Vec::new()),
            notes: Mutex::new(Vec::new()),
            photos: Mutex::new(crate::photos::PhotoState::default()),
            files: Mutex::new(crate::files::FileState::default()),
            mirror: Mutex::new(crate::mirror::MirrorState::default()),
            sync_settings: Mutex::new(SyncSettingsPayload::default()),
            local_notes: Mutex::new(local_notes),
            local_contacts: Mutex::new(local_contacts),
            pending_messages: Mutex::new(pending_messages),
            app_handle,
            _mdns: mdns,
        })
    }
}

/// Sends an outbound message to whichever device currently holds the live connection, if any.
/// Used by the clipboard poll loop and by Tauri commands (`dismiss_notification`, later
/// `sms.send`/`contacts.dial`/etc.) that need to talk to the phone from outside the
/// per-connection task. Silently does nothing if nothing's connected — callers that need to
/// know can check `active_device_id` first.
pub async fn send_to_active<T: Serialize>(
    state: &AppState,
    message_type: &str,
    payload: &T,
) -> anyhow::Result<()> {
    let guard = state.active_connection.lock().await;
    let Some(conn) = guard.as_ref() else {
        anyhow::bail!("no active connection");
    };
    let message = Message {
        message_type: message_type.to_string(),
        payload: serde_json::to_value(payload)?,
    };
    let plaintext = serde_json::to_vec(&message)?;
    let frame = secure_channel::seal(&plaintext, &conn.session_key)?;
    let text = serde_json::to_string(&frame)?;
    conn.sender
        .send(WsMessage::Text(text.into()))
        .map_err(|_| anyhow::anyhow!("outbound channel closed"))?;
    Ok(())
}

#[derive(Serialize, Clone)]
struct PairedEvent {
    device_id: String,
    device_name: String,
    is_new_pairing: bool,
}

#[derive(Serialize, Clone)]
struct DisconnectedEvent {
    device_id: String,
}

/// Safe-to-expose subset of `PairedDevice` — no `deviceToken`/`pairingSalt`, those are session
/// secrets and never need to reach the frontend.
#[derive(Serialize)]
pub struct PairedDeviceSummary {
    id: String,
    device_name: String,
    paired_at: String,
    is_active: bool,
}

#[tauri::command]
pub async fn list_paired_devices(
    state: tauri::State<'_, Arc<AppState>>,
) -> Result<Vec<PairedDeviceSummary>, String> {
    let paired = state.paired_devices.lock().await;
    let active_id = state.active_device_id.lock().await;
    Ok(paired
        .devices()
        .iter()
        .map(|d| PairedDeviceSummary {
            id: d.id.clone(),
            device_name: d.device_name.clone(),
            paired_at: d.paired_at.clone(),
            is_active: active_id.as_deref() == Some(d.id.as_str()),
        })
        .collect())
}

/// Mirrors `forgetDevice` in `ConnectionServer.swift` — removes stored credentials entirely;
/// that phone will need a fresh QR pair to reconnect. Doesn't attempt to sever a currently-live
/// connection to that device (the WebSocket handle for the active connection isn't threaded
/// through to shared state yet — a small gap, not a Phase B blocker, since forgetting a device
/// you're not actively using is the common case this is for).
#[tauri::command]
pub async fn forget_device(
    state: tauri::State<'_, Arc<AppState>>,
    id: String,
) -> Result<(), String> {
    state
        .paired_devices
        .lock()
        .await
        .remove(&id)
        .map_err(|e| e.to_string())
}

/// Tauri command: starts a new pairing session (single-use token, matching
/// `beginNewPairingSession` in `ConnectionServer.swift`) and returns the payload the frontend
/// renders as a QR code for Android to scan.
#[tauri::command]
pub async fn begin_pairing(
    state: tauri::State<'_, Arc<AppState>>,
) -> Result<PairingQrPayload, String> {
    use aes_gcm::aead::Generate;
    let token_bytes = <[u8; 16] as Generate>::generate();
    *state.active_pairing_token.lock().await = Some(token_bytes.to_vec());

    let host = local_network::primary_ipv4_address()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|| "0.0.0.0".to_string());

    Ok(PairingQrPayload {
        host,
        port: PORT,
        pairing_token: BASE64.encode(token_bytes),
        mac_device_id: state.identity.device_id.clone(),
    })
}

fn start_mdns(identity: &IdentityStore) -> Result<ServiceDaemon, Box<dyn std::error::Error>> {
    let daemon = ServiceDaemon::new()?;

    let raw_host = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "LinkToMac".to_string());
    let dns_safe_host = sanitize_for_dns(&raw_host);
    let host_name = format!("{dns_safe_host}.local.");

    let pk = identity.public_key_base64();
    let id = identity.device_id.clone();
    let properties: [(&str, &str); 2] = [("pk", pk.as_str()), ("id", id.as_str())];

    let service_info = ServiceInfo::new(
        SERVICE_TYPE,
        &raw_host,
        &host_name,
        "",
        PORT,
        &properties[..],
    )?
    .enable_addr_auto();

    tracing::info!(
        "advertising {} as {} ({}), pk={}...",
        SERVICE_TYPE,
        raw_host,
        host_name,
        &pk[..pk.len().min(12)]
    );
    daemon.register(service_info)?;
    Ok(daemon)
}

fn sanitize_for_dns(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' { c } else { '-' })
        .collect()
}

fn local_device_name() -> String {
    hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "This computer".to_string())
}

pub async fn run(state: Arc<AppState>) -> std::io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", PORT)).await?;
    tracing::info!("WebSocket server listening on 0.0.0.0:{}", PORT);

    loop {
        let (stream, addr) = listener.accept().await?;
        let state = Arc::clone(&state);
        tokio::spawn(async move {
            if let Err(e) = handle_connection(stream, addr, state).await {
                tracing::warn!("connection {} ended: {}", addr, e);
            }
        });
    }
}

type WsWriter = futures_util::stream::SplitSink<WebSocketStream<TcpStream>, WsMessage>;

async fn handle_connection(
    stream: TcpStream,
    addr: SocketAddr,
    state: Arc<AppState>,
) -> anyhow::Result<()> {
    tracing::info!("incoming connection from {}", addr);
    let ws_stream = tokio_tungstenite::accept_async(stream).await?;
    let (mut write, mut read) = ws_stream.split();

    // Step 1: serverHello, unencrypted, immediately — see docs/PROTOCOL.md.
    let server_hello = ServerHelloPayload {
        mac_public_key: state.identity.public_key_base64(),
        mac_device_id: state.identity.device_id.clone(),
        mac_device_name: local_device_name(),
    };
    send_plain(&mut write, "serverHello", &server_hello).await?;

    // Step 2: wait for hello.
    let hello = match read.next().await {
        Some(Ok(WsMessage::Text(text))) => text,
        Some(Ok(other)) => {
            anyhow::bail!("expected text hello, got {:?}", other);
        }
        Some(Err(e)) => return Err(e.into()),
        None => anyhow::bail!("connection closed before hello"),
    };
    let message: Message = serde_json::from_str(&hello)?;
    if message.message_type != "hello" {
        anyhow::bail!("expected hello, got {}", message.message_type);
    }
    let hello: HelloPayload = serde_json::from_value(message.payload)?;
    let device_id_for_log = hello.device_id.clone();

    // Step 3: validate + derive session key, exactly matching handleHello in
    // ConnectionServer.swift (same three-way branch: new pairing / reconnect / reject).
    let outcome = {
        let mut active_token = state.active_pairing_token.lock().await;
        let mut paired = state.paired_devices.lock().await;
        process_hello(&hello, &state.identity, &mut active_token, &mut paired)
    };

    let result = match outcome {
        Ok(Some(handshake)) => handshake,
        Ok(None) => {
            tracing::warn!(
                "rejecting hello from {} (device {}): no valid pairing/device token",
                addr,
                device_id_for_log
            );
            send_reject(&mut write).await?;
            return Ok(());
        }
        Err(e) => {
            tracing::warn!("failed to process hello from {}: {}", addr, e);
            return Ok(());
        }
    };

    // Step 4: encrypted helloAck.
    let ack = HelloAckPayload {
        status: "paired".to_string(),
        device_token: Some(result.device_token.clone()),
        mac_device_name: local_device_name(),
    };
    send_encrypted(&mut write, "helloAck", &ack, &result.session_key).await?;

    tracing::info!(
        "paired: device {} ({}) — {} — no re-pair required",
        device_id_for_log,
        result.device_name,
        if result.is_new_pairing {
            "new pairing"
        } else {
            "reconnect"
        }
    );
    let _ = state.app_handle.emit(
        "paired",
        PairedEvent {
            device_id: device_id_for_log.clone(),
            device_name: result.device_name.clone(),
            is_new_pairing: result.is_new_pairing,
        },
    );
    *state.active_device_id.lock().await = Some(device_id_for_log.clone());

    let (outbound_tx, mut outbound_rx) = tokio::sync::mpsc::unbounded_channel::<WsMessage>();
    *state.active_connection.lock().await = Some(ActiveConnection {
        sender: outbound_tx,
        session_key: result.session_key,
    });

    // Step 5: post-handshake loop — decrypt incoming messages and dispatch them (see
    // dispatch.rs), answer ping/pong so Android's 45s keepalive timeout doesn't fire, and
    // forward anything arriving on `outbound_rx` (from the clipboard poll loop, Tauri commands
    // like `dismiss_notification`, etc. — see `send_to_active`). Loop exit (clean close,
    // error, or EOF) always falls through to the cleanup below — no early `?` return that
    // would skip clearing `active_device_id`/`active_connection` or emitting `disconnected`.
    loop {
        tokio::select! {
            incoming = read.next() => {
                let msg = match incoming {
                    Some(Ok(msg)) => msg,
                    Some(Err(e)) => {
                        tracing::warn!("read error from {}: {}", addr, e);
                        break;
                    }
                    None => break,
                };
                match msg {
                    WsMessage::Text(text) => {
                        if let Err(e) =
                            handle_encrypted_text(&text, &result.session_key, &mut write, &state).await
                        {
                            tracing::warn!("failed to handle message from {}: {}", addr, e);
                        }
                    }
                    WsMessage::Binary(data) => {
                        match secure_channel::open_raw(&data, &result.session_key) {
                            Ok(plaintext) => {
                                state.mirror.lock().await.submit_frame(plaintext);
                            }
                            Err(e) => tracing::warn!("failed to decrypt binary frame: {}", e),
                        }
                    }
                    WsMessage::Close(_) => break,
                    _ => {}
                }
            }
            outgoing = outbound_rx.recv() => {
                let Some(msg) = outgoing else { continue };
                if let Err(e) = write.send(msg).await {
                    tracing::warn!("failed to send outbound message to {}: {}", addr, e);
                    break;
                }
            }
        }
    }

    let mut active_conn = state.active_connection.lock().await;
    *active_conn = None;
    drop(active_conn);
    let mut active = state.active_device_id.lock().await;
    if active.as_deref() == Some(device_id_for_log.as_str()) {
        *active = None;
    }
    drop(active);
    let _ = state.app_handle.emit(
        "disconnected",
        DisconnectedEvent {
            device_id: device_id_for_log.clone(),
        },
    );
    tracing::info!("disconnected: device {}", device_id_for_log);
    Ok(())
}

async fn handle_encrypted_text(
    text: &str,
    session_key: &[u8; 32],
    write: &mut WsWriter,
    state: &Arc<AppState>,
) -> anyhow::Result<()> {
    let frame: EncryptedFrame = serde_json::from_str(text)?;
    let plaintext = secure_channel::open(&frame, session_key)?;
    let message: Message = serde_json::from_slice(&plaintext)?;

    if message.message_type == "ping" {
        send_encrypted(write, "pong", &EmptyPayload {}, session_key).await?;
        return Ok(());
    }

    crate::dispatch::handle(message, state).await
}

struct HandshakeResult {
    session_key: [u8; 32],
    device_token: String,
    device_name: String,
    is_new_pairing: bool,
}

/// Mirrors `ConnectionServer.handleHello` exactly: `Ok(Some(_))` = paired (new or reconnect),
/// `Ok(None)` = explicit reject (bad/expired token), `Err(_)` = malformed input, logged and
/// dropped without a reply (matches the Swift code's behavior when `deriveSessionKey` throws).
fn process_hello(
    hello: &HelloPayload,
    identity: &IdentityStore,
    active_pairing_token: &mut Option<Vec<u8>>,
    paired: &mut PairedDeviceStore,
) -> Result<Option<HandshakeResult>, SecureChannelError> {
    let (salt, is_new_pairing): (Vec<u8>, bool) =
        if let (Some(pairing_token_b64), Some(active)) =
            (&hello.pairing_token, active_pairing_token.as_ref())
        {
            let token_bytes = BASE64.decode(pairing_token_b64)?;
            if &token_bytes == active {
                (token_bytes, true)
            } else {
                return Ok(None);
            }
        } else if let Some(device_token) = &hello.device_token {
            match paired.device_with_token(device_token) {
                Some(existing) if existing.id == hello.device_id => {
                    let salt = BASE64.decode(&existing.pairing_salt)?;
                    (salt, false)
                }
                _ => return Ok(None),
            }
        } else {
            return Ok(None);
        };

    let session_key = secure_channel::derive_session_key(
        &identity.key_pair.secret_key,
        &hello.android_public_key,
        &salt,
    )?;

    let device_token = if is_new_pairing {
        use aes_gcm::aead::Generate;
        let token_bytes = <[u8; 32] as Generate>::generate();
        BASE64.encode(token_bytes)
    } else {
        hello.device_token.clone().unwrap()
    };

    paired
        .upsert(
            &hello.device_id,
            &hello.device_name,
            &device_token,
            &BASE64.encode(&salt),
        )
        .map_err(|_| SecureChannelError::SealFailed)?;

    if is_new_pairing {
        *active_pairing_token = None;
    }

    Ok(Some(HandshakeResult {
        session_key,
        device_token,
        device_name: hello.device_name.clone(),
        is_new_pairing,
    }))
}

async fn send_plain<T: serde::Serialize>(
    write: &mut WsWriter,
    message_type: &str,
    payload: &T,
) -> anyhow::Result<()> {
    let message = Message {
        message_type: message_type.to_string(),
        payload: serde_json::to_value(payload)?,
    };
    let text = serde_json::to_string(&message)?;
    write.send(WsMessage::Text(text.into())).await?;
    Ok(())
}

async fn send_encrypted<T: serde::Serialize>(
    write: &mut WsWriter,
    message_type: &str,
    payload: &T,
    session_key: &[u8; 32],
) -> anyhow::Result<()> {
    let message = Message {
        message_type: message_type.to_string(),
        payload: serde_json::to_value(payload)?,
    };
    let plaintext = serde_json::to_vec(&message)?;
    let frame = secure_channel::seal(&plaintext, session_key)?;
    let text = serde_json::to_string(&frame)?;
    write.send(WsMessage::Text(text.into())).await?;
    Ok(())
}

async fn send_reject(write: &mut WsWriter) -> anyhow::Result<()> {
    let payload = HelloAckPayload {
        status: "rejected".to_string(),
        device_token: None,
        mac_device_name: local_device_name(),
    };
    send_plain(write, "helloAck", &payload).await?;
    write.close().await?;
    Ok(())
}
