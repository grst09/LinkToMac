import SwiftUI
import AVFoundation
import AppKit

struct ScreenMirrorView: View {
    var server: ConnectionServer
    @State private var textInput = ""

    var body: some View {
        VStack(spacing: 0) {
            if server.mirrorStore.isActive, let config = server.mirrorStore.config {
                MirrorDisplayView(mirrorStore: server.mirrorStore, server: server)
                    .aspectRatio(CGFloat(config.width) / CGFloat(config.height), contentMode: .fit)
                    .background(Color.black)

                HStack(spacing: 12) {
                    Button { server.sendMirrorKey(action: "back") } label: {
                        Image(systemName: "chevron.left")
                    }
                    .help("Back")
                    Button { server.sendMirrorKey(action: "home") } label: {
                        Image(systemName: "circle")
                    }
                    .help("Home")
                    Button { server.sendMirrorKey(action: "recents") } label: {
                        Image(systemName: "square.on.square")
                    }
                    .help("Recents")

                    Spacer()

                    TextField("Type to send…", text: $textInput)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 220)
                        .onSubmit {
                            guard !textInput.isEmpty else { return }
                            server.sendMirrorTextInput(text: textInput)
                            textInput = ""
                        }

                    Button("Stop Mirroring") { server.stopMirroring() }
                }
                .padding(8)
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "rectangle.on.rectangle")
                        .font(.system(size: 40))
                        .foregroundStyle(.secondary)
                    Text(stoppedReasonMessage)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 320)
                    Button("Start Mirroring") { server.startMirroring() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private var stoppedReasonMessage: String {
        switch server.mirrorStore.stoppedReason {
        case "permission_denied":
            return "Screen mirroring permission was denied on your phone. Tap Start Mirroring to try again — you'll need to accept the prompt on your phone this time."
        case "error":
            return "Screen mirroring stopped unexpectedly. Tap Start Mirroring to try again."
        default:
            return "Mirror your phone's screen here. Starting this will show a permission prompt on your phone — that's an Android requirement, not skippable."
        }
    }
}

/// Bridges AppKit's `AVSampleBufferDisplayLayer` (no SwiftUI-native equivalent for
/// decoded-video rendering) and forwards mouse taps/drags as normalized tap/swipe messages.
private struct MirrorDisplayView: NSViewRepresentable {
    var mirrorStore: MirrorStore
    var server: ConnectionServer

    func makeNSView(context: Context) -> MirrorNSView {
        let view = MirrorNSView()
        view.server = server
        mirrorStore.frameSink = { [weak view] pixelBuffer in
            DispatchQueue.main.async {
                view?.enqueue(pixelBuffer)
            }
        }
        return view
    }

    func updateNSView(_ nsView: MirrorNSView, context: Context) {
        nsView.server = server
    }
}

private final class MirrorNSView: NSView {
    var server: ConnectionServer?
    private let displayLayer = AVSampleBufferDisplayLayer()
    private var dragStart: NSPoint?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer = displayLayer
        displayLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layout() {
        super.layout()
        displayLayer.frame = bounds
    }

    func enqueue(_ pixelBuffer: CVPixelBuffer) {
        var formatDescription: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        )
        guard let formatDescription else { return }

        var timingInfo = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        let status = CMSampleBufferCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: formatDescription,
            sampleTiming: &timingInfo,
            sampleBufferOut: &sampleBuffer
        )
        guard status == noErr, let sampleBuffer else { return }

        if displayLayer.isReadyForMoreMediaData {
            displayLayer.enqueue(sampleBuffer)
        }
    }

    override func mouseDown(with event: NSEvent) {
        dragStart = convert(event.locationInWindow, from: nil)
        print("LinkToMac: mouseDown at \(dragStart!), server=\(server != nil)")
        fflush(stdout)
    }

    override func mouseUp(with event: NSEvent) {
        guard let start = dragStart else {
            print("LinkToMac: mouseUp with no dragStart")
            fflush(stdout)
            return
        }
        let end = convert(event.locationInWindow, from: nil)
        let distance = hypot(end.x - start.x, end.y - start.y)
        if distance < 4 {
            let n = normalize(start)
            print("LinkToMac: sending tap x=\(n.x) y=\(n.y)")
            fflush(stdout)
            server?.sendMirrorTap(x: n.x, y: n.y)
        } else {
            let n1 = normalize(start)
            let n2 = normalize(end)
            print("LinkToMac: sending swipe from \(n1) to \(n2)")
            fflush(stdout)
            server?.sendMirrorSwipe(startX: n1.x, startY: n1.y, endX: n2.x, endY: n2.y, durationMs: 300)
        }
        dragStart = nil
    }

    /// AppKit's Y axis is bottom-up; the phone's (and the wire protocol's) is top-down.
    private func normalize(_ point: NSPoint) -> (x: Double, y: Double) {
        let x = Double(point.x / max(bounds.width, 1)).clamped(to: 0...1)
        let y = Double(1 - point.y / max(bounds.height, 1)).clamped(to: 0...1)
        return (x, y)
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
