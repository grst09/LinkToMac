//! Bidirectional clipboard sync, ported from `Sync/ClipboardSyncManager.swift`, plus a capped
//! history of both directions' copies for the Clipboard section's UI.
//!
//! Neither macOS nor Linux clipboard backends expose a change-notification API (confirmed by
//! the Swift code's own comment — this isn't a platform gap specific to the rewrite), so the
//! local→phone direction is a 1-second poll, same interval as the old app. The phone→local
//! direction is push-based (`clipboard.update` arrives via `dispatch.rs`).

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use tauri::Emitter;
use uuid::Uuid;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::ClipboardUpdatePayload;

const POLL_INTERVAL: Duration = Duration::from_secs(1);
/// Caps the in-memory/UI history, not the sync itself — this is a browsing convenience, not a
/// durability guarantee (nothing here persists across a restart, matching `clipboard_last_synced`
/// not persisting either).
const MAX_HISTORY: usize = 100;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardEntry {
    pub id: String,
    pub text: String,
    /// "mac" | "android" — which side this copy originated on.
    pub source: String,
    pub timestamp: f64,
    #[serde(default)]
    pub is_pinned: bool,
}

/// Pinned-first (each partition keeping its own newest-first order, which `history` is already
/// stored in) — the view every command/event actually hands to the frontend. `history` itself
/// stays in plain insertion order; this is computed on demand rather than maintained as the
/// stored order, so pinning/unpinning is just a flag flip, not a re-sort of the source of truth.
pub fn ordered(history: &[ClipboardEntry]) -> Vec<ClipboardEntry> {
    let mut pinned: Vec<ClipboardEntry> = history.iter().filter(|e| e.is_pinned).cloned().collect();
    let mut rest: Vec<ClipboardEntry> = history.iter().filter(|e| !e.is_pinned).cloned().collect();
    pinned.append(&mut rest);
    pinned
}

/// Records one accepted copy (already deduped against `clipboard_last_synced` by the caller)
/// into the shared history and tells the frontend. Newest-first, capped at `MAX_HISTORY` — except
/// pinned entries, which are exempt from that cap entirely (evicting the oldest *unpinned* entry
/// instead), matching the point of pinning something: it shouldn't disappear into the stack.
async fn record_history(state: &Arc<AppState>, text: String, source: &str) {
    let entry = ClipboardEntry {
        id: Uuid::new_v4().to_string(),
        text,
        source: source.to_string(),
        timestamp: now_millis(),
        is_pinned: false,
    };
    let history = {
        let mut history = state.clipboard_history.lock().await;
        history.insert(0, entry);
        while history.len() > MAX_HISTORY {
            let Some(idx) = history.iter().rposition(|e| !e.is_pinned) else {
                break; // everything left is pinned — let it exceed the cap rather than evict a pin
            };
            history.remove(idx);
        }
        history.clone()
    };
    let _ = state.app_handle.emit("clipboard-history-updated", ordered(&history));
}

/// Applies a `clipboard.update` received from the phone. Sets `clipboard_last_synced` *before*
/// writing to the system clipboard so the next local poll doesn't treat this write as a new
/// local copy and echo it straight back — matches `applyRemoteUpdate`'s ordering exactly.
pub async fn apply_remote_update(state: &Arc<AppState>, text: String) {
    if !state.settings.lock().await.get().clipboard_sync_enabled {
        return;
    }
    {
        let mut last = state.clipboard_last_synced.lock().await;
        if last.as_deref() == Some(text.as_str()) {
            return;
        }
        *last = Some(text.clone());
    }
    record_history(state, text.clone(), "android").await;

    let result = tokio::task::spawn_blocking({
        let text = text.clone();
        move || arboard::Clipboard::new()?.set_text(text)
    })
    .await;

    match result {
        Ok(Ok(())) => {
            tracing::info!("clipboard: applied remote update from phone: {:.40}", text);
        }
        Ok(Err(e)) => tracing::warn!("clipboard: failed to apply remote update: {}", e),
        Err(e) => tracing::warn!("clipboard: apply task panicked: {}", e),
    }
}

/// Runs forever — spawned once at startup alongside the WebSocket server. Polling (not a
/// change-notification callback) because none exists on either target platform; see module doc.
pub async fn run_poll_loop(state: Arc<AppState>) {
    loop {
        tokio::time::sleep(POLL_INTERVAL).await;

        if !state.settings.lock().await.get().clipboard_sync_enabled {
            continue;
        }

        let text = match tokio::task::spawn_blocking(|| arboard::Clipboard::new()?.get_text()).await
        {
            Ok(Ok(text)) => text,
            // No clipboard content (e.g. an image, not text) or no clipboard access — not an
            // error worth logging every second, just skip this tick.
            Ok(Err(_)) => continue,
            Err(e) => {
                tracing::warn!("clipboard: poll task panicked: {}", e);
                continue;
            }
        };

        if text.is_empty() {
            continue;
        }

        {
            let mut last = state.clipboard_last_synced.lock().await;
            if last.as_deref() == Some(text.as_str()) {
                continue;
            }
            *last = Some(text.clone());
        }
        record_history(&state, text.clone(), "mac").await;

        tracing::info!("clipboard: local change detected, pushing to phone: {:.40}", text);
        let payload = ClipboardUpdatePayload {
            text,
            source_device_id: state.identity.device_id.clone(),
            timestamp: now_millis(),
        };
        if let Err(e) = send_to_active(&state, "clipboard.update", &payload).await {
            // Not connected — expected most of the time this loop runs, not worth a warning.
            tracing::debug!("clipboard: no active connection to send update to: {}", e);
        }
    }
}

fn now_millis() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as f64)
        .unwrap_or(0.0)
}
