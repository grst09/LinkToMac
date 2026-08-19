use std::sync::Arc;

use serde::Serialize;
use tauri_plugin_autostart::ManagerExt;

use crate::net::server::AppState;
use crate::store::settings::AppSettings;

#[tauri::command]
pub async fn get_settings(state: tauri::State<'_, Arc<AppState>>) -> Result<AppSettings, String> {
    Ok(state.settings.lock().await.get())
}

#[tauri::command]
pub async fn update_settings(state: tauri::State<'_, Arc<AppState>>, settings: AppSettings) -> Result<(), String> {
    state
        .settings
        .lock()
        .await
        .update(settings)
        .map_err(|e| e.to_string())
}

/// Launch-at-login is OS-managed state (a login-item registration), not part of our own
/// settings.json — the OS is the source of truth, so this reads it fresh rather than caching.
#[tauri::command]
pub fn get_launch_at_login(app: tauri::AppHandle) -> Result<bool, String> {
    app.autolaunch().is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn set_launch_at_login(app: tauri::AppHandle, enabled: bool) -> Result<(), String> {
    let manager = app.autolaunch();
    if enabled { manager.enable() } else { manager.disable() }.map_err(|e| e.to_string())
}

#[derive(Serialize)]
pub struct StorageInfo {
    file_count: u64,
    total_bytes: u64,
}

/// Reports on `~/Downloads/LinkToMac` for the Settings page's Storage section. Flat directory —
/// this app never creates subfolders in it — so a non-recursive read is enough.
#[tauri::command]
pub fn get_storage_info(app: tauri::AppHandle) -> Result<StorageInfo, String> {
    let dir = crate::files::downloads_dir(&app)?;
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return Ok(StorageInfo { file_count: 0, total_bytes: 0 });
    };
    let mut file_count = 0u64;
    let mut total_bytes = 0u64;
    for entry in entries.flatten() {
        if let Ok(metadata) = entry.metadata() {
            if metadata.is_file() {
                file_count += 1;
                total_bytes += metadata.len();
            }
        }
    }
    Ok(StorageInfo { file_count, total_bytes })
}

#[tauri::command]
pub fn clear_downloaded_files(app: tauri::AppHandle) -> Result<(), String> {
    let dir = crate::files::downloads_dir(&app)?;
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return Ok(());
    };
    for entry in entries.flatten() {
        if let Ok(metadata) = entry.metadata() {
            if metadata.is_file() {
                let _ = std::fs::remove_file(entry.path());
            }
        }
    }
    Ok(())
}

#[tauri::command]
pub fn get_app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
