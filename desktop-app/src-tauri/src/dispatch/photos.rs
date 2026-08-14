use serde::Serialize;
use tauri::Emitter;

use crate::net::server::{send_to_active, AppState};
use crate::protocol::envelope::{PhotoFullPayload, PhotoPagePayload, PhotoPageRequestPayload};

const DEFAULT_PAGE_SIZE: i64 = 30;

#[derive(Serialize, Clone)]
struct PhotosAppendedEvent {
    photos: Vec<crate::protocol::envelope::PhotoThumbnail>,
    has_more: bool,
}

/// Appends (de-duped) rather than replacing — matches `PhotoStore.appendPage`. Emits only the
/// new page's photos, not the whole accumulated list, since re-sending everything on every
/// page would grow O(n) per page for a large library; the frontend appends client-side.
pub async fn page(payload: PhotoPagePayload, state: &std::sync::Arc<AppState>) {
    let (appended, has_more) = {
        let mut photos = state.photos.lock().await;
        let appended = photos.append_page(payload.photos, payload.has_more);
        (appended, photos.has_more)
    };
    tracing::info!("photo.page: +{} photos, hasMore={}", appended.len(), has_more);
    let _ = state.app_handle.emit(
        "photos-appended",
        PhotosAppendedEvent { photos: appended, has_more },
    );
}

pub async fn full(payload: PhotoFullPayload, state: &std::sync::Arc<AppState>) {
    tracing::info!("photo.full: {} ({} bytes)", payload.id, payload.data_base64.len());
    state
        .photos
        .lock()
        .await
        .full_images
        .insert(payload.id.clone(), (payload.data_base64.clone(), payload.mime_type.clone()));
    let _ = state.app_handle.emit("photo-full", payload);
}

/// Hard reset, then immediately re-requests page 0 — matches the dispatcher-level handling in
/// `ConnectionServer.dispatch` (invalidate + re-page is driven centrally, not left to the view).
pub async fn library_changed(state: &std::sync::Arc<AppState>) {
    tracing::info!("photo.libraryChanged: resetting and re-paging from 0");
    state.photos.lock().await.reset();
    let _ = state.app_handle.emit("photos-reset", ());
    let _ = send_to_active(
        state,
        "photo.pageRequest",
        &PhotoPageRequestPayload { offset: 0, limit: DEFAULT_PAGE_SIZE },
    )
    .await;
}
