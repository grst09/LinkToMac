import Foundation
import Observation

/// Holds photo thumbnails paged in from the phone on demand (see docs/PROTOCOL.md — photos use
/// request/response paging, not the full-snapshot model calls/SMS use) plus any full-resolution
/// images fetched so far, keyed by photo id.
@Observable
final class PhotoStore {
    private(set) var photos: [PhotoThumbnail] = []
    private(set) var hasMore = true
    private(set) var isLoadingMore = false
    private(set) var fullImageData: [String: Data] = [:]

    func beginLoadingMore() {
        isLoadingMore = true
    }

    func appendPage(_ page: [PhotoThumbnail], hasMore: Bool) {
        let existingIds = Set(photos.map(\.id))
        photos.append(contentsOf: page.filter { !existingIds.contains($0.id) })
        self.hasMore = hasMore
        isLoadingMore = false
    }

    func setFullImage(id: String, data: Data) {
        fullImageData[id] = data
    }

    /// Called on `photo.libraryChanged` (a photo was added/deleted on the phone). Clears
    /// everything loaded so far — see docs/PROTOCOL.md for why this is a hard reset rather
    /// than a diff against the change.
    func invalidate() {
        photos = []
        hasMore = true
        isLoadingMore = false
        fullImageData = [:]
    }
}
