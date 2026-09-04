use tauri::Emitter;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{AppsListPayload, EmptyPayload, MirrorConfigPayload, MirrorStoppedPayload};

/// Mirrors `MirrorStore.updateConfig`: (re)builds the decoder from the SPS/PPS parameter sets
/// and marks mirroring active. Emitted config (minus the base64 parameter sets, which the
/// frontend has no use for) lets the UI size its canvas and show the live view.
pub async fn config(payload: MirrorConfigPayload, state: &std::sync::Arc<AppState>) {
    let quality = state.settings.lock().await.get().mirror_quality;
    let result = state.mirror.lock().await.configure(payload.clone(), quality);
    if let Err(e) = result {
        tracing::warn!("failed to configure mirror decoder: {}", e);
        return;
    }
    tracing::info!(
        "mirror.config: {}x{} @ {}fps",
        payload.width,
        payload.height,
        payload.fps
    );
    let _ = state.app_handle.emit("mirror-config", payload);

    // The app-launcher grid should just be there once mirroring starts, no extra click —
    // fire-and-forget, same reasoning as `refresh_contacts`'s doc comment.
    let _ = send_to_active(state, "mirror.appsListRequest", &EmptyPayload {}).await;
}

/// Mirrors `MirrorStore.handleStopped`.
pub async fn stopped(payload: MirrorStoppedPayload, state: &std::sync::Arc<AppState>) {
    state.mirror.lock().await.stopped(payload.reason.clone());
    tracing::info!("mirror.stopped: {}", payload.reason);
    let _ = state.app_handle.emit("mirror-stopped", payload);
}

pub async fn apps_list(payload: AppsListPayload, state: &std::sync::Arc<AppState>) {
    tracing::info!("mirror.appsList: {} apps", payload.apps.len());
    state.mirror.lock().await.set_apps(payload.apps.clone());
    let _ = state.app_handle.emit("mirror-apps-updated", payload.apps);
}
