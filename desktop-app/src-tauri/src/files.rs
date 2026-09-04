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

/// What `dispatch::files::download_result` should do with a `files.download` response —
/// recorded per-request in `FileState::pending_downloads` so it's no longer just "the last
/// request wins" (see that field's doc comment for why that broke).
#[derive(Clone, Copy)]
pub enum DownloadIntent {
    /// The "Download" context-menu item — save to `~/Downloads/LinkToMac` and reveal in Finder.
    Reveal,
    /// A double-click — save to `~/Downloads/LinkToMac` and open with the OS default app.
    Open,
    /// A selection in the Files preview panel — hand the bytes straight to the frontend, never
    /// touch disk.
    Preview,
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
    /// FIFO queue of in-flight `files.download` requests and what each one's result should do —
    /// `download_file` and `preview_file` both push onto this, and `download_result` matches a
    /// response back to its request by path (falling back to the front of the queue) and removes
    /// it. There's no request-id/correlation scheme in the wire protocol, so this still assumes
    /// responses arrive in the order they were requested (true for a single WebSocket
    /// connection) — but unlike the single-path flags this replaced, it correctly handles
    /// several downloads in flight at once, which Preview view causes constantly (clicking
    /// through files quickly starts a new preview fetch before the last one's response has
    /// arrived). A response with no matching entry is a stale/duplicate and is just dropped,
    /// instead of falling through and being treated as some other request's download.
    pub pending_downloads: std::collections::VecDeque<(String, DownloadIntent)>,
}

const IMAGE_EXTENSIONS: &[&str] = &["png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg"];
const VIDEO_EXTENSIONS: &[&str] = &["mp4", "mov", "m4v", "webm"];
const AUDIO_EXTENSIONS: &[&str] = &["mp3", "m4a", "aac", "wav", "flac"];

fn has_extension(name: &str, extensions: &[&str]) -> bool {
    match name.rsplit('.').next() {
        Some(ext) if ext != name => extensions.contains(&ext.to_lowercase().as_str()),
        _ => false,
    }
}

/// Whether a downloaded file's bytes can go straight to the frontend as an image, vs. needing
/// `quicklook::thumbnail` first — mirrors the frontend's own `isPreviewableImage` in
/// `theme/fileTypeIcons.ts`.
pub fn is_image_name(name: &str) -> bool {
    has_extension(name, IMAGE_EXTENSIONS)
}

/// Whether a downloaded file's bytes can go straight to the frontend as a `<video>` source —
/// mirrors the frontend's own `isPreviewableVideo` in `theme/fileTypeIcons.ts`.
pub fn is_video_name(name: &str) -> bool {
    has_extension(name, VIDEO_EXTENSIONS)
}

/// Whether a downloaded file's bytes can go straight to the frontend as an `<audio>` source —
/// mirrors the frontend's own `isPreviewableAudio` in `theme/fileTypeIcons.ts`.
pub fn is_audio_name(name: &str) -> bool {
    has_extension(name, AUDIO_EXTENSIONS)
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
