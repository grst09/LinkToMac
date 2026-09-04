use std::sync::Arc;

use tauri::Emitter;

use crate::clipboard::{ordered, ClipboardEntry};
use crate::net::server::AppState;

#[tauri::command]
pub async fn list_clipboard_history(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<ClipboardEntry>, String> {
    Ok(ordered(&state.clipboard_history.lock().await))
}

#[tauri::command]
pub async fn set_clipboard_entry_pinned(
    state: tauri::State<'_, Arc<AppState>>,
    id: String,
    pinned: bool,
) -> Result<(), String> {
    let history = {
        let mut history = state.clipboard_history.lock().await;
        if let Some(entry) = history.iter_mut().find(|e| e.id == id) {
            entry.is_pinned = pinned;
        }
        history.clone()
    };
    let _ = state.app_handle.emit("clipboard-history-updated", ordered(&history));
    Ok(())
}

/// Re-copies an old history entry back onto the Mac's system clipboard — a "copy again" action.
/// This is a genuinely new local copy from the poll loop's perspective, so it naturally gets
/// pushed to the phone and re-recorded into history on the next tick, same as any other copy.
#[tauri::command]
pub async fn copy_clipboard_entry(text: String) -> Result<(), String> {
    tokio::task::spawn_blocking(move || arboard::Clipboard::new()?.set_text(text))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())
}

/// Image counterpart to `copy_clipboard_entry` — same "copy again" role, same downstream
/// re-sync-on-next-tick behavior.
#[tauri::command]
pub async fn copy_clipboard_image_entry(image_base64: String) -> Result<(), String> {
    tokio::task::spawn_blocking(move || -> anyhow::Result<()> {
        use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
        let png_bytes = BASE64.decode(&image_base64)?;
        let rgba = image::load_from_memory(&png_bytes)?.into_rgba8();
        let (width, height) = rgba.dimensions();
        arboard::Clipboard::new()?.set_image(arboard::ImageData {
            width: width as usize,
            height: height as usize,
            bytes: rgba.into_raw().into(),
        })?;
        Ok(())
    })
    .await
    .map_err(|e| e.to_string())?
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn clear_clipboard_history(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    state.clipboard_history.lock().await.clear();
    Ok(())
}
