package com.richkobe.piikandroid.capture

import com.richkobe.piikandroid.service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import com.richkobe.piikandroid.ProjectionActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Owns the single MediaProjection (screen-capture consent) and the screen
 * frame source built on it. Since Android 14 a projection may create only one
 * VirtualDisplay, so the display outlives individual Piik capture processes
 * (quality changes restart them) and is released only after a quiet period.
 */
object ProjectionHolder {
    private const val TAG = "PiikProjection"
    private const val IDLE_RELEASE_MS = 20_000L

    private val lock = Object()
    private var projection: MediaProjection? = null
    private var pending: CountDownLatch? = null
    private var denied = false
    private var screen: ScreenSource? = null
    private var users = 0
    private val stopListeners = CopyOnWriteArrayList<() -> Unit>()
    private val thread = HandlerThread("piik-projection").apply { start() }
    val handler = Handler(thread.looper)
    private val idleRelease = Runnable { stopIfIdle() }

    /** Called by [ProjectionActivity] after the user answered the system dialog. */
    var onProjectionStarted: (() -> Unit)? = null
    var onProjectionEnded: (() -> Unit)? = null

    val active: Boolean get() = synchronized(lock) { projection != null }

    /**
     * Returns the projection, asking the user if needed. Blocks the calling
     * capture thread; the system dialog is the "picker" in Piik's terms.
     */
    fun acquire(context: Context, timeoutMs: Long): MediaProjection {
        val wait: CountDownLatch
        synchronized(lock) {
            projection?.let { return it }
            val existing = pending
            if (existing != null) {
                wait = existing
            } else {
                wait = CountDownLatch(1)
                pending = wait
                denied = false
                context.startActivity(
                    Intent(context, ProjectionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }
        if (!wait.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            synchronized(lock) { if (pending === wait) pending = null }
            throw IllegalStateException("screen selection timed out")
        }
        synchronized(lock) {
            return projection ?: throw IllegalStateException(
                if (denied) "screen sharing was not allowed" else "screen selection failed",
            )
        }
    }

    fun onConsent(result: MediaProjection?) {
        synchronized(lock) {
            if (result != null) {
                projection = result
                result.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = ended(result)

                    override fun onCapturedContentResize(width: Int, height: Int) {
                        synchronized(lock) { screen }?.onContentResize(width, height)
                    }
                }, handler)
            } else {
                denied = true
            }
            pending?.countDown()
            pending = null
        }
        if (result != null) onProjectionStarted?.invoke()
    }

    private fun ended(which: MediaProjection) {
        val listeners: List<() -> Unit>
        val old: ScreenSource?
        synchronized(lock) {
            if (projection !== which) return
            projection = null
            old = screen
            screen = null
            users = 0
            listeners = stopListeners.toList()
        }
        Log.i(TAG, "screen capture ended")
        listeners.forEach { runCatching(it) }
        old?.release() // outside the lock: release waits for the GL thread
        onProjectionEnded?.invoke()
    }

    /** Shared screen source; call [retain]/[release] around its use. */
    fun screen(context: Context): ScreenSource = synchronized(lock) {
        val p = projection ?: throw IllegalStateException("screen capture is not active")
        screen ?: ScreenSource(context.applicationContext, p, handler).also { screen = it }
    }

    fun retain() = synchronized(lock) {
        users++
        handler.removeCallbacks(idleRelease)
    }

    fun release() = synchronized(lock) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) handler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    fun addStopListener(listener: () -> Unit) = stopListeners.add(listener)
    fun removeStopListener(listener: () -> Unit) = stopListeners.remove(listener)

    private fun stopIfIdle() {
        val p = synchronized(lock) { if (users == 0) projection else null } ?: return
        Log.i(TAG, "no capture in use; ending screen capture")
        p.stop() // triggers onStop → ended()
    }

    fun stop() {
        synchronized(lock) { projection }?.stop()
    }
}

/** The phone screen as a [GlSource], via one long-lived VirtualDisplay. */
class ScreenSource(context: Context, projection: MediaProjection, handler: Handler) {
    val gl = GlSource("piik-screen", tickFps = 60)
    private val displayManager = context.service<DisplayManager>()
    private val display: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    private var contentAspect: Float? = null // set when a single app is shared (Android 14+)
    private val virtualDisplay: VirtualDisplay
    private var size = targetSize()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) resize() // rotation, resolution change
        }
    }

    init {
        gl.setContentSize(size.first, size.second)
        virtualDisplay = projection.createVirtualDisplay(
            "piik-screen", size.first, size.second, densityDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, gl.inputSurface, null, handler,
        ) ?: throw IllegalStateException("could not create the capture display")
        displayManager.registerDisplayListener(displayListener, handler)
    }

    private fun densityDpi(): Int {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.densityDpi
    }

    /** Screen size scaled to fit Piik's largest frame (2560×1440 in either orientation). */
    private fun targetSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        var w = metrics.widthPixels.toFloat()
        var h = metrics.heightPixels.toFloat()
        contentAspect?.let { a -> if (w / h > a) w = h * a else h = w / a }
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val scale = minOf(1f, Smed.MAX_WIDTH / long, Smed.MAX_HEIGHT / short)
        val tw = ((w * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val th = ((h * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        return tw to th
    }

    private fun resize() {
        val next = targetSize()
        if (next == size) return
        size = next
        Log.i("PiikScreen", "capture size ${next.first}x${next.second}")
        gl.setContentSize(next.first, next.second)
        virtualDisplay.resize(next.first, next.second, densityDpi())
    }

    fun onContentResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || Build.VERSION.SDK_INT < 34) return
        contentAspect = width.toFloat() / height
        resize()
    }

    fun release() {
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        try { virtualDisplay.release() } catch (_: Throwable) {}
        gl.release()
    }
}
