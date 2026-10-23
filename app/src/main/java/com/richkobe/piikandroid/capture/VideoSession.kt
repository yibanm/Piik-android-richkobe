package com.richkobe.piikandroid.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `--capture-video` and `--encoded-video`: the Android counterpart of
 * native/capture/linux/main.c capture_video().
 *
 * Screen (or decoded input) → GlSource → one hardware encoder per enabled
 * output layer → SMED H.264 frames on stdout. Control commands on stdin:
 * `K <layer|-1>` key frame, `A <layer> <0|1>` (de)activate,
 * `B <layer> <bps>` live bitrate, `Q` stop.
 */
class VideoSession(private val context: Context, private val ch: ShimChannel) : GlSource.Listener {
    private class Output(val layer: Int, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
        @Volatile var enabled = false
        @Volatile var failed = false
        @Volatile var decodable = false
        @Volatile var keyPending = true
        @Volatile var encoder: VideoEncoder? = null
        @Volatile var sink: GlSource.Sink? = null
        var lastDrawNs = 0L
    }

    private val encodedMode = ch.args.firstOrNull() == "--encoded-video"
    private val outputs = ArrayList<Output>()
    private var original = 0
    private var encoderIndex = 0
    private var profileFps = 30
    private lateinit var codecName: String

    private var source: GlSource? = null
    private var usingScreen = false
    @Volatile private var failure: String? = null
    private val stopping = AtomicBoolean(false)
    @Volatile private var activeSent = false
    private val frameDuration100ns get() = 10_000_000L / profileFps

