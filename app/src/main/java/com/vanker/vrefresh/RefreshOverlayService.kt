package com.vanker.vrefresh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * User-controlled floating display-Hz indicator.
 * The value is provided by Android DisplayManager; it is NOT a game FPS meter.
 * Moves within the available display; position and opacity survive service restarts.
 */
class RefreshOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.vanker.vrefresh.START_OVERLAY"
        const val ACTION_STOP = "com.vanker.vrefresh.STOP_OVERLAY"
        const val ACTION_UPDATE_SETTINGS = "com.vanker.vrefresh.UPDATE_OVERLAY_SETTINGS"
        const val ACTION_RESET_POSITION = "com.vanker.vrefresh.RESET_OVERLAY_POSITION"
        const val PREFS = "overlay_state"
        const val KEY_RUNNING = "running"
        const val KEY_ALPHA_PERCENT = "alpha_percent"
        private const val KEY_HAS_POSITION = "has_position"
        private const val KEY_X_RATIO = "x_ratio"
        private const val KEY_Y_RATIO = "y_ratio"
        const val DEFAULT_ALPHA_PERCENT = 90
        const val MIN_ALPHA_PERCENT = 35
        private const val OVERLAY_WIDTH_DP = 196
        private const val CHANNEL = "vrefresh_monitor"
        private const val NOTICE_ID = 108

        @Volatile var isRunning: Boolean = false
            private set

        fun clampAlpha(value: Int) = value.coerceIn(MIN_ALPHA_PERCENT, 100)
    }

    private lateinit var wm: WindowManager
    private lateinit var dm: DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var hzValue: TextView? = null
    private var opacityButton: TextView? = null
    private var running = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private fun t(cn: String, en: String) = if (LocaleChoice.isChinese(this)) cn else en
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SETTINGS -> {
                if (running) applyOpacity()
            }
            ACTION_RESET_POSITION -> {
                prefs.edit().remove(KEY_HAS_POSITION).remove(KEY_X_RATIO).remove(KEY_Y_RATIO).apply()
                if (running) resetPosition()
            }
            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!running) {
                    try {
                        createNotification()
                        showOverlay()
                        running = true
                        isRunning = true
                        prefs.edit().putBoolean(KEY_RUNNING, true).apply()
                        handler.post(update)
                    } catch (_: Exception) {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, t("高刷工具悬浮监控", "HighHz floating monitor"), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = t("显示 Android 系统报告的屏幕刷新率", "Displays OS-reported refresh rate")
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.vrefresh_notification)
            .setContentTitle(t("高刷工具 · 悬浮监控运行中", "HighHz · Floating monitor"))
            .setContentText(t("按住悬浮窗拖动；可调透明度", "Drag the overlay; adjust opacity"))
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTICE_ID, notification)
        }
    }

    private fun showOverlay() {
        val background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(242, 15, 23, 42))
            setStroke(dp(1), Color.argb(80, 148, 163, 184))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.background = background
            setPadding(dp(12), dp(8), dp(10), dp(11))
            elevation = dp(6).toFloat()
            isClickable = true
            contentDescription = t("高刷悬浮窗，按住移动", "Refresh-rate overlay, drag to move")
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(this).apply {
            text = t("≡ 按住移动", "≡ Drag")
            textSize = 11f
            setTextColor(Color.rgb(203, 213, 225))
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = t("按住这里移动悬浮窗", "Drag here to move overlay")
            setPadding(0, dp(2), dp(2), dp(2))
        }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(34), 1f))
        opacityButton = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(125, 211, 252))
            gravity = Gravity.CENTER
            contentDescription = t("调整悬浮窗透明度", "Change overlay opacity")
            setOnClickListener { cycleOpacity() }
        }
        header.addView(opacityButton, LinearLayout.LayoutParams(dp(44), dp(34)))
        val close = TextView(this).apply {
            text = "×"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = t("关闭悬浮监控", "Close monitor")
            setOnClickListener { stopSelf() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(header)

        hzValue = TextView(this).apply {
            text = "-- Hz"
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(hzValue)
        val subtitle = TextView(this).apply {
            text = t("屏幕 Hz · 系统报告值", "OS-reported display Hz")
            textSize = 10f
            setTextColor(Color.rgb(148, 163, 184))
        }
        root.addView(subtitle)
        val fps = TextView(this).apply {
            text = t("游戏 FPS  —  未读取", "Game FPS  —  unavailable")
            textSize = 12f
            setTextColor(Color.rgb(125, 211, 252))
            setPadding(0, dp(7), 0, 0)
        }
        root.addView(fps)

        params = WindowManager.LayoutParams(
            dp(OVERLAY_WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = currentOpacity() / 100f
            x = dp(12)
            y = dp(110)
        }
        val dragHandler = makeDragListener()
        // Any non-control portion of the panel is a generous drag surface.
        // Opacity and close controls retain independent click targets.
        root.setOnTouchListener(dragHandler)
        handle.setOnTouchListener(dragHandler)
        hzValue?.setOnTouchListener(dragHandler)
        subtitle.setOnTouchListener(dragHandler)
        fps.setOnTouchListener(dragHandler)

        wm.addView(root, params)
        view = root
        root.post { restorePosition() }
        updateOpacityLabel()
    }

    private fun currentOpacity(): Int = clampAlpha(prefs.getInt(KEY_ALPHA_PERCENT, DEFAULT_ALPHA_PERCENT))

    private fun updateOpacityLabel() {
        opacityButton?.text = "${currentOpacity()}%"
    }

    private fun applyOpacity() {
        val root = view ?: return
        val lp = params ?: return
        lp.alpha = currentOpacity() / 100f
        updateOpacityLabel()
        safelyUpdate(root, lp)
    }

    private fun cycleOpacity() {
        val value = currentOpacity()
        val next = intArrayOf(100, 85, 70, 55, 40).firstOrNull { it < value } ?: 100
        prefs.edit().putInt(KEY_ALPHA_PERCENT, next).apply()
        applyOpacity()
    }

    @Suppress("DEPRECATION")
    private fun limits(): Pair<Int, Int> {
        val point = Point()
        wm.defaultDisplay.getSize(point)
        val width = params?.width?.takeIf { it > 0 } ?: dp(OVERLAY_WIDTH_DP)
        val height = view?.height?.takeIf { it > 0 } ?: dp(133)
        return (point.x - width).coerceAtLeast(0) to (point.y - height).coerceAtLeast(0)
    }

    private fun safelyUpdate(root: View, lp: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(root, lp) } catch (_: IllegalArgumentException) { }
    }

    private fun setPosition(x: Int, y: Int) {
        val root = view ?: return
        val lp = params ?: return
        val (maxX, maxY) = limits()
        lp.x = x.coerceIn(0, maxX)
        lp.y = y.coerceIn(0, maxY)
        safelyUpdate(root, lp)
    }

    private fun restorePosition() {
        val (maxX, maxY) = limits()
        if (prefs.getBoolean(KEY_HAS_POSITION, false)) {
            val xRatio = prefs.getFloat(KEY_X_RATIO, .03f).coerceIn(0f, 1f)
            val yRatio = prefs.getFloat(KEY_Y_RATIO, .15f).coerceIn(0f, 1f)
            setPosition((xRatio * maxX).roundToInt(), (yRatio * maxY).roundToInt())
        } else {
            resetPosition()
        }
    }

    private fun resetPosition() {
        setPosition(dp(12), dp(110))
    }

    private fun savePosition() {
        val lp = params ?: return
        val (maxX, maxY) = limits()
        prefs.edit()
            .putBoolean(KEY_HAS_POSITION, true)
            .putFloat(KEY_X_RATIO, if (maxX == 0) 0f else lp.x.toFloat() / maxX)
            .putFloat(KEY_Y_RATIO, if (maxY == 0) 0f else lp.y.toFloat() / maxY)
            .apply()
    }

    private fun makeDragListener(): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = params ?: return@OnTouchListener false
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        setPosition(startX + dx, startY + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) savePosition()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) savePosition()
                    true
                }
                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.post { restorePosition() }
    }

    private val update = object : Runnable {
        override fun run() {
            if (!running) return
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val hz = display?.refreshRate?.takeIf { it.isFinite() && it > 1f }
                ?: display?.mode?.refreshRate?.takeIf { it.isFinite() && it > 1f }
            hzValue?.text = if (hz != null) {
                val rounded = kotlin.math.round(hz)
                val number = if (abs(hz - rounded) < .15f) {
                    rounded.toInt().toString()
                } else String.format(Locale.US, "%.1f", hz)
                "$number Hz"
            } else "-- Hz"
            handler.postDelayed(this, 600L)
        }
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        handler.removeCallbacks(update)
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null
        params = null
        hzValue = null
        opacityButton = null
        prefs.edit().putBoolean(KEY_RUNNING, false).apply()
        super.onDestroy()
    }
}
