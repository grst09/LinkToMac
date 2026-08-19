mod clipboard;
mod commands;
mod crypto;
mod dispatch;
mod files;
mod messages;
mod mirror;
mod net;
mod notify;
mod photos;
mod protocol;
mod store;

use std::sync::Arc;

use tauri::Manager;

use net::server::AppState;
use store::identity::IdentityStore;
use store::local_contacts::LocalContactsStore;
use store::local_notes::LocalNotesStore;
use store::paired_devices::PairedDeviceStore;
use store::pending_messages::PendingMessagesStore;
use store::settings::SettingsStore;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
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
            commands::get_mirror_state,
            commands::start_mirroring,
            commands::stop_mirroring,
            commands::send_mirror_tap,
            commands::send_mirror_swipe,
            commands::send_mirror_key,
            commands::send_mirror_text,
            commands::list_notes,
            commands::refresh_notes,
            commands::create_note,
            commands::update_note,
            commands::delete_note,
            commands::set_note_pinned,
            commands::list_local_notes,
            commands::list_local_contacts,
            commands::list_pending_messages,
            commands::get_sync_settings,
            commands::get_settings,
            commands::update_settings,
            commands::get_launch_at_login,
            commands::set_launch_at_login,
            commands::get_storage_info,
            commands::clear_downloaded_files,
            commands::get_app_version,
            commands::list_clipboard_history,
            commands::copy_clipboard_entry,
            commands::clear_clipboard_history,
        ])
        .setup(|app| {
            let app_data_dir = app.path().app_data_dir()?;
            let identity = IdentityStore::load_or_create(&app_data_dir)?;
            let paired_devices = PairedDeviceStore::load_or_create(&app_data_dir)?;
            let settings = SettingsStore::load_or_create(&app_data_dir)?;
            let local_notes = LocalNotesStore::load_or_create(&app_data_dir)?;
            let local_contacts = LocalContactsStore::load_or_create(&app_data_dir)?;
            let pending_messages = PendingMessagesStore::load_or_create(&app_data_dir)?;
            let state = Arc::new(AppState::new(
                identity,
                paired_devices,
                settings,
                local_notes,
                local_contacts,
                pending_messages,
                app.handle().clone(),
            )?);
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
