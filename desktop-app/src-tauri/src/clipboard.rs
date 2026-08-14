//! Bidirectional clipboard sync, ported from `Sync/ClipboardSyncManager.swift`.
//!
//! Neither macOS nor Linux clipboard backends expose a change-notification API (confirmed by
//! the Swift code's own comment — this isn't a platform gap specific to the rewrite), so the
//! local→phone direction is a 1-second poll, same interval as the old app. The phone→local
//! direction is push-based (`clipboard.update` arrives via `dispatch.rs`).

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::ClipboardUpdatePayload;

const POLL_INTERVAL: Duration = Duration::from_secs(1);

/// Applies a `clipboard.update` received from the phone. Sets `clipboard_last_synced` *before*
/// writing to the system clipboard so the next local poll doesn't treat this write as a new
/// local copy and echo it straight back — matches `applyRemoteUpdate`'s ordering exactly.
pub async fn apply_remote_update(state: &Arc<AppState>, text: String) {
    {
        let mut last = state.clipboard_last_synced.lock().await;
        if last.as_deref() == Some(text.as_str()) {
            return;
        }
        *last = Some(text.clone());
    }

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
