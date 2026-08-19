use tauri::Emitter;

use crate::net::server::AppState;
use crate::protocol::envelope::{
    NoteCreateResultPayload, NoteDeleteResultPayload, NoteUpdateResultPayload, NotesSyncPayload,
};

/// Full-snapshot replace, newest-edited first — matches `NoteStore.readAll` on the Android side.
pub async fn sync(payload: NotesSyncPayload, state: &std::sync::Arc<AppState>) {
    let mut notes = payload.notes;
    notes.sort_by(|a, b| b.updated_at.total_cmp(&a.updated_at));
    tracing::info!("notes.sync: {} notes", notes.len());
    *state.notes.lock().await = notes.clone();
    let _ = state.app_handle.emit("notes-updated", notes);
}

/// Same pattern as Contacts: the three mutation results only ever surface a *failure* — a
/// successful create/update/delete is reflected by the `notes.sync` that immediately follows it,
/// not applied optimistically here.
pub async fn create_result(payload: NoteCreateResultPayload, state: &std::sync::Arc<AppState>) {
    if !payload.success {
        emit_error(state, payload.error).await;
    }
}

pub async fn update_result(payload: NoteUpdateResultPayload, state: &std::sync::Arc<AppState>) {
    if !payload.success {
        emit_error(state, payload.error).await;
    }
}

pub async fn delete_result(payload: NoteDeleteResultPayload, state: &std::sync::Arc<AppState>) {
    if !payload.success {
        emit_error(state, payload.error).await;
    }
}

async fn emit_error(state: &std::sync::Arc<AppState>, error: Option<String>) {
    let message = error.unwrap_or_else(|| "Something went wrong".to_string());
    tracing::warn!("notes operation failed: {}", message);
    let _ = state.app_handle.emit("notes-error", message);
}
