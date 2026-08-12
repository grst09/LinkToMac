import SwiftUI
import AppKit

/// Draggable divider between a list column and a detail panel. SwiftUI has no lightweight
/// resizable-pane primitive outside NavigationSplitView (which would nest awkwardly inside
/// MainWindowView's own split view), so this tracks the drag manually against the width at
/// gesture-start rather than accumulating `translation` directly, which would otherwise compound
/// across repeated drags.
struct ResizableDivider: View {
    @Binding var width: CGFloat
    let minWidth: CGFloat
    let maxWidth: CGFloat
    @State private var widthAtDragStart: CGFloat?

    var body: some View {
        Divider()
            .frame(width: 6)
            .contentShape(Rectangle())
            .onHover { hovering in
                if hovering {
                    NSCursor.resizeLeftRight.push()
                } else {
                    NSCursor.pop()
                }
            }
            .gesture(
                DragGesture()
                    .onChanged { value in
                        let base = widthAtDragStart ?? width
                        if widthAtDragStart == nil { widthAtDragStart = base }
                        width = min(max(base + value.translation.width, minWidth), maxWidth)
                    }
                    .onEnded { _ in widthAtDragStart = nil }
            )
    }
}
