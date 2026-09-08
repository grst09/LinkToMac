use tauri::Emitter;

use crate::net::server::AppState;
use crate::protocol::envelope::{NotificationPostedPayload, NotificationRemovedPayload, NotificationsSyncPayload};

const MAX_NOTIFICATIONS: usize = 200;

/// Mirrors `NotificationStore.add` exactly: dedupe by id, insert at front (newest first), cap
/// at 200. Also fires the native OS banner, matching `LocalNotifier.post`.
pub async fn posted(payload: NotificationPostedPayload, state: &std::sync::Arc<AppState>) {
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
    if state.settings.lock().await.get().show_notification_banners {
        crate::notify::post(&state.app_handle, &payload);
    }
}

/// Mirrors `NotificationStore.remove` — also clears the delivered OS banner, matching
/// `LocalNotifier.remove`.
pub async fn removed(payload: NotificationRemovedPayload, state: &std::sync::Arc<AppState>) {
    state.notifications.lock().await.retain(|n| n.id != payload.id);
    tracing::info!("notification.removed: {}", payload.id);
    let _ = state.app_handle.emit("notification-removed", payload.clone());
    crate::notify::remove(&state.app_handle, &payload.id);
}

/// Full-snapshot replace — response to `refresh_notifications` (see commands/notifications.rs).
/// Unlike `posted`/`removed`, which only ever move the in-memory list forward one event at a
/// time, this reconciles it against whatever's *actually* showing on the phone right now — the
/// fix for a notification that was posted or dismissed while the connection was down and so
/// never reached either of those handlers. No native banners fire here (unlike `posted`): this is
/// catching up on state, not announcing something new.
pub async fn sync(payload: NotificationsSyncPayload, state: &std::sync::Arc<AppState>) {
    let mut notifications = payload.notifications;
    notifications.truncate(MAX_NOTIFICATIONS);
    tracing::info!("notifications.sync: {} notifications", notifications.len());
    *state.notifications.lock().await = notifications.clone();
    let _ = state.app_handle.emit("notifications-synced", notifications);
}
