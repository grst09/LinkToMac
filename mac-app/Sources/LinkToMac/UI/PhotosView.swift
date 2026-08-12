import SwiftUI
import AppKit

struct PhotosView: View {
    var server: ConnectionServer
    @State private var selectedPhoto: PhotoThumbnail?

    private let pageSize = ConnectionServer.defaultPhotoPageSize

    var body: some View {
        let store = server.photoStore
        VStack(spacing: 0) {
            SectionHeaderView(
                icon: "photo.on.rectangle.angled",
                iconColor: .purple,
                title: "Photos",
                subtitle: "\(store.photos.count) photo\(store.photos.count == 1 ? "" : "s")\(store.hasMore ? "+" : "")"
            )
            photoGrid(store: store)
        }
        .task {
            if store.photos.isEmpty {
                loadNextPage()
            }
        }
        .sheet(item: $selectedPhoto) { photo in
            PhotoDetailView(photo: photo, server: server)
        }
    }

    @ViewBuilder
    private func photoGrid(store: PhotoStore) -> some View {
        ScrollView {
            if store.photos.isEmpty && !store.isLoadingMore {
                VStack(spacing: 8) {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                    Text("No photos loaded yet")
                        .foregroundStyle(.secondary)
                    Button("Load Photos") { loadNextPage() }
                        .padding(.top, 8)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 60)
            } else {
                LazyVStack(alignment: .leading, spacing: 16) {
                    ForEach(groupedByMonth, id: \.month) { group in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.month)
                                .font(.headline)
                                .padding(.horizontal)
                            LazyVGrid(columns: gridColumns, spacing: 4) {
                                ForEach(group.photos) { photo in
                                    PhotoThumbnailView(photo: photo)
                                        .onTapGesture {
                                            selectedPhoto = photo
                                            server.requestPhotoFull(id: photo.id)
                                        }
                                }
                            }
                            .padding(.horizontal)
                        }
                    }

                    if store.hasMore {
                        HStack {
                            Spacer()
                            if store.isLoadingMore {
                                ProgressView()
                            } else {
                                Button("Load More") { loadNextPage() }
                            }
                            Spacer()
                        }
                        .padding()
                    }
                }
                .padding(.vertical)
            }
        }
    }

    private func loadNextPage() {
        guard !server.photoStore.isLoadingMore else { return }
        server.requestPhotoPage(offset: server.photoStore.photos.count, limit: pageSize)
    }

    private var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: 110, maximum: 160), spacing: 4)]
    }

    private struct MonthGroup {
        let month: String
        let photos: [PhotoThumbnail]
    }

    private var groupedByMonth: [MonthGroup] {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMMM yyyy"
        var groups: [String: [PhotoThumbnail]] = [:]
        var order: [String] = []
        for photo in server.photoStore.photos {
            let date = Date(timeIntervalSince1970: photo.takenAt / 1000)
            let key = formatter.string(from: date)
            if groups[key] == nil {
                order.append(key)
            }
            groups[key, default: []].append(photo)
        }
        return order.map { MonthGroup(month: $0, photos: groups[$0] ?? []) }
    }
}

private struct PhotoThumbnailView: View {
    let photo: PhotoThumbnail

    var body: some View {
        Group {
            if let data = Data(base64Encoded: photo.thumbnailBase64), let nsImage = NSImage(data: data) {
                Image(nsImage: nsImage)
                    .resizable()
                    .aspectRatio(1, contentMode: .fill)
                    .clipped()
            } else {
                Rectangle().fill(.quaternary)
            }
        }
        .frame(minWidth: 110, minHeight: 110)
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }
}

private struct PhotoDetailView: View {
    let photo: PhotoThumbnail
    var server: ConnectionServer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack {
            // Reads the store directly (rather than a snapshotted value passed at sheet
            // presentation time) so the view updates once photo.full actually arrives.
            if let imageData = server.photoStore.fullImageData[photo.id], let nsImage = NSImage(data: imageData) {
                Image(nsImage: nsImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                ProgressView("Loading full-resolution photo…")
                    .frame(width: 400, height: 300)
            }
            Button("Close") { dismiss() }
                .padding()
        }
        .padding()
        .frame(minWidth: 400, minHeight: 300)
    }
}
