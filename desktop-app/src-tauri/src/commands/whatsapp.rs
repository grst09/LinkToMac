use std::sync::Arc;

use serde::Serialize;
use serde_json::json;
use tauri::Manager;

use crate::net::server::AppState;
use crate::whatsapp::{bridge, LinkStatus, WaChat, WaMessage};

#[derive(Serialize)]
pub struct WhatsappStatus {
    link_status: LinkStatus,
    qr: Option<String>,
}

fn session_dir(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    Ok(app.path().app_data_dir().map_err(|e| e.to_string())?.join("whatsapp-session"))
}

/// Starts the bridge process if it isn't already running (idempotent — safe to call every
/// time the WhatsApp tab is opened). The actual QR/connection state arrives asynchronously via
/// the `whatsapp-qr`/`whatsapp-connection` events; this just guarantees the process exists.
#[tauri::command]
pub async fn whatsapp_link_start(app: tauri::AppHandle, state: tauri::State<'_, Arc<AppState>>) -> Result<WhatsappStatus, String> {
    let already_running = state.whatsapp_bridge.lock().await.is_some();
    if !already_running {
        let dir = session_dir(&app)?;
        let handle = bridge::start(app.clone(), Arc::clone(&state), dir)
            .await
            .map_err(|e| e.to_string())?;
        *state.whatsapp_bridge.lock().await = Some(handle);
    }
    whatsapp_status(state).await
}

#[tauri::command]
pub async fn whatsapp_status(state: tauri::State<'_, Arc<AppState>>) -> Result<WhatsappStatus, String> {
    let wa = state.whatsapp.lock().await;
    Ok(WhatsappStatus {
        link_status: wa.link_status,
        qr: wa.qr.clone(),
    })
}

