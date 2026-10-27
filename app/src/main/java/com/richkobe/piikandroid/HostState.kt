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
    /**
     * 用户实际停留过的房间深链（host 页 `/`、launcher `/client`）。引擎的
     * OPEN marker 只报根路径，重开运行中房间时若只 load 根路径会回到 launcher
     * 而非用户原来的房间页；WebActivity 在 onPageFinished 记录真实停留页，
     * 重开时优先用它。进程级存活（服务不销毁即有效），引擎停止时清空。
     */
    @Volatile var roomPageUrl: String? = null
        private set
    @Volatile var message: String? = null
        private set

    /**
     * 是否静音「系统声音」（手机正在播放的声音）。只作用于屏幕共享音频，
     * 不影响麦克风与房间语音。上游 web 的声音开关绑死了 Windows 特有的
     * 「按应用排除音频」能力，Android 上永远不显示，所以开关放在壳层：
     * 采集侧照常读 AudioRecord，只是把要发给引擎的 PCM 换成静音。
     */
    @Volatile var systemAudioMuted = false

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
        // 引擎停止/失败后房间已不存在，深链随之失效，避免下次误跳旧房间。
        if (phase == Phase.STOPPED || phase == Phase.FAILED) roomPageUrl = null
        this.message = message
        main.post { listeners.forEach { it() } }
    }

    /** WebActivity 记录用户实际停留的房间页（仅本地 host/launcher 路径）。 */
    fun noteRoomPage(url: String) {
        roomPageUrl = url
    }

    /** Piik asked to open a non-local page (e.g. a release page). */
    fun openExternal(url: String) = main.post { externalListeners.forEach { it(url) } }

    fun appendLog(line: String) = synchronized(log) {
        log.addLast(line)
        while (log.size > 400) log.removeFirst()
    }

    fun logText(): String = synchronized(log) { log.joinToString("\n") }
}