    // --encoded-video
    private var decoder: MediaCodec? = null
    private var decoderThread: Thread? = null
    private val ptsMap = object : LinkedHashMap<Long, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 120
    }

    private val projectionStopped: () -> Unit = { fail("screen sharing was stopped") }

    override val maxFps: Int get() = profileFps

    fun run(): Int {
        try {
            parse()
        } catch (e: Exception) {
            ch.stderr("Piik capture unavailable: ${e.message ?: "invalid arguments"}")
            return 2
        }
        val encoders = Codecs.avcEncoders
        if (encoders.isEmpty()) {
            ch.stderr("Piik capture unavailable: no H.264 encoder on this device")
            return 2
        }
        codecName = encoders[encoderIndex.coerceIn(0, encoders.size - 1)].name
        try {
            if (encodedMode) {
                source = GlSource("piik-encoded", tickFps = 0)
            } else {
                // The system screen-capture dialog is the picker; wait for the user.
                ProjectionHolder.acquire(context, 180_000)
                val screen = ProjectionHolder.screen(context)
                ProjectionHolder.retain()
                usingScreen = true
                ProjectionHolder.addStopListener(projectionStopped)
                source = screen.gl
            }
            ch.status(startingJson())
            for (o in outputs) if (o.enabled) {
                o.enabled = false
                setActive(o, true)
            }
            if (outputs[original].failed && !encodedMode) {
                throw IllegalStateException("the hardware encoder could not start at ${outputs[original].width}x${outputs[original].height}")
            }
            source!!.addListener(this)
            controlLoop()
        } catch (e: Exception) {
            if (failure == null && !stopping.get()) failure = e.message ?: e.toString()
        } finally {
            cleanup()
        }
        failure?.let {
            ch.stderr("Piik capture unavailable: $it")
            return 2
        }
        return 0
    }

    private fun parse() {
        val parsed = CaptureProtocol.parseVideoArgs(ch.args)
        encoderIndex = parsed.encoderIndex
        profileFps = parsed.fps
        original = parsed.original
        parsed.outputs.forEachIndexed { i, p ->
            outputs.add(Output(i, p.width, p.height, p.fps, p.bitrate).also { it.enabled = parsed.initiallyEnabled(i) })
        }
    }

    private fun profiles() = outputs.map { CaptureProtocol.OutputProfile(it.width, it.height, it.fps, it.bitrate) }

    private fun startingJson() = CaptureProtocol.starting(encoderIndex, codecName, profiles())

    private fun activeJson(profileLevelId: String) =
        CaptureProtocol.active(profileLevelId, profiles()[original], profiles())

    // ---- outputs ----

    private fun setActive(o: Output, enable: Boolean) {
        if (enable == o.enabled) return
        if (!enable) {
            o.enabled = false
            o.decodable = false
            releaseOutput(o)
            return
        }
        if (o.failed) return
        try {
            if (!Codecs.supports(codecName, o.width, o.height, o.fps)) {
                Log.w(TAG, "$codecName may not support ${o.width}x${o.height}@${o.fps}; trying anyway")
            }
            val enc = VideoEncoder(codecName, o.layer, o.width, o.height, o.fps, o.bitrate, ::onEncoded, ::onEncoderError)
            o.encoder = enc
            o.sink = source!!.createSink(enc.inputSurface, o.width, o.height)
            o.decodable = false
            o.keyPending = true
            o.lastDrawNs = 0
            o.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "output ${o.layer} unavailable", e)
            markFailed(o, "hardware encoder configuration failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun releaseOutput(o: Output) {
        val sink = o.sink
        val enc = o.encoder
        o.sink = null
        o.encoder = null
        if (sink != null) try { source?.releaseSink(sink) } catch (_: Exception) {}
        enc?.release()
    }

    private fun markFailed(o: Output, message: String) {
        if (o.failed) return
        o.failed = true
        o.enabled = false
        releaseOutput(o)
        try {
            ch.frame(Smed.LAYER_UNAVAILABLE, layer = o.layer, payload = message.take(512).toByteArray())
        } catch (_: IOException) {
            fail("native media output closed")
        }
    }

    // ---- frames ----

    /** GL thread: a new input frame; emit Begin, then render into due outputs. */
    override fun onInputFrame(source: GlSource, timestampNs: Long) {
        if (failure != null || stopping.get()) return
        val ts100 = timestamp100(timestampNs / 1000)
        try {
            ch.frame(Smed.BEGIN, timestamp100ns = ts100, duration100ns = frameDuration100ns)
        } catch (_: IOException) {
            fail("native media output closed")
            return
        }
        for (o in outputs) {
            val sink = o.sink ?: continue
            if (!o.enabled) continue
            val interval = 1_000_000_000L / o.fps
            if (o.lastDrawNs != 0L && timestampNs - o.lastDrawNs < interval - interval / 10) continue
            o.lastDrawNs = timestampNs
            if (o.keyPending) {
                o.keyPending = false
                o.encoder?.requestKeyFrame()
            }
            source.draw(sink, timestampNs)
        }
    }

    /** Encoder thread: deliver an access unit if its layer can be decoded from it. */
    private fun onEncoded(enc: VideoEncoder, data: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        val o = outputs[enc.layer]
        if (!o.enabled || o.encoder !== enc || failure != null) return
        val s = H264.summarize(data)
        val recovery = keyFrame && s.sps && s.pps && s.idr && s.profileLevelId?.startsWith("42c0") == true
        if (recovery) o.decodable = true
        if (!o.decodable) return // viewers need a key frame first; one was requested on activation
        try {
            ch.frame(
                Smed.H264, keyFrame = recovery, layer = o.layer, width = o.width, height = o.height,
                timestamp100ns = timestamp100(ptsUs), duration100ns = 10_000_000L / o.fps, payload = data,
            )
            if (!activeSent && o.layer == original && s.profileLevelId != null) {
                activeSent = true
                ch.status(activeJson(s.profileLevelId))
            }
        } catch (_: IOException) {
            fail("native media output closed")
        }
    }

    private fun onEncoderError(enc: VideoEncoder, message: String) {
        val o = outputs[enc.layer]
        if (o.encoder === enc) markFailed(o, message)
    }

    private fun timestamp100(ptsUs: Long): Long =
        synchronized(ptsMap) { ptsMap[ptsUs] } ?: (ptsUs * 10)

    // ---- control ----

    private fun controlLoop() {
        val input = DataInputStream(ch.stdin)
        while (failure == null) {
            val frame = try {
                Smed.read(input)
            } catch (e: IOException) {
                if (failure != null) return
                null
            } ?: return // stdin closed: Piik is done with us
            when (frame.kind) {
                Smed.CONTROL -> if (!control(frame.text)) {
                    if (stopping.get()) return
                    fail("invalid or failed native input")
                    return
                }
                Smed.H264 -> if (!encodedMode || !decode(frame)) {
                    fail("invalid or failed native input")
                    return
                }
            }
        }
    }

    private fun control(line: String): Boolean {
        val v = line.trim().split(Regex("[ \t\r]+"))
        if (v.size == 1 && v[0] == "Q") {
            stopping.set(true)
            return false
        }
        fun layer(s: String) = s.toIntOrNull()?.takeIf { it in outputs.indices }
        return when {
            v.size == 2 && v[0] == "K" -> {
                val all = v[1] == "-1"
                val l = if (all) -1 else layer(v[1]) ?: return false
                for (o in outputs) if (all || o.layer == l) {
                    o.keyPending = true
                    o.encoder?.requestKeyFrame()
                }
                true
            }
            v.size == 3 && v[0] == "A" -> {
                val o = outputs[layer(v[1]) ?: return false]
                when (v[2]) {
                    "1" -> setActive(o, true)
                    "0" -> setActive(o, false)
                    else -> return false
                }
                true
            }
            v.size == 3 && v[0] == "B" -> {
                val o = outputs[layer(v[1]) ?: return false]
                val bps = v[2].toIntOrNull() ?: return false
                if (bps < 1_000 || bps > o.bitrate) return false
                if (!o.failed && o.encoder?.setBitrate(bps) == false) {
                    markFailed(o, "hardware encoder cannot apply the live bitrate")
                }
                true
            }
            else -> false
        }
    }

    // ---- --encoded-video input ----

    private fun decode(frame: Smed.InputFrame): Boolean {
        val src = source ?: return false
        val ptsUs = frame.timestamp100ns / 10
        synchronized(ptsMap) { ptsMap[ptsUs] = frame.timestamp100ns }
        var dec = decoder
        if (dec == null) {
            val params = H264.parameterSets(frame.data) ?: return false // must start with SPS/PPS/IDR
            src.setContentSize(frame.width, frame.height)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, frame.width, frame.height).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(params.first))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(params.second))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                dec.configure(format, src.inputSurface, null, 0)
            } catch (e: Exception) {
                format.removeKey(MediaFormat.KEY_LOW_LATENCY)
                dec.reset()
                dec.configure(format, src.inputSurface, null, 0)
            }
            dec.start()
            decoder = dec
            decoderThread = Thread({ drainDecoder(dec) }, "piik-decoder").apply { start() }
        } else if (frame.width != src.contentWidth || frame.height != src.contentHeight) {
            src.setContentSize(frame.width, frame.height)
        }
        val index = dec.dequeueInputBuffer(500_000)
        if (index < 0) return false
        val buffer = dec.getInputBuffer(index) ?: return false
        buffer.clear()
        if (buffer.capacity() < frame.data.size) return false
        buffer.put(frame.data)
        dec.queueInputBuffer(index, 0, frame.data.size, ptsUs, 0)
        return true
    }

    private fun drainDecoder(dec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!stopping.get() && failure == null) {
            val index = try {
                dec.dequeueOutputBuffer(info, 100_000)
            } catch (_: IllegalStateException) {
                return
            }
            if (index >= 0) {
                try { dec.releaseOutputBuffer(index, info.size > 0) } catch (_: IllegalStateException) { return }
            }
        }
    }

    // ---- lifecycle ----

    private fun fail(message: String) {
        if (failure == null && !stopping.get()) failure = message
        try { ch.stdin.close() } catch (_: Exception) {} // unblocks controlLoop
    }

    private fun cleanup() {
        stopping.set(true)
        val src = source
        try { src?.removeListener(this) } catch (_: Exception) {}
        for (o in outputs) {
            o.enabled = false
            releaseOutput(o)
        }
        decoder?.let {
            try { it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        decoderThread?.join(500)
        if (usingScreen) {
            ProjectionHolder.removeStopListener(projectionStopped)
            ProjectionHolder.release()
        } else {
            src?.release()
        }
    }

    companion object {
        private const val TAG = "PiikVideo"
    }
}
