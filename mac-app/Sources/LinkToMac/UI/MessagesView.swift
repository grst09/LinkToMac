import SwiftUI

struct MessagesView: View {
    var server: ConnectionServer

    @State private var selectedThreadId: String?
    @State private var searchText = ""
    @State private var isComposing = false
    @State private var composeAddress = ""
    @State private var listWidth: CGFloat = 260

    private var store: MessageStore { server.messageStore }

    var body: some View {
        VStack(spacing: 0) {
            SectionHeaderView(
                icon: "message.fill",
                iconColor: .blue,
                title: "Messages",
                subtitle: "\(store.allThreads.count) conversation\(store.allThreads.count == 1 ? "" : "s")"
            ) {
                HStack(spacing: 8) {
                    Button {
                        server.requestMessagesRefresh()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("Refresh")

                    Button {
                        startComposing(address: "")
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("New Message")
                }
            }

            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    SearchBarView(text: $searchText, prompt: "Search conversations")
                    threadList
                }
                .frame(width: listWidth)

                ResizableDivider(width: $listWidth, minWidth: 200, maxWidth: 460)

                if isComposing {
                    ComposeView(
                        address: $composeAddress,
                        onSend: { body in
                            let address = composeAddress.trimmingCharacters(in: .whitespaces)
                            guard !address.isEmpty else { return }
                            server.sendSms(address: address, body: body)
                            // The phone will never reflect this back for a brand-new
                            // conversation unless LinkToMac is the default SMS app — see
                            // MessageStore's doc comment — so show it locally right away
                            // instead of waiting on a sync that won't arrive.
                            selectedThreadId = store.addLocalMessage(address: address, body: body)
                            isComposing = false
                        },
                        onCancel: { isComposing = false }
                    )
                } else if let thread = selectedThread {
                    ConversationView(
                        thread: thread,
                        displayName: displayName(for: thread),
                        onSend: { body in server.sendSms(address: thread.address, body: body) }
                    )
                } else {
                    emptyDetailPlaceholder
                }
            }
        }
        .task {
            if selectedThreadId == nil {
                selectedThreadId = store.allThreads.first?.threadId
            }
            // Handles the case where Contacts set pendingMessageAddress and switched the
            // sidebar selection to Messages in the same beat this view was created — .onChange
            // below only fires on a subsequent change, not on a value that was already set
            // before this view existed to observe it.
            handlePendingMessageAddress()
        }
        .onChange(of: server.pendingMessageAddress) {
            handlePendingMessageAddress()
        }
        .onChange(of: selectedThreadId) {
            if selectedThreadId != nil {
                isComposing = false
            }
        }
    }

    private func handlePendingMessageAddress() {
        guard let address = server.pendingMessageAddress else { return }
        if let thread = store.allThreads.first(where: { $0.address == address }) {
            isComposing = false
            selectedThreadId = thread.threadId
        } else {
            startComposing(address: address)
        }
        server.pendingMessageAddress = nil
    }

    private var emptyDetailPlaceholder: some View {
        VStack(spacing: 10) {
            Image(systemName: "message")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("Select a conversation")
                .font(.headline)
                .foregroundStyle(.secondary)
            Text("Choose a conversation on the left, or start a new one.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func startComposing(address: String) {
        composeAddress = address
        isComposing = true
        selectedThreadId = nil
    }

    /// Android already resolves `thread.contactName` for real synced threads, but a
    /// local-only thread (see MessageStore) never went through that lookup — so this also
    /// falls back to matching against the Mac's own synced ContactStore, which covers both
    /// cases with one lookup and doesn't require a round trip to the phone.
    private func displayName(for thread: SmsThread) -> String {
        if let name = thread.contactName, !name.isEmpty {
            return name
        }
        if let match = server.contactStore.contacts.first(where: { phoneNumbersMatch($0.phoneNumber, thread.address) }) {
            return match.name
        }
        return thread.address
    }

    private func phoneNumbersMatch(_ a: String, _ b: String) -> Bool {
        let digitsA = a.filter(\.isNumber)
        let digitsB = b.filter(\.isNumber)
        let length = min(digitsA.count, digitsB.count, 10)
        guard length > 0 else { return false }
        return digitsA.suffix(length) == digitsB.suffix(length)
    }

    private var selectedThread: SmsThread? {
        store.allThreads.first { $0.threadId == selectedThreadId }
    }

    private var filteredThreads: [SmsThread] {
        guard !searchText.isEmpty else { return store.allThreads }
        return store.allThreads.filter { thread in
            (thread.contactName ?? "").localizedCaseInsensitiveContains(searchText)
                || thread.address.localizedCaseInsensitiveContains(searchText)
                || (thread.messages.last?.body ?? "").localizedCaseInsensitiveContains(searchText)
        }
    }

    private var threadList: some View {
        Group {
            if store.allThreads.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "message")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                    Text("No messages yet")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredThreads.isEmpty {
                Text("No matching conversations")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(filteredThreads, selection: $selectedThreadId) { thread in
                    ThreadRowView(thread: thread, displayName: displayName(for: thread))
                        .tag(thread.threadId)
                }
            }
        }
    }
}

private struct ThreadRowView: View {
    let thread: SmsThread
    let displayName: String

