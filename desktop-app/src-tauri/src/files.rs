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
}

/// Parent of a path — used to figure out which directory to re-list after a rename/delete
/// result only echoes back the mutated item's own full path, not its containing directory.
pub fn parent_path(current_path: &str) -> String {
    match current_path.rfind('/') {
        Some(idx) => current_path[..idx].to_string(),
        None => String::new(),
    }
}
