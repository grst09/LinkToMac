import Foundation
import Observation

/// Holds the phone's call log, replaced wholesale on every `call.sync` — see
/// docs/PROTOCOL.md's full-snapshot-on-change note.
@Observable
final class CallLogStore {
    private(set) var calls: [CallLogEntry] = []

    func update(_ calls: [CallLogEntry]) {
        self.calls = calls.sorted { $0.date > $1.date }
    }
}
