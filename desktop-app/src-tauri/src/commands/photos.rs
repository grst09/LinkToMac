use std::sync::Arc;

use serde::Serialize;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{PhotoFullRequestPayload, PhotoPageRequestPayload, PhotoThumbnail};

#[derive(Serialize)]
pub struct PhotosSnapshot {
    photos: Vec<PhotoThumbnail>,
    has_more: bool,
}

#[tauri::command]
pub async fn list_photos(state: tauri::State<'_, Arc<AppState>>) -> Result<PhotosSnapshot, String> {
    let photos = state.photos.lock().await;
    Ok(PhotosSnapshot {
        photos: photos.photos.clone(),
        has_more: photos.has_more,
    })
}

/// Single-flight, matching `PhotoStore`'s `isLoadingMore` guard: bails immediately (as an
/// `Err` the frontend just treats as "no-op, already in flight") rather than sending a
/// duplicate request if one is already outstanding.
#[tauri::command]
pub async fn request_photo_page(state: tauri::State<'_, Arc<AppState>>) -> Result<(), String> {
    let offset = {
        let mut photos = state.photos.lock().await;
        if photos.is_loading_more {
            return Err("already loading".to_string());
        }
        photos.is_loading_more = true;
        photos.photos.len() as i64
    };
    send_to_active(
        &state,
        "photo.pageRequest",
        &PhotoPageRequestPayload { offset, limit: 30 },
    )
    .await
    .map_err(|e| e.to_string())
}

/// Checks the cache first — unlike the old Swift app (which always re-requested, unguarded;
/// see docs/PLAN.md's Phase D notes), this avoids a redundant round trip for a photo that's
/// already been fetched, without needing real in-flight tracking for the common case (a user
/// re-opening a photo they already viewed).
#[tauri::command]
pub async fn request_photo_full(state: tauri::State<'_, Arc<AppState>>, id: String) -> Result<(), String> {
    if state.photos.lock().await.full_images.contains_key(&id) {
        return Ok(());
    }
    send_to_active(&state, "photo.fullRequest", &PhotoFullRequestPayload { id })
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_photo_full(
    state: tauri::State<'_, Arc<AppState>>,
    id: String,
) -> Result<Option<(String, String)>, String> {
    Ok(state.photos.lock().await.full_images.get(&id).cloned())
}
