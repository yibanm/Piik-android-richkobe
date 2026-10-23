package com.richkobe.piikandroid.capture

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Piik's native capture envelope ("SMED" v2), as defined by
 * internal/app/nativecapture/protocol.go: a 32-byte big-endian header and a
 * payload. Timestamps and durations are in 100 ns units.
 */
object Smed {
    const val PCM = 1
    const val H264 = 2
    const val STATUS = 3
    const val VP8 = 4
    const val BEGIN = 5
    const val LAYER_UNAVAILABLE = 6
    const val CONTROL = 7

    const val HEADER_BYTES = 32
    const val MAX_MEDIA_BYTES = 4 * 1024 * 1024
    const val MAX_STATUS_BYTES = 4 * 1024
    const val MAX_CONTROL_BYTES = 64

    /** Frame dimensions Piik accepts for video (protocol.go). */
    const val MAX_WIDTH = 2560
    const val MAX_HEIGHT = 1440

    fun header(
        kind: Int, keyFrame: Boolean, layer: Int, width: Int, height: Int,
        timestamp100ns: Long, duration100ns: Long, size: Int,
    ): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES)
        b.put('S'.code.toByte()).put('M'.code.toByte()).put('E'.code.toByte()).put('D'.code.toByte())
        b.put(2).put(kind.toByte()).put(if (keyFrame) 1 else 0).put(layer.toByte())
        b.putLong(timestamp100ns)
        b.putLong(duration100ns)
        b.putShort(width.toShort())
        b.putShort(height.toShort())
        b.putInt(size)
        return b.array()
    }

    class InputFrame(
        val kind: Int,
        val keyFrame: Boolean,
        val width: Int,
        val height: Int,
        val timestamp100ns: Long,
        val duration100ns: Long,
        val data: ByteArray,
    ) {
        val text: String get() = String(data, StandardCharsets.US_ASCII)
    }

    /**
     * Reads frames Piik writes to the capture process stdin: control commands
     * (K/A/B/Q) and, for --encoded-video, H.264 access units.
     * Returns null at end of input.
     */
    fun read(input: DataInputStream): InputFrame? {
        val header = ByteArray(HEADER_BYTES)
        try {
            input.readFully(header)
        } catch (e: EOFException) {
            return null
        }
        val b = ByteBuffer.wrap(header)
        if (header[0] != 'S'.code.toByte() || header[1] != 'M'.code.toByte() ||
            header[2] != 'E'.code.toByte() || header[3] != 'D'.code.toByte() || header[4].toInt() != 2
        ) throw ProtocolException("invalid native input envelope")
        val kind = header[5].toInt()
        val key = header[6].toInt() == 1
        val timestamp = b.getLong(8)
        val duration = b.getLong(16)
        val width = b.getShort(24).toInt() and 0xffff
        val height = b.getShort(26).toInt() and 0xffff
        val size = b.getInt(28)
        val max = when (kind) {
            CONTROL -> MAX_CONTROL_BYTES
            H264 -> MAX_MEDIA_BYTES
            else -> throw ProtocolException("unexpected native input kind $kind")
        }
        if (size <= 0 || size > max) throw ProtocolException("invalid native input size")
        val data = ByteArray(size)
        input.readFully(data)
        if (kind == CONTROL && data.any { it < 32 || it > 126 }) {
            throw ProtocolException("native control payload is not ASCII")
        }
        return InputFrame(kind, key, width, height, timestamp, duration, data)
    }
}

class ProtocolException(message: String) : Exception(message)

/** Minimal JSON string escaping for the status payloads we emit. */
fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}

/**
 * One capture process invocation, relayed by the native shim:
 * args/env from the header line, stdin from the socket, and stdout/stderr/exit
 * written back as framed records.
 */
class ShimChannel(
    val args: List<String>,
    val env: Map<String, String>,
    val stdin: InputStream,
    private val out: OutputStream,
) {
    private val lock = Any()
    @Volatile private var closed = false

    private fun record(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        synchronized(lock) {
            if (closed) throw java.io.IOException("capture channel closed")
            val head = ByteBuffer.allocate(5).put(type.toByte()).putInt(length).array()
            out.write(head)
            out.write(payload, offset, length)
            out.flush()
        }
    }

    fun stdout(payload: ByteArray) = record(1, payload)

    fun stderr(text: String) {
        try {
            record(2, (text.trimEnd() + "\n").toByteArray())
        } catch (_: Exception) {
        }
    }

    /** Writes one SMED frame atomically (header and payload in one record). */
    fun frame(
        kind: Int, keyFrame: Boolean = false, layer: Int = 0, width: Int = 0, height: Int = 0,
        timestamp100ns: Long = 0, duration100ns: Long = 0, payload: ByteArray = ByteArray(0),
        payloadOffset: Int = 0, payloadLength: Int = payload.size,
    ) {
        val header = Smed.header(kind, keyFrame, layer, width, height, timestamp100ns, duration100ns, payloadLength)
        val all = ByteArray(header.size + payloadLength)
        System.arraycopy(header, 0, all, 0, header.size)
        System.arraycopy(payload, payloadOffset, all, header.size, payloadLength)
        record(1, all)
    }

    fun status(json: String) = frame(Smed.STATUS, payload = json.toByteArray(StandardCharsets.UTF_8))

    fun exit(code: Int) {
        synchronized(lock) {
            if (closed) return
            try {
                val head = ByteBuffer.allocate(6).put(3).putInt(1).put(code.toByte()).array()
                out.write(head)
                out.flush()
            } catch (_: Exception) {
            }
            closed = true
        }
    }
}
