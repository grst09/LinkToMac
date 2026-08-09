import Foundation
import Observation
import CoreVideo

/// Holds screen-mirroring session state and owns the `VideoDecoder`.
///
/// Decoded frames deliberately do *not* flow through an `@Observable` property: pushing a new
/// `CVPixelBuffer` through observable state 30 times a second would mean a full SwiftUI view
/// diff on every frame for no benefit. Instead `frameSink` is a plain callback that
/// `ScreenMirrorView` sets while it's visible and clears when it isn't — frames go straight to
/// the display layer. `isActive`/`config`/`stoppedReason` are the small, infrequently-changing
/// bits SwiftUI actually needs to react to.
@Observable
final class MirrorStore {
    private(set) var config: MirrorConfigPayload?
    private(set) var isActive = false
    private(set) var stoppedReason: String?

    let decoder = VideoDecoder()

    @ObservationIgnored
    var frameSink: ((CVPixelBuffer) -> Void)?

    init() {
        decoder.onDecodedFrame = { [weak self] pixelBuffer in
            self?.frameSink?(pixelBuffer)
        }
    }

    func updateConfig(_ payload: MirrorConfigPayload) {
        guard let spsData = Data(base64Encoded: payload.spsBase64),
              let ppsData = Data(base64Encoded: payload.ppsBase64) else { return }
        decoder.configure(spsData: spsData, ppsData: ppsData)
        config = payload
        isActive = true
        stoppedReason = nil
    }

    func handleFrame(_ data: Data) {
        decoder.decode(frameData: data)
    }

    func handleStopped(reason: String) {
        isActive = false
        stoppedReason = reason
        config = nil
    }
}
