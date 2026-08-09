import Foundation
import Observation

/// Holds the phone's SMS threads, replaced wholesale on every `sms.sync` — see
/// docs/PROTOCOL.md's full-snapshot-on-change note.
@Observable
final class MessageStore {
    private(set) var threads: [SmsThread] = []

    func update(_ threads: [SmsThread]) {
        self.threads = threads.sorted { ($0.messages.last?.date ?? 0) > ($1.messages.last?.date ?? 0) }
    }
}
