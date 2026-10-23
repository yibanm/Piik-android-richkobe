package com.richkobe.piikandroid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.richkobe.piikandroid.capture.CaptureServer
import com.richkobe.piikandroid.capture.ProjectionHolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs Piik's own App engine (the Go `piik-app`, shipped as libpiik.so) as a
 * child process, plus the capture service it talks to through the native
 * shim. A foreground service keeps both alive while you switch apps to share
 * them.
 */
class PiikService : Service() {
    private var process: Process? = null
    private var capture: CaptureServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var projecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = service<NotificationManager>()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
            },
        )
        ProjectionHolder.onProjectionEnded = {
            projecting = false
            if (process != null) goForeground()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHosting()
                return START_NOT_STICKY
            }
            else -> if (process == null) startHosting()
        }
        return START_NOT_STICKY
    }

    private fun startHosting() {
        stopping = false
        HostState.update(HostState.Phase.STARTING, null)
        if (!goForeground()) return
        val lib = File(applicationInfo.nativeLibraryDir)
        val engine = File(lib, "libpiik.so")
        val shim = File(lib, "libpiikcapture.so")
        if (!engine.exists() || !shim.exists()) {
            fail(getString(R.string.host_error_no_engine))
            return
        }
        try {
            val server = CaptureServer(this)
            capture = server
            val piikDir = File(filesDir, "piik").apply { mkdirs() }
            val args = mutableListOf(
                engine.path,
                "--capture-process", shim.path,
                "--config", File(piikDir, "client.json").path,
                "--debug", "--log-dir", Diagnostics.logDir(this@PiikService).path,
            )
            val tunnel = File(lib, "libcloudflared.so")
            if (tunnel.exists()) args += listOf("--tunnel-process", tunnel.path)
            val pb = ProcessBuilder(args).directory(piikDir)
            pb.environment().apply {
                put("HOME", filesDir.path)
                put("TMPDIR", cacheDir.path)
                put("XDG_CONFIG_HOME", File(filesDir, "config").path)
                put("XDG_CACHE_HOME", cacheDir.path)
                put("TERM", "dumb")
                put("NO_COLOR", "1")
                put("PIIK_ANDROID_CAPTURE_SOCKET", server.socketName)
                put("PIIK_DNS_SERVERS", NetInfo.dnsServers(this@PiikService))
                put("PIIK_LAN_ADDRESSES", NetInfo.lanAddresses(this@PiikService))
            }
            val p = pb.start()
            process = p
            reader("piik-stderr", p.errorStream.bufferedReader()) { line -> onEngineLine(line) }
            reader("piik-stdout", p.inputStream.bufferedReader()) { line -> HostState.appendLog(line) }
            Thread({
                val code = p.waitFor()
                Log.i(TAG, "engine exited with $code")
                if (process === p) {
                    process = null
                    cleanup()
                    if (code == 0 || stopping) HostState.update(HostState.Phase.STOPPED)
                    else HostState.update(HostState.Phase.FAILED, message = getString(R.string.host_error_crashed, code, HostState.logText().takeLast(800)))
                    stopSelf()
                }
            }, "piik-wait").start()
            acquireWifiLock()
        } catch (e: Exception) {
            Log.e(TAG, "could not start engine", e)
            fail(getString(R.string.host_error_start_failed, e.message ?: ""))
        }
    }

    private fun reader(name: String, r: java.io.BufferedReader, onLine: (String) -> Unit) =
        Thread({
            try {
                r.forEachLine(onLine)
            } catch (_: Exception) {
            }
        }, name).apply { isDaemon = true; start() }

    private fun onEngineLine(line: String) {
        HostState.appendLog(line)
        if (!line.startsWith(OPEN_MARKER)) return
        val url = line.removePrefix(OPEN_MARKER).trim()
        val host = Uri.parse(url).host ?: return
        if (host == "127.0.0.1" || host == "localhost") {
            HostState.update(HostState.Phase.RUNNING, url)
        } else {
            HostState.openExternal(url)
        }
    }

    @Volatile private var stopping = false

    private fun stopHosting() {
        stopping = true
        val p = process
        process = null
        ProjectionHolder.stop()
        Thread({
            if (p != null) {
                p.destroy() // SIGTERM: Piik shuts its room down cleanly
                if (!p.waitFor(4, TimeUnit.SECONDS)) p.destroyForcibly()
            }
            cleanup()
            HostState.update(HostState.Phase.STOPPED)
            stopSelf()
        }, "piik-stop").start()
    }

    private fun fail(message: String) {
        cleanup()
        HostState.update(HostState.Phase.FAILED, message = message)
        stopSelf()
    }

    private fun cleanup() {
        capture?.close()
        capture = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.service<WifiManager>()
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "piik-hosting").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Lets the engine resolve WebRTC mDNS (.local) candidates on Wi-Fi.
        multicastLock = wm.createMulticastLock("piik-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Called right after the user allows screen capture, before getMediaProjection(). */
    fun promoteForProjection(): Boolean {
        projecting = true
        return goForeground()
    }

    private fun goForeground(): Boolean {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_MODE, WebActivity.MODE_HOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PiikService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (projecting) R.string.notif_title_projecting else R.string.notif_title_hosting))
            .setContentText(getString(R.string.notif_text_open))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_action_stop), stop).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (projecting) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            fail(getString(R.string.host_error_background, e.message ?: ""))
            false
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        process?.let { p ->
            p.destroy()
            Thread { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }.start()
        }
        process = null
        capture?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PiikService"
        private const val CHANNEL = "hosting"
        private const val NOTIFICATION_ID = 1
        private const val OPEN_MARKER = "PIIK_ANDROID_OPEN "
        const val ACTION_START = "com.richkobe.piikandroid.START"
        const val ACTION_STOP = "com.richkobe.piikandroid.STOP"

        @Volatile var instance: PiikService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PiikService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PiikService::class.java).setAction(ACTION_STOP))
        }
    }
}
