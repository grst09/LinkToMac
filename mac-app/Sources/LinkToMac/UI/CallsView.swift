import SwiftUI

struct CallsView: View {
    var store: CallLogStore

    @State private var searchText = ""

    var body: some View {
        VStack(spacing: 0) {
            SectionHeaderView(
                icon: "phone.fill",
                iconColor: .green,
                title: "Calls",
                subtitle: "\(store.calls.count) call\(store.calls.count == 1 ? "" : "s")"
            )
            SearchBarView(text: $searchText, prompt: "Search calls")
            if store.calls.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "phone")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                    Text("No call history yet")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredCalls.isEmpty {
                Text("No matching calls")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(filteredCalls) { call in
                    CallRowView(call: call)
                }
            }
        }
    }

    private var filteredCalls: [CallLogEntry] {
        guard !searchText.isEmpty else { return store.calls }
        return store.calls.filter { call in
            (call.contactName ?? "").localizedCaseInsensitiveContains(searchText)
                || call.number.localizedCaseInsensitiveContains(searchText)
        }
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
        .padding(.vertical, 8)
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
