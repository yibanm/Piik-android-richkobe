package com.richkobe.piikandroid.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A GPU frame source: something renders into [inputSurface] (a VirtualDisplay
 * or a video decoder), and every listener gets each frame drawn, scaled and
 * letterboxed, into its encoder input surfaces ("sinks").
 *
 * [tickFps] > 0: frames are emitted on a steady clock (the screen only
 * produces frames when something changes; Piik expects a steady rate, like
 * the Linux sidecar's PipeWire keepalive). 0: emitted once per input frame.
 */
class GlSource(name: String, private val tickFps: Int) {
    interface Listener {
        /** Called on the GL thread with a monotonic, microsecond-aligned timestamp. */
        fun onInputFrame(source: GlSource, timestampNs: Long)
        val maxFps: Int
    }

    class Sink internal constructor(
        val surface: Surface,
        val width: Int,
        val height: Int,
        internal var egl: EGLSurface,
    )

    private val thread = HandlerThread(name).apply { start() }
    val handler = Handler(thread.looper)

    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var config: EGLConfig
    private lateinit var pbuffer: EGLSurface
    private var texture = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private val texMatrix = FloatArray(16)

    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var inputSurface: Surface
        private set

    @Volatile var contentWidth = 0
        private set
    @Volatile var contentHeight = 0
        private set

    private val listeners = ArrayList<Listener>()
    private val lastEmit = HashMap<Listener, Long>()
    private var pendingFrame = false
    private var hasFrame = false
    private var released = false
    private var ticking = false

    init {
        sync {
            setupEgl()
            setupGl()
            surfaceTexture = SurfaceTexture(texture)
            surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
            inputSurface = Surface(surfaceTexture)
        }
    }

    /** Runs [block] on the GL thread and waits for it. */
    fun <T> sync(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        if (!handler.post {
                try {
                    result.set(block())
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    done.countDown()
                }
            }
        ) throw IllegalStateException("GL thread is gone")
        if (!done.await(10, TimeUnit.SECONDS)) throw IllegalStateException("GL thread timed out")
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    fun setContentSize(width: Int, height: Int) {
        sync {
            contentWidth = width
            contentHeight = height
            surfaceTexture.setDefaultBufferSize(width, height)
        }
    }

    fun addListener(listener: Listener) = sync {
        listeners.add(listener)
        if (tickFps > 0 && !ticking) {
            ticking = true
            handler.post(tick)
        }
    }

    fun removeListener(listener: Listener) = sync {
        listeners.remove(listener)
        lastEmit.remove(listener)
    }

    val listenerCount: Int get() = sync { listeners.size }

    fun createSink(surface: Surface, width: Int, height: Int): Sink = sync {
        val attrs = intArrayOf(EGL14.EGL_NONE)
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, attrs, 0)
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        Sink(surface, width, height, egl)
    }

    fun releaseSink(sink: Sink) = sync {
        if (sink.egl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, sink.egl)
            sink.egl = EGL14.EGL_NO_SURFACE
        }
    }

    /** Draws the current frame into [sink]. Must be called on the GL thread (from a listener). */
    fun draw(sink: Sink, timestampNs: Long): Boolean {
        if (sink.egl == EGL14.EGL_NO_SURFACE || !hasFrame) return false
        if (!EGL14.eglMakeCurrent(display, sink.egl, sink.egl, context)) return false
        GLES20.glViewport(0, 0, sink.width, sink.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val cw = if (contentWidth > 0) contentWidth else sink.width
        val ch = if (contentHeight > 0) contentHeight else sink.height
        // Letterbox: keep the source aspect ratio inside the output frame.
        val scale = minOf(sink.width.toFloat() / cw, sink.height.toFloat() / ch)
        val vw = Math.round(cw * scale).coerceIn(1, sink.width)
        val vh = Math.round(ch * scale).coerceIn(1, sink.height)
        GLES20.glViewport((sink.width - vw) / 2, (sink.height - vh) / 2, vw, vh)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, sink.egl, timestampNs)
        return EGL14.eglSwapBuffers(display, sink.egl)
    }

    private fun onFrameAvailable() {
        if (released) return
        if (tickFps > 0) {
            pendingFrame = true
            return
        }
        // Decoder-driven: one output frame per decoded input frame.
        latch()
        val ts = surfaceTexture.timestamp / 1000 * 1000
        for (l in listeners.toList()) l.onInputFrame(this, ts)
    }

    private fun latch() {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        hasFrame = true
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released || listeners.isEmpty()) {
                ticking = false
                return
            }
            val fps = listeners.maxOf { it.maxFps }.coerceIn(1, 60)
            val interval = 1_000_000_000L / fps
            try {
                if (pendingFrame) {
                    pendingFrame = false
                    latch()
                }
                if (hasFrame) {
                    val now = System.nanoTime() / 1000 * 1000
                    for (l in listeners.toList()) {
                        val own = 1_000_000_000L / l.maxFps.coerceIn(1, 60)
                        val last = lastEmit[l] ?: 0L
                        // 10% slack so a listener at the tick rate never skips a beat.
                        if (now - last >= own - own / 10) {
                            lastEmit[l] = now
                            l.onInputFrame(this@GlSource, now)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "frame tick failed", t)
            }
            handler.postAtTime(this, SystemClock.uptimeMillis() + interval / 1_000_000L)
        }
    }

    fun release() {
        try {
            sync {
                released = true
                listeners.clear()
                try { surfaceTexture.release() } catch (_: Throwable) {}
                try { inputSurface.release() } catch (_: Throwable) {}
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        }
        thread.quitSafely()
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no recordable EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }
    }

    private fun setupGl() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        android.opengl.Matrix.setIdentityM(texMatrix, 0)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun link(vertex: String, fragment: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "PiikGl"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }

        private val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }
}
