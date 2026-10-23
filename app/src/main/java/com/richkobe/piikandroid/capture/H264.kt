package com.richkobe.piikandroid.capture

/**
 * H.264 helpers. Piik only accepts constrained-baseline streams whose
 * profile-level-id is 42c0xx with a level from a fixed list
 * (nativehost.validH264ProfileLevelID). Android hardware encoders often
 * report other constraint flags (42 80 / 42 e0) or a level above 5.1 even for
 * baseline output, so we normalize those three SPS bytes.
 */
object H264 {
    /** Levels Piik accepts, with H.264 Table A-1 limits: (level_idc, MaxMBPS, MaxFS). */
    private val LEVELS = arrayOf(
        intArrayOf(0x1e, 40_500, 1_620),
        intArrayOf(0x1f, 108_000, 3_600),
        intArrayOf(0x20, 216_000, 5_120),
        intArrayOf(0x28, 245_760, 8_192),
        intArrayOf(0x29, 245_760, 8_192),
        intArrayOf(0x2a, 522_240, 8_704),
        intArrayOf(0x32, 589_824, 22_080),
        intArrayOf(0x33, 983_040, 36_864),
    )

    /** Smallest accepted level that fits width×height@fps (never below the encoder's own). */
    fun levelFor(width: Int, height: Int, fps: Int, encoderLevel: Int): Int {
        val mbs = ((width + 15) / 16) * ((height + 15) / 16)
        val mbps = mbs * fps
        for (l in LEVELS) {
            if (l[0] >= encoderLevel && l[2] >= mbs && l[1] >= mbps) return l[0]
        }
        // The encoder claimed a level above 5.1; anything Piik allows that fits is valid.
        for (l in LEVELS) if (l[2] >= mbs && l[1] >= mbps) return l[0]
        return LEVELS.last()[0]
    }

    class Nal(val offset: Int, val length: Int, val type: Int)

    /** Splits an Annex-B buffer into NAL units (offsets exclude start codes). */
    fun nals(data: ByteArray, start: Int = 0, end: Int = data.size): List<Nal> {
        val out = ArrayList<Nal>(4)
        var i = start
        var nalStart = -1
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val three = data[i + 2].toInt() == 1
                val four = !three && i + 3 < end && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
                if (three || four) {
                    if (nalStart >= 0) out.add(Nal(nalStart, i - nalStart, data[nalStart].toInt() and 0x1f))
                    i += if (three) 3 else 4
                    nalStart = i
                    continue
                }
            }
            i++
        }
        if (nalStart in 0 until end) out.add(Nal(nalStart, end - nalStart, data[nalStart].toInt() and 0x1f))
        // Trailing zero bytes belong to the next start code, not the NAL.
        return out.map { n ->
            var len = n.length
            while (len > 1 && data[n.offset + len - 1].toInt() == 0) len--
            Nal(n.offset, len, n.type)
        }
    }

    class Summary(val sps: Boolean, val pps: Boolean, val idr: Boolean, val profileLevelId: String?)

    fun summarize(data: ByteArray, length: Int = data.size): Summary {
        var sps = false
        var pps = false
        var idr = false
        var pli: String? = null
        for (n in nals(data, 0, length)) {
            when (n.type) {
                7 -> {
                    sps = true
                    if (n.length > 3) {
                        pli = String.format(
                            "%02x%02x%02x",
                            data[n.offset + 1].toInt() and 0xff,
                            data[n.offset + 2].toInt() and 0xff,
                            data[n.offset + 3].toInt() and 0xff,
                        )
                    }
                }
                8 -> pps = true
                5 -> idr = true
            }
        }
        return Summary(sps, pps, idr, pli)
    }

    /**
     * Rewrites every SPS in [data] to constrained baseline (42 c0) at [level].
     * These are fixed-length fields before any Exp-Golomb data, so nothing
     * else in the NAL moves. Only valid for baseline-compatible streams, which
     * is what we configure the encoders to produce.
     */
    fun normalizeSps(data: ByteArray, length: Int, level: Int) {
        for (n in nals(data, 0, length)) {
            if (n.type == 7 && n.length > 3) {
                data[n.offset + 1] = 0x42
                data[n.offset + 2] = 0xc0.toByte()
                data[n.offset + 3] = level.toByte()
            }
        }
    }

    fun spsLevel(data: ByteArray, length: Int = data.size): Int {
        for (n in nals(data, 0, length)) if (n.type == 7 && n.length > 3) return data[n.offset + 3].toInt() and 0xff
        return 0
    }

    /** Extracts SPS and PPS (with start codes) for decoder csd-0 / csd-1. */
    fun parameterSets(data: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (n in nals(data)) {
            val withCode = byteArrayOf(0, 0, 0, 1) + data.copyOfRange(n.offset, n.offset + n.length)
            if (n.type == 7 && sps == null) sps = withCode
            if (n.type == 8 && pps == null) pps = withCode
        }
        return if (sps != null && pps != null) sps to pps else null
    }
}
