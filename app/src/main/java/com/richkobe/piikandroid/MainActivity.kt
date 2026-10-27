package com.richkobe.piikandroid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Home: watch an invite link, or host from this phone. */
class MainActivity : Activity() {
    private lateinit var linkInput: EditText
    private lateinit var recentBox: LinearLayout
    private lateinit var hostStatus: TextView
    private lateinit var hostPrimary: Button
    private lateinit var hostStop: Button
    private lateinit var floatSwitch: Switch
    private lateinit var audioSwitch: Switch
    private var openHostWhenReady = false
    /** 用户点了悬浮球开关 → 去系统授权页了；回来时若已授权则补开悬浮球。 */
    private var pendingFloatBall = false
    /** 为真时表示正在用代码同步开关 UI（非用户点击），抑制其回调，避免重复触发业务逻辑。 */
    private var syncingSwitch = false

    private val prefs by lazy { getSharedPreferences("piik", MODE_PRIVATE) }
    private val hostListener: () -> Unit = { renderHost() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleShare(intent)
        HostState.addListener(hostListener)
        renderHost()
        renderRecents()
        maybePromptBatteryOptimization()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        // 用户从系统「显示在其他应用上层」授权页返回：若已授权且之前想开，则补开。
        syncingSwitch = true
        try {
            if (pendingFloatBall) {
                pendingFloatBall = false
                if (FloatingBallService.canDrawOverlays(this)) {
                    floatSwitch.isChecked = FloatingBallService.start(this)
                } else {
                    floatSwitch.isChecked = false
                    Toast.makeText(this, getString(R.string.float_need_permission), Toast.LENGTH_LONG).show()
                }
            } else {
                // 悬浮球可能被收起（停止共享时自动收起，或通知栏收起），同步开关状态。
                floatSwitch.isChecked = FloatingBallService.visible()
            }
            audioSwitch.isChecked = !HostState.systemAudioMuted
        } finally {
            syncingSwitch = false
        }
    }

    override fun onDestroy() {
        HostState.removeListener(hostListener)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard.
        if (hasFocus && linkInput.text.isEmpty()) {
            val clip = service<ClipboardManager>().primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            extractLink(text)?.let { if (it !in recents()) linkInput.setText(it) }
        }
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            extractLink(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { watch(it) }
        }
    }

    // ---- watch ----

    private fun watch(raw: String) {
        val url = normalize(raw) ?: run {
            linkInput.error = getString(R.string.watch_link_invalid)
            return
        }
        val list = (listOf(url) + recents().filter { it != url }).take(8)
        prefs.edit().putString("recents", list.joinToString("\n")).apply()
        renderRecents()
        startActivity(
            Intent(this, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_MODE, WebActivity.MODE_WATCH)
                .putExtra(WebActivity.EXTRA_URL, url),
        )
    }

    private fun recents(): List<String> =
        prefs.getString("recents", "")!!.split("\n").filter { it.isNotBlank() }

    // ---- host ----

