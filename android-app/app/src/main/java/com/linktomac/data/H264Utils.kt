package com.linktomac.data

/** Small helpers for the one-time parse of MediaCodec's codec-config buffer into separate
 *  SPS/PPS NAL units — see docs/PROTOCOL.md's `mirror.config` message. Regular frame buffers
 *  are forwarded to the Mac untouched (still Annex-B), no parsing needed for those. */
object H264Utils {

    /** Splits an Annex-B byte stream (0x000001 or 0x00000001-prefixed) into individual NAL
     *  units, start codes stripped. */
    fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        var start = -1
        while (i < data.size - 3) {
            val isStart4 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isStart3 = !isStart4 && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (isStart4 || isStart3) {
                if (start >= 0) nals.add(data.copyOfRange(start, i))
                val codeLength = if (isStart4) 4 else 3
                start = i + codeLength
                i += codeLength
            } else {
                i++
            }
        }
        if (start in 0 until data.size) nals.add(data.copyOfRange(start, data.size))
        return nals
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** Scales down to a max longer-edge dimension (keeps encode/bandwidth cost reasonable —
     *  native resolution on a modern phone is overkill for a mirrored control view) while
     *  keeping both dimensions even, which H.264 encoders require. */
    fun scaledDimensions(nativeWidth: Int, nativeHeight: Int, maxLongEdge: Int = 1280): Pair<Int, Int> {
        val longEdge = maxOf(nativeWidth, nativeHeight)
        val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1f
        var width = (nativeWidth * scale).toInt()
        var height = (nativeHeight * scale).toInt()
        if (width % 2 != 0) width -= 1
        if (height % 2 != 0) height -= 1
        return width to height
    }
}
