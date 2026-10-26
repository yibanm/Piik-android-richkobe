package com.richkobe.piikandroid.capture

import com.richkobe.piikandroid.HostState
import com.richkobe.piikandroid.service
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.DataInputStream
import java.io.IOException

/**
 * `--capture-audio` (what the phone is playing, via the screen-capture
 * consent) and `--capture-microphone [--device id]`. Output matches the Linux
 * sidecar: one {"state":"active","audio":true} status, then 20 ms PCM frames
 * of 48 kHz stereo S16LE with 100 ns timestamps.
 */
class AudioSession(private val context: Context, private val ch: ShimChannel) {
    @Volatile private var running = true

    fun run(): Int {
        val microphone = ch.args.firstOrNull() == "--capture-microphone"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ch.stderr("Piik capture unavailable: allow Piik to record audio in Android settings")
            return 2
        }
        if (!microphone) {
            val a = ch.args
            if (a.size != 4 || (a[1] != "picker" && a[1] != "display")) {
                ch.stderr("Piik capture unavailable: unsupported audio source")
                return 2
            }
        }

        // Q or end of stdin stops the capture (also while waiting for consent).
        val control = Thread({
            val input = DataInputStream(ch.stdin)
            try {
                while (running) {
                    val f = Smed.read(input) ?: break
                    if (f.kind == Smed.CONTROL && f.text.trim() == "Q") break
                }
            } catch (_: Exception) {
            }
            running = false
        }, "piik-audio-control").apply { isDaemon = true; start() }

        var timestamp = 0L
        var active = false
        val silence = ByteArray(FRAME_BYTES)
        var record: AudioRecord? = null
        var holdsProjection = false
        val stopOnProjectionEnd: () -> Unit = { running = false }
        var code = 0
        try {
            if (microphone) {
                record = microphoneRecord()
                deviceArgument()?.let { id ->
                    context.service<AudioManager>()
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id.toString() == id }
                        ?.let { record.setPreferredDevice(it) }
                }
            } else {
                // Piik gives audio 3 s to become active, but on the first share
                // Android's consent dialog is still open. Report active at once
                // and send silence until the user answers.
                ch.status(CaptureProtocol.AUDIO_ACTIVE)
                active = true
                timestamp = System.nanoTime() / 100
                val waiting = java.util.concurrent.atomic.AtomicBoolean(true)
                val clock = java.util.concurrent.atomic.AtomicLong(timestamp)
                val filler = Thread({
                    var next = System.nanoTime()
                    try {
                        while (waiting.get() && running) {
                            ch.frame(Smed.PCM, timestamp100ns = clock.getAndAdd(FRAME_100NS), duration100ns = FRAME_100NS, payload = silence)
                            next += 20_000_000L
                            val sleep = (next - System.nanoTime()) / 1_000_000L
                            if (sleep > 0) Thread.sleep(sleep)
                        }
                    } catch (_: Exception) {
                        running = false
                    }
                }, "piik-audio-silence").apply { start() }
                val projection = try {
                    ProjectionHolder.acquire(context, 180_000)
                } finally {
                    waiting.set(false)
                    filler.join()
                    timestamp = clock.get()
                }
                ProjectionHolder.retain()
                holdsProjection = true
                ProjectionHolder.addStopListener(stopOnProjectionEnd)
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                record = AudioRecord.Builder()
                    .setAudioFormat(STEREO)
                    .setBufferSizeInBytes(bufferSize(AudioFormat.CHANNEL_IN_STEREO))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("audio input is unavailable")
            record.startRecording()
            val mono = record.channelCount == 1
            val pcm = ByteArray(FRAME_BYTES)
            val readBuf = ByteArray(if (mono) FRAME_BYTES / 2 else FRAME_BYTES)
            while (running) {
                var filled = 0
                while (filled < readBuf.size && running) {
                    val n = record.read(readBuf, filled, readBuf.size - filled)
                    if (n < 0) throw IllegalStateException("audio input stopped ($n)")
                    filled += n
                }
                if (!running) break
                if (!active) {
                    ch.status(CaptureProtocol.AUDIO_ACTIVE)
                    active = true
                    timestamp = System.nanoTime() / 100
                }
                // 壳层「系统声音」静音：只静音屏幕共享音频，麦克风与房间语音不受影响。
                // 照常读 AudioRecord（保持采集管线运转），但把发出去的帧换成静音。
                val muted = !microphone && HostState.systemAudioMuted
                if (mono) {
                    // Duplicate each 16-bit sample into left and right.
                    var o = 0
                    var i = 0
                    while (i < readBuf.size) {
                        pcm[o] = readBuf[i]; pcm[o + 1] = readBuf[i + 1]
                        pcm[o + 2] = readBuf[i]; pcm[o + 3] = readBuf[i + 1]
                        o += 4; i += 2
                    }
                    ch.frame(Smed.PCM, timestamp100ns = timestamp, duration100ns = FRAME_100NS, payload = if (muted) silence else pcm)
                } else {
                    ch.frame(Smed.PCM, timestamp100ns = timestamp, duration100ns = FRAME_100NS, payload = if (muted) silence else readBuf)
                }
                timestamp += FRAME_100NS
            }
        } catch (_: IOException) {
            // Piik closed the stream.
        } catch (e: SecurityException) {
            ch.stderr("Piik capture unavailable: audio permission denied")
            code = 2
        } catch (e: Exception) {
            if (running) {
                ch.stderr("Piik capture unavailable: ${e.message ?: "audio capture failed"}")
                code = 2
            }
        } finally {
            running = false
            record?.let {
                try { it.stop() } catch (_: Throwable) {}
                it.release()
            }
            if (holdsProjection) {
                ProjectionHolder.removeStopListener(stopOnProjectionEnd)
                ProjectionHolder.release()
            }
            control.interrupt()
        }
        return code
    }

    private fun deviceArgument(): String? {
        val a = ch.args
        val i = a.indexOf("--device")
        return if (i >= 0 && i + 1 < a.size) a[i + 1].takeIf { it != "default" } else null
    }

    @Suppress("MissingPermission")
    private fun microphoneRecord(): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        // Voice communication enables the platform echo canceller, which
        // matters when the phone speaker is playing the stream.
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize(AudioFormat.CHANNEL_IN_MONO))
            .build()
    }

    companion object {
        const val RATE = 48_000
        const val FRAME_BYTES = 960 * 2 * 2 // 20 ms, stereo, 16-bit
        const val FRAME_100NS = 200_000L

        private val STEREO: AudioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        private fun bufferSize(mask: Int) =
            maxOf(AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT), FRAME_BYTES * 4)

        /** `--list-microphones`: [{"id":..,"label":..}] */
        fun listMicrophones(context: Context): String {
            val devices = context.service<AudioManager>().getDevices(AudioManager.GET_DEVICES_INPUTS)
            val items = mutableListOf("""{"id":"default","label":"Phone microphone"}""")
            for (d in devices) {
                val label = when (d.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> null // covered by "default"
                    else -> null
                } ?: continue
                val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
                items.add("""{"id":${jsonString(d.id.toString())},"label":${jsonString(if (name != null) "$label ($name)" else label)}}""")
            }
            return items.joinToString(",", "[", "]")
        }
    }
}
