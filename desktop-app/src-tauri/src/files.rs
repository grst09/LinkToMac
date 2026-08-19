//! File-browser state, ported from `Storage/FileStore.swift`. Owned server-side (not just in
//! the frontend) for the same reason calls/messages/contacts/photos are: it should survive the
//! FilesView component unmounting when the sidebar switches away and back, and Rust is already
//! the single source of truth for everything else synced from the phone.

use crate::protocol::envelope::FileEntry;

#[derive(Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ClipboardOp {
    Cut,
    Copy,
}

#[derive(Clone, serde::Serialize)]
pub struct FileClipboard {
    pub path: String,
    pub name: String,
    pub operation: ClipboardOp,
}

#[derive(Default)]
pub struct FileState {
    pub current_path: String,
    pub entries: Vec<FileEntry>,
    pub clipboard: Option<FileClipboard>,
    /// Name of the file currently uploading, if any — at most one at a time (see
    /// docs/PLAN.md's Phase D notes: multiple simultaneous drops aren't queued, matching the
    /// old app's actual behavior, not an idealized one).
    pub uploading_file_name: Option<String>,
    /// Set by `download_file` when called with `open: true` (double-click) — the remote path
    /// whose next `files.downloadResult` should be opened with the OS default app instead of
    /// just revealed in Finder. There's no request-id/correlation scheme for downloads (matches
    /// the rest of this feature — see `store::settings::AppSettings::max_transfer_mb`'s doc
    /// comment), so this assumes at most one download is ever in flight at a time, same as
    /// everywhere else in this file.
    pub pending_open_path: Option<String>,
}

/// Parent of a path — used to figure out which directory to re-list after a rename/delete
/// result only echoes back the mutated item's own full path, not its containing directory.
pub fn parent_path(current_path: &str) -> String {
    match current_path.rfind('/') {
        Some(idx) => current_path[..idx].to_string(),
        None => String::new(),
    }
}

/// Where downloaded files land — shared by `dispatch::files::save_file` (which writes into it)
/// and the Settings page's Storage section (which reports its size and can clear it).
pub fn downloads_dir(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    use tauri::Manager;
    let downloads_dir = app.path().download_dir().map_err(|e| e.to_string())?;
    Ok(downloads_dir.join("LinkToMac"))
}
