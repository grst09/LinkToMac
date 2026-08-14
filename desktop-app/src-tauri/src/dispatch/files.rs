use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use serde::Serialize;
use tauri::{Emitter, Manager};

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    FilesCreateFolderResultPayload, FilesDeleteResultPayload, FilesDownloadResultPayload,
    FilesListRequestPayload, FilesListResultPayload, FilesRenameResultPayload,
    FilesTransferResultPayload, FilesUploadResultPayload,
};

#[derive(Serialize, Clone)]
struct FilesListingEvent {
    path: String,
    entries: Vec<crate::protocol::envelope::FileEntry>,
    error: Option<String>,
}

pub async fn list_result(payload: FilesListResultPayload, state: &std::sync::Arc<AppState>) {
    tracing::info!(
        "files.listResult: {} entries at '{}'{}",
        payload.entries.len(),
        payload.path,
        payload.error.as_ref().map(|e| format!(" (error: {e})")).unwrap_or_default()
    );
    {
        let mut files = state.files.lock().await;
        files.current_path = payload.path.clone();
        files.entries = payload.entries.clone();
    }
    let _ = state.app_handle.emit(
        "files-listing",
        FilesListingEvent {
            path: payload.path,
            entries: payload.entries,
            error: payload.error,
        },
    );
}

pub async fn download_result(payload: FilesDownloadResultPayload, state: &std::sync::Arc<AppState>) {
    let Some(data_base64) = payload.data_base64 else {
        emit_error(state, payload.error.unwrap_or_else(|| format!("Couldn't download {}", payload.name))).await;
        return;
    };

    let result = save_and_reveal(state, &payload.name, &data_base64).await;
    match result {
        Ok(()) => {
            tracing::info!("files.downloadResult: saved and revealed {}", payload.name);
            emit_success(state, format!("Downloaded {}", payload.name)).await;
        }
        Err(e) => {
            tracing::warn!("files.downloadResult: failed to save {}: {}", payload.name, e);
            emit_error(state, format!("Couldn't save {}: {}", payload.name, e)).await;
        }
    }
}

async fn save_and_reveal(
    state: &std::sync::Arc<AppState>,
    name: &str,
    data_base64: &str,
) -> Result<(), String> {
    let bytes = BASE64.decode(data_base64).map_err(|e| e.to_string())?;
    let downloads_dir = state
        .app_handle
        .path()
        .download_dir()
        .map_err(|e| e.to_string())?;
    let dest_dir = downloads_dir.join("LinkToMac");
    std::fs::create_dir_all(&dest_dir).map_err(|e| e.to_string())?;
    let dest = dest_dir.join(name);
    std::fs::write(&dest, bytes).map_err(|e| e.to_string())?;
    tauri_plugin_opener::reveal_item_in_dir(&dest).map_err(|e| e.to_string())
}

pub async fn upload_result(payload: FilesUploadResultPayload, state: &std::sync::Arc<AppState>) {
    state.files.lock().await.uploading_file_name = None;
    let _ = state.app_handle.emit("files-upload-progress", Option::<String>::None);
    if payload.success {
        tracing::info!("files.uploadResult: uploaded {}", payload.name);
        emit_success(state, format!("Uploaded {}", payload.name)).await;
        refresh_listing(state, &payload.path).await;
    } else {
        emit_error(state, payload.error.unwrap_or_else(|| format!("Couldn't upload {}", payload.name))).await;
    }
}

pub async fn create_folder_result(payload: FilesCreateFolderResultPayload, state: &std::sync::Arc<AppState>) {
    if payload.success {
        emit_success(state, format!("Created {}", payload.name)).await;
        refresh_listing(state, &payload.path).await;
    } else {
        emit_error(state, payload.error.unwrap_or_else(|| "Couldn't create folder".to_string())).await;
    }
}

pub async fn rename_result(payload: FilesRenameResultPayload, state: &std::sync::Arc<AppState>) {
    if payload.success {
        emit_success(state, format!("Renamed to {}", payload.new_name)).await;
        let dir = crate::files::parent_path(&payload.path);
        refresh_listing(state, &dir).await;
    } else {
        emit_error(state, payload.error.unwrap_or_else(|| "Couldn't rename".to_string())).await;
    }
}

pub async fn delete_result(payload: FilesDeleteResultPayload, state: &std::sync::Arc<AppState>) {
    if payload.success {
        emit_success(state, "Deleted".to_string()).await;
        let dir = crate::files::parent_path(&payload.path);
        refresh_listing(state, &dir).await;
    } else {
        emit_error(state, payload.error.unwrap_or_else(|| "Couldn't delete".to_string())).await;
    }
}

/// Copy leaves the clipboard alone (so the same item can be pasted again elsewhere); move
/// clears it (a one-shot operation) — matches `applyCopyResult`/`applyMoveResult` exactly.
pub async fn copy_result(payload: FilesTransferResultPayload, state: &std::sync::Arc<AppState>) {
    if payload.success {
        emit_success(state, "Copied".to_string()).await;
        refresh_listing(state, &payload.destination_path).await;
    } else {
        emit_error(state, payload.error.unwrap_or_else(|| "Couldn't copy".to_string())).await;
    }
}

pub async fn move_result(payload: FilesTransferResultPayload, state: &std::sync::Arc<AppState>) {
    if payload.success {
        state.files.lock().await.clipboard = None;
        emit_success(state, "Moved".to_string()).await;
        refresh_listing(state, &payload.destination_path).await;
    } else {
        emit_error(state, payload.error.unwrap_or_else(|| "Couldn't move".to_string())).await;
    }
}

async fn refresh_listing(state: &std::sync::Arc<AppState>, path: &str) {
    let _ = send_to_active(
        state,
        "files.list",
        &FilesListRequestPayload { path: path.to_string() },
    )
    .await;
}

async fn emit_success(state: &std::sync::Arc<AppState>, message: String) {
    let _ = state.app_handle.emit("files-success", message);
}

async fn emit_error(state: &std::sync::Arc<AppState>, message: String) {
    tracing::warn!("files operation failed: {}", message);
    let _ = state.app_handle.emit("files-error", message);
}
