//! Routes decrypted post-handshake messages to the right feature handler. Split out from
//! `net/server.rs` so that module stays focused on transport/handshake as more message types
//! land here in later phases (Messages, Contacts, Files, ...).

use tauri::Emitter;

use crate::net::server::AppState;
use crate::protocol::envelope::{
    ClipboardUpdatePayload, DeviceStatusPayload, Message, NotificationPostedPayload,
    NotificationRemovedPayload,
};

const MAX_NOTIFICATIONS: usize = 200;

pub async fn handle(message: Message, state: &std::sync::Arc<AppState>) -> anyhow::Result<()> {
    match message.message_type.as_str() {
        "notification.posted" => {
            let payload: NotificationPostedPayload = serde_json::from_value(message.payload)?;
            handle_notification_posted(payload, state).await;
        }
        "notification.removed" => {
            let payload: NotificationRemovedPayload = serde_json::from_value(message.payload)?;
            handle_notification_removed(payload, state).await;
        }
        "device.status" => {
            let payload: DeviceStatusPayload = serde_json::from_value(message.payload)?;
            tracing::info!(
                "device.status: {}% {}",
                payload.battery_percent,
                if payload.is_charging { "(charging)" } else { "" }
            );
            *state.device_status.lock().await = Some(payload.clone());
            let _ = state.app_handle.emit("device-status", payload);
        }
        "clipboard.update" => {
            let payload: ClipboardUpdatePayload = serde_json::from_value(message.payload)?;
            crate::clipboard::apply_remote_update(state, payload.text).await;
        }
        other => {
            tracing::info!("received {} (dispatch not yet implemented)", other);
        }
    }
    Ok(())
}

/// Mirrors `NotificationStore.add` exactly: dedupe by id, insert at front (newest first), cap
/// at 200. Also fires the native OS banner, matching `LocalNotifier.post`.
async fn handle_notification_posted(payload: NotificationPostedPayload, state: &std::sync::Arc<AppState>) {
    let count = {
        let mut notifications = state.notifications.lock().await;
        notifications.retain(|n| n.id != payload.id);
        notifications.insert(0, payload.clone());
        let excess = notifications.len().saturating_sub(MAX_NOTIFICATIONS);
        if excess > 0 {
            notifications.truncate(MAX_NOTIFICATIONS);
        }
        notifications.len()
    };
    tracing::info!(
        "notification.posted: {} — {} ({} total)",
        payload.app_name,
        payload.title,
        count
    );
    let _ = state.app_handle.emit("notification-posted", payload.clone());
    crate::notify::post(&state.app_handle, &payload);
}

/// Mirrors `NotificationStore.remove` — also clears the delivered OS banner, matching
/// `LocalNotifier.remove`.
async fn handle_notification_removed(payload: NotificationRemovedPayload, state: &std::sync::Arc<AppState>) {
    state.notifications.lock().await.retain(|n| n.id != payload.id);
    tracing::info!("notification.removed: {}", payload.id);
    let _ = state.app_handle.emit("notification-removed", payload.clone());
    crate::notify::remove(&state.app_handle, &payload.id);
}
