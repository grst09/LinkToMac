import Foundation
import UserNotifications

/// Posts mirrored notifications as native macOS banners — the only place mirrored
/// notifications show up; the menu bar popover only shows connection status.
///
/// `UNUserNotificationCenter.current()` throws an uncaught `NSException` (not a
/// catchable Swift error) when the process has no bundle identifier — which is the
/// case when running the raw executable (`swift run`, or Xcode's Run button on the
/// package target) instead of through `Scripts/run.sh`'s `.app` bundle. We detect
/// that up front and skip native banners entirely in that mode.
enum LocalNotifier {
    private static let isRunningInAppBundle = Bundle.main.bundleIdentifier != nil

    static func requestAuthorization() {
        guard isRunningInAppBundle else {
            print("LinkToMac: not running from an app bundle — native notification banners disabled, use the in-app list instead.")
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                print("LinkToMac: notification authorization request failed: \(error)")
            } else {
                print("LinkToMac: notification authorization granted=\(granted)")
            }
        }
    }

    static func post(_ notification: NotificationPostedPayload) {
        guard isRunningInAppBundle else { return }
        let content = UNMutableNotificationContent()
        content.title = notification.title
        content.subtitle = notification.appName
        content.body = notification.text
        let request = UNNotificationRequest(identifier: notification.id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                print("LinkToMac: failed to post notification: \(error)")
            }
        }
    }

    /// Clears the delivered banner/Notification Center entry when the phone reports the
    /// underlying notification was dismissed there, keeping the two sides in sync.
    static func remove(id: String) {
        guard isRunningInAppBundle else { return }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
    }
}
