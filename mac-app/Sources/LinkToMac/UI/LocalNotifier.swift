import Foundation
import UserNotifications

/// Posts mirrored notifications as native macOS banners.
///
/// `UNUserNotificationCenter.current()` throws an uncaught `NSException` (not a
/// catchable Swift error) when the process has no bundle identifier — which is the
/// case when running the raw executable (`swift run`, or Xcode's Run button on the
/// package target) instead of through `Scripts/run.sh`'s `.app` bundle. We detect
/// that up front and skip native banners entirely in that mode; notifications remain
/// visible in the in-app popover list regardless.
enum LocalNotifier {
    private static let isRunningInAppBundle = Bundle.main.bundleIdentifier != nil

    static func requestAuthorization() {
        guard isRunningInAppBundle else {
            print("LinkToMac: not running from an app bundle — native notification banners disabled, use the in-app list instead.")
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func post(_ notification: NotificationPostedPayload) {
        guard isRunningInAppBundle else { return }
        let content = UNMutableNotificationContent()
        content.title = notification.title
        content.subtitle = notification.appName
        content.body = notification.text
        let request = UNNotificationRequest(identifier: notification.id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
