//! Frontend-facing Tauri commands — reads of and actions over `AppState`. `dispatch/` handles
//! the other direction (incoming protocol messages); this module is what the React side calls.
//! Split by feature once the flat list got long enough to want it, mirroring `dispatch/`.

mod clipboard;
mod contacts;
mod files;
mod messages;
mod mirror;
mod notes;
mod notifications;
mod photos;
mod settings;

pub use clipboard::*;
pub use contacts::*;
pub use files::*;
pub use messages::*;
pub use mirror::*;
pub use notes::*;
pub use notifications::*;
pub use photos::*;
pub use settings::*;

use std::sync::Arc;

use crate::net::server::AppState;
use crate::protocol::envelope::DeviceStatusPayload;

#[tauri::command]
pub async fn get_device_status(
    state: tauri::State<'_, Arc<AppState>>,
) -> Result<Option<DeviceStatusPayload>, String> {
    Ok(state.device_status.lock().await.clone())
}
