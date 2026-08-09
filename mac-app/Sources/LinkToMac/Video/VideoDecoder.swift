import Foundation
import VideoToolbox
import CoreMedia

/// Wraps `VTDecompressionSession`: takes SPS/PPS (from `mirror.config`) to build a
/// `CMFormatDescription`, then decodes each Annex-B H.264 frame from the wire into a
/// `CVPixelBuffer` delivered via `onDecodedFrame`. Call `decode` off the main thread — video
/// arrives at up to 30fps and decoding shouldn't compete with UI work.
final class VideoDecoder {
    private var formatDescription: CMFormatDescription?
    private var session: VTDecompressionSession?

    var onDecodedFrame: ((CVPixelBuffer) -> Void)?

    func configure(spsData: Data, ppsData: Data) {
        invalidateSession()

        let spsBytes = [UInt8](spsData)
        let ppsBytes = [UInt8](ppsData)

        var formatDesc: CMFormatDescription?
        let status = spsBytes.withUnsafeBufferPointer { spsPtr -> OSStatus in
            ppsBytes.withUnsafeBufferPointer { ppsPtr -> OSStatus in
                let pointers: [UnsafePointer<UInt8>] = [spsPtr.baseAddress!, ppsPtr.baseAddress!]
                let sizes: [Int] = [spsPtr.count, ppsPtr.count]
                return pointers.withUnsafeBufferPointer { pointersBuffer -> OSStatus in
                    sizes.withUnsafeBufferPointer { sizesBuffer -> OSStatus in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers: pointersBuffer.baseAddress!,
                            parameterSetSizes: sizesBuffer.baseAddress!,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut: &formatDesc
                        )
                    }
                }
            }
        }
        guard status == noErr, let formatDesc else {
            print("LinkToMac: failed to create H.264 format description: \(status)")
            return
        }
        formatDescription = formatDesc
        createSession(formatDescription: formatDesc)
    }

    private func createSession(formatDescription: CMFormatDescription) {
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: decompressionOutputCallback,
            decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque()
        )
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ]
        var newSession: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: formatDescription,
            decoderSpecification: nil,
            imageBufferAttributes: attributes as CFDictionary,
            outputCallback: &callback,
            decompressionSessionOut: &newSession
        )
        guard status == noErr, let newSession else {
            print("LinkToMac: failed to create decompression session: \(status)")
            return
        }
        session = newSession
    }

    private func invalidateSession() {
        if let session {
            VTDecompressionSessionInvalidate(session)
        }
        session = nil
    }

    /// `frameData` is a raw regular-frame buffer straight off the wire: Annex-B, may or may not
    /// have SPS/PPS repeated inline (some encoders do this before keyframes) — those are
    /// filtered out since the session already has them from `configure`.
    func decode(frameData: Data) {
        guard let formatDescription, let session else { return }

        let nals = H264Parser.splitAnnexBNalUnits(frameData).filter {
            let type = H264Parser.nalType($0)
            return type != 7 && type != 8
        }
        guard !nals.isEmpty else { return }
        let avcc = H264Parser.avccData(fromNalUnits: nals)

        var blockBuffer: CMBlockBuffer?
        let createStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard createStatus == kCMBlockBufferNoErr, let blockBuffer else { return }

        let replaceStatus = avcc.withUnsafeBytes { rawBuffer -> OSStatus in
            CMBlockBufferReplaceDataBytes(
                with: rawBuffer.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: avcc.count
            )
        }
        guard replaceStatus == kCMBlockBufferNoErr else { return }

        var sampleBuffer: CMSampleBuffer?
        var sampleSizeArray = [avcc.count]
        let sbStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 0,
            sampleTimingArray: nil,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSizeArray,
            sampleBufferOut: &sampleBuffer
        )
        guard sbStatus == noErr, let sampleBuffer else {
            print("LinkToMac: failed to create sample buffer: \(sbStatus)")
            return
        }

        var flagOut = VTDecodeInfoFlags()
        VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sampleBuffer,
            flags: [],
            frameRefcon: nil,
            infoFlagsOut: &flagOut
        )
    }
}

private func decompressionOutputCallback(
    decompressionOutputRefCon: UnsafeMutableRawPointer?,
    sourceFrameRefCon: UnsafeMutableRawPointer?,
    status: OSStatus,
    infoFlags: VTDecodeInfoFlags,
    imageBuffer: CVImageBuffer?,
    presentationTimeStamp: CMTime,
    presentationDuration: CMTime
) {
    guard status == noErr, let imageBuffer, let refCon = decompressionOutputRefCon else { return }
    let decoder = Unmanaged<VideoDecoder>.fromOpaque(refCon).takeUnretainedValue()
    decoder.onDecodedFrame?(imageBuffer)
}
