import SwiftUI

struct MessagesView: View {
    var store: MessageStore
    var onSend: (String, String) -> Void

    @State private var selectedThreadId: String?

    var body: some View {
        HStack(spacing: 0) {
            threadList
                .frame(width: 260)
            Divider()
            if let thread = selectedThread {
                ConversationView(thread: thread, onSend: { body in onSend(thread.address, body) })
            } else {
                Text("Select a conversation")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task {
            if selectedThreadId == nil {
                selectedThreadId = store.threads.first?.threadId
            }
        }
    }

    private var selectedThread: SmsThread? {
        store.threads.first { $0.threadId == selectedThreadId }
    }

    private var threadList: some View {
        Group {
            if store.threads.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "message")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                    Text("No messages yet")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(store.threads, selection: $selectedThreadId) { thread in
                    ThreadRowView(thread: thread)
                        .tag(thread.threadId)
                }
            }
        }
    }
}

private struct ThreadRowView: View {
    let thread: SmsThread

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(thread.contactName ?? thread.address).font(.body.bold())
            if let last = thread.messages.last {
                Text(last.body).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct ConversationView: View {
    let thread: SmsThread
    var onSend: (String) -> Void

    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
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
