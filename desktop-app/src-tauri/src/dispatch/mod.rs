//! Routes decrypted post-handshake messages to the right feature handler. Split out from
//! `net/server.rs` (transport/handshake only) and, as the message-type count grew, split again
//! into per-feature submodules — `notifications.rs`, `contacts.rs`, and so on as later phases
//! (Photos, Files) land.

use tauri::Emitter;

use crate::net::server::AppState;
use crate::protocol::envelope::{
    CallLogSyncPayload, ClipboardUpdatePayload, ContactCreateResultPayload,
    ContactDeleteResultPayload, ContactUpdateResultPayload, ContactsSyncPayload,
    DeviceStatusPayload, FilesCreateFolderResultPayload, FilesDeleteResultPayload,
    FilesDownloadResultPayload, FilesListResultPayload, FilesRenameResultPayload,
    FilesTransferResultPayload, FilesUploadResultPayload, Message, MirrorConfigPayload,
    MirrorStoppedPayload, NoteCreateResultPayload, NoteDeleteResultPayload,
    NoteUpdateResultPayload, NotesSyncPayload, NotificationPostedPayload,
    NotificationRemovedPayload, PhotoFullPayload, PhotoPagePayload, SmsSyncPayload,
};

pub mod contacts;
pub mod files;
pub mod mirror;
pub mod notes;
pub mod notifications;
pub mod photos;

pub async fn handle(message: Message, state: &std::sync::Arc<AppState>) -> anyhow::Result<()> {
    match message.message_type.as_str() {
        "notification.posted" => {
            let payload: NotificationPostedPayload = serde_json::from_value(message.payload)?;
            notifications::posted(payload, state).await;
        }
        "notification.removed" => {
            let payload: NotificationRemovedPayload = serde_json::from_value(message.payload)?;
            notifications::removed(payload, state).await;
        }
        "device.status" => {
            let payload: DeviceStatusPayload = serde_json::from_value(message.payload)?;
            tracing::info!(
                "device.status: {}% {}",
                payload.battery_percent,
                if payload.is_charging { "(charging)" } else { "" }
            );
            *state.device_status.lock().await = Some(payload.clone());
            let _ = state.app_handle.emit("device-status", payload);
        }
        "clipboard.update" => {
            let payload: ClipboardUpdatePayload = serde_json::from_value(message.payload)?;
            crate::clipboard::apply_remote_update(state, payload.text).await;
        }
        "call.sync" => {
            let payload: CallLogSyncPayload = serde_json::from_value(message.payload)?;
            tracing::info!("call.sync: {} entries", payload.calls.len());
            *state.calls.lock().await = payload.calls.clone();
            let _ = state.app_handle.emit("calls-updated", payload.calls);
        }
        "sms.sync" => {
            let payload: SmsSyncPayload = serde_json::from_value(message.payload)?;
            tracing::info!("sms.sync: {} threads", payload.threads.len());
            let all_threads = {
                let mut messages = state.messages.lock().await;
                messages.update(payload.threads);
                messages.all_threads()
            };
            let _ = state.app_handle.emit("threads-updated", all_threads);
        }
        "contacts.sync" => {
            let payload: ContactsSyncPayload = serde_json::from_value(message.payload)?;
            contacts::sync(payload, state).await;
        }
        "contacts.updateResult" => {
            let payload: ContactUpdateResultPayload = serde_json::from_value(message.payload)?;
            contacts::update_result(payload, state).await;
        }
        "contacts.createResult" => {
            let payload: ContactCreateResultPayload = serde_json::from_value(message.payload)?;
            contacts::create_result(payload, state).await;
        }
        "contacts.deleteResult" => {
            let payload: ContactDeleteResultPayload = serde_json::from_value(message.payload)?;
            contacts::delete_result(payload, state).await;
        }
        "photo.page" => {
            let payload: PhotoPagePayload = serde_json::from_value(message.payload)?;
            photos::page(payload, state).await;
        }
        "photo.full" => {
            let payload: PhotoFullPayload = serde_json::from_value(message.payload)?;
            photos::full(payload, state).await;
        }
        "photo.libraryChanged" => {
            photos::library_changed(state).await;
        }
        "files.listResult" => {
            let payload: FilesListResultPayload = serde_json::from_value(message.payload)?;
            files::list_result(payload, state).await;
        }
        "files.downloadResult" => {
            let payload: FilesDownloadResultPayload = serde_json::from_value(message.payload)?;
            files::download_result(payload, state).await;
        }
        "files.uploadResult" => {
            let payload: FilesUploadResultPayload = serde_json::from_value(message.payload)?;
            files::upload_result(payload, state).await;
        }
        "files.createFolderResult" => {
            let payload: FilesCreateFolderResultPayload = serde_json::from_value(message.payload)?;
            files::create_folder_result(payload, state).await;
        }
        "files.renameResult" => {
            let payload: FilesRenameResultPayload = serde_json::from_value(message.payload)?;
            files::rename_result(payload, state).await;
        }
        "files.deleteResult" => {
            let payload: FilesDeleteResultPayload = serde_json::from_value(message.payload)?;
            files::delete_result(payload, state).await;
        }
        "files.copyResult" => {
            let payload: FilesTransferResultPayload = serde_json::from_value(message.payload)?;
            files::copy_result(payload, state).await;
        }
        "files.moveResult" => {
            let payload: FilesTransferResultPayload = serde_json::from_value(message.payload)?;
            files::move_result(payload, state).await;
        }
        "mirror.config" => {
            let payload: MirrorConfigPayload = serde_json::from_value(message.payload)?;
            mirror::config(payload, state).await;
        }
        "mirror.stopped" => {
            let payload: MirrorStoppedPayload = serde_json::from_value(message.payload)?;
            mirror::stopped(payload, state).await;
        }
        "notes.sync" => {
            let payload: NotesSyncPayload = serde_json::from_value(message.payload)?;
            notes::sync(payload, state).await;
        }
        "notes.createResult" => {
            let payload: NoteCreateResultPayload = serde_json::from_value(message.payload)?;
            notes::create_result(payload, state).await;
        }
        "notes.updateResult" => {
            let payload: NoteUpdateResultPayload = serde_json::from_value(message.payload)?;
            notes::update_result(payload, state).await;
        }
        "notes.deleteResult" => {
            let payload: NoteDeleteResultPayload = serde_json::from_value(message.payload)?;
            notes::delete_result(payload, state).await;
        }
        other => {
            tracing::info!("received {} (dispatch not yet implemented)", other);
        }
    }
    Ok(())
}
