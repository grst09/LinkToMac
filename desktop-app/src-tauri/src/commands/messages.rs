use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{CallLogEntry, EmptyPayload, SmsSendPayload, SmsThread};

#[tauri::command]
pub async fn list_calls(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<CallLogEntry>, String> {
    Ok(state.calls.lock().await.clone())
}

#[tauri::command]
pub async fn list_threads(state: tauri::State<'_, Arc<AppState>>) -> Result<Vec<SmsThread>, String> {
    Ok(state.messages.lock().await.all_threads())
}

/// Sends an SMS and, matching `MessagesView`'s `onSend`, immediately shows a local-echo copy —
/// Android never reflects a send-to-a-new-number back through `sms.sync` (see `messages.rs`),
/// so waiting on a sync that won't arrive would leave the UI looking like nothing happened.
/// Returns the merged thread list so the frontend can select the (possibly new) thread right
/// away without a round trip.
#[tauri::command]
pub async fn send_sms(
    state: tauri::State<'_, Arc<AppState>>,
    address: String,
    body: String,
) -> Result<Vec<SmsThread>, String> {
    send_to_active(
        &state,
        "sms.send",
        &SmsSendPayload {
            address: address.clone(),
            body: body.clone(),
        },
    )
    .await
    .map_err(|e| e.to_string())?;

    let all_threads = {
        let mut messages = state.messages.lock().await;
        messages.add_local_message(&address, &body, now_millis());
        messages.all_threads()
    };
    Ok(all_threads)
}

#[tauri::command]
pub async fn refresh_messages(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    send_to_active(&state, "sms.refresh", &EmptyPayload {})
        .await
        .map_err(|e| e.to_string())
}

fn now_millis() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as f64)
        .unwrap_or(0.0)
}
