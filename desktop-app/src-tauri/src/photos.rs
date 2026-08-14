//! Photo grid state, ported from `Storage/PhotoStore.swift`. Paginated request/response (not
//! full-snapshot, unlike calls/SMS/contacts) since a library can hold thousands of items — see
//! docs/PROTOCOL.md's Phase 3 note.

use std::collections::HashMap;

use crate::protocol::envelope::PhotoThumbnail;

pub struct PhotoState {
    pub photos: Vec<PhotoThumbnail>,
    pub has_more: bool,
    pub is_loading_more: bool,
    /// Keyed by photo id — never evicted except on a full `libraryChanged` reset, matching
    /// `PhotoStore.fullImageData`'s lifetime exactly.
    pub full_images: HashMap<String, (String, String)>, // (dataBase64, mimeType)
}

impl Default for PhotoState {
    fn default() -> Self {
        Self {
            photos: Vec::new(),
            has_more: true,
            is_loading_more: false,
            full_images: HashMap::new(),
        }
    }
}

impl PhotoState {
    /// Appends a page, de-duped by id against what's already loaded — guards against a page
    /// overlapping a concurrent `libraryChanged` reset, matching `PhotoStore.appendPage`.
    pub fn append_page(&mut self, new_photos: Vec<PhotoThumbnail>, has_more: bool) -> Vec<PhotoThumbnail> {
        let existing_ids: std::collections::HashSet<&str> =
            self.photos.iter().map(|p| p.id.as_str()).collect();
        let deduped: Vec<PhotoThumbnail> = new_photos
            .into_iter()
            .filter(|p| !existing_ids.contains(p.id.as_str()))
            .collect();
        self.photos.extend(deduped.clone());
        self.has_more = has_more;
        self.is_loading_more = false;
        deduped
    }

    /// Full, unconditional reset — matches `PhotoStore.invalidate` exactly (including
    /// `hasMore` resetting to `true`, not `false`; see docs/PLAN.md's Phase D notes on why the
    /// scroll position/detail-view loss this causes is accepted, not a bug to fix).
    pub fn reset(&mut self) {
        self.photos.clear();
        self.has_more = true;
        self.is_loading_more = false;
        self.full_images.clear();
    }
}
