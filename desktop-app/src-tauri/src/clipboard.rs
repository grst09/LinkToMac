//! Bidirectional clipboard sync, ported from `Sync/ClipboardSyncManager.swift`, plus a capped
//! history of both directions' copies for the Clipboard section's UI.
//!
//! Neither macOS nor Linux clipboard backends expose a change-notification API (confirmed by
//! the Swift code's own comment — this isn't a platform gap specific to the rewrite), so the
//! local→phone direction is a 1-second poll, same interval as the old app. The phone→local
//! direction is push-based (`clipboard.update`/`clipboard.updateImage` arrive via `dispatch.rs`).
//!
//! Images run alongside the text path rather than replacing it: arboard's clipboard is
//! either-or per read (`get_text`/`get_image` each fail if the clipboard holds the other kind),
//! so an image tick is only ever reached after the text attempt has already failed. Images are
//! carried as PNG (via the `image` crate) rather than arboard's raw RGBA `ImageData` — a
//! screenshot-sized image raw would be tens of MB over the wire; this is a one-off event, not
//! real-time like screen mirroring, so paying PNG's compression cost is the right tradeoff here.

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use image::{ImageBuffer, ImageFormat, Rgba};
use serde::Serialize;
use tauri::Emitter;
use uuid::Uuid;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{ClipboardImageUpdatePayload, ClipboardUpdatePayload};

const POLL_INTERVAL: Duration = Duration::from_secs(1);
/// Caps the in-memory/UI history, not the sync itself — this is a browsing convenience, not a
/// durability guarantee (nothing here persists across a restart, matching `clipboard_last_synced`
/// not persisting either).
const MAX_HISTORY: usize = 100;
/// Shown in the Clipboard history list in place of actual text for an image entry — the field
/// stays a plain `String` (rather than becoming `Option<String>`) so every existing bit of
/// frontend code that reads `entry.text` (search-filtering, the fallback row) keeps working
/// unchanged for image entries too.
const IMAGE_ENTRY_LABEL: &str = "📷 Image";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardEntry {
    pub id: String,
    pub text: String,
    /// PNG, base64-encoded — present only for image entries.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_base64: Option<String>,
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

/// Records one accepted copy (already deduped by the caller) into the shared history and tells
/// the frontend. Newest-first, capped at `MAX_HISTORY` — except pinned entries, which are exempt
/// from that cap entirely (evicting the oldest *unpinned* entry instead), matching the point of
/// pinning something: it shouldn't disappear into the stack.
async fn record_history(state: &Arc<AppState>, text: String, image_base64: Option<String>, source: &str) {
    let entry = ClipboardEntry {
        id: Uuid::new_v4().to_string(),
        text,
        image_base64,
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
    record_history(state, text.clone(), None, "android").await;

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

/// Same shape as `apply_remote_update`, for an image — see the module doc comment for why
/// images are PNG over the wire rather than arboard's raw `ImageData`.
pub async fn apply_remote_image_update(state: &Arc<AppState>, image_base64: String) {
    if !state.settings.lock().await.get().clipboard_sync_enabled {
        return;
    }
    let png_bytes = match BASE64.decode(&image_base64) {
        Ok(bytes) => bytes,
        Err(e) => {
            tracing::warn!("clipboard: failed to decode remote image: {}", e);
            return;
        }
    };
    {
        let mut last = state.clipboard_last_synced_image.lock().await;
        if last.as_deref() == Some(png_bytes.as_slice()) {
            return;
        }
        *last = Some(png_bytes.clone());
    }
    record_history(state, IMAGE_ENTRY_LABEL.to_string(), Some(image_base64), "android").await;

    let result = tokio::task::spawn_blocking(move || -> anyhow::Result<()> {
        let rgba = image::load_from_memory(&png_bytes)?.into_rgba8();
        let (width, height) = rgba.dimensions();
        arboard::Clipboard::new()?.set_image(arboard::ImageData {
            width: width as usize,
            height: height as usize,
            bytes: rgba.into_raw().into(),
        })?;
        Ok(())
    })
    .await;

    match result {
        Ok(Ok(())) => tracing::info!("clipboard: applied remote image update from phone"),
        Ok(Err(e)) => tracing::warn!("clipboard: failed to apply remote image update: {}", e),
        Err(e) => tracing::warn!("clipboard: apply-image task panicked: {}", e),
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

        let text_result = tokio::task::spawn_blocking(|| arboard::Clipboard::new()?.get_text()).await;
        match text_result {
            Ok(Ok(text)) if !text.is_empty() => {
                {
                    let mut last = state.clipboard_last_synced.lock().await;
                    if last.as_deref() == Some(text.as_str()) {
                        continue;
                    }
                    *last = Some(text.clone());
                }
                record_history(&state, text.clone(), None, "mac").await;

                tracing::info!("clipboard: local change detected, pushing to phone: {:.40}", text);
                let payload = ClipboardUpdatePayload {
                    text,
                    source_device_id: state.identity.device_id.clone(),
                    timestamp: now_millis(),
                };
                if let Err(e) = send_to_active(&state, "clipboard.update", &payload).await {
                    // Not connected — expected most of the time this loop runs, not a warning.
                    tracing::debug!("clipboard: no active connection to send update to: {}", e);
                }
                continue;
            }
            Ok(Ok(_empty)) => continue,
            // Not text (could be an image, or nothing) — fall through to check for an image
            // below rather than giving up on this tick.
            Ok(Err(_)) => {}
            Err(e) => {
                tracing::warn!("clipboard: poll task panicked: {}", e);
                continue;
            }
        }

        let image_result =
            tokio::task::spawn_blocking(|| -> anyhow::Result<Option<Vec<u8>>> {
                let image = match arboard::Clipboard::new()?.get_image() {
                    Ok(image) => image,
                    // No image either — not an error worth logging every second.
                    Err(_) => return Ok(None),
                };
                let buffer: ImageBuffer<Rgba<u8>, _> = ImageBuffer::from_raw(
                    image.width as u32,
                    image.height as u32,
                    image.bytes.into_owned(),
                )
                .ok_or_else(|| anyhow::anyhow!("clipboard image had inconsistent dimensions"))?;
                let mut png_bytes = Vec::new();
                buffer.write_to(&mut std::io::Cursor::new(&mut png_bytes), ImageFormat::Png)?;
                Ok(Some(png_bytes))
            })
            .await;

        let png_bytes = match image_result {
            Ok(Ok(Some(bytes))) => bytes,
            Ok(Ok(None)) => continue,
            Ok(Err(e)) => {
                tracing::warn!("clipboard: failed to encode local image: {}", e);
                continue;
            }
            Err(e) => {
                tracing::warn!("clipboard: encode-image task panicked: {}", e);
                continue;
            }
        };

        {
            let mut last = state.clipboard_last_synced_image.lock().await;
            if last.as_deref() == Some(png_bytes.as_slice()) {
                continue;
            }
            *last = Some(png_bytes.clone());
        }
        let image_base64 = BASE64.encode(&png_bytes);
        record_history(&state, IMAGE_ENTRY_LABEL.to_string(), Some(image_base64.clone()), "mac").await;

        tracing::info!("clipboard: local image change detected, pushing to phone ({} bytes)", png_bytes.len());
        let payload = ClipboardImageUpdatePayload {
            image_base64,
            source_device_id: state.identity.device_id.clone(),
            timestamp: now_millis(),
        };
        if let Err(e) = send_to_active(&state, "clipboard.updateImage", &payload).await {
            tracing::debug!("clipboard: no active connection to send image update to: {}", e);
        }
    }
}

fn now_millis() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as f64)
        .unwrap_or(0.0)
}
