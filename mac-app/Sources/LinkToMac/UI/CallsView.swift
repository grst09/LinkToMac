import SwiftUI

struct CallsView: View {
    var store: CallLogStore

    var body: some View {
        if store.calls.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "phone")
                    .font(.system(size: 32))
                    .foregroundStyle(.secondary)
                Text("No call history yet")
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List(store.calls) { call in
                CallRowView(call: call)
            }
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
