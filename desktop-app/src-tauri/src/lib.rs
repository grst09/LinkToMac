mod clipboard;
mod crypto;
mod dispatch;
mod net;
mod notify;
mod protocol;
mod store;

use std::sync::Arc;

use tauri::Manager;

use net::server::AppState;
use store::identity::IdentityStore;
use store::paired_devices::PairedDeviceStore;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .invoke_handler(tauri::generate_handler![
            net::server::begin_pairing,
            net::server::list_paired_devices,
            net::server::forget_device,
            dispatch_commands::list_notifications,
            dispatch_commands::dismiss_notification,
            dispatch_commands::get_device_status,
        ])
        .setup(|app| {
            let app_data_dir = app.path().app_data_dir()?;
            let identity = IdentityStore::load_or_create(&app_data_dir)?;
            let paired_devices = PairedDeviceStore::load_or_create(&app_data_dir)?;
            let state = Arc::new(AppState::new(identity, paired_devices, app.handle().clone())?);
            app.manage(Arc::clone(&state));

            notify::request_authorization(app.handle());

            tauri::async_runtime::spawn(async move {
                if let Err(e) = net::server::run(Arc::clone(&state)).await {
                    tracing::error!("WebSocket server failed: {}", e);
                }
            });
            let clipboard_state = Arc::clone(app.state::<Arc<AppState>>().inner());
            tauri::async_runtime::spawn(clipboard::run_poll_loop(clipboard_state));

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

/// Small Tauri-command wrappers that don't belong in `dispatch.rs` itself (that module handles
/// *incoming* protocol messages; these are frontend-facing reads/actions over the same state).
mod dispatch_commands {
    use std::sync::Arc;

    use crate::net::server::{send_to_active, AppState};
    use crate::protocol::envelope::{DeviceStatusPayload, NotificationPostedPayload, NotificationRemovedPayload};

    #[tauri::command]
    pub async fn list_notifications(
        state: tauri::State<'_, Arc<AppState>>,
    ) -> Result<Vec<NotificationPostedPayload>, String> {
        Ok(state.notifications.lock().await.clone())
    }

    #[tauri::command]
    pub async fn dismiss_notification(
        state: tauri::State<'_, Arc<AppState>>,
        id: String,
    ) -> Result<(), String> {
        state.notifications.lock().await.retain(|n| n.id != id);
        send_to_active(&state, "notification.dismiss", &NotificationRemovedPayload { id })
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn get_device_status(
        state: tauri::State<'_, Arc<AppState>>,
    ) -> Result<Option<DeviceStatusPayload>, String> {
        Ok(state.device_status.lock().await.clone())
    }
}
