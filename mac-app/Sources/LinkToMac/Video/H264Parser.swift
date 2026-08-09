import Foundation

/// Converts Annex-B H.264 NAL units (start-code delimited — what MediaCodec's encoder output
/// and the wire format use) into the length-prefixed AVCC format VideoToolbox's CMBlockBuffer
/// expects. Mirrors the Android-side H264Utils.kt used to build `mirror.config`.
enum H264Parser {
    /// Splits an Annex-B byte stream into individual NAL units, start codes stripped.
    static func splitAnnexBNalUnits(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var nals: [Data] = []
        var i = 0
        var start = -1
        while i < bytes.count - 3 {
            let isStart4 = bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 0 && bytes[i + 3] == 1
            let isStart3 = !isStart4 && bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1
            if isStart4 || isStart3 {
                if start >= 0 {
                    nals.append(Data(bytes[start..<i]))
                }
                let codeLength = isStart4 ? 4 : 3
                start = i + codeLength
                i += codeLength
            } else {
                i += 1
            }
        }
        if start >= 0 && start < bytes.count {
            nals.append(Data(bytes[start...]))
        }
        return nals
    }

    static func nalType(_ nal: Data) -> UInt8 {
        guard let first = nal.first else { return 0xFF }
        return first & 0x1F
    }

    /// Concatenates NAL units (start codes already stripped) into AVCC form: a 4-byte
    /// big-endian length prefix before each unit's payload.
    static func avccData(fromNalUnits nals: [Data]) -> Data {
        var result = Data()
        for nal in nals {
            var length = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &length) { result.append(contentsOf: $0) }
            result.append(nal)
        }
        return result
    }
}