    private fun startHosting() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.RECORD_AUDIO) // phone audio + microphone in shares
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), REQUEST_HOST_PERMISSIONS)
            return
        }
        launchHost()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Hosting works without them; shares just won't include audio.
        if (requestCode == REQUEST_HOST_PERMISSIONS) launchHost()
    }

    private fun launchHost() {
        openHostWhenReady = true
        if (HostState.phase == HostState.Phase.RUNNING) {
            openHostPage()
        } else {
            PiikService.start(this)
        }
        renderHost()
    }

    private fun openHostPage() {
        openHostWhenReady = false
        startActivity(Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_MODE, WebActivity.MODE_HOST))
    }

    /**
     * 房间已在后台运行时点「开始共享」：不重新开一个，先问用户是进入现有房间还是取消。
     * launcher 页面检测到已有房间时本身就显示「进入」按钮（上游 LauncherForm 行为），
     * 这里只是把提示做明确，避免用户以为要重新建房间。
     */
    private fun confirmEnterRunningRoom() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.room_exists_title))
            .setMessage(getString(R.string.room_exists_message))
            .setPositiveButton(getString(R.string.room_exists_enter)) { _, _ -> openHostPage() }
            .setNegativeButton(getString(R.string.room_exists_cancel), null)
            .show()
    }

    /** 悬浮球开关：Android 6+ 悬浮窗权限无法静默授予，先引导授权，授权后再由 onResume 补开。 */
    private fun toggleFloatingBall(on: Boolean) {
        if (!on) {
            FloatingBallService.stop(this)
            floatSwitch.isChecked = false
            return
        }
        if (!FloatingBallService.canDrawOverlays(this)) {
            pendingFloatBall = true
            Toast.makeText(this, getString(R.string.float_need_permission), Toast.LENGTH_LONG).show()
            FloatingBallService.requestPermission(this)
            return
        }
        floatSwitch.isChecked = FloatingBallService.start(this)
    }

    /**
     * 首启引导「忽略电池优化」：共享是长时前台任务，被 Doze/电池优化冻结会断流。
     * 只问一次（prefs 记 battery_asked）；用户选「暂不」不再打扰，选「去允许」
     * 走系统对话框（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，非普通权限弹窗）。
     * API 23+ 才有该 intent；低版本直接跳过。
     */
    private fun maybePromptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < 23) return
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            // 已忽略（厂商白名单或用户此前设过）：记为已问过，不再弹。
            prefs.edit().putBoolean("battery_asked", true).apply()
            return
        }
        prefs.edit().putBoolean("battery_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_title))
            .setMessage(getString(R.string.battery_message))
            .setPositiveButton(getString(R.string.battery_allow)) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName")),
                    )
                }
            }
            .setNegativeButton(getString(R.string.battery_later), null)
            .show()
    }

    private fun renderHost() {
        when (HostState.phase) {
            HostState.Phase.STOPPED -> {
                hostStatus.text = getString(R.string.host_status_stopped)
                hostPrimary.text = getString(R.string.host_button_start)
                hostStop.visibility = View.GONE
            }
            HostState.Phase.STARTING -> {
                hostStatus.text = getString(R.string.host_status_starting)
                hostPrimary.text = getString(R.string.host_button_starting)
                hostStop.visibility = View.VISIBLE
            }
            HostState.Phase.RUNNING -> {
                hostStatus.text = getString(R.string.host_status_running) +
                    if (!NetInfo.onWifi(this)) getString(R.string.host_status_running_no_wifi) else ""
                hostPrimary.text = getString(R.string.host_button_open_page)
                hostStop.visibility = View.VISIBLE
                if (openHostWhenReady) openHostPage()
            }
            HostState.Phase.FAILED -> {
                hostStatus.text = HostState.message ?: getString(R.string.host_status_stopped_default)
                hostPrimary.text = getString(R.string.host_button_retry)
                hostStop.visibility = View.GONE
            }
        }
    }

    private fun saveDebugLog() {
        android.widget.Toast.makeText(this, getString(R.string.debug_log_collecting), android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val name = runCatching { Diagnostics.saveDebugLog(this) }.getOrNull()
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (name != null) getString(R.string.debug_log_saved, name) else getString(R.string.debug_log_failed),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    /**
     * 一键复制诊断信息：真机用户不会发 logcat，复制粘贴是唯一可行的反馈通道。
     * 内容尽量全（2026-10-27 用户要求「详细」）：版本/内核/ABI/屏幕参数/网络/
     * 房间深链/共享状态/引擎输出尾 3000 字——老内核故障与路由问题的关键线索都在里面。
     */
    private fun copyDiagnostics() {
        val wv = runCatching {
            val vi = android.webkit.WebView.getCurrentWebViewPackage()
            "${vi?.packageName} ${vi?.versionName}"
        }.getOrDefault("unknown")
        // BuildConfig 在 AGP 8 默认不生成（buildConfig=false），版本一律走 packageManager。
        val pkg = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val vCode = if (Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode?.toString() else @Suppress("DEPRECATION") pkg?.versionCode?.toString()
        val cfg = resources.configuration
        val text = buildString {
            appendLine("Piik Android richkobe 诊断")
            appendLine("应用版本: ${pkg?.versionName ?: "?"} (code $vCode)")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("WebView: $wv")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("屏幕: ${cfg.screenWidthDp}x${cfg.screenHeightDp}dp smallest=${cfg.smallestScreenWidthDp}dp density=${resources.displayMetrics.densityDpi}")
            appendLine("手表形态判定(v7a+小屏): ${Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a" && cfg.smallestScreenWidthDp < 300}")
            appendLine("WiFi: ${NetInfo.onWifi(this@MainActivity)} LAN: ${runCatching { NetInfo.lanAddresses(this@MainActivity) }.getOrDefault("?")}")
            appendLine("DNS: ${runCatching { NetInfo.dnsServers(this@MainActivity) }.getOrDefault("?")}")
            appendLine("共享状态: ${HostState.phase} 房间深链: ${HostState.roomPageUrl ?: "(无)"} 引擎页: ${HostState.pageUrl ?: "(无)"}")
            appendLine("---- 引擎最近输出(尾3000字) ----")
            append(HostState.logText().takeLast(3000))
        }
        service<ClipboardManager>().setPrimaryClip(ClipData.newPlainText("Piik 诊断", text))
        Toast.makeText(this, getString(R.string.diag_copied), Toast.LENGTH_LONG).show()
    }

    // ---- UI ----

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), LINE)
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = GradientDrawable().apply {
            setColor(if (primary) ACCENT else CARD2); cornerRadius = dp(12).toFloat()
        }
        stateListAnimator = null
        // 点击时缩一下再回弹，给按钮按下去的实感（不平铺）。
        setOnClickListener { UiFx.tapBounce(this) { onClick() } }
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    /** 一行开关：左侧标题（说明写在标题的括号里），右侧 Switch。 */
    private fun switchRow(title: String, initial: Boolean, onChange: (Boolean) -> Unit): Pair<View, Switch> {
        val sw = Switch(this).apply {
            isChecked = initial
            // syncingSwitch 为真时说明是 onResume 里用代码同步 UI 状态（不是用户点击），
            // 不应触发业务回调（否则会去 start/stop 悬浮球、改静音标志），只反映现状。
            setOnCheckedChangeListener { _, checked -> if (!syncingSwitch) onChange(checked) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(text(title, 14f, TEXT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sw)
        }
        return row to sw
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        // 标题行：左侧应用标题，右上角 GitHub 图标（点击打开本仓库）
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            text(getString(R.string.home_title), 24f, TEXT, bold = true),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        titleRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_github)
                // 层叠徽标：投影 + 渐变面板 + 描边 + 点击水波纹，不再平贴页面。
                background = getDrawable(R.drawable.ripple_github_badge)
                contentDescription = getString(R.string.github_repo_desc)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    UiFx.tapBounce(this) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO)))
                    }
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        column.addView(titleRow)
        column.addView(text(getString(R.string.home_subtitle), 14f, MUTED))
        column.addView(gap(22))

        val watch = card()
        watch.addView(text(getString(R.string.watch_card_title), 18f, TEXT, bold = true))
        watch.addView(gap(6))
        watch.addView(text(getString(R.string.watch_card_desc), 14f, MUTED))
        watch.addView(gap(12))
        linkInput = EditText(this).apply {
            hint = getString(R.string.watch_input_hint)
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(INPUT); cornerRadius = dp(10).toFloat(); setStroke(dp(1), LINE) }
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { watch(text.toString()); true } else false }
        }
        watch.addView(linkInput)
        watch.addView(gap(10))
        watch.addView(button(getString(R.string.watch_button), true) { watch(linkInput.text.toString()) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        watch.addView(recentBox)
        column.addView(watch)
        column.addView(gap(16))

        val host = card()
        host.addView(text(getString(R.string.host_card_title), 18f, TEXT, bold = true))
        host.addView(gap(6))
        hostStatus = text("", 14f, MUTED)
        host.addView(hostStatus)
        host.addView(gap(12))
        hostPrimary = button(getString(R.string.host_button_start), true) {
            // 房间已在后台跑：提示进入现有房间，而不是静默重开一个新的。
            if (HostState.phase == HostState.Phase.RUNNING) confirmEnterRunningRoom() else startHosting()
        }
        host.addView(hostPrimary, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        hostStop = button(getString(R.string.host_button_stop), false) { PiikService.stop(this) }
        host.addView(gap(8))
        host.addView(hostStop, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        host.addView(gap(8))
        host.addView(button(getString(R.string.host_button_debug_log), false) { saveDebugLog() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        host.addView(gap(8))
        // 诊断信息一键复制：真机出问题时用户不用会发日志，复制粘贴即可反馈。
        // 内容含 WebView 内核版本、引擎 stderr 尾部、应用版本——定位老内核故障的关键。
        host.addView(button(getString(R.string.diag_copy), false) { copyDiagnostics() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        host.addView(gap(10))

        // 系统声音开关：默认开（引擎共享屏幕时无条件采集系统声音）。关掉后
        // AudioSession 把系统声音 PCM 换成静音，麦克风与房间语音不受影响。
        val audioRow = switchRow(getString(R.string.audio_switch_label), initial = !HostState.systemAudioMuted) { checked ->
            HostState.systemAudioMuted = !checked
        }
        audioSwitch = audioRow.second
        host.addView(audioRow.first)

        // 悬浮球开关：常驻屏幕边缘的小球，在别的 App 里也能开始 / 停止共享。
        val floatRow = switchRow(getString(R.string.float_switch_label), initial = FloatingBallService.visible()) { checked ->
            toggleFloatingBall(checked)
        }
        floatSwitch = floatRow.second
        host.addView(floatRow.first)

        column.addView(host)
        column.addView(gap(22))

        // 许可证署名保留文字，但不做成链接：主页已有右上角 GitHub 徽标作为仓库入口，
        // 页脚再挂一个可点文字只是多余的点击区。
        column.addView(text(getString(R.string.footer_license), 12f, MUTED).apply {
            gravity = Gravity.CENTER
        })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(column, MATCH_PARENT, WRAP_CONTENT)
            fitsSystemWindows = true
        }
    }

    private fun renderRecents() {
        recentBox.removeAllViews()
        val list = recents()
        if (list.isEmpty()) return
        recentBox.addView(gap(14))
        recentBox.addView(text(getString(R.string.recent_label), 12f, MUTED, bold = true))
        for (url in list) {
            recentBox.addView(text(Uri.parse(url).host ?: url, 15f, ACCENT).apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { UiFx.tapBounce(this) { watch(url) } }
                setOnLongClickListener {
                    prefs.edit().putString("recents", recents().filter { it != url }.joinToString("\n")).apply()
                    renderRecents()
                    true
                }
            })
        }
    }

    companion object {
        private const val REQUEST_HOST_PERMISSIONS = 1
        /** 右上角 GitHub 图标打开的仓库（本移植版）。 */
        private const val GITHUB_REPO = "https://github.com/yibanm/Piik-android-richkobe"
        private val BG = Color.parseColor("#0B0C10")
        private val CARD = Color.parseColor("#15171E")
        private val CARD2 = Color.parseColor("#1E2130")
        private val INPUT = Color.parseColor("#0F1116")
        private val LINE = Color.parseColor("#262A36")
        private val TEXT = Color.parseColor("#E9ECF5")
        private val MUTED = Color.parseColor("#8C93A3")
        private val ACCENT = Color.parseColor("#6E8BFF")

        private val LINK = Regex("""(https?://[^\s<>"']+)|((?:\d{1,3}\.){3}\d{1,3}:\d{2,5}[^\s<>"']*)""")

        fun extractLink(text: String?): String? = text?.let { LINK.find(it)?.value }

        /** Accepts full links, bare hosts and LAN "ip:port" invites. */
        fun normalize(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            val withScheme = when {
                t.startsWith("http://") || t.startsWith("https://") -> t
                Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""").matches(t) -> "http://$t" // Local rooms are plain HTTP
                t.contains('.') && !t.contains(' ') -> "https://$t"
                else -> return null
            }
            val uri = Uri.parse(withScheme)
            return if (uri.host.isNullOrBlank()) null else withScheme
        }
    }
}
