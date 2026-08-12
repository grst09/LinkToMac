import SwiftUI
import AppKit

/// Combines contacts and call history in one section with an internal tab switcher, matching
/// Phone Link's Calls app layout — see the sidebar restructuring note in docs/PLAN.md. Master
/// list on the left, detail panel on the right — same split pattern as MessagesView/FilesView.
/// The contacts list/detail styling deliberately follows macOS Contacts.app: alphabetical
/// sections, an explicit "Select" mode with checkboxes (rather than relying on cmd/shift-click
/// multi-select, which isn't discoverable), and a hero-card detail panel.
struct ContactsView: View {
    var server: ConnectionServer

    @State private var subTab: SubTab = .contacts
    @State private var searchText = ""
    @State private var selectedContactId: String?
    @State private var checkedContactIds: Set<String> = []
    @State private var isSelecting = false
    @State private var selectedCallId: String?
    @State private var listWidth: CGFloat = 340
    @State private var isEditingContact = false
    @State private var isCreatingContact = false
    @State private var isConfirmingBulkDelete = false

    private enum SubTab: String, CaseIterable {
        case contacts = "Contacts"
        case callHistory = "Call History"
    }

    private var contactStore: ContactStore { server.contactStore }
    private var callStore: CallLogStore { server.callLogStore }

