mod crypto;
mod net;
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
        .invoke_handler(tauri::generate_handler![net::server::begin_pairing])
        .setup(|app| {
            let app_data_dir = app.path().app_data_dir()?;
            let identity = IdentityStore::load_or_create(&app_data_dir)?;
            let paired_devices = PairedDeviceStore::load_or_create(&app_data_dir)?;
            let state = Arc::new(AppState::new(identity, paired_devices, app.handle().clone())?);
            app.manage(Arc::clone(&state));

            tauri::async_runtime::spawn(async move {
                if let Err(e) = net::server::run(state).await {
                    tracing::error!("WebSocket server failed: {}", e);
                }
            });

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
