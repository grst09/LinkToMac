mod clipboard;
mod commands;
mod crypto;
mod dispatch;
mod files;
mod messages;
mod net;
mod notify;
mod photos;
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
            commands::list_notifications,
            commands::dismiss_notification,
            commands::get_device_status,
            commands::list_calls,
            commands::list_threads,
            commands::send_sms,
            commands::refresh_messages,
            commands::list_contacts,
            commands::refresh_contacts,
            commands::dial_contact,
            commands::update_contact,
            commands::create_contact,
            commands::delete_contact,
            commands::list_photos,
            commands::request_photo_page,
            commands::request_photo_full,
            commands::get_photo_full,
            commands::get_files_state,
            commands::list_files,
            commands::download_file,
            commands::upload_file,
            commands::create_folder,
            commands::rename_file,
            commands::delete_file,
            commands::cut_to_clipboard,
            commands::copy_to_clipboard,
            commands::paste_clipboard,
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
