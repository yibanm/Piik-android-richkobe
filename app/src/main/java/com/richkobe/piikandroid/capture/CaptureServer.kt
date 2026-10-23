package com.richkobe.piikandroid.capture

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom

/**
 * Serves Piik's capture-process contract to the native shim over an abstract
 * Unix socket. Only connections from this app's own UID are accepted.
 */
class CaptureServer(private val context: Context) {
    val socketName: String = "piik-capture-" + Process.myUid() + "-" +
        SecureRandom().nextLong().toULong().toString(36)

    private val server = LocalServerSocket(socketName)
    @Volatile private var closed = false
    private val acceptor = Thread({ acceptLoop() }, "piik-capture-accept").apply { isDaemon = true; start() }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "accept failed", e)
                return
            }
            Thread({ serve(socket) }, "piik-capture").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: LocalSocket) {
        socket.use {
            try {
                if (socket.peerCredentials.uid != Process.myUid()) {
                    Log.w(TAG, "rejected capture connection from uid ${socket.peerCredentials.uid}")
                    return
                }
                val input = socket.inputStream
                val header = JSONObject(readLine(input) ?: return)
                val argsJson = header.optJSONArray("args")
                val args = (0 until (argsJson?.length() ?: 0)).map { argsJson!!.getString(it) }
                val envJson = header.optJSONObject("env")
                val env = HashMap<String, String>()
                envJson?.keys()?.forEach { k -> env[k] = envJson.getString(k) }
                val channel = ShimChannel(args, env, input, BufferedOutputStream(socket.outputStream, 256 * 1024))
                Log.i(TAG, "capture command: ${args.firstOrNull()}")
                val code = try {
                    dispatch(channel)
                } catch (t: Throwable) {
                    Log.e(TAG, "capture command failed", t)
                    channel.stderr("Piik capture unavailable: ${t.message ?: t.javaClass.simpleName}")
                    2
                }
                channel.exit(code)
            } catch (e: Exception) {
                Log.w(TAG, "capture connection failed", e)
            }
        }
    }

    private fun dispatch(ch: ShimChannel): Int {
        val a = ch.args
        return when {
            a == listOf("--probe") -> { ch.stdout(probe().toByteArray()); 0 }
            a == listOf("--list") -> {
                ch.stdout(CaptureProtocol.SOURCES.toByteArray())
                0
            }
            a == listOf("--list-microphones") -> { ch.stdout(AudioSession.listMicrophones(context).toByteArray()); 0 }
            a.firstOrNull() == "--capture-video" || a.firstOrNull() == "--encoded-video" -> VideoSession(context, ch).run()
            a.firstOrNull() == "--capture-audio" || a.firstOrNull() == "--capture-microphone" -> AudioSession(context, ch).run()
            else -> {
                ch.stderr("Piik capture unavailable: unsupported command on Android")
                2
            }
        }
    }

    /** Capability probe (internal/app/nativecapture/probe.go, protocol 7). */
    private fun probe(): String {
        val build = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ${Build.MANUFACTURER} ${Build.MODEL}"
        val encoders = Codecs.avcEncoders.map {
            CaptureProtocol.EncoderEntry(if (it.hardware) it.name else "${it.name} (software)", it.name)
        }
        return CaptureProtocol.probe("android", build, encoders, systemAudio = true)
    }

    fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
        // accept() on an abstract LocalServerSocket does not wake on close; poke it.
        try { LocalSocket().use { it.connect(android.net.LocalSocketAddress(socketName)) } } catch (_: Exception) {}
        acceptor.interrupt()
    }

    companion object {
        private const val TAG = "PiikCapture"

        /** Reads the header line byte by byte so no stdin bytes are consumed. */
        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (out.size() < 64 * 1024) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return out.toString("UTF-8")
                out.write(b)
            }
            return null
        }
    }
}
