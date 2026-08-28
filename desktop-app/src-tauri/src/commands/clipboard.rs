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

#[tauri::command]
pub async fn clear_clipboard_history(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    state.clipboard_history.lock().await.clear();
    Ok(())
}