    var body: some View {
        HStack(spacing: 10) {
            InitialsAvatarView(name: displayName)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 4) {
                    Text(displayName).font(.body.bold())
                    if MessageStore.isLocalOnly(thread.threadId) {
                        Image(systemName: "iphone.slash")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .help("Not synced with your phone — see the conversation for details")
                    }
                }
                if let last = thread.messages.last {
                    Text(last.body).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

/// Blank "new message" screen — a To: field plus the same input row ConversationView uses.
/// Sending here is what actually creates the conversation on the phone (there's no thread to
/// select until at least one message exists in it).
private struct ComposeView: View {
    @Binding var address: String
    var onSend: (String) -> Void
    var onCancel: () -> Void

    @State private var draft = ""
    @FocusState private var addressFieldFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("To:")
                    .foregroundStyle(.secondary)
                TextField("Phone number", text: $address)
                    .textFieldStyle(.plain)
                    .focused($addressFieldFocused)
                Spacer()
                Button("Cancel", action: onCancel)
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
            .padding(12)
            Divider()

            Spacer()
            VStack(spacing: 8) {
                Image(systemName: "square.and.pencil")
                    .font(.system(size: 36))
                    .foregroundStyle(.secondary)
                Text("New Message")
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
            Spacer()

            Divider()
            HStack {
                TextField("Message", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button("Send", action: send)
                    .disabled(
                        draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || address.trimmingCharacters(in: .whitespaces).isEmpty
                    )
            }
            .padding(12)
        }
        .onAppear {
            if address.isEmpty {
                addressFieldFocused = true
            }
        }
    }

    private func send() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !address.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        onSend(trimmed)
        draft = ""
    }
}

private struct ConversationView: View {
    let thread: SmsThread
    let displayName: String
    var onSend: (String) -> Void

    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            conversationHeader
            Divider()
            if MessageStore.isLocalOnly(thread.threadId) {
                localOnlyBanner
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(thread.messages) { message in
                            MessageBubbleView(message: message)
                                .id(message.id)
                        }
                    }
                    .padding()
                }
                .onAppear {
                    if let lastId = thread.messages.last?.id {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
                .onChange(of: thread.messages.last?.id) { _, newId in
                    guard let newId else { return }
                    withAnimation {
                        proxy.scrollTo(newId, anchor: .bottom)
                    }
                }
            }
            Divider()
            HStack {
                TextField("Message", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button("Send", action: send)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(12)
        }
    }

    private func send() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        onSend(trimmed)
        draft = ""
    }

    private var conversationHeader: some View {
        HStack(spacing: 10) {
            InitialsAvatarView(name: displayName, diameter: 30)
            Text(displayName).font(.headline)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    /// Android restricts writes to the shared SMS database to whichever app holds the default
    /// SMS role — LinkToMac sending to a brand-new number never gets recorded there, confirmed
    /// by directly querying the phone's SMS database after a send that Google Messages itself
    /// showed as delivered. So this conversation is Mac-only: it'll never show a reply.
    private var localOnlyBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "iphone.slash").foregroundStyle(.orange)
            Text("Not synced with your phone — replies won't appear here. Check your phone's messaging app to continue this conversation.")
                .font(.caption)
            Spacer()
        }
        .padding(10)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal, 12)
        .padding(.top, 12)
    }
}

private struct MessageBubbleView: View {
    let message: SmsMessage

    var body: some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: 40) }
            Text(message.body)
                .padding(10)
                .background(
                    message.isOutgoing ? Color.accentColor : Color(nsColor: .controlBackgroundColor),
                    in: RoundedRectangle(cornerRadius: 14)
                )
                .foregroundStyle(message.isOutgoing ? .white : .primary)
            if !message.isOutgoing { Spacer(minLength: 40) }
        }
    }
}
