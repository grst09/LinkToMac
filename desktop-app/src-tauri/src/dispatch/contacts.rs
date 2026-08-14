use tauri::Emitter;

use crate::net::server::AppState;
use crate::protocol::envelope::{
    ContactCreateResultPayload, ContactDeleteResultPayload, ContactUpdateResultPayload,
    ContactsSyncPayload,
};

/// Full-snapshot replace, sorted case-insensitively by name — matches `ContactStore.update`.
pub async fn sync(payload: ContactsSyncPayload, state: &std::sync::Arc<AppState>) {
    let mut contacts = payload.contacts;
    contacts.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    tracing::info!("contacts.sync: {} contacts", contacts.len());
    *state.contacts.lock().await = contacts.clone();
    let _ = state.app_handle.emit("contacts-updated", contacts);
}

/// The three mutation results only ever surface a *failure* to the UI — a successful
/// update/create/delete is reflected by the next `contacts.sync` push, not applied optimistically
/// here, matching `ContactStore.applyOperationResult`'s actual behavior (it only ever sets
/// `lastError`, it doesn't locally patch `contacts`).
pub async fn update_result(payload: ContactUpdateResultPayload, state: &std::sync::Arc<AppState>) {
    if !payload.success {
        emit_error(state, payload.error).await;
    }
}

pub async fn create_result(payload: ContactCreateResultPayload, state: &std::sync::Arc<AppState>) {
    if !payload.success {
        emit_error(state, payload.error).await;
    }
}

pub async fn delete_result(payload: ContactDeleteResultPayload, state: &std::sync::Arc<AppState>) {
    if !payload.success {
        emit_error(state, payload.error).await;
    }
}

async fn emit_error(state: &std::sync::Arc<AppState>, error: Option<String>) {
    let message = error.unwrap_or_else(|| "Something went wrong".to_string());
    tracing::warn!("contacts operation failed: {}", message);
    let _ = state.app_handle.emit("contacts-error", message);
}
