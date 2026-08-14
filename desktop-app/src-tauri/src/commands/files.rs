use std::sync::Arc;

use serde::Serialize;
use tauri::Emitter;

use crate::files::{ClipboardOp, FileClipboard};
use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    FileEntry, FilesCreateFolderPayload, FilesDeletePayload, FilesDownloadRequestPayload,
    FilesListRequestPayload, FilesRenamePayload, FilesTransferPayload, FilesUploadPayload,
};

/// Matches the server-side cap exactly (docs/PROTOCOL.md: enforced on both sides) — checked
/// client-side before ever sending a byte, same as `FilesView.swift`'s upload guard. Download
/// has no equivalent pre-check, also matching the old app (see docs/PLAN.md's Phase D notes).
const MAX_TRANSFER_BYTES: usize = 50 * 1024 * 1024;

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

#[tauri::command]
pub async fn download_file(state: tauri::State<'_, Arc<AppState>>, path: String) -> Result<(), String> {
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
    let byte_len = data_base64.len() / 4 * 3; // close enough for a size-cap check on base64 text
    if byte_len > MAX_TRANSFER_BYTES {
        let message = format!("{name} is too large to transfer (50 MB max)");
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
