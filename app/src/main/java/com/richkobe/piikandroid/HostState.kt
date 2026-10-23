package com.richkobe.piikandroid

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Hosting state shared between the service and the UI (main-thread callbacks). */
object HostState {
    enum class Phase { STOPPED, STARTING, RUNNING, FAILED }

    @Volatile var phase = Phase.STOPPED
        private set
    /** The Piik page to show: the launcher first, then the Host page. */
    @Volatile var pageUrl: String? = null
        private set
    @Volatile var message: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val externalListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val log = ArrayDeque<String>()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun addExternalListener(l: (String) -> Unit) = externalListeners.add(l)
    fun removeExternalListener(l: (String) -> Unit) = externalListeners.remove(l)

    fun update(phase: Phase, pageUrl: String? = this.pageUrl, message: String? = null) {
        this.phase = phase
        this.pageUrl = if (phase == Phase.STOPPED || phase == Phase.FAILED) null else pageUrl
        this.message = message
        main.post { listeners.forEach { it() } }
    }

    /** Piik asked to open a non-local page (e.g. a release page). */
    fun openExternal(url: String) = main.post { externalListeners.forEach { it(url) } }

    fun appendLog(line: String) = synchronized(log) {
        log.addLast(line)
        while (log.size > 400) log.removeFirst()
    }

    fun logText(): String = synchronized(log) { log.joinToString("\n") }
}
