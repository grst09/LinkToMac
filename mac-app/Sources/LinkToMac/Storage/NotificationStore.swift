import Foundation
import Observation

/// In-memory feed of mirrored notifications, newest first, shown in the main window's
/// Notifications section. Capped so a chatty phone can't grow this unbounded; persistence
/// across launches is a later-phase concern.
@Observable
final class NotificationStore {
    private(set) var notifications: [NotificationPostedPayload] = []
    private let maxCount = 200

    func add(_ notification: NotificationPostedPayload) {
        notifications.removeAll { $0.id == notification.id }
        notifications.insert(notification, at: 0)
        if notifications.count > maxCount {
            notifications.removeLast(notifications.count - maxCount)
        }
        LocalNotifier.post(notification)
    }

    func remove(id: String) {
        notifications.removeAll { $0.id == id }
        LocalNotifier.remove(id: id)
    }

    func clear() {
        notifications.removeAll()
    }
}