#[tauri::command]
pub async fn whatsapp_list_chats(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<WaChat>, String> {
    Ok(state.whatsapp.lock().await.chats.clone())
}

#[tauri::command]
pub async fn whatsapp_list_messages(state: tauri::State<'_, Arc<AppState>>, chat_id: String) -> Result<Vec<WaMessage>, String> {
    Ok(state
        .whatsapp
        .lock()
        .await
        .messages_by_chat
        .get(&chat_id)
        .cloned()
        .unwrap_or_default())
}

async fn require_bridge(state: &tauri::State<'_, Arc<AppState>>) -> Result<Arc<bridge::BridgeHandle>, String> {
    state
        .whatsapp_bridge
        .lock()
        .await
        .clone()
        .ok_or_else(|| "whatsapp not linked".to_string())
}

fn check_ack(ack: &serde_json::Value) -> Result<(), String> {
    if ack.get("ok").and_then(serde_json::Value::as_bool).unwrap_or(false) {
        Ok(())
    } else {
        Err(ack
            .get("error")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("bridge command failed")
            .to_string())
    }
}

#[tauri::command]
pub async fn whatsapp_send_message(state: tauri::State<'_, Arc<AppState>>, jid: String, text: String) -> Result<(), String> {
    let handle = require_bridge(&state).await?;
    let mut payload = serde_json::Map::new();
    payload.insert("jid".into(), json!(jid));
    payload.insert("text".into(), json!(text));
    let ack = handle.send_command("send_text", payload).await.map_err(|e| e.to_string())?;
    check_ack(&ack)?;
    if let Some(message) = ack.get("message").cloned() {
        if let Ok(message) = serde_json::from_value::<WaMessage>(message) {
            state.whatsapp.lock().await.append_messages(vec![message]);
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
#[tauri::command]
pub async fn whatsapp_send_media(
    state: tauri::State<'_, Arc<AppState>>,
    jid: String,
    media_type: String,
    data_base64: String,
    mime_type: String,
    file_name: Option<String>,
    caption: Option<String>,
) -> Result<(), String> {
    let handle = require_bridge(&state).await?;
    let mut payload = serde_json::Map::new();
    payload.insert("jid".into(), json!(jid));
    payload.insert("mediaType".into(), json!(media_type));
    payload.insert("dataBase64".into(), json!(data_base64));
    payload.insert("mimeType".into(), json!(mime_type));
    if let Some(file_name) = file_name {
        payload.insert("fileName".into(), json!(file_name));
    }
    if let Some(caption) = caption {
        payload.insert("caption".into(), json!(caption));
    }
    let ack = handle.send_command("send_media", payload).await.map_err(|e| e.to_string())?;
    check_ack(&ack)?;
    if let Some(message) = ack.get("message").cloned() {
        if let Ok(message) = serde_json::from_value::<WaMessage>(message) {
            state.whatsapp.lock().await.append_messages(vec![message]);
        }
    }
    Ok(())
}

/// Cache-only read, mirroring `get_photo_full` — call `whatsapp_request_media` first if this
/// returns `None`.
#[tauri::command]
pub async fn whatsapp_get_media(state: tauri::State<'_, Arc<AppState>>, message_id: String) -> Result<Option<(String, String)>, String> {
    Ok(state.whatsapp.lock().await.media_cache.get(&message_id).cloned())
}

/// Downloads one message's media on demand via the bridge (Baileys keeps the encrypted blob
/// on WhatsApp's servers until asked, same as WhatsApp Desktop's own lazy media loading),
/// caching the result so re-opening the same bubble doesn't re-fetch.
#[tauri::command]
pub async fn whatsapp_request_media(state: tauri::State<'_, Arc<AppState>>, message_id: String) -> Result<(String, String), String> {
    if let Some(cached) = state.whatsapp.lock().await.media_cache.get(&message_id).cloned() {
        return Ok(cached);
    }
    let handle = require_bridge(&state).await?;
    let mut payload = serde_json::Map::new();
    payload.insert("messageId".into(), json!(message_id));
    let ack = handle.send_command("fetch_media", payload).await.map_err(|e| e.to_string())?;
    check_ack(&ack)?;
    let result = bridge::parse_media_result(&ack).ok_or("malformed media result")?;
    let pair = (result.data_base64, result.mime_type);
    state.whatsapp.lock().await.media_cache.insert(result.message_id, pair.clone());
    Ok(pair)
}

#[tauri::command]
pub async fn whatsapp_mark_read(
    state: tauri::State<'_, Arc<AppState>>,
    chat_id: String,
    message_keys: Vec<serde_json::Value>,
) -> Result<(), String> {
    let handle = require_bridge(&state).await?;
    let mut payload = serde_json::Map::new();
    payload.insert("messageKeys".into(), json!(message_keys));
    let ack = handle.send_command("mark_read", payload).await.map_err(|e| e.to_string())?;
    check_ack(&ack)?;
    if let Some(chat) = state.whatsapp.lock().await.chats.iter_mut().find(|c| c.id == chat_id) {
        chat.unread_count = 0;
    }
    Ok(())
}

#[tauri::command]
pub async fn whatsapp_send_reaction(
    state: tauri::State<'_, Arc<AppState>>,
    jid: String,
    message_key: serde_json::Value,
    emoji: String,
) -> Result<(), String> {
    let handle = require_bridge(&state).await?;
    let mut payload = serde_json::Map::new();
    payload.insert("jid".into(), json!(jid));
    payload.insert("messageKey".into(), message_key);
    payload.insert("emoji".into(), json!(emoji));
    let ack = handle.send_command("send_reaction", payload).await.map_err(|e| e.to_string())?;
    check_ack(&ack)
}

#[tauri::command]
pub async fn whatsapp_logout(app: tauri::AppHandle, state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    let handle = state
        .whatsapp_bridge
        .lock()
        .await
        .take()
        .ok_or_else(|| "whatsapp not linked".to_string())?;
    let ack = handle
        .send_command("logout", serde_json::Map::new())
        .await
        .map_err(|e| e.to_string())?;
    check_ack(&ack)?;

    // `handle` was the only owner of this Arc (the bridge's stdout-reader task holds its own
    // `pending` map clone, not the handle itself) — tearing the process down here rather than
    // leaving it running as an orphan after logout.
    match Arc::try_unwrap(handle) {
        Ok(handle) => {
            if let Err(e) = handle.kill() {
                tracing::warn!("failed to kill whatsapp bridge process after logout: {e}");
            }
        }
        Err(_) => tracing::warn!("whatsapp bridge handle still referenced elsewhere; leaving process running"),
    }

    // Baileys doesn't delete its own auth files on `sock.logout()` — without this, the next
    // `whatsapp_link_start` would load now-invalid creds, fail to reconnect, and (since the
    // bridge only retries on a non-logged-out disconnect) just sit idle instead of ever
    // presenting a fresh QR code.
    if let Ok(dir) = session_dir(&app) {
        let _ = std::fs::remove_dir_all(dir);
    }

    let mut wa = state.whatsapp.lock().await;
    *wa = crate::whatsapp::WhatsappState::default();
    wa.link_status = LinkStatus::Unlinked;
    Ok(())
}
