use std::sync::Arc;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{EmptyPayload, NoteCreatePayload, NoteDeletePayload, NoteEntry, NoteUpdatePayload};

#[tauri::command]
pub async fn list_notes(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<NoteEntry>, String> {
    Ok(state.notes.lock().await.clone())
}

#[tauri::command]
pub async fn refresh_notes(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    send_to_active(&state, "notes.refresh", &EmptyPayload {})
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn create_note(state: tauri::State<'_, Arc<AppState>>, title: String, body: String) -> Result<(), String> {
    send_to_active(&state, "notes.create", &NoteCreatePayload { title, body })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn update_note(
    state: tauri::State<'_, Arc<AppState>>,
    id: String,
    title: String,
    body: String,
) -> Result<(), String> {
    send_to_active(&state, "notes.update", &NoteUpdatePayload { id, title, body })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_note(state: tauri::State<'_, Arc<AppState>>, id: String) -> Result<(), String> {
    send_to_active(&state, "notes.delete", &NoteDeletePayload { id })
        .await
        .map_err(|e| e.to_string())
}
