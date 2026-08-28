//! Native OS banners for mirrored Android notifications, ported from `LocalNotifier.swift`.
//!
//! One real platform gap vs. the old app: `UNUserNotificationCenter` on macOS lets you clear a
//! specific delivered banner by identifier (`removeDeliveredNotifications`); the desktop
//! backend of `tauri-plugin-notification` (this crate wraps `notify-rust`, which has no such
//! API on Linux/macOS) doesn't expose an equivalent, so `remove()`/`remove_all()` here are
//! documented no-ops rather than a fake success — the banner stays until the user or the OS
//! dismisses it, even though the in-app notification list (which is the real source of truth)
//! does update.
//!
//! This was tried directly once already: calling `UNUserNotificationCenter` from Rust (via
//! `objc2-user-notifications`) does support removing a specific delivered banner — but
//! `+[UNUserNotificationCenter currentNotificationCenter]` asserts and throws an uncaught
//! NSException (→ SIGABRT) unless the calling process is a genuine, signed `.app` bundle.
//! `tauri dev` runs this backend as a bare binary (`target/debug/desktop-app`), not the bundled
//! `LinkToMac.app`, so that call crashed the app on every dev-mode launch. `notify-rust`'s
//! default macOS backend avoids `UNUserNotificationCenter` for exactly this reason — don't
//! reintroduce it here without first solving that (e.g. gating it to release builds only, which
//! still leaves dev mode broken and untestable).

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
/// no-op (rather than deleted) so the call sites in dispatch.rs/commands/notifications.rs stay
/// self-documenting about what *should* happen here once/if this gets revisited.
pub fn remove(_app: &tauri::AppHandle, _id: &str) {}

/// See [`remove`] — same no-op reasoning, for the "Clear All" path.
pub fn remove_all(_app: &tauri::AppHandle) {}
