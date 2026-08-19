use std::sync::Arc;

use tauri::Emitter;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    EmptyPayload, NoteCreatePayload, NoteDeletePayload, NoteEntry, NoteSetPinnedPayload, NoteUpdatePayload,
};
use crate::store::local_notes::PendingNote;

#[tauri::command]
pub async fn list_notes(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<NoteEntry>, String> {
    Ok(state.notes.lock().await.clone())
}

#[tauri::command]
pub async fn list_local_notes(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<PendingNote>, String> {
    Ok(state.local_notes.lock().await.pending())
}

#[tauri::command]
pub async fn refresh_notes(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    send_to_active(&state, "notes.refresh", &EmptyPayload {})
        .await
        .map_err(|e| e.to_string())
}

/// While notes sync is off, a new note stays entirely local (see `store/local_notes.rs`) instead
/// of being sent to the phone — it gets pushed for real once sync is re-enabled.
#[tauri::command]
pub async fn create_note(state: tauri::State<'_, Arc<AppState>>, title: String, body: String) -> Result<(), String> {
    let notes_enabled = state.sync_settings.lock().await.notes_enabled;
    if !notes_enabled {
        let mut store = state.local_notes.lock().await;
        store.add(title, body);
        let pending = store.pending();
        drop(store);
        let _ = state.app_handle.emit("local-notes-updated", pending);
        return Ok(());
    }
    send_to_active(&state, "notes.create", &NoteCreatePayload { title, body })
        .await
        .map_err(|e| e.to_string())
}

/// `id` starting with `"local-"` means the note only ever existed on the Mac (never sent to the
/// phone) — those can always be edited/deleted locally regardless of the sync toggle, since
/// there's no phone-side copy to conflict with. Any other id is phone-origin: editing it requires
/// sync to be on (no offline-merge story for mutating a record we don't have live access to).
#[tauri::command]
pub async fn update_note(state: tauri::State<'_, Arc<AppState>>, id: String, title: String, body: String) -> Result<(), String> {
    if id.starts_with("local-") {
        let mut store = state.local_notes.lock().await;
        store.update(&id, title, body);
        let pending = store.pending();
        drop(store);
        let _ = state.app_handle.emit("local-notes-updated", pending);
        return Ok(());
    }
    if !state.sync_settings.lock().await.notes_enabled {
        return Err("Notes sync is off — turn it back on to edit synced notes".to_string());
    }
    send_to_active(&state, "notes.update", &NoteUpdatePayload { id, title, body })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_note(state: tauri::State<'_, Arc<AppState>>, id: String) -> Result<(), String> {
    if id.starts_with("local-") {
        let mut store = state.local_notes.lock().await;
        store.remove(&id);
        let pending = store.pending();
        drop(store);
        let _ = state.app_handle.emit("local-notes-updated", pending);
        return Ok(());
    }
    if !state.sync_settings.lock().await.notes_enabled {
        return Err("Notes sync is off — turn it back on to edit synced notes".to_string());
    }
    send_to_active(&state, "notes.delete", &NoteDeletePayload { id })
        .await
        .map_err(|e| e.to_string())
}

/// Same local-vs-phone-origin id branching as `update_note`/`delete_note` — pinning a purely
/// local note is always allowed, pinning a phone-origin one needs sync on.
#[tauri::command]
pub async fn set_note_pinned(state: tauri::State<'_, Arc<AppState>>, id: String, is_pinned: bool) -> Result<(), String> {
    if id.starts_with("local-") {
        let mut store = state.local_notes.lock().await;
        store.set_pinned(&id, is_pinned);
        let pending = store.pending();
        drop(store);
        let _ = state.app_handle.emit("local-notes-updated", pending);
        return Ok(());
    }
    if !state.sync_settings.lock().await.notes_enabled {
        return Err("Notes sync is off — turn it back on to edit synced notes".to_string());
    }
    send_to_active(&state, "note.setPinned", &NoteSetPinnedPayload { id, is_pinned })
        .await
        .map_err(|e| e.to_string())
}
