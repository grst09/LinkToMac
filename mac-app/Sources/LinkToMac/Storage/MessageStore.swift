import Foundation
import Observation

/// Holds the phone's SMS threads, replaced wholesale on every `sms.sync` — see
/// docs/PROTOCOL.md's full-snapshot-on-change note.
///
/// Also tracks "local-only" threads: a message sent to a brand-new conversation (no existing
/// address in `threads`) never gets recorded on the phone's side at all when LinkToMac isn't
/// the default SMS app — Android restricts writes to the shared SMS database to whichever app
/// holds that role, and a third-party app's `SmsManager.sendTextMessage` call doesn't put it
/// back into the provider LinkToMac reads from, confirmed by directly querying the phone's SMS
/// database and finding no row for a message Google Messages itself showed as sent. So these
/// threads exist purely to show optimistic confirmation the text went out — they'll never
/// receive replies, since the phone has nothing to attach them to. If a real thread for that
/// address ever does show up in a sync (e.g. the recipient's reply happens to create one, or
/// this address was already a real conversation), the local-only copy is dropped in favor of it.
@Observable
final class MessageStore {
    private(set) var threads: [SmsThread] = []
    private(set) var localOnlyThreads: [SmsThread] = []

    /// The list the UI should actually render — real threads plus any local-only ones whose
    /// address doesn't (yet) have a real thread, sorted together by most recent message.
    var allThreads: [SmsThread] {
        let realAddresses = Set(threads.map(\.address))
        let merged = threads + localOnlyThreads.filter { !realAddresses.contains($0.address) }
        return merged.sorted { ($0.messages.last?.date ?? 0) > ($1.messages.last?.date ?? 0) }
    }

    func update(_ threads: [SmsThread]) {
        self.threads = threads.sorted { ($0.messages.last?.date ?? 0) > ($1.messages.last?.date ?? 0) }
        let realAddresses = Set(self.threads.map(\.address))
        localOnlyThreads.removeAll { realAddresses.contains($0.address) }
    }

    /// Called right after sending the first message of a new conversation. Returns the
    /// synthetic thread id so the caller can select it immediately, without waiting on a sync
    /// that will never arrive.
    @discardableResult
    func addLocalMessage(address: String, body: String) -> String {
        let threadId = Self.localThreadId(for: address)
        let message = SmsMessage(
            id: UUID().uuidString,
            address: address,
            body: body,
            date: Date().timeIntervalSince1970 * 1000,
            isOutgoing: true
        )
        if let index = localOnlyThreads.firstIndex(where: { $0.threadId == threadId }) {
            let existing = localOnlyThreads[index]
            localOnlyThreads[index] = SmsThread(
                threadId: threadId,
                address: address,
                contactName: existing.contactName,
                messages: existing.messages + [message]
            )
        } else {
            localOnlyThreads.append(SmsThread(threadId: threadId, address: address, contactName: nil, messages: [message]))
        }
        return threadId
    }

    static func localThreadId(for address: String) -> String {
        "local:\(address)"
    }

    static func isLocalOnly(_ threadId: String) -> Bool {
        threadId.hasPrefix("local:")
    }
}
