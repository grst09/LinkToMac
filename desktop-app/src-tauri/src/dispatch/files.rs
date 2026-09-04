use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use serde::Serialize;
use tauri::Emitter;

use crate::files::DownloadIntent;
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

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct FilesPreviewEvent {
    path: String,
    name: String,
    mime_type: Option<String>,
    data_base64: String,
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
    let intent = {
        let mut files = state.files.lock().await;
        // Match by path (a click in Preview view can leapfrog an earlier one that hasn't
        // resolved yet, so the oldest in-flight entry isn't always the right one), falling back
        // to the front of the queue if somehow nothing matches.
        let idx = files
            .pending_downloads
            .iter()
            .position(|(path, _)| path == &payload.path)
            .unwrap_or(0);
        files.pending_downloads.remove(idx).map(|(_, intent)| intent)
    };

    // No matching in-flight request — a stale/duplicate response for something already
    // resolved. Silently drop it rather than guessing at what to do with it (that guess is
    // exactly what used to make Preview view download-and-reveal-in-Finder every file you
    // clicked past).
    let Some(intent) = intent else {
        tracing::warn!("files.downloadResult: no in-flight request for '{}', dropping", payload.path);
        return;
    };

    if matches!(intent, DownloadIntent::Preview) {
        match payload.data_base64 {
            Some(data_base64) => emit_preview(state, payload.path, payload.name, payload.mime_type, data_base64).await,
            None => {
                emit_error(state, payload.error.unwrap_or_else(|| format!("Couldn't preview {}", payload.name))).await;
            }
        }
        return;
    }

    let Some(data_base64) = payload.data_base64 else {
        emit_error(state, payload.error.unwrap_or_else(|| format!("Couldn't download {}", payload.name))).await;
        return;
    };

    let should_open = matches!(intent, DownloadIntent::Open);
    let result = save_file(state, &payload.name, &data_base64, should_open).await;
    match result {
        Ok(()) => {
            tracing::info!(
                "files.downloadResult: saved {} ({})",
                payload.name,
                if should_open { "opened" } else { "revealed" }
            );
            // Opening a file hands off to the OS default app immediately, which is its own
            // feedback — only the silent "reveal in Finder" path (the Download menu action)
            // needs a banner to tell the user anything happened at all.
            if !should_open {
                emit_success(state, format!("Downloaded {}", payload.name)).await;
            }
        }
        Err(e) => {
            tracing::warn!("files.downloadResult: failed to save {}: {}", payload.name, e);
            emit_error(state, format!("Couldn't save {}: {}", payload.name, e)).await;
        }
    }
}

/// Saves the downloaded bytes to `~/Downloads/LinkToMac`, then either opens the file with the
/// OS default app (double-click) or reveals it in Finder (the "Download" menu action) — see
/// `commands::files::download_file`'s `open` flag.
async fn save_file(
    state: &std::sync::Arc<AppState>,
    name: &str,
    data_base64: &str,
    open: bool,
) -> Result<(), String> {
    let bytes = BASE64.decode(data_base64).map_err(|e| e.to_string())?;
    let dest_dir = crate::files::downloads_dir(&state.app_handle)?;
    std::fs::create_dir_all(&dest_dir).map_err(|e| e.to_string())?;
    let dest = dest_dir.join(name);
    std::fs::write(&dest, bytes).map_err(|e| e.to_string())?;
    if open {
        tauri_plugin_opener::open_path(&dest, None::<&str>).map_err(|e| e.to_string())
    } else {
        tauri_plugin_opener::reveal_item_in_dir(&dest).map_err(|e| e.to_string())
    }
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

/// Images, videos, and audio go straight to the frontend as-is (an `<img>`/`<video>`/`<audio>`
/// can each play its own bytes directly); everything else (PDF, Word, PowerPoint, …) is
/// rendered to a PNG via Quick Look first — either way the frontend just gets media bytes, it
/// never needs to know which path was taken.
async fn emit_preview(
    state: &std::sync::Arc<AppState>,
    path: String,
    name: String,
    mime_type: Option<String>,
    data_base64: String,
) {
    if crate::files::is_image_name(&name) || crate::files::is_video_name(&name) || crate::files::is_audio_name(&name)
    {
        let _ = state.app_handle.emit("files-preview", FilesPreviewEvent { path, name, mime_type, data_base64 });
        return;
    }

    let thumb_name = name.clone();
    let result = tokio::task::spawn_blocking(move || {
        let bytes = BASE64.decode(&data_base64).map_err(|e| e.to_string())?;
        crate::quicklook::thumbnail(&thumb_name, &bytes)
    })
    .await;

    match result {
        Ok(Ok(thumb_bytes)) => {
            let _ = state.app_handle.emit(
                "files-preview",
                FilesPreviewEvent {
                    path,
                    name,
                    mime_type: Some("image/png".to_string()),
                    data_base64: BASE64.encode(thumb_bytes),
                },
            );
        }
        Ok(Err(e)) => emit_error(state, format!("Couldn't preview {name}: {e}")).await,
        Err(e) => emit_error(state, format!("Couldn't preview {name}: {e}")).await,
    }
}

async fn emit_success(state: &std::sync::Arc<AppState>, message: String) {
    let _ = state.app_handle.emit("files-success", message);
}

async fn emit_error(state: &std::sync::Arc<AppState>, message: String) {
    tracing::warn!("files operation failed: {}", message);
    let _ = state.app_handle.emit("files-error", message);
}
