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
    send_to_active(&state, "notification.dismiss", &NotificationRemovedPayload { id })
        .await
        .map_err(|e| e.to_string())
}
