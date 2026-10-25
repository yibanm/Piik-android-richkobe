package com.richkobe.piikandroid

import android.Manifest
import android.app.Activity
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
import android.widget.TextView

/** Home: watch an invite link, or host from this phone. */
class MainActivity : Activity() {
    private lateinit var linkInput: EditText
    private lateinit var recentBox: LinearLayout
    private lateinit var hostStatus: TextView
    private lateinit var hostPrimary: Button
    private lateinit var hostStop: Button
    private var openHostWhenReady = false

    private val prefs by lazy { getSharedPreferences("piik", MODE_PRIVATE) }
    private val hostListener: () -> Unit = { renderHost() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleShare(intent)
        HostState.addListener(hostListener)
        renderHost()
        renderRecents()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
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
            if (HostState.phase == HostState.Phase.RUNNING) openHostPage() else startHosting()
        }
        host.addView(hostPrimary, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        hostStop = button(getString(R.string.host_button_stop), false) { PiikService.stop(this) }
        host.addView(gap(8))
        host.addView(hostStop, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        host.addView(gap(8))
        host.addView(button(getString(R.string.host_button_debug_log), false) { saveDebugLog() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
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
