//! Native OS banners for mirrored Android notifications, ported from `LocalNotifier.swift`.
//!
//! One real platform gap vs. the old app: `UNUserNotificationCenter` on macOS lets you clear a
//! specific delivered banner by identifier (`removeDeliveredNotifications`); the desktop
//! backend of `tauri-plugin-notification` (this crate wraps `notify-rust`, which has no such
//! API on Linux/macOS) doesn't expose an equivalent, so `remove()` here is a documented no-op
//! rather than a fake success — the banner stays until the user or the OS dismisses it, even
//! though the in-app notification list (which is the real source of truth) does update.

use tauri_plugin_notification::NotificationExt;

use crate::protocol::envelope::NotificationPostedPayload;

pub fn request_authorization(app: &tauri::AppHandle) {
    match app.notification().request_permission() {
        Ok(state) => tracing::info!("notification permission: {:?}", state),
        Err(e) => tracing::warn!("notification permission request failed: {}", e),
    }
}

pub fn post(app: &tauri::AppHandle, notification: &NotificationPostedPayload) {
    let body = if notification.text.is_empty() {
        notification.app_name.clone()
    } else {
        format!("{} — {}", notification.app_name, notification.text)
    };
    let result = app
        .notification()
        .builder()
        .title(&notification.title)
        .body(body)
        .show();
    if let Err(e) = result {
        tracing::warn!("failed to post native notification: {}", e);
    }
}

/// See module doc — this can't actually clear the OS banner on desktop today, kept as a named
/// no-op (rather than deleted) so the call site in dispatch.rs stays self-documenting about
/// what *should* happen here once/if the underlying crate supports it.
pub fn remove(_app: &tauri::AppHandle, _id: &str) {}
