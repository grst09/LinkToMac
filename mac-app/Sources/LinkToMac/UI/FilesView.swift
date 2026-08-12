import SwiftUI
import UniformTypeIdentifiers

struct FilesView: View {
    var server: ConnectionServer
    @State private var isDropTargeted = false
    @State private var selectedName: String?
    @State private var lastTapName: String?
    @State private var lastTapTime: Date?
    @State private var isCreatingFolder = false
    @State private var newFolderName = ""
    @State private var renamingEntry: FileEntry?
    @State private var renameText = ""
    @State private var entryPendingDelete: FileEntry?

    private var store: FileStore { server.fileStore }

    private var viewModeBinding: Binding<FileStore.ViewMode> {
        Binding(get: { store.viewMode }, set: { store.viewMode = $0 })
    }

    var body: some View {
        VStack(spacing: 0) {
            SectionHeaderView(
                icon: "folder.fill",
                iconColor: .blue,
                title: "Files",
                subtitle: "Browsing \(deviceName)"
            )

            dropZone

            if let uploadingName = store.uploadingFileName {
                uploadProgressBanner(uploadingName)
            }
            if let message = store.lastSuccessMessage {
                banner(message, icon: "checkmark.circle.fill", tint: .green)
            }
            if let error = store.lastError {
                banner(error, icon: "exclamationmark.triangle.fill", tint: .orange)
            }

            toolbarRow

            Group {
                if store.isLoading && store.entries.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if store.entries.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "folder")
                            .font(.system(size: 32))
                            .foregroundStyle(.secondary)
                        Text("This folder is empty")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .contextMenu { backgroundMenuItems }
                } else if store.viewMode == .grid {
                    gridContent
                } else {
                    listContent
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Divider()
            footerBar
        }
        .task {
            if store.entries.isEmpty && !store.isLoading {
                server.requestFilesList(path: store.currentPath)
            }
        }
        .onChange(of: store.currentPath) {
            selectedName = nil
        }
        .onChange(of: store.entries) {
            if let name = store.lastCreatedOrUploadedName, store.entries.contains(where: { $0.name == name }) {
                selectedName = name
            }
        }
        .onChange(of: store.lastSuccessMessage) {
            guard store.lastSuccessMessage != nil else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                store.clearSuccessMessage()
            }
        }
        .sheet(isPresented: $isCreatingFolder) {
            newFolderSheet
        }
        .sheet(item: $renamingEntry) { entry in
            renameSheet(entry)
        }
        .alert(
            "Delete \(entryPendingDelete?.name ?? "")?",
            isPresented: Binding(get: { entryPendingDelete != nil }, set: { if !$0 { entryPendingDelete = nil } }),
            presenting: entryPendingDelete
        ) { entry in
            Button("Delete", role: .destructive) { server.deleteEntry(name: entry.name) }
            Button("Cancel", role: .cancel) {}
        } message: { entry in
            Text(entry.isDirectory ? "This will delete the folder and everything in it." : "This can't be undone.")
        }
    }

    private var deviceName: String {
        if case .connected(let name) = server.state { return name }
        return "device"
    }

    // MARK: - Drop zone

    private var dropZone: some View {
        RoundedRectangle(cornerRadius: 10)
            .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
            .foregroundStyle(isDropTargeted ? Color.accentColor : Color.secondary.opacity(0.4))
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(isDropTargeted ? Color.accentColor.opacity(0.08) : Color.clear)
            )
            .frame(height: 110)
            .overlay {
                VStack(spacing: 6) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 20))
                        .foregroundStyle(.secondary)
                    Text("Drag files here to upload")
                        .font(.subheadline.bold())
                    Text("Supports files, folders, and multiple items")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 12)
            .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
                handleDrop(providers)
            }
    }

    /// Indeterminate — the upload goes over the wire as a single message, not chunked (see
    /// docs/PROTOCOL.md), so there's no real byte count to report progress from.
    private func uploadProgressBanner(_ name: String) -> some View {
        HStack(spacing: 8) {
            ProgressView()
                .controlSize(.small)
            Text("Uploading \(name)…").font(.caption)
            Spacer()
        }
        .padding(10)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func banner(_ message: String, icon: String, tint: Color) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon).foregroundStyle(tint)
            Text(message).font(.caption)
            Spacer()
        }
        .padding(10)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: - Toolbar (device label, back, new folder, view mode)

    private var toolbarRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "chevron.down")
                .font(.caption)
                .foregroundStyle(.secondary)
            Image(systemName: "internaldrive")
                .foregroundStyle(.secondary)
            Text("Device Storage")
                .font(.subheadline.bold())

            Spacer()

            Button {
                navigateUp()
            } label: {
                Label("Back", systemImage: "chevron.left")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .disabled(store.currentPath.isEmpty)

            Button {
                newFolderName = ""
                isCreatingFolder = true
            } label: {
                Label("New Folder", systemImage: "folder.badge.plus")
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.accentColor)

            Picker("View", selection: viewModeBinding) {
                Image(systemName: "square.grid.2x2").tag(FileStore.ViewMode.grid)
                Image(systemName: "list.bullet").tag(FileStore.ViewMode.list)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 90)
        }
        .font(.subheadline)
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    // MARK: - Grid view

    private var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: 90, maximum: 120), spacing: 16)]
    }

    private var gridContent: some View {
        ScrollView {
            LazyVGrid(columns: gridColumns, spacing: 16) {
                ForEach(store.entries) { entry in
                    FileEntryView(entry: entry, isSelected: entry.name == selectedName, isCutPending: isCutPending(entry))
                        .onTapGesture { handleTap(entry) }
                        .contextMenu { itemMenuItems(entry) }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .contentShape(Rectangle())
            .contextMenu { backgroundMenuItems }
        }
    }

    // MARK: - List view

    private var listContent: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(store.entries) { entry in
                    FileListRowView(entry: entry, isSelected: entry.name == selectedName, isCutPending: isCutPending(entry))
                        .onTapGesture { handleTap(entry) }
                        .contextMenu { itemMenuItems(entry) }
                    Divider().padding(.leading, 44)
                }
            }
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .contentShape(Rectangle())
            .contextMenu { backgroundMenuItems }
        }
    }

    // MARK: - Context menus

    @ViewBuilder
    private func itemMenuItems(_ entry: FileEntry) -> some View {
        Button("Rename…") {
            renameText = entry.name
            renamingEntry = entry
        }
        Button("Cut") { server.cutEntryToClipboard(name: entry.name) }
        Button("Copy") { server.copyEntryToClipboard(name: entry.name) }
        if store.clipboardPath != nil {
            Button("Paste") { server.pasteClipboard() }
        }
        Divider()
        Button("Delete", role: .destructive) { entryPendingDelete = entry }
    }

    @ViewBuilder
    private var backgroundMenuItems: some View {
        if store.clipboardPath != nil {
            Button("Paste") { server.pasteClipboard() }
        }
        Button("New Folder") {
            newFolderName = ""
            isCreatingFolder = true
        }
    }

    private func isCutPending(_ entry: FileEntry) -> Bool {
        store.clipboardOperation == .cut && store.clipboardName == entry.name
    }

    // MARK: - Selection / open (manual tap timing avoids SwiftUI's built-in double-tap
    // disambiguation delay, which made single-tap selection feel laggy)

    private func handleTap(_ entry: FileEntry) {
        let now = Date()
        if selectedName == entry.name, lastTapName == entry.name,
           let last = lastTapTime, now.timeIntervalSince(last) < 0.4 {
            open(entry)
            lastTapTime = nil
            lastTapName = nil
        } else {
            selectedName = entry.name
            lastTapName = entry.name
            lastTapTime = now
        }
    }

    private func open(_ entry: FileEntry) {
        let path = fullPath(for: entry.name)
        if entry.isDirectory {
            server.requestFilesList(path: path)
        } else {
            server.requestFileDownload(path: path)
        }
    }

    private func navigateUp() {
        guard !store.currentPath.isEmpty else { return }
        let parent = store.currentPath.split(separator: "/").dropLast().joined(separator: "/")
        server.requestFilesList(path: parent)
    }

    private func fullPath(for name: String) -> String {
        store.currentPath.isEmpty ? name : "\(store.currentPath)/\(name)"
    }

    // MARK: - New folder

    private var newFolderSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("New Folder").font(.headline)
            TextField("Folder name", text: $newFolderName)
                .textFieldStyle(.roundedBorder)
                .onSubmit(createFolder)
            HStack {
                Spacer()
                Button("Cancel") { isCreatingFolder = false }
                    .keyboardShortcut(.cancelAction)
                Button("Create", action: createFolder)
                    .keyboardShortcut(.defaultAction)
                    .disabled(newFolderName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 320)
    }

    private func createFolder() {
        let name = newFolderName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        server.createFolder(name: name)
        isCreatingFolder = false
    }

    // MARK: - Rename

    private func renameSheet(_ entry: FileEntry) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Rename").font(.headline)
            TextField("Name", text: $renameText)
                .textFieldStyle(.roundedBorder)
                .onSubmit { renameEntry(entry) }
            HStack {
                Spacer()
                Button("Cancel") { renamingEntry = nil }
                    .keyboardShortcut(.cancelAction)
                Button("Rename") { renameEntry(entry) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(renameText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 320)
    }

    private func renameEntry(_ entry: FileEntry) {
        let newName = renameText.trimmingCharacters(in: .whitespaces)
        guard !newName.isEmpty, newName != entry.name else {
            renamingEntry = nil
            return
        }
        server.renameEntry(name: entry.name, to: newName)
        renamingEntry = nil
    }

    // MARK: - Footer breadcrumb + stats

    private struct Breadcrumb {
        let name: String
        let path: String
    }

    private var breadcrumbSegments: [Breadcrumb] {
        var segments = [Breadcrumb(name: "Device", path: "")]
        guard !store.currentPath.isEmpty else { return segments }
        var accumulated = ""
        for part in store.currentPath.split(separator: "/") {
            accumulated = accumulated.isEmpty ? String(part) : "\(accumulated)/\(part)"
            segments.append(Breadcrumb(name: String(part), path: accumulated))
        }
        return segments
    }

    private var footerBar: some View {
        HStack(spacing: 4) {
            ForEach(Array(breadcrumbSegments.enumerated()), id: \.offset) { index, segment in
                HStack(spacing: 4) {
                    if index > 0 {
                        Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.secondary)
                    }
                    Button(segment.name) {
                        server.requestFilesList(path: segment.path)
                    }
                    .buttonStyle(.plain)
                }
            }

            Spacer()

            Text(footerStats)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .font(.subheadline)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var footerStats: String {
        let itemWord = store.entries.count == 1 ? "item" : "items"
        let itemsText = "\(store.entries.count) \(itemWord)"
        let files = store.entries.filter { !$0.isDirectory }
        guard !files.isEmpty else { return itemsText }
        let totalBytes = files.reduce(0.0) { $0 + $1.sizeBytes }
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        let sizeText = formatter.string(fromByteCount: Int64(totalBytes))
        return "\(itemsText) · \(sizeText)"
    }

    // MARK: - Drop handling

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        var handled = false
        for provider in providers where provider.canLoadObject(ofClass: URL.self) {
            handled = true
            _ = provider.loadObject(ofClass: URL.self) { url, error in
                guard let url else {
                    if let error {
                        print("LinkToMac: file drop failed to resolve URL: \(error)")
                    }
                    return
                }
                var isDirectory: ObjCBool = false
                guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory), !isDirectory.boolValue else { return }
                DispatchQueue.main.async {
                    server.uploadFile(fileURL: url)
                }
            }
        }
        return handled
    }
}

