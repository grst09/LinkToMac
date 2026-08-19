use std::sync::Arc;

use serde::Serialize;
use tauri::ipc::{Channel, InvokeResponseBody};

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    EmptyPayload, MirrorConfigPayload, MirrorKeyPayload, MirrorSwipePayload, MirrorTapPayload,
    MirrorTextInputPayload,
};

#[derive(Serialize)]
pub struct MirrorStateSnapshot {
    is_active: bool,
    config: Option<MirrorConfigPayload>,
    stopped_reason: Option<String>,
}

#[tauri::command]
pub async fn get_mirror_state(state: tauri::State<'_, Arc<AppState>>) -> Result<MirrorStateSnapshot, String> {
    let mirror = state.mirror.lock().await;
    Ok(MirrorStateSnapshot {
        is_active: mirror.is_active,
        config: mirror.config.clone(),
        stopped_reason: mirror.stopped_reason.clone(),
    })
}

/// Registers the decoded-frame channel and asks the phone to start capturing. The phone brings
/// its own Activity to the foreground for the `MediaProjection` permission prompt — a real UX
/// interruption with no way around it (see docs/PROTOCOL.md) — so this may take a few seconds
/// before `mirror.config` arrives and frames start flowing.
#[tauri::command]
pub async fn start_mirroring(
    state: tauri::State<'_, Arc<AppState>>,
    on_frame: Channel<InvokeResponseBody>,
) -> Result<(), String> {
    state.mirror.lock().await.set_channel(on_frame);
    send_to_active(&state, "mirror.start", &EmptyPayload {})
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn stop_mirroring(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    {
        let mut mirror = state.mirror.lock().await;
        mirror.clear_channel();
        mirror.stopped("requested".to_string());
    }
    send_to_active(&state, "mirror.stop", &EmptyPayload {})
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn send_mirror_tap(state: tauri::State<'_, Arc<AppState>>, x: f64, y: f64) -> Result<(), String> {
    send_to_active(&state, "mirror.tap", &MirrorTapPayload { x, y })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn send_mirror_swipe(
    state: tauri::State<'_, Arc<AppState>>,
    start_x: f64,
    start_y: f64,
    end_x: f64,
    end_y: f64,
    duration_ms: i64,
) -> Result<(), String> {
    send_to_active(
        &state,
        "mirror.swipe",
        &MirrorSwipePayload {
            start_x,
            start_y,
            end_x,
            end_y,
            duration_ms,
        },
    )
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn send_mirror_key(state: tauri::State<'_, Arc<AppState>>, action: String) -> Result<(), String> {
    send_to_active(&state, "mirror.key", &MirrorKeyPayload { action })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn send_mirror_text(state: tauri::State<'_, Arc<AppState>>, text: String) -> Result<(), String> {
    send_to_active(&state, "mirror.textInput", &MirrorTextInputPayload { text })
        .await
        .map_err(|e| e.to_string())
}
