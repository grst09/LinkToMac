import AppKit
import Darwin

/// Keeps the Mac's system clipboard and the paired Android device's clipboard in sync.
///
/// macOS has no clipboard-change notification API, so local copies are detected by polling
/// `NSPasteboard.changeCount` — the standard approach (same one clipboard-manager apps use).
/// Remote updates are written straight into `NSPasteboard.general`, so a subsequent Cmd+V in
/// any Mac app picks them up exactly like a local copy would.
final class ClipboardSyncManager {
    private let pasteboard = NSPasteboard.general
    private var lastChangeCount: Int
    private var lastSyncedText: String?
    private var timer: Timer?

    /// Fired when a local copy is detected that should be pushed to the phone.
    var onLocalChange: ((String) -> Void)?

    init() {
        lastChangeCount = pasteboard.changeCount
    }

    func start() {
        stop()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.pollLocalClipboard()
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func pollLocalClipboard() {
        guard pasteboard.changeCount != lastChangeCount else { return }
        lastChangeCount = pasteboard.changeCount
        guard let text = pasteboard.string(forType: .string), !text.isEmpty, text != lastSyncedText else { return }
        lastSyncedText = text
        print("LinkToMac: clipboard — local change detected, pushing to phone: \(text.prefix(40))")
        fflush(stdout)
        onLocalChange?(text)
    }

    /// Applies a `clipboard.update` received from the phone. Updates `lastSyncedText` and
    /// `lastChangeCount` first so the next poll doesn't treat this write as a new local copy
    /// and echo it straight back to Android.
    func applyRemoteUpdate(_ text: String) {
        guard text != lastSyncedText else { return }
        lastSyncedText = text
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastChangeCount = pasteboard.changeCount
        print("LinkToMac: clipboard — applied remote update from phone: \(text.prefix(40))")
        fflush(stdout)
    }
}
