use std::sync::Arc;

use tauri::Emitter;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    ContactCreatePayload, ContactDeletePayload, ContactEntry, ContactUpdatePayload,
    ContactsDialPayload, EmptyPayload,
};
use crate::store::local_contacts::PendingContact;

#[tauri::command]
pub async fn list_contacts(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<ContactEntry>, String> {
    Ok(state.contacts.lock().await.clone())
}

#[tauri::command]
pub async fn list_local_contacts(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<PendingContact>, String> {
    Ok(state.local_contacts.lock().await.pending())
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

/// `id` starting with `"local-"` is a contact that only ever existed on the Mac — always
/// editable locally. A phone-origin id requires sync to be on, same reasoning as
/// `notes::update_note`.
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
    if id.starts_with("local-") {
        let mut store = state.local_contacts.lock().await;
        store.update(&id, name, phone_number, email, organization);
        let pending = store.pending();
        drop(store);
        let _ = state.app_handle.emit("local-contacts-updated", pending);
        return Ok(());
    }
    if !state.sync_settings.lock().await.contacts_enabled {
        return Err("Contacts sync is off — turn it back on to edit synced contacts".to_string());
    }
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

/// While contacts sync is off, a new contact stays entirely local (see
/// `store/local_contacts.rs`) instead of being sent to the phone.
#[tauri::command]
pub async fn create_contact(
    state: tauri::State<'_, Arc<AppState>>,
    name: String,
    phone_number: String,
    email: Option<String>,
    organization: Option<String>,
) -> Result<(), String> {
    let contacts_enabled = state.sync_settings.lock().await.contacts_enabled;
    if !contacts_enabled {
        let mut store = state.local_contacts.lock().await;
        store.add(name, phone_number, email, organization);
        let pending = store.pending();
        drop(store);
        let _ = state.app_handle.emit("local-contacts-updated", pending);
        return Ok(());
    }
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
    if id.starts_with("local-") {
        let mut store = state.local_contacts.lock().await;
        store.remove(&id);
        let pending = store.pending();
        drop(store);
        let _ = state.app_handle.emit("local-contacts-updated", pending);
        return Ok(());
    }
    if !state.sync_settings.lock().await.contacts_enabled {
        return Err("Contacts sync is off — turn it back on to edit synced contacts".to_string());
    }
    send_to_active(&state, "contacts.delete", &ContactDeletePayload { id })
        .await
        .map_err(|e| e.to_string())
}
