import SwiftUI

/// Card-style header shown once at the top of a section (icon, title, item-count subtitle).
/// The window's native title bar is left generic ("LinkToMac") so this card is the only place
/// the section name and count actually appear — see MainWindowView.
struct SectionHeaderView: View {
    var icon: String
    var iconColor: Color = .accentColor
    var title: String
    var subtitle: String

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(iconColor.gradient)
                Image(systemName: icon)
                    .foregroundStyle(.white)
                    .font(.system(size: 16, weight: .medium))
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(12)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 4)
    }
}
