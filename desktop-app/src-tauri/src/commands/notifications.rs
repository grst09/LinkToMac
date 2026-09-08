use std::sync::Arc;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{EmptyPayload, NotificationPostedPayload, NotificationRemovedPayload};

#[tauri::command]
pub async fn list_notifications(
    state: tauri::State<'_, Arc<AppState>>,
) -> Result<Vec<NotificationPostedPayload>, String> {
    Ok(state.notifications.lock().await.clone())
}

/// Asks the phone for a fresh full list of whatever's currently showing — see
/// `dispatch::notifications::sync` for why this exists (recovering from a posted/removed event
/// missed while disconnected, which the live `notification.posted`/`notification.removed` stream
/// has no way to catch up on by itself).
#[tauri::command]
pub async fn refresh_notifications(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    send_to_active(&state, "notifications.refresh", &EmptyPayload {})
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn dismiss_notification(
    state: tauri::State<'_, Arc<AppState>>,
    id: String,
) -> Result<(), String> {
    state.notifications.lock().await.retain(|n| n.id != id);
    // Doesn't actually clear the native Notification Center banner today — see notify.rs's module doc.
    crate::notify::remove(&state.app_handle, &id);
    send_to_active(&state, "notification.dismiss", &NotificationRemovedPayload { id })
        .await
        .map_err(|e| e.to_string())
}

/// No bulk `notification.dismissAll` on the wire — clears every notification's phone-side copy
/// with the same per-id `notification.dismiss` message `dismiss_notification` sends, just for all
/// of them, while `notify::remove_all` clears every native banner in one call rather than one
/// `removeDeliveredNotificationsWithIdentifiers` round trip per id.
///
/// Skips `ongoing` notifications (Android's FLAG_ONGOING_EVENT/FLAG_NO_CLEAR, e.g. a car-key
/// connection status) entirely: the OS refuses to let a NotificationListenerService cancel those,
/// so sending the dismiss would just silently no-op and the next sync would bring them right back —
/// leave them in `state.notifications` and their native banner alone instead of flashing them away.
#[tauri::command]
pub async fn dismiss_all_notifications(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    let dismissible_ids: Vec<String> = {
        let mut notifications = state.notifications.lock().await;
        let (dismissible, remaining): (Vec<_>, Vec<_>) =
            notifications.drain(..).partition(|n| !n.ongoing);
        *notifications = remaining;
        dismissible.into_iter().map(|n| n.id).collect()
    };
    for id in &dismissible_ids {
        crate::notify::remove(&state.app_handle, id);
    }
    let mut failed = 0usize;
    for id in &dismissible_ids {
        if send_to_active(&state, "notification.dismiss", &NotificationRemovedPayload { id: id.clone() })
            .await
            .is_err()
        {
            failed += 1;
        }
    }
    tracing::info!(
        "dismiss_all_notifications: {} sent, {} failed to send",
        dismissible_ids.len() - failed,
        failed
    );
    Ok(())
}
