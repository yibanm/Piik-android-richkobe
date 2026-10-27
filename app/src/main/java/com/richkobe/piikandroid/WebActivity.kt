package com.richkobe.piikandroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Shows Piik's own web interface: an invite link to watch, or (host mode) the
 * pages served by the Piik engine running on this phone.
 */
class WebActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var navBack: android.widget.Button
    private lateinit var navForward: android.widget.Button
    private var mode = MODE_WATCH
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingPermission: PermissionRequest? = null
    private var videoPlaying = false
    private var videoAspect = Rational(16, 9)

    private val hostListener: () -> Unit = { onHostState() }
    private val externalListener: (String) -> Unit = { url -> openExternal(url) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 网页前进/后退箭头：原生悬浮按钮叠在 WebView 上（左下角，半透明）。
        // 不用 JS 注入实现——失败页（「无法访问」）里页面脚本可能已死，
        // 原生按钮仍可用，而这正是最需要后退离开的场景。主机模式返回键
        // 仍直接回原生主页（见 onBackPressed），箭头只负责网页内历史。
        val nav = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        navBack = navButton(getString(R.string.web_nav_back)) { if (web.canGoBack()) web.goBack() }
        navForward = navButton(getString(R.string.web_nav_forward)) { if (web.canGoForward()) web.goForward() }
        nav.addView(navBack)
        nav.addView(navForward)
        root.addView(
            nav,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 左上角（用户指定），但下移到网页头部行之下：贴顶会压住页面自己的
                // logo/菜单行（真机截图证实）。
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                setMargins(dp(8), dp(52), 0, 0)
            },
        )
        updateNavVisibility()
        // 内容避让系统手势条/导航栏：不设置时页面底部控件（公开/私密切换、房间号
        // 输入）会被手势条压住（真机截图证实重叠）。
        root.fitsSystemWindows = true
        setContentView(root)
        web.setBackgroundColor(Color.BLACK)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "$userAgentString PiikAndroid/${packageManager.getPackageInfo(packageName, 0).versionName}"
        }
        web.addJavascriptInterface(Bridge(), "PiikAndroid")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, _, disposition, mime, _ ->
            val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            when {
                url.startsWith("blob:") || url.startsWith("data:") ->
                    web.evaluateJavascript("window.__piikSave && window.__piikSave(${jsonQuote(url)}, ${jsonQuote(name)})", null)
                else -> openExternal(url)
            }
        }
        handle(intent, savedInstanceState)
    }

    private fun handle(intent: Intent, saved: Bundle? = null) {
        mode = intent.getStringExtra(EXTRA_MODE) ?: if (intent.action == Intent.ACTION_VIEW) MODE_WATCH else mode
        val url = intent.getStringExtra(EXTRA_URL) ?: intent.data?.toString()
        if (mode == MODE_HOST) {
            HostState.addListener(hostListener)
            HostState.addExternalListener(externalListener)
            // 重开运行中房间一律回引擎根路径（launcher）：引擎端口与 piik-client-port
            // 每次启动都变，壳层自记的「深链」必然过期（2026-10-27 真机诊断证实：
            // 记到的 ?debug=1 / /client 旧链加载后连不上，表现为「无法访问」与
            // 「要重新分享」）。上游 launcher 自带带新端口的「进入」按钮，交给它。
            if (saved != null) web.restoreState(saved) else HostState.pageUrl?.let { lastHostPage = it; web.loadUrl(it) }
            if (HostState.pageUrl == null) onHostState()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (saved != null) web.restoreState(saved) else if (url != null) web.loadUrl(url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL) ?: intent.data?.toString()
        val newMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_WATCH
        if (newMode == MODE_HOST) {
            mode = MODE_HOST
            HostState.addListener(hostListener)
            HostState.addExternalListener(externalListener)
            HostState.pageUrl?.let { if (web.url != it) web.loadUrl(it) }
        } else if (url != null) {
            mode = MODE_WATCH
            web.loadUrl(url)
        }
    }

    private var lastHostPage: String? = null

    private fun onHostState() {
        when (HostState.phase) {
            HostState.Phase.RUNNING -> HostState.pageUrl?.let { page ->
                if (page != lastHostPage) {
                    lastHostPage = page
                    web.loadUrl(page)
                }
            }
            HostState.Phase.STARTING -> if (web.url == null) web.loadData(statusPage(getString(R.string.web_status_starting)), "text/html", "utf-8")
            HostState.Phase.FAILED -> web.loadData(statusPage(HostState.message ?: getString(R.string.web_status_stopped)), "text/html", "utf-8")
            HostState.Phase.STOPPED -> if (mode == MODE_HOST) finish()
        }
    }

    private fun statusPage(text: String): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;")
        return "<html><body style='background:#0b0c10;color:#e9ecf5;font-family:sans-serif;padding:24px;white-space:pre-wrap'>$safe</body></html>"
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    /**
     * Host mode: let Piik's page reach the engine on this phone. WebView hides
     * local addresses behind ".local" names in WebRTC; assets/icefix.js swaps
     * them for 127.0.0.1 and the phone's own addresses (idempotent).
     */
    private fun injectIceFix(view: WebView, url: String?) {
        val host = url?.let { Uri.parse(it).host } ?: return
        if (mode != MODE_HOST || (host != "127.0.0.1" && host != "localhost")) return
        val addresses = listOf("127.0.0.1") +
            NetInfo.lanAddresses(this).split(",").mapNotNull { it.substringBefore("/").takeIf(String::isNotBlank) }
        val json = addresses.distinct().joinToString(",", "[", "]") { "\"$it\"" }
        val script = iceFixSource ?: assets.open("icefix.js").bufferedReader().use { it.readText() }.also { iceFixSource = it }
        view.evaluateJavascript(script.replace("__PIIK_ADDRESSES__", json), null)
    }

    private var iceFixSource: String? = null

    /**
     * 老 WebView 兼容注入（幂等，内部有 __piikOldWv / __piikAndroid 守卫）：
     * assets/old-webview.js 补运行时 API（globalThis、Promise.allSettled、
     * replaceAll、Object.hasOwn、structuredClone、.at() 等）并收集页面错误，
     * 白屏时展示中文诊断页；POLYFILLS 是剪贴板/分享/画中画桥接。
     * onPageStarted 注入保证垫片先于页面脚本运行；onPageFinished 再注入
     * 一次作为 SPA 路由切换后的兜底（守卫使其成为 no-op）。
     */
    private fun injectOldWebViewShims(view: WebView) {
        try {
            val script = oldWebViewSource
                ?: assets.open("old-webview.js").bufferedReader().use { it.readText() }
                    .also { oldWebViewSource = it }
            view.evaluateJavascript(script, null)
            view.evaluateJavascript(POLYFILLS, null)
        } catch (e: Exception) {
            Log.w("PiikWeb", "old-webview shims inject failed", e)
        }
    }

    private var oldWebViewSource: String? = null

    /**
     * 小屏（手表等）压缩布局。用 max-width media query 门控：只有物理小屏设备
     * 命中，arm64/v7a 手机零影响——等价于「只改 v7a」（手表均为 32 位跑 v7a 包），
     * 且不会误伤同样跑 v7a 包的 32 位手机。注入而非改 dist：防上游升级冲掉。
     * 幂等：style 标签带 id，重复注入 no-op。
     */
    private fun injectSmallScreenCss(view: WebView) {
        // 双重门控（用户硬性要求：手表改动不得波及 V8a，也不得误伤 v7a 手机）：
        // ① ABI = armeabi-v7a（手表均为 32 位；V8a 包到此直接返回，一行不注入）；
        // ② 物理小屏 smallestScreenWidthDp < 300（手表约 180–230dp，手机 ≥320dp，
        //    该值不随旋转变化）。CSS 内的 media query 是第三层兜底。
        // 2026-10-27 教训：单靠 media query(max-width:480px) 门控失败——max-width
        // 是 CSS px(≈dp)，手机视口 360–430dp 全命中，V8a 手机按钮被压没。
        if (!isWatchFormFactor()) return
        view.evaluateJavascript(
            "(function(){if(document.getElementById('piik-small-screen'))return;" +
                "var s=document.createElement('style');s.id='piik-small-screen';" +
                "s.textContent=" + jsonQuote(SMALL_SCREEN_CSS) + ";" +
                "(document.head||document.documentElement).appendChild(s);})();",
            null,
        )
    }

    /**
     * 手表形态判定：**矮屏小屏**（smallestScreenWidthDp < 400 且 screenHeightDp < 480）。
     * 2026-10-27 真机诊断推翻「手表=v7a」前提：用户的手表是 64 位设备、装 V8a 包
     * （ABI 首位 arm64-v8a，320x393dp），按 ABI 门控会让手表完全拿不到压缩布局。
     * 改按屏幕形态：手表屏高 ≤393dp 命中；手机（含 320x568 老小屏机）屏高 ≥560dp
     * 不命中；纵横比不能用作判据（手机竖屏比例同样 <1）。media query 在 CSS 内兜底。
     */
    private fun isWatchFormFactor(): Boolean {
        val cfg = resources.configuration
        return cfg.smallestScreenWidthDp < 400 && cfg.screenHeightDp < 480
    }

    private fun dp(v: Int) = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    /**
     * 导航按钮做成网页同款风格：Piik web UI 是深色圆角控件（卡片 #262b36、
     * 圆角胶囊），原生灰底方按钮叠在上面格格不入（用户反馈）。改半透明深色
     * 圆形 + 白色箭头，视觉上像页面自己的悬浮控件。
     */
    private fun navButton(label: String, onClick: () -> Unit): android.widget.Button =
        android.widget.Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor("#E9ECF5"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#B3262B36")) // 网页卡片色 + 半透明
                cornerRadius = dp(22).toFloat() // 正圆胶囊
            }
            background = bg
            val size = dp(44)
            minimumWidth = size
            minimumHeight = size
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(8)
            }
        }

    /** 没有对应历史时禁用按钮（灰显），避免点了没反应像坏了。 */
    private fun updateNavVisibility() {
        if (::navBack.isInitialized) {
            navBack.isEnabled = web.canGoBack()
            navBack.alpha = if (web.canGoBack()) 0.9f else 0.3f
            navForward.isEnabled = web.canGoForward()
            navForward.alpha = if (web.canGoForward()) 0.9f else 0.3f
        }
    }

    // ---- WebView clients ----

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme) {
                "http", "https" -> false
                else -> {
                    openExternal(uri.toString())
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            // 兼容垫片必须先于页面自身脚本注入：onPageFinished 太晚，
            // 模块脚本在此之前已解析执行（老内核直接 SyntaxError → 白屏）。
            injectOldWebViewShims(view)
            injectIceFix(view, url)
            injectSmallScreenCss(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            injectOldWebViewShims(view)
            injectIceFix(view, url)
            injectSmallScreenCss(view)
            updateNavVisibility()
            // Piik's host page talks to the engine on this phone over WebRTC.
            // WebView hides local addresses behind mDNS names unless the page
            // has microphone access, and those names can't be resolved without
            // multicast (never on mobile data). Briefly opening the mic lifts
            // the hiding; the track is stopped immediately.
            val host = url?.let { Uri.parse(it).host }
            if (mode == MODE_HOST && (host == "127.0.0.1" || host == "localhost") &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                view.evaluateJavascript(UNLOCK_LOCAL_ADDRESSES, null)
            }
        }

        /**
         * SPA 路由用 pushState 切换，onPageFinished 不触发；WebView 的访问历史
         * 变化（含 pushState）会走这里，是刷新前进/后退按钮状态的唯一可靠钩子。
         */
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            updateNavVisibility()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val needed = ArrayList<String>()
                for (r in request.resources) {
                    when (r) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> needed += Manifest.permission.RECORD_AUDIO
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> needed += Manifest.permission.CAMERA
                    }
                }
                val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingPermission = request
                    requestPermissions(missing.toTypedArray(), REQUEST_WEB_PERMISSION)
                }
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.visibility = View.GONE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            immersive(true)
        }

        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            web.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            immersive(false)
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d("PiikWeb", "${message.messageLevel()}: ${message.message()}")
            return true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_WEB_PERMISSION) return
        val request = pendingPermission ?: return
        pendingPermission = null
        val granted = request.resources.filter { r ->
            val perm = when (r) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> null
            }
            perm == null || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun immersive(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ---- JS bridge (clipboard / share, which WebView doesn't fully provide) ----

    private inner class Bridge {
        /** 垫片回传的页内错误：记入引擎日志流，诊断复制/调试日志能带出真凶。 */
        @JavascriptInterface
        fun reportPageError(msg: String) {
            HostState.appendLog("[page] $msg")
            Log.w("PiikWeb", msg)
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                service<ClipboardManager>().setPrimaryClip(ClipData.newPlainText("Piik", text))
                if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this@WebActivity, getString(R.string.web_copied), Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull() ?: return
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "piik-download" }
            val ok = Diagnostics.saveToDownloads(this@WebActivity, safe, mime, bytes) != null
            runOnUiThread {
                Toast.makeText(this@WebActivity, if (ok) getString(R.string.web_saved, safe) else getString(R.string.web_save_failed, safe), Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun shareText(text: String, title: String?) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, title ?: getString(R.string.web_share_chooser)))
            }
        }

        @JavascriptInterface
        fun videoState(playing: Boolean, width: Int, height: Int) {
            videoPlaying = playing
            if (width > 0 && height > 0) {
                val r = width.toFloat() / height
                // PiP accepts aspect ratios between 1:2.39 and 2.39:1.
                videoAspect = when {
                    r > 2.39f -> Rational(239, 100)
                    r < 1 / 2.39f -> Rational(100, 239)
                    else -> Rational(width, height)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) runOnUiThread { updatePip() }
        }
    }

    // ---- picture-in-picture for watching ----

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(videoAspect)
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(mode == MODE_WATCH && videoPlaying).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePip() {
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (mode == MODE_WATCH && videoPlaying && android.os.Build.VERSION.SDK_INT < 31) {
            try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        web.evaluateJavascript("document.documentElement.classList.toggle('piik-android-pip', $isInPictureInPictureMode)", null)
    }

    // ---- lifecycle ----

    @Deprecated("Platform back handling without AndroidX")
    override fun onBackPressed() {
        when {
            customView != null -> customCallback?.onCustomViewHidden()
            // 主机页是单页应用（launcher ↔ 房间页之间会产生网页历史）。若让返回键
            // 走网页后退，用户会一直在这几页里打转，永远回不到原生主页，也就拿不到
            // 主页的「停止共享」按钮，只能杀进程。所以主机模式下返回键直接回主页，
            // 后台共享不受影响（前台服务仍在跑）。
            mode == MODE_HOST -> {
                if (HostState.phase == HostState.Phase.RUNNING) {
                    Toast.makeText(this, getString(R.string.web_back_keeps_hosting), Toast.LENGTH_LONG).show()
                }
                @Suppress("DEPRECATION") super.onBackPressed()
            }
            // 观看模式保留网页历史后退：观看端在多路画面之间切换需要它。
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        HostState.removeListener(hostListener)
        HostState.removeExternalListener(externalListener)
        web.destroy()
        super.onDestroy()
    }

    private fun jsonQuote(v: String) = org.json.JSONObject.quote(v)

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URL = "url"
        const val MODE_WATCH = "watch"
        const val MODE_HOST = "host"
        private const val REQUEST_WEB_PERMISSION = 3

        /**
         * 小屏压缩布局（手表）。双重门控：**仅 armeabi-v7a 包 + 物理小屏**才注入——
         * 用户硬性要求手表改动不得波及 arm64-v8a；且 media query 的 max-width 是
         * CSS px（≈dp），手机视口普遍 360–430dp 全 <480，单靠 media query 会误伤
         * 手机（2026-10-27 实测：V8a 手机创建房间按钮被压没），故必须叠加 ABI 门控。
         * 选择器纪律（2026-10-27 踩坑）：`.lr-comic-tip-wrap` 是 Tooltip 的**外壳**
         * （包着触发按钮），display:none 会把「开始分享/加入房间」按钮一起藏掉；
         * 只能藏气泡 `.lr-comic-tip` 本身。注入而非改 dist：防上游升级冲掉。
         * 幂等：style 标签带 id，重复注入 no-op。
         */
        private const val SMALL_SCREEN_CSS =
            "@media (max-width:480px){" +
                ".lr-app{padding:8px!important}" +
                ".lr-tile,.lr-sharing-section,.lr-sharing-panel,.lr-viewer-settings{padding:8px!important;margin:4px 0!important}" +
                ".lr-btn{min-height:36px!important;padding:6px 10px!important;font-size:13px!important}" +
                ".lr-input{min-height:34px!important;font-size:13px!important;padding:6px 8px!important}" +
                ".lr-comic-tip{display:none!important}" +
                ".lr-brand-sparkles{display:none!important}" +
                ".lr-row-group{gap:6px!important}" +
                ".lr-chip{padding:3px 8px!important;font-size:12px!important}" +
                ".lr-toggle{min-height:32px!important}" +
                "h1,h2{font-size:16px!important}h3{font-size:14px!important}" +
                "}"

        private const val UNLOCK_LOCAL_ADDRESSES = """            (function () {
              if (window.__piikUnlocked || !navigator.mediaDevices) return; window.__piikUnlocked = true;
              navigator.mediaDevices.getUserMedia({ audio: true })
                .then(function (s) { s.getTracks().forEach(function (t) { t.stop(); }); })
                .catch(function () {});
            })();
        """

        /**
         * Small, idempotent page shims: clipboard and Web Share go through the
         * app; the largest playing <video> is reported for picture-in-picture,
         * which then shows only that video.
         */
        private val POLYFILLS = """
            (function () {
              if (window.__piikAndroid) return; window.__piikAndroid = true;
              var bridge = window.PiikAndroid;
              try {
                var clip = navigator.clipboard || {};
                var orig = clip.writeText && clip.writeText.bind(clip);
                var writeText = function (t) {
                  bridge.copyText(String(t));
                  return orig ? orig(t).catch(function () {}) : Promise.resolve();
                };
                if (navigator.clipboard) navigator.clipboard.writeText = writeText;
                else Object.defineProperty(navigator, 'clipboard', { value: { writeText: writeText } });
              } catch (e) {}
              window.__piikSave = function (href, name) {
                fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
                  var fr = new FileReader();
                  fr.onload = function () {
                    var data = String(fr.result); var i = data.indexOf(',');
                    bridge.saveFile(name || 'piik-download', b.type || 'application/octet-stream', data.slice(i + 1));
                  };
                  fr.readAsDataURL(b);
                }).catch(function () {});
              };
              document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                if (!a || !/^(blob|data):/.test(a.href)) return;
                e.preventDefault();
                window.__piikSave(a.href, a.getAttribute('download'));
              }, true);
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                if (this.hasAttribute('download') && /^(blob|data):/.test(this.href)) {
                  window.__piikSave(this.href, this.getAttribute('download'));
                  return;
                }
                return origClick.call(this);
              };
              navigator.share = function (d) {
                d = d || {};
                bridge.shareText([d.text, d.url].filter(Boolean).join(' '), d.title || null);
                return Promise.resolve();
              };
              navigator.canShare = function () { return true; };
              var style = document.createElement('style');
              style.textContent = 'html.piik-android-pip body *{visibility:hidden!important}' +
                'html.piik-android-pip video.piik-android-main{visibility:visible!important;position:fixed!important;' +
                'inset:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important;' +
                'z-index:2147483647!important;background:#000!important}';
              // onPageStarted 注入时 <head> 可能尚未解析出来，退回 documentElement。
              (document.head || document.documentElement).appendChild(style);
              var last = '';
              setInterval(function () {
                var best = null, area = 0;
                document.querySelectorAll('video').forEach(function (v) {
                  var r = v.getBoundingClientRect(), a = r.width * r.height;
                  if (!v.paused && v.readyState > 2 && a >= area) { best = v; area = a; }
                });
                document.querySelectorAll('video.piik-android-main').forEach(function (v) {
                  if (v !== best) v.classList.remove('piik-android-main');
                });
                if (best) best.classList.add('piik-android-main');
                var s = best ? ('1,' + best.videoWidth + ',' + best.videoHeight) : '0,0,0';
                if (s !== last) { last = s; var p = s.split(','); bridge.videoState(p[0] === '1', +p[1], +p[2]); }
              }, 1000);
            })();
        """.trimIndent()
    }
}
