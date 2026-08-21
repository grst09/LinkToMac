//! Handles the phone's `sync.settings` push and reconciles anything queued locally on the Mac
//! (see `store/local_notes.rs`, `store/local_contacts.rs`, `store/pending_messages.rs`) the
//! moment a category flips from off to on.
//!
//! Reconciliation always pushes queued items as brand-new phone-side records (`notes.create`,
//! `contacts.create`) rather than trying to merge against whatever the phone's copy might have
//! become in the meantime — there's no conflict-resolution story here, just "keep both". Queued
//! outbound texts are the one case where "create fresh" doesn't apply — they're just sent for
//! real via `sms.send`, since a text that was already shown as sent in the Mac's own thread view
//! (see `commands/messages.rs`) needs to actually go out, not turn into a duplicate.

use tauri::Emitter;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{
    ContactCreatePayload, EmptyPayload, NoteCreatePayload, NoteDeletePayload, NoteSetPinnedPayload,
    SmsSendPayload, SyncSettingsPayload,
};
use crate::store::pending_note_mutations::PendingNoteMutation;

pub async fn update(payload: SyncSettingsPayload, state: &std::sync::Arc<AppState>) {
    let previous = *state.sync_settings.lock().await;
    *state.sync_settings.lock().await = payload;
    let _ = state.app_handle.emit("sync-settings-updated", payload);

    if !previous.notes_enabled && payload.notes_enabled {
        reconcile_notes(state).await;
    }
    // Unlike `reconcile_notes` above (which only matters right when the toggle flips on), any
    // pin/delete queued while disconnected (see `store/pending_note_mutations.rs`) needs
    // replaying every time the phone reconnects — which is the common case here, since the
    // toggle is usually already on and this push is just the phone saying hello again (it's
    // sent right after every reconnect, before anything else — see `SyncForegroundService.kt`).
    if payload.notes_enabled {
        reconcile_note_mutations(state).await;
    }
    if !previous.contacts_enabled && payload.contacts_enabled {
        reconcile_contacts(state).await;
    }
    if !previous.calls_and_messages_enabled && payload.calls_and_messages_enabled {
        reconcile_messages(state).await;
    }
}

async fn reconcile_note_mutations(state: &std::sync::Arc<AppState>) {
    let pending = state.pending_note_mutations.lock().await.all();
    if pending.is_empty() {
        return;
    }
    tracing::info!("device reconnected: replaying {} queued note mutation(s)", pending.len());
    let mut succeeded = Vec::new();
    for mutation in pending {
        let ok = match &mutation {
            PendingNoteMutation::SetPinned { id, is_pinned } => send_to_active(
                state,
                "note.setPinned",
                &NoteSetPinnedPayload { id: id.clone(), is_pinned: *is_pinned },
            )
            .await
            .is_ok(),
            PendingNoteMutation::Delete { id } => {
                send_to_active(state, "notes.delete", &NoteDeletePayload { id: id.clone() })
                    .await
                    .is_ok()
            }
        };
        if ok {
            succeeded.push(mutation);
        }
    }
    state.pending_note_mutations.lock().await.remove(&succeeded);
}

async fn reconcile_notes(state: &std::sync::Arc<AppState>) {
    let pending = state.local_notes.lock().await.take_all();
    if pending.is_empty() {
        return;
    }
    tracing::info!("notes sync re-enabled: pushing {} pending note(s)", pending.len());
    for note in pending {
        let _ = send_to_active(
            state,
            "notes.create",
            &NoteCreatePayload { title: note.title, body: note.body, image_base64: note.image_base64 },
        )
        .await;
    }
    let _ = send_to_active(state, "notes.refresh", &EmptyPayload {}).await;
    let _ = state
        .app_handle
        .emit("local-notes-updated", Vec::<crate::store::local_notes::PendingNote>::new());
}

async fn reconcile_contacts(state: &std::sync::Arc<AppState>) {
    let pending = state.local_contacts.lock().await.take_all();
    if pending.is_empty() {
        return;
    }
    tracing::info!("contacts sync re-enabled: pushing {} pending contact(s)", pending.len());
    for contact in pending {
        let _ = send_to_active(
            state,
            "contacts.create",
            &ContactCreatePayload {
                name: contact.name,
                phone_number: contact.phone_number,
                email: contact.email,
                organization: contact.organization,
            },
        )
        .await;
    }
    let _ = send_to_active(state, "contacts.refresh", &EmptyPayload {}).await;
    let _ = state.app_handle.emit(
        "local-contacts-updated",
        Vec::<crate::store::local_contacts::PendingContact>::new(),
    );
}

async fn reconcile_messages(state: &std::sync::Arc<AppState>) {
    let pending = state.pending_messages.lock().await.take_all();
    if pending.is_empty() {
        return;
    }
    tracing::info!("calls & messages sync re-enabled: sending {} pending message(s)", pending.len());
    for message in pending {
        let _ = send_to_active(
            state,
            "sms.send",
            &SmsSendPayload { address: message.address, body: message.body },
        )
        .await;
    }
    let _ = state.app_handle.emit(
        "pending-messages-updated",
        Vec::<crate::store::pending_messages::PendingMessage>::new(),
    );
}
