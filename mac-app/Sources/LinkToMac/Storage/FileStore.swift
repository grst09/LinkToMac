import Foundation
import Observation

/// Current directory listing for the Files browser. `currentPath` is always relative to the
/// phone's shared storage root ("" is that root) — see FilesListRequestPayload in
/// docs/PROTOCOL.md.
///
/// Owned by `ConnectionServer`, so it (and `viewMode`/clipboard below) survives FilesView being
/// torn down and recreated when the sidebar selection switches away and back — plain `@State`
/// in the view doesn't.
@Observable
final class FileStore {
    enum ViewMode {
        case grid, list
    }

    enum ClipboardOperation {
        case copy, cut
    }

    private(set) var currentPath: String = ""
    private(set) var entries: [FileEntry] = []
    private(set) var isLoading = false
    private(set) var lastError: String?
    /// Set after a successful upload/folder creation so the view can show a transient banner
    /// and auto-select the new item; the view is responsible for clearing it after display.
    private(set) var lastSuccessMessage: String?
    private(set) var lastCreatedOrUploadedName: String?
    /// Name of the file currently mid-upload, or nil. There's no chunked transfer over the
    /// wire (see docs/PROTOCOL.md) so this can only drive an indeterminate spinner, not a real
    /// percentage.
    private(set) var uploadingFileName: String?

    var viewMode: ViewMode = .grid

    private(set) var clipboardPath: String?
    private(set) var clipboardName: String?
    private(set) var clipboardOperation: ClipboardOperation?

    func beginLoading() {
        isLoading = true
    }

    func beginUpload(name: String) {
        uploadingFileName = name
    }

    func applyListResult(path: String, entries: [FileEntry], error: String?) {
        currentPath = path
        self.entries = entries
        lastError = error
        isLoading = false
    }

    func applyUploadResult(name: String, success: Bool, error: String?) {
        if uploadingFileName == name {
            uploadingFileName = nil
        }
        if success {
            lastSuccessMessage = "Uploaded \(name)"
            lastCreatedOrUploadedName = name
        } else {
            lastError = error
        }
    }

    func applyCreateFolderResult(name: String, success: Bool, error: String?) {
        if success {
            lastSuccessMessage = "Created \(name)"
            lastCreatedOrUploadedName = name
        } else {
            lastError = error
        }
    }

    func applyOperationResult(successMessage: String?, success: Bool, error: String?) {
        if success {
            lastSuccessMessage = successMessage
        } else {
            lastError = error
        }
    }

    func applyDownloadError(_ error: String) {
        lastError = error
    }

    func clearSuccessMessage() {
        lastSuccessMessage = nil
    }

    func clearError() {
        lastError = nil
    }

    func setClipboard(path: String, name: String, operation: ClipboardOperation) {
        clipboardPath = path
        clipboardName = name
        clipboardOperation = operation
    }

    func clearClipboard() {
        clipboardPath = nil
        clipboardName = nil
        clipboardOperation = nil
    }
}
