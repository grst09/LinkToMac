import Foundation
import Observation

@Observable
final class ContactStore {
    private(set) var contacts: [ContactEntry] = []
    private(set) var lastSyncedAt: Date?
    private(set) var lastError: String?

    func update(_ contacts: [ContactEntry]) {
        self.contacts = contacts.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        lastSyncedAt = Date()
    }

    func applyOperationResult(success: Bool, error: String?) {
        if !success {
            lastError = error
        }
    }

    func clearError() {
        lastError = nil
    }
}
