use std::sync::Arc;

use serde::Serialize;
use tauri::Emitter;

use crate::files::{ClipboardOp, DownloadIntent, FileClipboard};
use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    FileEntry, FilesCreateFolderPayload, FilesDeletePayload, FilesDownloadRequestPayload,
    FilesListRequestPayload, FilesRenamePayload, FilesTransferPayload, FilesUploadPayload,
};

#[derive(Serialize)]
pub struct FilesSnapshot {
    current_path: String,
    entries: Vec<FileEntry>,
    clipboard: Option<FileClipboard>,
}

#[tauri::command]
pub async fn get_files_state(state: tauri::State<'_, Arc<AppState>>) -> Result<FilesSnapshot, String> {
    let files = state.files.lock().await;
    Ok(FilesSnapshot {
        current_path: files.current_path.clone(),
        entries: files.entries.clone(),
        clipboard: files.clipboard.clone(),
    })
}

#[tauri::command]
pub async fn list_files(state: tauri::State<'_, Arc<AppState>>, path: String) -> Result<(), String> {
    send_to_active(&state, "files.list", &FilesListRequestPayload { path })
        .await
        .map_err(|e| e.to_string())
}

/// `open: true` (double-click) opens the downloaded file with the OS default app once it
/// arrives; `open: false` (the "Download" context-menu item) keeps the original behavior of
/// saving it and revealing it in Finder — see `dispatch::files::download_result`.
#[tauri::command]
pub async fn download_file(
    state: tauri::State<'_, Arc<AppState>>,
    path: String,
    open: bool,
) -> Result<(), String> {
    let intent = if open { DownloadIntent::Open } else { DownloadIntent::Reveal };
    state.files.lock().await.pending_downloads.push_back((path.clone(), intent));
    send_to_active(&state, "files.download", &FilesDownloadRequestPayload { path })
        .await
        .map_err(|e| e.to_string())
}

/// Fetches a file's bytes for the Files preview panel (selecting a file in Preview view)
/// without saving anything to disk — see `dispatch::files::download_result`'s preview branch.
/// Browsing Preview view fires one of these per click, often before the last one's response has
/// come back, so this — unlike a single "pending path" flag — has to tolerate several being in
/// flight at once.
#[tauri::command]
pub async fn preview_file(state: tauri::State<'_, Arc<AppState>>, path: String) -> Result<(), String> {
    state.files.lock().await.pending_downloads.push_back((path.clone(), DownloadIntent::Preview));
    send_to_active(&state, "files.download", &FilesDownloadRequestPayload { path })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn upload_file(
    state: tauri::State<'_, Arc<AppState>>,
    path: String,
    name: String,
    data_base64: String,
    mime_type: String,
) -> Result<(), String> {
    let max_transfer_mb = state.settings.lock().await.get().max_transfer_mb;
    let byte_len = data_base64.len() / 4 * 3; // close enough for a size-cap check on base64 text
    if byte_len > (max_transfer_mb as usize) * 1024 * 1024 {
        let message = format!("{name} is too large to transfer ({max_transfer_mb} MB max)");
        let _ = state.app_handle.emit("files-error", &message);
        return Err(message);
    }
    state.files.lock().await.uploading_file_name = Some(name.clone());
    let _ = state.app_handle.emit("files-upload-progress", Some(name.clone()));
    send_to_active(
        &state,
        "files.upload",
        &FilesUploadPayload { path, name, data_base64, mime_type },
    )
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn create_folder(state: tauri::State<'_, Arc<AppState>>, name: String) -> Result<(), String> {
    let path = state.files.lock().await.current_path.clone();
    send_to_active(&state, "files.createFolder", &FilesCreateFolderPayload { path, name })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn rename_file(
    state: tauri::State<'_, Arc<AppState>>,
    path: String,
    new_name: String,
) -> Result<(), String> {
    send_to_active(&state, "files.rename", &FilesRenamePayload { path, new_name })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_file(state: tauri::State<'_, Arc<AppState>>, path: String) -> Result<(), String> {
    send_to_active(&state, "files.delete", &FilesDeletePayload { path })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn cut_to_clipboard(state: tauri::State<'_, Arc<AppState>>, path: String, name: String) -> Result<(), String> {
    state.files.lock().await.clipboard = Some(FileClipboard { path, name, operation: ClipboardOp::Cut });
    Ok(())
}

#[tauri::command]
pub async fn copy_to_clipboard(state: tauri::State<'_, Arc<AppState>>, path: String, name: String) -> Result<(), String> {
    state.files.lock().await.clipboard = Some(FileClipboard { path, name, operation: ClipboardOp::Copy });
    Ok(())
}

/// Decides `files.copy` vs `files.move` based on the pending clipboard operation — always
/// targets the currently-open directory, matching `pasteClipboard()`'s exact semantics.
#[tauri::command]
pub async fn paste_clipboard(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    let (clipboard, destination) = {
        let files = state.files.lock().await;
        (files.clipboard.clone(), files.current_path.clone())
    };
    let Some(clipboard) = clipboard else {
        return Ok(());
    };
    let message_type = match clipboard.operation {
        ClipboardOp::Copy => "files.copy",
        ClipboardOp::Cut => "files.move",
    };
    send_to_active(
        &state,
        message_type,
        &FilesTransferPayload {
            source_path: clipboard.path,
            destination_path: destination,
        },
    )
    .await
    .map_err(|e| e.to_string())
}
