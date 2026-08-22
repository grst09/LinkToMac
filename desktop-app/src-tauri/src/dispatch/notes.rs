use tauri::Emitter;

use crate::net::server::AppState;
use crate::protocol::envelope::{
    NoteCreateResultPayload, NoteDeleteResultPayload, NoteSetPinnedResultPayload,
    NoteUpdateResultPayload, NotesSyncPayload,
};
use crate::store::pending_note_mutations::PendingNoteMutation;

/// Full-snapshot replace, newest-edited first — matches `NoteStore.readAll` on the Android side.
/// A reconnect races this against `dispatch::sync_settings`' replay of any queued pin/delete
/// (see `store/pending_note_mutations.rs`) — the phone can easily send this stale snapshot
/// before that replay lands. Re-applying whatever's still queued on top closes that window: the
/// UI never flashes back to the pre-offline-edit state just because this arrived first.
pub async fn sync(payload: NotesSyncPayload, state: &std::sync::Arc<AppState>) {
    let mut notes = payload.notes;
    let pending = state.pending_note_mutations.lock().await.all();
    for mutation in &pending {
        match mutation {
            PendingNoteMutation::Delete { id } => {
                notes.retain(|n| &n.id != id);
            }
            PendingNoteMutation::Change { id, set_pinned, content } => {
                if let Some(note) = notes.iter_mut().find(|n| &n.id == id) {
                    if let Some(is_pinned) = set_pinned {
                        note.is_pinned = *is_pinned;
                    }
                    if let Some(c) = content {
                        note.title = c.title.clone();
                        note.body = c.body.clone();
                        note.image_base64 = c.image_base64.clone();
                    }
                }
            }
        }
    }
    notes.sort_by(|a, b| b.updated_at.total_cmp(&a.updated_at));
    tracing::info!("notes.sync: {} notes ({} pending mutation(s) re-applied)", notes.len(), pending.len());
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

pub async fn set_pinned_result(payload: NoteSetPinnedResultPayload, state: &std::sync::Arc<AppState>) {
    if !payload.success {
        emit_error(state, payload.error).await;
    }
}

async fn emit_error(state: &std::sync::Arc<AppState>, error: Option<String>) {
    let message = error.unwrap_or_else(|| "Something went wrong".to_string());
    tracing::warn!("notes operation failed: {}", message);
    let _ = state.app_handle.emit("notes-error", message);
}
