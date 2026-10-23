package com.richkobe.piikandroid.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.Handler
import android.util.Log
import android.view.Surface

/** Hardware H.264 encoders on this device, in Piik's "mft index" order. */
object Codecs {
    class Encoder(val name: String, val hardware: Boolean)

    val avcEncoders: List<Encoder> by lazy {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && !info.isAlias &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        val hw = all.filter { it.isHardwareAccelerated && !it.isSoftwareOnly }.map { Encoder(it.name, true) }
        // Emulators and a few budget devices only have the software encoder.
        hw.ifEmpty { all.map { Encoder(it.name, false) } }
    }

    fun info(name: String): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }

    fun supports(name: String, width: Int, height: Int, fps: Int): Boolean {
        val caps = info(name)?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities ?: return false
        return try {
            caps.areSizeAndRateSupported(width, height, fps.toDouble()) || caps.isSizeSupported(width, height)
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * One MediaCodec H.264 encoder fed from an input Surface. Output access units
 * are Annex-B, carry SPS/PPS on every key frame, and have their SPS normalized
 * to the constrained-baseline profile-level-id Piik accepts.
 */
class VideoEncoder(
    codecName: String,
    val layer: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    bitrate: Int,
    private val onFrame: (encoder: VideoEncoder, data: ByteArray, keyFrame: Boolean, ptsUs: Long) -> Unit,
    private val onError: (encoder: VideoEncoder, message: String) -> Unit,
) {
    private val thread = HandlerThread("piik-enc-$layer").apply { start() }
    private val codec: MediaCodec = MediaCodec.createByCodecName(codecName)
    val inputSurface: Surface
    private var config: ByteArray? = null
    @Volatile private var level = 0
    @Volatile var released = false
        private set

    init {
        val info = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val constrained = info.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline }
        val cbr = info.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

        fun format(full: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 2f) // Linux sidecar: key-int-max = 2 s
            setInteger(
                MediaFormat.KEY_PROFILE,
                if (constrained) MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
                else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
            if (full) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
                setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some vendor encoders reject optional low-latency keys; retry with the basics.
            Log.w(TAG, "encoder $codecName rejected low-latency format, retrying", e)
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            onConfig(bytes)
                        } else {
                            onAccessUnit(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    if (!released) onError(this@VideoEncoder, "encoder output failed: ${e.message}")
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (!released) onError(this@VideoEncoder, "hardware encoder error: ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val sps = format.getByteBuffer("csd-0")
                val pps = format.getByteBuffer("csd-1")
                if (sps != null && pps != null && config == null) {
                    val a = ByteArray(sps.remaining()).also { sps.duplicate().get(it) }
                    val b = ByteArray(pps.remaining()).also { pps.duplicate().get(it) }
                    onConfig(a + b)
                }
            }
        }, Handler(thread.looper))
        codec.start()
    }

    private fun onConfig(bytes: ByteArray) {
        val ownLevel = H264.spsLevel(bytes)
        level = H264.levelFor(width, height, fps, ownLevel)
        H264.normalizeSps(bytes, bytes.size, level)
        config = bytes
    }

    private fun onAccessUnit(bytes: ByteArray, flaggedKey: Boolean, ptsUs: Long) {
        var data = bytes
        val summary = H264.summarize(data)
        val key = flaggedKey || summary.idr
        if (key && !summary.sps) {
            val c = config ?: return // never send a key frame viewers cannot decode
            data = c + data
        }
        if (summary.sps && level == 0) {
            level = H264.levelFor(width, height, fps, H264.spsLevel(data))
        }
        H264.normalizeSps(data, data.size, if (level != 0) level else 0x33)
        onFrame(this, data, key, ptsUs)
    }

    fun requestKeyFrame() {
        if (released) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: IllegalStateException) {
        }
    }

    fun setBitrate(bitsPerSecond: Int): Boolean {
        if (released) return false
        return try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond) })
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { inputSurface.release() } catch (_: Throwable) {}
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "PiikEncoder"
    }
}
