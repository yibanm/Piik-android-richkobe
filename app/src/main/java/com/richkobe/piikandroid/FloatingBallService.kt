package com.richkobe.piikandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮球：一个小圆球常驻屏幕边缘，点击展开菜单，用来开始 / 停止共享。
 *
 * 为什么用独立的前台服务而不是挂在 [PiikService] 上：悬浮球要能在**共享开始前**
 * 就把用户带进共享页（PiikService 那时还没起）。没有前台服务的话，App 退到后台
 * 被系统回收，悬浮球会跟着消失。
 *
 * 「开始共享」为什么是把 App 拉回前台、而不是直接开播：开播的入口是主机页里的
 * `host-start-share` 按钮，点击后要经过 web 前端 → 引擎 → 采集服务才弹系统授权框。
 * 引擎没有对外的「直接开播」接口，而后台 WebView 会被 Android 节流甚至回收，
 * 注入 JS 去点那个按钮不可靠。拉回前台让用户自己点，是唯一保证可用的路径。
 */
class FloatingBallService : Service() {
    private var windowManager: WindowManager? = null
    private var ball: View? = null
    /** 球的窗口参数，供菜单定位读取真实 y（球无父布局，View.getY() 恒为 0，不能用）。 */
    private var ballWindowParams: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private val hostListener: () -> Unit = { refreshMenu() }
    /** 延迟收起悬浮球用；Service 没有 runOnUiThread（那是 Activity 的），需要主线程 Handler。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        if (!goForeground()) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission revoked while running")
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBall()
        HostState.addListener(hostListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) hide()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        HostState.removeListener(hostListener)
        // stopSharing 用 mainHandler 延迟收起悬浮球；服务先被销毁时要撤掉这个待执行
        // 任务，否则它会继续持有本服务引用并在销毁后触发 hide()。
        mainHandler.removeCallbacksAndMessages(null)
        removeViews()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- 悬浮球 ----

    private fun addBall() {
        val size = dp(BALL_DP)
        val view = TextView(this).apply {
            text = "P"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0.82f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_BG)
                setStroke(dp(1), Color.parseColor("#7A8BA8"))
            }
        }
        val params = ballParams(size)
        // 上次收起时的位置，默认贴右边、垂直居中偏上。
        params.x = if (stickLeft) 0 else edgeInset()
        params.y = lastY.takeIf { it >= 0 } ?: dp(140)
        view.setOnTouchListener(Drag(params, size))
        try {
            windowManager?.addView(view, params)
            ball = view
            ballWindowParams = params
        } catch (e: Exception) {
            Log.e(TAG, "add ball failed", e)
            stopSelf()
        }
    }

    private fun ballParams(size: Int) = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 不抢焦点，否则会挡住游戏/视频里的输入。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** 拖动 + 松手吸附到左右边；位移小于阈值算点击，展开菜单。 */
    private inner class Drag(private val params: WindowManager.LayoutParams, private val size: Int) : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var originX = 0
        private var originY = 0
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) moved = true
                    if (moved) {
                        params.x = (originX + dx).coerceAtLeast(0)
                        params.y = (originY + dy).coerceAtLeast(0)
                        dismissMenu()
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleMenu(view)
                    } else {
                        // 吸附到更近的一侧边缘。
                        stickLeft = params.x + size / 2 < screenWidth() / 2
                        params.x = if (stickLeft) 0 else edgeInset()
                        lastY = params.y
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    return true
                }
            }
            return false
        }
    }

    // ---- 菜单 ----

    private fun toggleMenu(anchor: View) {
        if (menu != null) { dismissMenu(); return }
        showMenu(anchor)
    }

    private fun showMenu(anchor: View) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(MENU_BG); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4056"))
            }
        }
        addMenuItems(panel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE 是必须的，两个理由：① 可聚焦窗口会抢走底下游戏/视频的
            // 输入焦点，悬浮球就不该打扰前台应用；② FLAG_WATCH_OUTSIDE_TOUCH 只在
            // 窗口不可聚焦时才会投递 ACTION_OUTSIDE，否则「点外部收起菜单」会失效。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (stickLeft) dp(BALL_DP + 8) else screenWidth() - dp(BALL_DP + 8) - dp(MENU_WIDTH_DP)
            // 用球的窗口 y 定位菜单（球挂在 WindowManager 上无父布局，View.getY() 恒为 0）。
            val ballY = ballWindowParams?.y ?: dp(140)
            y = (ballY - dp(4)).coerceAtLeast(dp(8))
        }
        panel.setOnTouchListener { _, event ->
            // FLAG_WATCH_OUTSIDE_TOUCH：点在菜单外只收到这一个 ACTION_OUTSIDE。
            if (event.action == MotionEvent.ACTION_OUTSIDE) { dismissMenu(); true } else false
        }
        try {
            windowManager?.addView(panel, params)
            menu = panel
        } catch (e: Exception) {
            Log.e(TAG, "add menu failed", e)
        }
    }

    private fun addMenuItems(panel: LinearLayout) {
        val running = HostState.phase == HostState.Phase.RUNNING
        val starting = HostState.phase == HostState.Phase.STARTING
        // 标题：当前共享状态，让用户不必回 App 也知道在不在播。
        panel.addView(menuLabel(if (running) getString(R.string.float_state_sharing) else getString(R.string.float_state_idle)))
        if (running) {
            panel.addView(menuButton(getString(R.string.float_open_host), ACCENT) { openHostPage() })
            panel.addView(menuButton(getString(R.string.float_stop), DANGER) { stopSharing() })
        } else {
            panel.addView(menuButton(
                if (starting) getString(R.string.float_starting) else getString(R.string.float_start),
                ACCENT,
                enabled = !starting,
            ) { openApp() })
        }
        panel.addView(menuButton(getString(R.string.float_hide), NEUTRAL) { hide() })
    }

    /**
     * 状态变化（开始/停止共享）时刷新菜单内容，前提是菜单正开着。
     * [HostState.update] 用主线程 Handler 通知监听者（与 MainActivity.renderHost
     * 同一契约），所以这里直接操作悬浮窗视图即可，不必再切线程。
     */
    private fun refreshMenu() {
        val anchor = ball ?: return
        if (menu == null) return
        dismissMenu()
        showMenu(anchor)
    }

    private fun menuLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(2), dp(6), dp(8))
    }

    private fun menuButton(label: String, color: Int, enabled: Boolean = true, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            alpha = if (enabled) 1f else 0.45f
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(MENU_WIDTH_DP - 16), dp(40)).apply {
                topMargin = dp(4)
            }
            if (enabled) setOnClickListener { dismissMenu(); onClick() }
        }

    private fun dismissMenu() {
        menu?.let { runCatching { windowManager?.removeView(it) } }
        menu = null
    }

    // ---- 动作 ----

    /** 拉回前台让用户自己点「开始共享」——唯一可靠的路径，理由见类注释。 */
    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private fun openHostPage() {
        startActivity(
            Intent(this, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_MODE, WebActivity.MODE_HOST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private fun stopSharing() {
        PiikService.stop(this)
        // 用户要求「停止共享之后把悬浮球收了」。延迟一点，让 Toast 有机会显示。
        // 用 mainHandler 而不是 View.postDelayed：后者依赖球的 attach 状态，
        // 球若已分离则收起动作不会执行，悬浮球会残留在屏幕上。
        mainHandler.postDelayed({ hide() }, 200)
    }

    private fun hide() {
        stopSelf()
    }

    private fun removeViews() {
        dismissMenu()
        ball?.let { runCatching { windowManager?.removeView(it) } }
        ball = null
    }

    // ---- 尺寸 / 前台服务 ----

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    /** 贴右边时留出状态栏/圆角余量，别让球被切掉。 */
    private fun edgeInset(): Int = (screenWidth() - dp(BALL_DP)).coerceAtLeast(0)

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.float_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.float_channel_desc)
            },
        )
    }

    private fun goForeground(): Boolean {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hide = PendingIntent.getService(
            this, 1, Intent(this, FloatingBallService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.float_notification_title))
            .setContentText(getString(R.string.float_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.float_hide), hide).build())
            .build()
        return try {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "PiikFloat"
        private const val CHANNEL = "floating-ball"
        private const val NOTIFICATION_ID = 2
        const val ACTION_HIDE = "com.richkobe.piikandroid.HIDE_FLOAT"

        // 小球直径：40dp 够点按又不挡画面。菜单宽度取球径的 3 倍出头，一行放得下四个汉字。
        private const val BALL_DP = 40
        private const val MENU_WIDTH_DP = 132
        private const val TOUCH_SLOP = 8

        private val BALL_BG = Color.parseColor("#CC15171E")
        private val MENU_BG = Color.parseColor("#F215171E")
        private val ACCENT = Color.parseColor("#6E8BFF")
        private val DANGER = Color.parseColor("#C0524E")
        private val NEUTRAL = Color.parseColor("#2A2F42")
        private val MUTED = Color.parseColor("#8C93A3")

        // 记忆上次停靠位置，收起再打开时不必重新拖。
        private var stickLeft = false
        private var lastY = -1

        @Volatile var instance: FloatingBallService? = null
            private set

        fun visible(): Boolean = instance != null

        /** 没有悬浮窗权限时返回 false，调用方负责引导用户去系统设置授权。 */
        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            context.startForegroundService(Intent(context, FloatingBallService::class.java))
            return true
        }

        fun stop(context: Context) {
            // 服务没在跑就直接返回：startService 会先 onCreate（把球加到屏幕上）
            // 再收到 ACTION_HIDE 停掉，球会闪一下。
            if (instance == null) return
            context.startService(Intent(context, FloatingBallService::class.java).setAction(ACTION_HIDE))
        }

        /** 跳系统「显示在其他应用上层」授权页；Android 6+ 该权限无法静默授予。 */
        fun requestPermission(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