/// Maps well-known Android storage folder names to a distinct icon + color, same idea as
/// Phone Link's colorized special folders — everything else falls back to a plain blue folder.
private func folderAppearance(for name: String) -> (icon: String, color: Color) {
    switch name.lowercased() {
    case "dcim", "pictures", "photos": return ("photo.fill", .orange)
    case "download", "downloads": return ("arrow.down.circle.fill", .green)
    case "music": return ("music.note", .pink)
    case "ringtones": return ("bell.fill", .purple)
    case "alarms": return ("alarm.fill", .red)
    case "podcasts": return ("mic.fill", .purple)
    case "audiobooks": return ("book.fill", .brown)
    case "recordings": return ("waveform", .red)
    case "notifications": return ("bell.badge.fill", .orange)
    case "movies", "video", "videos": return ("film.fill", .red)
    case "documents": return ("doc.text.fill", .blue)
    case "android": return ("gearshape.fill", .gray)
    case "log", "logs": return ("terminal.fill", .gray)
    default: return ("folder.fill", .accentColor)
    }
}

private struct FileEntryView: View {
    let entry: FileEntry
    var isSelected: Bool = false
    var isCutPending: Bool = false

    var body: some View {
        let appearance = entry.isDirectory ? folderAppearance(for: entry.name) : ("doc.fill", Color.secondary)
        VStack(spacing: 6) {
            Image(systemName: appearance.0)
                .font(.system(size: 36))
                .foregroundStyle(appearance.1)
            Text(entry.name)
                .font(.caption)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(height: 30)
        }
        .padding(8)
        .frame(width: 90)
        .opacity(isCutPending ? 0.5 : 1)
        .background(isSelected ? Color.accentColor.opacity(0.25) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
    }
}

private struct FileListRowView: View {
    let entry: FileEntry
    var isSelected: Bool = false
    var isCutPending: Bool = false

    var body: some View {
        let appearance = entry.isDirectory ? folderAppearance(for: entry.name) : ("doc.fill", Color.secondary)
        HStack(spacing: 10) {
            Image(systemName: appearance.0)
                .font(.system(size: 16))
                .foregroundStyle(appearance.1)
                .frame(width: 20)
            Text(entry.name)
                .font(.subheadline)
                .lineLimit(1)
            Spacer()
            Text(entry.isDirectory ? "" : sizeText)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 70, alignment: .trailing)
            Text(modifiedText)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 90, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .opacity(isCutPending ? 0.5 : 1)
        .background(isSelected ? Color.accentColor.opacity(0.25) : Color.clear)
        .contentShape(Rectangle())
    }

    private var sizeText: String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(entry.sizeBytes))
    }

    private var modifiedText: String {
        Date(timeIntervalSince1970: entry.modifiedAt / 1000).formatted(.relative(presentation: .named))
    }
}
