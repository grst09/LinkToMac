use std::sync::Arc;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    ContactCreatePayload, ContactDeletePayload, ContactEntry, ContactUpdatePayload,
    ContactsDialPayload, EmptyPayload,
};

#[tauri::command]
pub async fn list_contacts(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<ContactEntry>, String> {
    Ok(state.contacts.lock().await.clone())
}

#[tauri::command]
pub async fn refresh_contacts(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    send_to_active(&state, "contacts.refresh", &EmptyPayload {})
        .await
        .map_err(|e| e.to_string())
}

/// `ACTION_DIAL` semantics on the Android side — opens the phone's own dialer pre-filled with
/// the number, doesn't place a call. See `ContactsDialPayload`'s doc comment in envelope.rs.
#[tauri::command]
pub async fn dial_contact(state: tauri::State<'_, Arc<AppState>>, phone_number: String) -> Result<(), String> {
    send_to_active(&state, "contacts.dial", &ContactsDialPayload { phone_number })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn update_contact(
    state: tauri::State<'_, Arc<AppState>>,
    id: String,
    name: String,
    phone_number: String,
    is_starred: bool,
    email: Option<String>,
    organization: Option<String>,
) -> Result<(), String> {
    send_to_active(
        &state,
        "contacts.update",
        &ContactUpdatePayload {
            id,
            name,
            phone_number,
            is_starred,
            email,
            organization,
        },
    )
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn create_contact(
    state: tauri::State<'_, Arc<AppState>>,
    name: String,
    phone_number: String,
    email: Option<String>,
    organization: Option<String>,
) -> Result<(), String> {
    send_to_active(
        &state,
        "contacts.create",
        &ContactCreatePayload {
            name,
            phone_number,
            email,
            organization,
        },
    )
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_contact(state: tauri::State<'_, Arc<AppState>>, id: String) -> Result<(), String> {
    send_to_active(&state, "contacts.delete", &ContactDeletePayload { id })
        .await
        .map_err(|e| e.to_string())
}
