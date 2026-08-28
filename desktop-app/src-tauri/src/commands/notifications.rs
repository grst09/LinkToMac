use std::sync::Arc;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{NotificationPostedPayload, NotificationRemovedPayload};

#[tauri::command]
pub async fn list_notifications(
    state: tauri::State<'_, Arc<AppState>>,
) -> Result<Vec<NotificationPostedPayload>, String> {
    Ok(state.notifications.lock().await.clone())
}

#[tauri::command]
pub async fn dismiss_notification(
    state: tauri::State<'_, Arc<AppState>>,
    id: String,
) -> Result<(), String> {
    state.notifications.lock().await.retain(|n| n.id != id);
    // Clears the native Notification Center banner too — see notify.rs's module doc for why this
    // used to be a no-op.
    crate::notify::remove(&state.app_handle, &id);
    send_to_active(&state, "notification.dismiss", &NotificationRemovedPayload { id })
        .await
        .map_err(|e| e.to_string())
}

/// No bulk `notification.dismissAll` on the wire — clears every notification's phone-side copy
/// with the same per-id `notification.dismiss` message `dismiss_notification` sends, just for all
/// of them, while `notify::remove_all` clears every native banner in one call rather than one
/// `removeDeliveredNotificationsWithIdentifiers` round trip per id.
#[tauri::command]
pub async fn dismiss_all_notifications(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    let ids: Vec<String> = {
        let mut notifications = state.notifications.lock().await;
        let ids = notifications.iter().map(|n| n.id.clone()).collect();
        notifications.clear();
        ids
    };
    crate::notify::remove_all(&state.app_handle);
    for id in ids {
        let _ = send_to_active(&state, "notification.dismiss", &NotificationRemovedPayload { id }).await;
    }
    Ok(())
}
