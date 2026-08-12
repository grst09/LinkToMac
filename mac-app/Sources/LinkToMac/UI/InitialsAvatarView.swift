import SwiftUI

/// Colored-circle initials, used anywhere a contact/number needs an avatar but there's no
/// photo in the wire protocol — Contacts and Messages rows/headers both use this.
struct InitialsAvatarView: View {
    let name: String
    var diameter: CGFloat = 36

    var body: some View {
        ZStack {
            Circle().fill(Self.color(for: name).gradient)
            Text(initials)
                .font(.system(size: diameter * 0.4, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: diameter, height: diameter)
    }

    private var initials: String {
        let letters = name.split(separator: " ").prefix(2).compactMap { $0.first }
        let result = String(letters).uppercased()
        return result.isEmpty ? "?" : result
    }

    static func color(for name: String) -> Color {
        let palette: [Color] = [.purple, .orange, .teal, .green, .red, .blue, .pink, .indigo, .brown]
        return palette[abs(name.hashValue) % palette.count]
    }
}