    var body: some View {
        VStack(spacing: 0) {
            SectionHeaderView(
                icon: "person.2.fill",
                iconColor: .purple,
                title: "Contacts",
                subtitle: headerSubtitle
            ) {
                HStack(spacing: 8) {
                    if subTab == .contacts {
                        if isSelecting {
                            if !checkedContactIds.isEmpty {
                                Button(role: .destructive) {
                                    isConfirmingBulkDelete = true
                                } label: {
                                    Label("Delete (\(checkedContactIds.count))", systemImage: "trash")
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }

                            Button {
                                toggleSelectAll()
                            } label: {
                                Label(
                                    isAllContactsChecked ? "Deselect All" : "Select All",
                                    systemImage: isAllContactsChecked ? "checkmark.circle.fill" : "checkmark.circle"
                                )
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)

                            Button("Done") {
                                isSelecting = false
                                checkedContactIds.removeAll()
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        } else {
                            Button("Select") {
                                isSelecting = true
                                selectedContactId = nil
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)

                            Button {
                                isCreatingContact = true
                            } label: {
                                Label("New", systemImage: "person.badge.plus")
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }

                    Button {
                        server.requestContactsRefresh()
                    } label: {
                        Label("Sync", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }

            if let error = contactStore.lastError {
                errorBanner(error)
            }

            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    Picker("", selection: $subTab) {
                        ForEach(SubTab.allCases, id: \.self) { tab in
                            Text(tab.rawValue).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 8)

                    SearchBarView(text: $searchText, prompt: subTab == .contacts ? "Search contacts" : "Search calls")

                    Group {
                        switch subTab {
                        case .contacts: contactsList
                        case .callHistory: callHistoryList
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .frame(width: listWidth)

                ResizableDivider(width: $listWidth, minWidth: 260, maxWidth: 560)

                detailPanel
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .padding(12)
            }
        }
        .onChange(of: subTab) {
            searchText = ""
            selectedContactId = nil
            checkedContactIds.removeAll()
            isSelecting = false
            selectedCallId = nil
        }
        .onChange(of: selectedContactId) {
            isEditingContact = false
            isCreatingContact = false
        }
        .onChange(of: contactStore.lastError) {
            guard contactStore.lastError != nil else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                contactStore.clearError()
            }
        }
        .alert(
            "Delete \(checkedContactIds.count) Contact\(checkedContactIds.count == 1 ? "" : "s")?",
            isPresented: $isConfirmingBulkDelete
        ) {
            Button("Delete", role: .destructive, action: deleteCheckedContacts)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This can't be undone.")
        }
    }

    private var isAllContactsChecked: Bool {
        !filteredContacts.isEmpty && Set(filteredContacts.map(\.id)).isSubset(of: checkedContactIds)
    }

    private func toggleSelectAll() {
        if isAllContactsChecked {
            checkedContactIds.removeAll()
        } else {
            checkedContactIds = Set(filteredContacts.map(\.id))
        }
    }

    private func toggleChecked(_ id: String) {
        if checkedContactIds.contains(id) {
            checkedContactIds.remove(id)
        } else {
            checkedContactIds.insert(id)
        }
    }

    private func deleteCheckedContacts() {
        for id in checkedContactIds {
            server.deleteContact(id: id)
        }
        checkedContactIds.removeAll()
        isSelecting = false
    }

    private var headerSubtitle: String {
        switch subTab {
        case .contacts:
            let count = contactStore.contacts.count
            var text = "\(count) contact\(count == 1 ? "" : "s")"
            if let synced = contactStore.lastSyncedAt {
                text += " · Updated \(synced.formatted(date: .omitted, time: .shortened))"
            }
            return text
        case .callHistory:
            let count = callStore.calls.count
            return "\(count) call\(count == 1 ? "" : "s")"
        }
    }

    // MARK: - Detail panel

    @ViewBuilder
    private var detailPanel: some View {
        switch subTab {
        case .contacts:
            if isCreatingContact {
                ContactEditPanel(server: server, existing: nil, onDone: { isCreatingContact = false })
            } else if isSelecting {
                if checkedContactIds.isEmpty {
                    placeholder(icon: "checkmark.circle", text: "Select contacts to delete")
                } else {
                    bulkSelectionPanel
                }
            } else if let contact = contactStore.contacts.first(where: { $0.id == selectedContactId }) {
                if isEditingContact {
                    ContactEditPanel(server: server, existing: contact, onDone: { isEditingContact = false })
                } else {
                    ContactDetailPanel(
                        contact: contact,
                        server: server,
                        onEdit: { isEditingContact = true },
                        onDelete: {
                            server.deleteContact(id: contact.id)
                            selectedContactId = nil
                        }
                    )
                }
            } else {
                placeholder(icon: "person.crop.circle", text: "Select a contact")
            }
        case .callHistory:
            if let call = callStore.calls.first(where: { $0.id == selectedCallId }) {
                CallDetailPanel(call: call, server: server)
            } else {
                placeholder(icon: "phone.circle", text: "Select a call")
            }
        }
    }

    private var bulkSelectionPanel: some View {
        VStack(spacing: 12) {
            Image(systemName: "person.2.fill")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("\(checkedContactIds.count) contacts selected")
                .font(.title3.bold())
            Button(role: .destructive) {
                isConfirmingBulkDelete = true
            } label: {
                Label("Delete Selected", systemImage: "trash")
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            Text(message).font(.caption)
            Spacer()
        }
        .padding(10)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func placeholder(icon: String, text: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text(text)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Contacts

    private var filteredContacts: [ContactEntry] {
        guard !searchText.isEmpty else { return contactStore.contacts }
        return contactStore.contacts.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) || $0.phoneNumber.localizedCaseInsensitiveContains(searchText)
        }
    }

    /// Alphabetical sections, matching Contacts.app — non-letter-leading names land in "#".
    private var groupedContacts: [(letter: String, contacts: [ContactEntry])] {
        let groups = Dictionary(grouping: filteredContacts) { contact -> String in
            guard let first = contact.name.trimmingCharacters(in: .whitespaces).first, first.isLetter else { return "#" }
            return String(first).uppercased()
        }
        return groups.keys.sorted().map { key in
            (letter: key, contacts: groups[key]!.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending })
        }
    }

    private var contactsList: some View {
        Group {
            if contactStore.contacts.isEmpty {
                emptyState(icon: "person.2", text: "No contacts yet")
            } else if filteredContacts.isEmpty {
                emptyState(icon: "magnifyingglass", text: "No matching contacts")
            } else {
                List {
                    ForEach(groupedContacts, id: \.letter) { group in
                        Section {
                            ForEach(group.contacts) { contact in
                                ContactRowView(
                                    contact: contact,
                                    isSelecting: isSelecting,
                                    isChecked: checkedContactIds.contains(contact.id),
                                    isHighlighted: !isSelecting && contact.id == selectedContactId
                                )
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    if isSelecting {
                                        toggleChecked(contact.id)
                                    } else {
                                        selectedContactId = contact.id
                                    }
                                }
                            }
                        } header: {
                            Text(group.letter)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Call history

    private var filteredCalls: [CallLogEntry] {
        guard !searchText.isEmpty else { return callStore.calls }
        return callStore.calls.filter { call in
            (call.contactName ?? "").localizedCaseInsensitiveContains(searchText)
                || call.number.localizedCaseInsensitiveContains(searchText)
        }
    }

    private var callHistoryList: some View {
        Group {
            if callStore.calls.isEmpty {
                emptyState(icon: "phone", text: "No call history yet")
            } else if filteredCalls.isEmpty {
                emptyState(icon: "magnifyingglass", text: "No matching calls")
            } else {
                List(filteredCalls, selection: $selectedCallId) { call in
                    CallRowView(call: call).tag(call.id)
                }
            }
        }
    }

    private func emptyState(icon: String, text: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 32))
                .foregroundStyle(.secondary)
            Text(text)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ContactRowView: View {
    let contact: ContactEntry
    var isSelecting: Bool = false
    var isChecked: Bool = false
    var isHighlighted: Bool = false

    var body: some View {
        HStack(spacing: 12) {
            if isSelecting {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18))
                    .foregroundStyle(isChecked ? Color.accentColor : Color.secondary)
            }
            InitialsAvatarView(name: contact.name)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(contact.name).font(.body)
                    if contact.isStarred {
                        Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow)
                    }
                }
                Text(contact.phoneNumber).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 4)
        .background(isHighlighted ? Color.accentColor.opacity(0.25) : Color.clear, in: RoundedRectangle(cornerRadius: 6))
    }
}

private struct CallRowView: View {
    let call: CallLogEntry

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .foregroundStyle(iconColor)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(call.contactName ?? call.number)
                    .font(.body)
                if call.contactName != nil {
                    Text(call.number).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(relativeDate).font(.caption).foregroundStyle(.secondary)
                if call.durationSeconds > 0 {
                    Text(formattedDuration).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var iconName: String {
        switch call.type {
        case "incoming": return "phone.arrow.down.left"
        case "outgoing": return "phone.arrow.up.right"
        case "missed": return "phone.down"
        case "rejected", "blocked": return "phone.down.fill"
        case "voicemail": return "voicemail"
        default: return "phone"
        }
    }

    private var iconColor: Color {
        switch call.type {
        case "missed", "rejected", "blocked": return .red
        default: return .secondary
        }
    }

    private var relativeDate: String {
        Date(timeIntervalSince1970: call.date / 1000).formatted(.relative(presentation: .named))
    }

    private var formattedDuration: String {
        let minutes = call.durationSeconds / 60
        let seconds = call.durationSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

/// Hero-card layout modeled on macOS Contacts.app: a gradient card with the avatar overlapping
/// its bottom edge, name, circular action buttons, an info card, then full-width action rows.
private struct ContactDetailPanel: View {
    let contact: ContactEntry
    var server: ConnectionServer
    var onEdit: () -> Void
    var onDelete: () -> Void
    @State private var copied = false
    @State private var isConfirmingDelete = false

    private let avatarDiameter: CGFloat = 108

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Spacer().frame(height: 36)

                InitialsAvatarView(name: contact.name, diameter: avatarDiameter)

                VStack(spacing: 18) {
                    VStack(spacing: 4) {
                        Text(contact.name).font(.title2.bold())
                        if contact.isStarred {
                            Label("Starred", systemImage: "star.fill")
                                .font(.caption)
                                .foregroundStyle(.yellow)
                        }
                    }

                    HStack(spacing: 20) {
                        CircleActionButton(icon: "message.fill", tint: .green) {
                            server.pendingMessageAddress = contact.phoneNumber
                        }
                        CircleActionButton(icon: "phone.fill", tint: .blue) {
                            server.dialContact(phoneNumber: contact.phoneNumber)
                        }
                        CircleActionButton(icon: copied ? "checkmark" : "doc.on.doc", tint: .gray) {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(contact.phoneNumber, forType: .string)
                            copied = true
                        }
                    }

                    VStack(spacing: 0) {
                        infoRow(label: "phone", value: contact.phoneNumber)
                        if let email = contact.email {
                            Divider().padding(.leading, 12)
                            infoRow(label: "email", value: email)
                        }
                        if let organization = contact.organization {
                            Divider().padding(.leading, 12)
                            infoRow(label: "organization", value: organization)
                        }
                    }
                    .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 10))
                    .frame(maxWidth: 360)

                    VStack(spacing: 1) {
                        actionRow("Edit Contact", icon: "pencil", action: onEdit)
                        actionRow("Delete Contact", icon: "trash", tint: .red) {
                            isConfirmingDelete = true
                        }
                    }
                    .frame(maxWidth: 360)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 30)
            }
            .frame(maxWidth: .infinity)
        }
        .background(panelGradient)
        .alert("Delete \(contact.name)?", isPresented: $isConfirmingDelete) {
            Button("Delete", role: .destructive, action: onDelete)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This can't be undone.")
        }
        .onChange(of: contact.id) {
            copied = false
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    /// Covers the whole panel (not just a header card) — `.background` on the ScrollView fills
    /// its frame regardless of scrolled content height, fading from the contact's tint at the
    /// top down to nothing so it reads as an ambient wash rather than a hard-edged card.
    private var panelGradient: LinearGradient {
        let base = InitialsAvatarView.color(for: contact.name)
        return LinearGradient(
            colors: [base.opacity(0.45), base.opacity(0.18), Color.clear],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    private func infoRow(label: String, value: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.body)
            }
            Spacer()
        }
        .padding(12)
    }

    private func actionRow(_ title: String, icon: String, tint: Color = .accentColor, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(tint)
                Spacer()
                Image(systemName: icon).foregroundStyle(tint)
            }
            .padding(12)
            .background(Color(nsColor: .controlBackgroundColor))
        }
        .buttonStyle(.plain)
    }
}

/// Icon-only round button used in ContactDetailPanel's action row, matching Contacts.app's
/// message/call icon buttons under the contact name.
private struct CircleActionButton: View {
    let icon: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(tint.opacity(0.15))
                Image(systemName: icon).foregroundStyle(tint)
            }
            .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
    }
}

/// Fills the same detail panel slot as ContactDetailPanel/CallDetailPanel — editing and
/// creating both happen inline there rather than in a popup sheet.
private struct ContactEditPanel: View {
    var server: ConnectionServer
    var existing: ContactEntry?
    var onDone: () -> Void

    @State private var name: String
    @State private var phoneNumber: String
    @State private var email: String
    @State private var organization: String
    @State private var isStarred: Bool

    init(server: ConnectionServer, existing: ContactEntry?, onDone: @escaping () -> Void) {
        self.server = server
        self.existing = existing
        self.onDone = onDone
        _name = State(initialValue: existing?.name ?? "")
        _phoneNumber = State(initialValue: existing?.phoneNumber ?? "")
        _email = State(initialValue: existing?.email ?? "")
        _organization = State(initialValue: existing?.organization ?? "")
        _isStarred = State(initialValue: existing?.isStarred ?? false)
    }

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text(existing == nil ? "New Contact" : "Edit Contact").font(.title2.bold())

            VStack(alignment: .leading, spacing: 12) {
                field("Name") { TextField("Name", text: $name).textFieldStyle(.roundedBorder) }
                field("Phone Number") { TextField("Phone Number", text: $phoneNumber).textFieldStyle(.roundedBorder) }
                field("Email") { TextField("Optional", text: $email).textFieldStyle(.roundedBorder) }
                field("Organization") { TextField("Optional", text: $organization).textFieldStyle(.roundedBorder) }
                Toggle("Starred", isOn: $isStarred)
            }
            .frame(maxWidth: 320)

            HStack {
                Button("Cancel", action: onDone)
                    .keyboardShortcut(.cancelAction)
                Button(existing == nil ? "Create" : "Save", action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(
                        name.trimmingCharacters(in: .whitespaces).isEmpty
                            || phoneNumber.trimmingCharacters(in: .whitespaces).isEmpty
                    )
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            content()
        }
    }

    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let trimmedPhone = phoneNumber.trimmingCharacters(in: .whitespaces)
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        let trimmedOrg = organization.trimmingCharacters(in: .whitespaces)
        guard !trimmedName.isEmpty, !trimmedPhone.isEmpty else { return }
        if let existing {
            server.updateContact(
                id: existing.id,
                name: trimmedName,
                phoneNumber: trimmedPhone,
                isStarred: isStarred,
                email: trimmedEmail.isEmpty ? nil : trimmedEmail,
                organization: trimmedOrg.isEmpty ? nil : trimmedOrg
            )
        } else {
            server.createContact(
                name: trimmedName,
                phoneNumber: trimmedPhone,
                email: trimmedEmail.isEmpty ? nil : trimmedEmail,
                organization: trimmedOrg.isEmpty ? nil : trimmedOrg
            )
        }
        onDone()
    }
}

/// Round icon button with a caption, used in CallDetailPanel — "Call" opens the phone's own
/// dialer (see ContactsDialPayload), it doesn't place a call directly.
private struct DetailActionButton: View {
    let icon: String
    let label: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    Circle().fill(tint.opacity(0.15))
                    Image(systemName: icon).foregroundStyle(tint)
                }
                .frame(width: 44, height: 44)
                Text(label).font(.caption).foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
    }
}

private struct CallDetailPanel: View {
    let call: CallLogEntry
    var server: ConnectionServer

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Spacer()
            HStack {
                Spacer()
                VStack(spacing: 8) {
                    Image(systemName: iconName)
                        .font(.system(size: 40))
                        .foregroundStyle(iconColor)
                    Text(call.contactName ?? call.number).font(.title2.bold())
                    if call.contactName != nil {
                        Text(call.number).font(.title3).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            VStack(alignment: .leading, spacing: 10) {
                LabeledContent("Type", value: call.type.capitalized)
                LabeledContent("Date", value: fullDate)
                if call.durationSeconds > 0 {
                    LabeledContent("Duration", value: formattedDuration)
                }
            }
            .frame(maxWidth: 280)
            .frame(maxWidth: .infinity)

            HStack {
                Spacer()
                DetailActionButton(icon: "phone.fill", label: "Call", tint: .green) {
                    server.dialContact(phoneNumber: call.number)
                }
                DetailActionButton(icon: "message.fill", label: "Message", tint: .blue) {
                    server.pendingMessageAddress = call.number
                }
                Spacer()
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var iconName: String {
        switch call.type {
        case "incoming": return "phone.arrow.down.left"
        case "outgoing": return "phone.arrow.up.right"
        case "missed": return "phone.down"
        case "rejected", "blocked": return "phone.down.fill"
        case "voicemail": return "voicemail"
        default: return "phone"
        }
    }

    private var iconColor: Color {
        switch call.type {
        case "missed", "rejected", "blocked": return .red
        default: return .secondary
        }
    }

    private var fullDate: String {
        Date(timeIntervalSince1970: call.date / 1000).formatted(date: .abbreviated, time: .shortened)
    }

    private var formattedDuration: String {
        let minutes = call.durationSeconds / 60
        let seconds = call.durationSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}
