package com.vanker.vrefresh

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Independent Apple-inspired Android UI: no copied screens or assets. */
class MainActivity : Activity() {
    private lateinit var refreshController: RefreshController
    private val brand by lazy { BrandSupport.current() }
    private val loop = Handler(Looper.getMainLooper())
    private var tab = 0
    private var selectedHz: Float? = null
    private var currentHzView: TextView? = null
    private var statusView: TextView? = null
    private var monitorView: TextView? = null
    private var lastState: String? = null
    private var oppoGlobal: Boolean = true
    @Volatile private var oppoBusy = false
    private var oppoTargetPackage: String? = null
    private var oppoTargetLabel: String? = null
    private val vendorPrefs by lazy { getSharedPreferences("vendor_rate_requests", Context.MODE_PRIVATE) }
    private var snapshot: RefreshController.Snapshot? = null
    private val zh get() = LocaleChoice.isChinese(this)
    private fun t(cn: String, en: String) = if (zh) cn else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshController = RefreshController(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::refreshController.isInitialized) refreshValues()
        loop.removeCallbacks(tick)
        loop.post(tick)
    }
    override fun onPause() { loop.removeCallbacks(tick); super.onPause() }
    private val tick = object : Runnable {
        override fun run() {
            refreshValues()
            loop.postDelayed(this, 1200L)
        }
    }

    private fun color(id: Int): Int = getColor(id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun shape(fill: Int, radius: Int = 22, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }
    private fun panel(radius: Int = 22) = shape(color(R.color.panel), radius, color(R.color.stroke))
    private fun body(size: Float, bold: Boolean = false, secondary: Boolean = false, value: String = ""): TextView =
        TextView(this).apply {
            text = value; textSize = size
            setTextColor(color(if (secondary) R.color.text_secondary else R.color.text_primary))
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun margined(top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }
    private fun addText(parent: LinearLayout, value: String, size: Float, bold: Boolean = false,
                        secondary: Boolean = false, top: Int = 0, bottom: Int = 0): TextView =
        body(size, bold, secondary, value).also { parent.addView(it, margined(top, bottom)) }
    private fun card(padding: Int = 20): LinearLayout = vertical().apply {
        background = panel()
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color(if (primary) R.color.accent_text else R.color.chip_text))
        background = shape(color(if (primary) R.color.accent else R.color.chip), 16)
        minHeight = dp(52)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }
    private fun subheading(value: String): TextView = body(19f, true, value = value)
    private fun fmt(hz: Float?): String = if (hz == null) "—" else
        if (abs(hz - hz.roundToInt()) < .2f) hz.roundToInt().toString()
        else String.format(Locale.US, "%.1f", hz)

    private fun render() {
        snapshot = refreshController.snapshot()
        val root = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val page = vertical().apply { setPadding(dp(20), dp(16), dp(20), dp(40)) }
        // Tablet: center the content at a comfortable reading width instead of
        // stretching cards and footnotes across the entire screen.
        val screenDp = resources.configuration.screenWidthDp
        val maxPageDp = if (screenDp >= 600) (screenDp - 36).coerceAtMost(760) else screenDp
        scroll.addView(page, FrameLayout.LayoutParams(
            if (screenDp >= 600) dp(maxPageDp) else -1,
            -2, Gravity.CENTER_HORIZONTAL
        ))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                scroll.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
        window.statusBarColor = color(R.color.bg)
        window.navigationBarColor = color(R.color.bg)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        renderHeader(page)
        renderTabs(page)
        when (tab) {
            0 -> renderHome(page)
            1 -> renderBrands(page)
            else -> renderTools(page)
        }
        addText(page, t("安卓高刷工具 · 独立设计 · 无广告", "Android Refresh Rate Tool · Original · No ads"),
            11f, secondary = true, top = 26).gravity = Gravity.CENTER
        refreshValues()
    }
    private fun renderHeader(page: LinearLayout) {
        val row = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        // Use the SAME original black-on-white app artwork as the launcher icon.
        // Do not substitute a text "V" or a generic placeholder.
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.vrefresh_header_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = shape(0xFFFFFFFF.toInt(), 13, color(R.color.stroke))
            clipToOutline = true
            contentDescription = t("安卓高刷工具图标", "Android Refresh Rate Tool icon")
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val header = vertical().apply { setPadding(dp(12), 0, 0, 0) }
        addText(header, t("安卓高刷工具", "HighHz"), 21f, true)
        addText(header, "ANDROID REFRESH RATE TOOL  /  0.6.6 BETA", 10f, secondary = true, top = 4)
        row.addView(header, LinearLayout.LayoutParams(0, -2, 1f))
        val language = button(if (zh) "EN" else "中文") { chooseLanguage() }.apply {
            textSize = 13f; minHeight = dp(38); setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        row.addView(language, LinearLayout.LayoutParams(-2, dp(38)))
        page.addView(row, margined(bottom = 26))
    }
    private fun chooseLanguage() {
        val values = arrayOf(t("跟随系统", "Follow system"), "简体中文", "English")
        val options = arrayOf("system", "zh", "en")
        val checked = options.indexOf(LocaleChoice.option(this)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(t("界面语言", "Language"))
            .setSingleChoiceItems(values, checked) { dialog, idx ->
                val chosen = options[idx]
                dialog.dismiss()
                LocaleChoice.update(this, chosen)
                // Android 13+ may already recreate the activity when its locale changes.
                window.decorView.post { if (!isFinishing) recreate() }
            }.setNegativeButton(t("取消", "Cancel"), null).show()
    }
    private fun renderTabs(page: LinearLayout) {
        val labels = arrayOf(t("控制", "Control"), t("品牌适配", "Brands"), t("监控与诊断", "Tools"))
        val bg = horizontal().apply {
            background = shape(color(R.color.chip), 17)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        labels.forEachIndexed { idx, title ->
            val chosen = idx == tab
            val item = body(13f, chosen, value = title).apply {
                gravity = Gravity.CENTER
                setTextColor(color(if (chosen) R.color.text_primary else R.color.text_secondary))
                background = if (chosen) panel(13) else shape(color(R.color.chip), 13)
                setPadding(dp(6), dp(12), dp(6), dp(12))
                isClickable = true
                setOnClickListener { tab = idx; render() }
            }
            bg.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(bg, margined(bottom = 18))
    }
    private fun renderHome(page: LinearLayout) {
        val s = snapshot ?: return
        val hero = vertical().apply {
            background = shape(color(R.color.hero), 26, color(R.color.hero_border))
            setPadding(dp(21), dp(22), dp(18), dp(22))
        }
        val tag = addText(hero, t("当前屏幕刷新率", "CURRENT DISPLAY RATE"), 11f, true, secondary = true)
        tag.letterSpacing = .12f
        val center = horizontal()
        val numbers = vertical()
        val hz = body(48f, true, value = fmt(s.activeRate)).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(color(R.color.text_primary))
        }
        currentHzView = hz
        val numberRow = horizontal()
        numberRow.addView(hz)
        val unit = body(21f, secondary = true, value = "Hz").apply { setPadding(dp(6), dp(18), 0, 0) }
        numberRow.addView(unit)
        numbers.addView(numberRow)
        addText(numbers, BrandSupport.deviceLabel(), 12f, secondary = true, top = 8)
        center.addView(numbers, LinearLayout.LayoutParams(0, -2, 1f))
        val maxHz = s.rates.maxOrNull()?.coerceAtLeast(60f) ?: 120f
        center.addView(ModeRingView(this, maxHz, s.activeRate ?: 0f), LinearLayout.LayoutParams(dp(98), dp(98)))
        hero.addView(center, margined(top = 10))
        val line = View(this).apply { setBackgroundColor(color(R.color.hero_border)) }
        hero.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(14) })
        val summary = horizontal()
        summary.addView(body(12f, secondary = true, value = t("设备最高", "DISPLAY MAX")))
        summary.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        summary.addView(body(14f, true, value = "${fmt(maxHz)} Hz"))
        hero.addView(summary)
        page.addView(hero, margined(bottom = 18))

        val badge = card(18)
        val brandRow = horizontal()
        val brandName = vertical()
        addText(brandName, BrandSupport.currentBrandLabel(), 16f, true)
        addText(brandName, t("${Build.MODEL} · 厂商接口适配状态", "${Build.MODEL} · vendor adapter status"), 12f, secondary = true, top = 5)
        brandRow.addView(brandName, LinearLayout.LayoutParams(0, -2, 1f))
        val active = brand.id == "vivo" || brand.id == "oppo"
        val status = body(11f, true, value = if (brand.id == "vivo") t("RMS 测试", "RMS beta") else if (brand.id == "oppo") t("屏幕模式 · 实验中", "Screen mode · beta") else t("通用接口", "Public API")).apply {
            gravity = Gravity.CENTER
            setTextColor(color(if (active) R.color.success else R.color.text_secondary))
            background = shape(color(R.color.chip), 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        brandRow.addView(status)
        badge.addView(brandRow)
        page.addView(badge, margined(bottom = 18))

        val rateCard = card()
        addText(rateCard, t("选择刷新率", "Choose refresh rate"), 19f, true)
        addText(rateCard, t("仅显示设备报告的可用档位", "Only rates exposed by your device are shown"), 12f, secondary = true, top = 6, bottom = 17)
        val rates = s.rates.asReversed().filter { it > 1f }
        if (selectedHz == null || rates.none { abs(it - selectedHz!!) < .4f }) selectedHz = rates.firstOrNull()
        if (rates.isEmpty()) {
            addText(rateCard, t("系统未公开可选择的模式", "No public modes reported"), 13f, secondary = true)
        } else {
            rates.chunked(3).forEach { group ->
                val row = horizontal()
                for (i in 0..2) {
                    val rate = group.getOrNull(i)
                    if (rate == null) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(49), 1f)); continue }
                    val selected = abs((selectedHz ?: 0f) - rate) < .4f
                    val chip = button("${fmt(rate)} Hz", selected) {
                        selectedHz = rate; render()
                    }.apply { textSize = 13f; minHeight = dp(48); setPadding(0, dp(10), 0, dp(10)) }
                    row.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(2), dp(3), dp(2), dp(3))
                    })
                }
                rateCard.addView(row)
            }
        }
        if (brand.id == "oppo") {
            addText(rateCard, t("控制范围", "Scope"), 16f, true, top = 15, bottom = 10)
            val choice = horizontal()
            val allButton = button(t("全局应用（默认）", "All apps (default)"), oppoGlobal) {
                oppoGlobal = true; render()
            }
            val oneButton = button(t("指定应用", "One app"), !oppoGlobal) {
                oppoGlobal = false; render()
            }
            choice.addView(allButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(5) })
            choice.addView(oneButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(5) })
            rateCard.addView(choice)
            if (oppoGlobal) {
                val armedCount = vendorPrefs.getStringSet("oplus_armed_packages", emptySet())?.size ?: 0
                addText(rateCard, t("将逐个为手机中可读取的已安装应用请求高刷（包含部分系统组件），不等于底层硬锁整机。已记录 $armedCount 个请求。",
                    "Requests high rate for installed packages, including some system components. This is not a device-wide physical lock. $armedCount saved requests."),
                    12f, secondary = true, top = 9)
            } else {
                val target = oppoTargetLabel?.let { "$it  ($oppoTargetPackage)" }
                    ?: t("尚未选择目标应用", "No target app selected")
                addText(rateCard, target, 12f, secondary = true, top = 8)
                rateCard.addView(button(t("选择应用", "Choose an app")) { chooseOplusApp() }, margined(top = 10))
            }
        }
        val actionLabel = when (brand.id) {
            "vivo" -> t("尝试 RMS 高刷控制", "Try RMS control")
            "oppo" -> if (oppoGlobal) t("一键应用到全部应用", "Apply to all installed apps") else t("应用到指定 App", "Apply to selected app")
            else -> t("尝试系统刷新率偏好", "Request system preference")
        }
        rateCard.addView(button(actionLabel, true) { selectedHz?.let { applySelection(it) } }, margined(top = 17))
        rateCard.addView(button(t("撤销本应用请求 / 恢复自动", "Withdraw app requests / restore auto")) { restoreAutomatic() }, margined(top = 10))
        statusView = addText(rateCard, lastState ?: t("尚未提交刷新率请求", "No refresh request submitted"), 12f,
            secondary = true, top = 14)
        page.addView(rateCard, margined(bottom = 18))

        val tip = card(18)
        addText(tip, t("注意", "Please note"), 15f, true)
        addText(tip, t("屏幕 Hz 不等于游戏 FPS。厂商高刷、温控、省电和应用设置都可能影响实际效果。",
            "Display Hz is not game FPS. Vendor policy, thermals, battery saver and app settings can affect the actual mode."),
            12f, secondary = true, top = 8)
        page.addView(tip)
    }
    private fun renderBrands(page: LinearLayout) {
        addText(page, t("品牌兼容性", "Brand compatibility"), 25f, true, bottom = 7)
        addText(page, t("统一界面，独立适配。支持基础检测不等于破解厂商限制。",
            "One interface, separate adapters. Detection does not mean a vendor cap is bypassed."),
            13f, secondary = true, bottom = 20)
        BrandSupport.profiles.forEach { item ->
            val card = card(18)
            val row = horizontal()
            val text = vertical()
            addText(text, item.display, 15f, true)
            val isCurrent = brand.id == item.id
            if (isCurrent) addText(text, t("● 当前设备", "● This device"), 11f, secondary = true, top = 5)
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val stateText = when (item.level) {
                SupportLevel.EXPERIMENTAL -> if (item.id == "vivo") t("RMS 实验性", "RMS beta") else t("OPlus 实验性", "OPlus beta")
                SupportLevel.PUBLIC_API -> t("通用检测", "Public API")
                SupportLevel.DIAGNOSTICS -> t("待适配", "Pending")
            }
            row.addView(body(11f, true, value = stateText).apply {
                setTextColor(color(if (item.level == SupportLevel.EXPERIMENTAL) R.color.success else R.color.text_secondary))
                background = shape(color(R.color.chip), 12)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
            card.addView(row)
            addText(card, if (zh) item.detailZh else item.detailEn, 12f, secondary = true, top = 13)
            page.addView(card, margined(bottom = 10))
        }
    }
    private fun renderTools(page: LinearLayout) {
        addText(page, t("监控与诊断", "Monitor & diagnostics"), 25f, true, bottom = 8)
        addText(page, t("跨应用查看屏幕刷新率，检查设备能力并导出诊断信息。",
            "Watch display refresh rate across apps and export a compatibility report."),
            13f, secondary = true, bottom = 18)
        val overlay = card()
        addText(overlay, t("屏幕 Hz 悬浮监控", "Floating Hz monitor"), 18f, true)
        addText(overlay, t("按住悬浮窗标题或数字拖动位置；也可点击悬浮窗上的百分比快速调节透明度。",
            "Drag the overlay by its header or number. Tap its percentage to cycle opacity."),
            12f, secondary = true, top = 7, bottom = 12)
        val opacityPrefs = getSharedPreferences(RefreshOverlayService.PREFS, Context.MODE_PRIVATE)
        val currentOpacity = RefreshOverlayService.clampAlpha(opacityPrefs.getInt(
            RefreshOverlayService.KEY_ALPHA_PERCENT, RefreshOverlayService.DEFAULT_ALPHA_PERCENT))
        val opacityRow = horizontal()
        opacityRow.addView(body(14f, true, value = t("悬浮窗透明度", "Overlay opacity")),
            LinearLayout.LayoutParams(0, -2, 1f))
        val opacityValue = body(13f, true, value = "$currentOpacity%")
        opacityRow.addView(opacityValue)
        overlay.addView(opacityRow, margined(top = 4, bottom = 5))
        val opacitySlider = SeekBar(this).apply {
            max = 100 - RefreshOverlayService.MIN_ALPHA_PERCENT
            progress = currentOpacity - RefreshOverlayService.MIN_ALPHA_PERCENT
            contentDescription = t("调整悬浮窗透明度", "Adjust overlay opacity")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    opacityValue.text = "${progress + RefreshOverlayService.MIN_ALPHA_PERCENT}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val value = (bar?.progress ?: 0) + RefreshOverlayService.MIN_ALPHA_PERCENT
                    opacityPrefs.edit().putInt(RefreshOverlayService.KEY_ALPHA_PERCENT, value).apply()
                    if (RefreshOverlayService.isRunning) {
                        startService(Intent(this@MainActivity, RefreshOverlayService::class.java).apply {
                            action = RefreshOverlayService.ACTION_UPDATE_SETTINGS
                        })
                    }
                }
            })
        }
        overlay.addView(opacitySlider, margined(bottom = 4))
        addText(overlay, t("可调 35%–100%，自动保存。透明区域仍可能拦截其覆盖范围内的触摸；遮挡时请拖走或关闭。",
            "Adjust from 35% to 100%. Transparency does not make covered areas touch-through; move or close the overlay if needed."),
            11f, secondary = true, bottom = 12)
        monitorView = addText(overlay, "", 12f, secondary = true)
        overlay.addView(button(if (RefreshOverlayService.isRunning) t("关闭监控", "Stop monitor")
            else t("开启悬浮监控", "Start floating monitor"), true) { toggleOverlay() }, margined(top = 12))
        overlay.addView(button(t("重置悬浮窗位置", "Reset overlay position")) {
            opacityPrefs.edit().remove("has_position").remove("x_ratio").remove("y_ratio").apply()
            if (RefreshOverlayService.isRunning) {
                startService(Intent(this, RefreshOverlayService::class.java).apply {
                    action = RefreshOverlayService.ACTION_RESET_POSITION
                })
            }
            notice(t("悬浮窗位置已重置", "Overlay position reset"))
        }, margined(top = 9))
        page.addView(overlay, margined(bottom = 16))
        val settings = card()
        addText(settings, t("系统信息", "System information"), 18f, true)
        val s = snapshot ?: return
        listOf(
            t("设备", "Device") to "${s.manufacturer} ${s.model}",
            t("安卓系统", "Android") to s.androidVersion,
            t("峰值偏好", "Peak preference") to (s.peakSetting ?: "—"),
            t("最低偏好", "Minimum preference") to (s.minSetting ?: "—"),
            t("省电模式", "Battery saver") to (if (s.batterySaver) t("开启", "On") else t("关闭", "Off"))
        ).forEachIndexed { index, (name, value) ->
            if (index > 0) settings.addView(View(this).apply { setBackgroundColor(color(R.color.stroke)) },
                margined(top = 11, bottom = 11).apply { height = dp(1) })
            val row = horizontal()
            row.addView(body(12f, secondary = true, value = name), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(body(12f, true, value = value))
            settings.addView(row)
        }
        settings.addView(button(t("分享兼容性报告", "Share compatibility report")) { shareReport() }, margined(top = 19))
        settings.addView(button(t("打开系统显示设置", "Open display settings")) {
            try { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
            catch (_: Exception) { notice(t("无法打开系统设置", "Could not open system settings")) }
        }, margined(top = 10))
        page.addView(settings)
    }

    private fun applySelection(hz: Float) {
        if (brand.id == "oppo") {
            if (oppoBusy) { notice(t("正在处理上一条请求", "A previous request is still running")); return }
            if (oppoGlobal) {
                AlertDialog.Builder(this)
                    .setTitle(t("全部应用高刷 · 实验性", "All-apps high refresh · beta"))
                    .setMessage(t("将批量给已安装应用（包括部分系统组件）提交 ${fmt(hz)}Hz 请求。这可能影响视频、相机流畅度、耗电与温度。只有厂商接口真正接受后才会记录；不是系统全局硬锁。测试过程中请保持本页打开。",
                        "Requests ${fmt(hz)}Hz for installed packages, including some system components. Video/camera behavior, battery and heat may change. This is per-package coverage, not a physical display-wide lock. Keep this screen open while applying."))
                    .setPositiveButton(t("开始全局应用", "Apply to all")) { _, _ -> requestOplusAll(hz) }
                    .setNegativeButton(t("取消", "Cancel"), null).show()
            } else {
                val target = oppoTargetPackage
                if (target == null) { notice(t("请先选择目标应用", "Select a target app")); chooseOplusApp(); return }
                requestOplus(target, hz)
            }
        } else if (brand.id == "vivo") {
            AlertDialog.Builder(this)
                .setTitle(t("实验性 vivo RMS 控制", "Experimental vivo RMS"))
                .setMessage(t("将尝试使用未公开的 vivo RMS 接口。高刷可能增加发热、耗电或改变显示调光。实际是否成功以系统报告的屏幕模式为准。",
                    "This uses an undocumented vivo RMS interface. It may increase heat, power use or change dimming. The displayed mode will be checked after the request."))
                .setPositiveButton(t("继续", "Continue")) { _, _ -> requestVivo(hz) }
                .setNegativeButton(t("取消", "Cancel"), null).show()
        } else {
            if (!Settings.System.canWrite(this)) { requestWritePermission(); return }
            val result = refreshController.apply(hz)
            lastState = if (result.success) t("系统偏好已写入，尚未验证强制生效", "Preference stored; forced mode not verified")
            else t("系统拒绝设置或未保存", "System rejected or did not retain the settings")
            notice(lastState.orEmpty())
            render()
        }
    }
    /** Launcher-only subset for user-selected per-app scope. Global scope explicitly requests installed-package visibility. */
    private fun chooseOplusApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(intent, 0)
            .map { info ->
                val pkg = info.activityInfo?.packageName.orEmpty()
                pkg to info.loadLabel(packageManager).toString()
            }
            .filter { it.first.isNotEmpty() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
        if (list.isEmpty()) { notice(t("没有找到可启动的应用", "No launchable apps found")); return }
        val labels = list.map { "${it.second}  ·  ${it.first}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(t("选择高刷目标应用", "Choose target app"))
            .setItems(labels) { dialog, which ->
                oppoTargetPackage = list[which].first
                oppoTargetLabel = list[which].second
                dialog.dismiss()
                render()
            }.setNegativeButton(t("取消", "Cancel"), null).show()
    }

    private fun requestOplus(target: String, hz: Float) {
        if (abs(hz - hz.roundToInt()) > .4f) {
            notice(t("此档位无法映射到厂商模式", "Rate has no known vendor mapping")); return
        }
        lastState = t("正在检测 OPlus 接口…", "Checking OPlus API…")
        render()
        Thread {
            val result = OplusScreenModeBridge(this).request(target, hz.roundToInt())
            runOnUiThread {
                if (result.accepted) vendorPrefs.edit()
                    .putString("oplus_pkg", target)
                    .putInt("oplus_hz", hz.roundToInt())
                    .apply()
                lastState = if (result.accepted)
                    t("厂商已接受 $target 的 ${fmt(hz)}Hz 请求；实际是否切换仍需检查。",
                        "Vendor accepted ${fmt(hz)}Hz for $target; actual rate still needs verification.")
                else t("OPlus 未适配当前固件：${result.detail}", "OPlus not available on this firmware: ${result.detail}")
                render()
            }
        }.start()
    }

    /** Explicit all-app scope. Results may be partial; never display "global success" for a partial result. */
    private fun requestOplusAll(hz: Float) {
        if (oppoBusy) return
        if (abs(hz - hz.roundToInt()) > .4f) { notice(t("不支持的档位", "Unsupported vendor rate")); return }
        oppoBusy = true
        lastState = t("正在检查厂商服务…", "Checking vendor service…")
        render()
        Thread {
            try {
                val bridge = OplusScreenModeBridge(this)
                val preflight = bridge.probe()
                if (!preflight.accepted) {
                    runOnUiThread {
                        oppoBusy = false
                        lastState = t("未调用厂商控制：${preflight.detail}", "Vendor control not invoked: ${preflight.detail}")
                        render()
                    }
                    return@Thread
                }
                @Suppress("DEPRECATION")
                val allPackages = if (Build.VERSION.SDK_INT >= 33)
                    packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                else packageManager.getInstalledPackages(0)
                val names = allPackages.map { it.packageName }
                    .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
                    .distinct().sorted()
                if (names.size < 10) {
                    runOnUiThread {
                        oppoBusy = false
                        lastState = t("可见应用过少，未执行全局操作", "Too few visible packages; global operation cancelled")
                        render()
                    }
                    return@Thread
                }
                vendorPrefs.edit().putInt("oplus_all_hz", hz.roundToInt()).apply()
                val previous = vendorPrefs.getStringSet("oplus_armed_packages", emptySet()).orEmpty()
                val succeeded = previous.toMutableSet()
                var ok = 0
                var fail = 0
                var firstError: String? = null
                for ((index, pkg) in names.withIndex()) {
                    val result = bridge.request(pkg, hz.roundToInt())
                    if (result.accepted) { succeeded.add(pkg); ok++ }
                    else { fail++; if (firstError == null) firstError = result.detail }
                    // Don't hammer a restricted vendor service after repeated denials.
                    if (ok == 0 && fail >= 8) break
                    if ((index + 1) % 25 == 0) {
                        // Store partial success so users can always roll back after interruption.
                        vendorPrefs.edit().putStringSet("oplus_armed_packages", succeeded.toSet()).apply()
                        val progress = index + 1
                        runOnUiThread {
                            lastState = t("全局处理中 $progress/${names.size} · 接受 $ok · 拒绝 $fail",
                                "Applying $progress/${names.size} · accepted $ok · rejected $fail")
                            statusView?.text = lastState
                        }
                    }
                }
                vendorPrefs.edit().putStringSet("oplus_armed_packages", succeeded.toSet()).apply()
                runOnUiThread {
                    oppoBusy = false
                    lastState = if (fail == 0 && ok > 0)
                        t("厂商接受了 $ok 个应用请求；仍需到其他 App 验证实际 Hz。", "Vendor accepted $ok app requests; verify physical Hz in other apps.")
                    else t("部分完成：接受 $ok、拒绝 $fail。首个失败：${firstError ?: "—"}。不能算全局成功。",
                        "Partial result: $ok accepted, $fail rejected. First error: ${firstError ?: "—"}. Not globally verified.")
                    render()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    oppoBusy = false
                    lastState = t("全局模式失败：${e.javaClass.simpleName}", "Global mode failed: ${e.javaClass.simpleName}")
                    render()
                }
            }
        }.start()
    }

    private fun releaseOplus(target: String) {
        if (oppoBusy) return
        oppoBusy = true
        lastState = t("正在发送解除请求…", "Sending release request…")
        render()
        Thread {
            val result = OplusScreenModeBridge(this).release(target)
            runOnUiThread {
                oppoBusy = false
                if (result.accepted) {
                    vendorPrefs.edit().remove("oplus_pkg").remove("oplus_hz").apply()
                    lastState = t("厂商已接受撤销请求；仍需验证系统是否恢复自动调节", "Vendor accepted withdrawal; verify that the display returns to automatic mode")
                    val common = restorePublicBackupIfAny()
                    if (common.isNotEmpty()) lastState += " · $common"
                } else {
                    lastState = t("厂商撤销失败：${result.detail}", "Vendor withdrawal failed: ${result.detail}")
                    val common = restorePublicBackupIfAny()
                    if (common.isNotEmpty()) lastState += " · $common"
                }
                render()
            }
        }.start()
    }

    /**
     * Withdrawal is a per-package rateId=0 request; sending the previous rate
     * again would re-pin it. We only touch packages recorded by VRefresh.
     * A vendor acknowledgement does not prove the actual panel mode has changed.
     */
    private fun releaseOplusAll() {
        if (oppoBusy) return
        val targets = vendorPrefs.getStringSet("oplus_armed_packages", emptySet()).orEmpty().toList()
        if (targets.isEmpty()) {
            lastState = t("没有记录的全局请求；正在检查通用设置备份", "No recorded global requests; checking public-settings backup")
            val common = restorePublicBackupIfAny()
            if (common.isNotEmpty()) lastState += " · $common"
            render()
            return
        }
        oppoBusy = true
        lastState = t("正在逐个撤销本应用记录的请求…", "Withdrawing requests saved by this app…")
        render()
        Thread {
            val bridge = OplusScreenModeBridge(this)
            val pending = targets.toMutableSet()
            var accepted = 0
            var firstError: String? = null
            targets.forEachIndexed { index, pkg ->
                val result = bridge.release(pkg)
                if (result.accepted) {
                    pending.remove(pkg)
                    accepted++
                } else if (firstError == null) firstError = result.detail
                if ((index + 1) % 25 == 0) {
                    vendorPrefs.edit().putStringSet("oplus_armed_packages", pending.toSet()).commit()
                }
            }
            vendorPrefs.edit().putStringSet("oplus_armed_packages", pending.toSet()).apply()
            if (pending.isEmpty()) vendorPrefs.edit().remove("oplus_all_hz").apply()
            runOnUiThread {
                oppoBusy = false
                lastState = t("厂商接受撤销 $accepted 项；失败 ${pending.size} 项。请切换应用验证实际 Hz。",
                    "Vendor accepted $accepted withdrawals; ${pending.size} failed. Check display Hz in other apps.")
                if (firstError != null) lastState += " · ${firstError}"
                val common = restorePublicBackupIfAny()
                if (common.isNotEmpty()) lastState += " · $common"
                render()
            }
        }.start()
    }

    /** Restore public Android preferences only when this application saved originals.
     * Do not wipe a user's settings if there is no backup created by this application.
     */
    private fun restorePublicBackupIfAny(): String {
        if (!refreshController.snapshot().backupExists) return ""
        if (!Settings.System.canWrite(this)) return t("通用偏好未恢复：需要修改系统设置权限", "Public preferences not restored: WRITE_SETTINGS permission needed")
        val result = refreshController.restore()
        return if (result.success) t("通用设置备份已恢复", "Public-settings backup restored")
            else t("通用设置恢复失败，备份已保留", "Public-settings restore failed; backup retained")
    }

    private fun requestVivo(hz: Float) {
        if (hz < 60 || hz > 300 || abs(hz - hz.roundToInt()) > .25f) {
            notice(t("该模式不适合 RMS 测试", "Mode not eligible for RMS")); return
        }
        lastState = t("正在发送 RMS 请求并验证…", "Sending RMS vote and verifying…")
        render()
        Thread {
            try {
                val bridge = VivoRmsBridge()
                val sent = bridge.request(hz.roundToInt())
                Thread.sleep(1200)
                val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
                val modeHz = dm.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.refreshRate
                val verified = sent && modeHz != null && abs(modeHz - hz) < .65f
                runOnUiThread {
                    lastState = when {
                        verified -> t("✓ 已验证当前屏幕 ${fmt(modeHz)}Hz；跨应用仍需测试", "✓ Verified ${fmt(modeHz)}Hz on this screen; test other apps")
                        !sent -> t("RMS 请求未被接受", "RMS request was not accepted")
                        else -> t("已发送请求，当前 ${fmt(modeHz)}Hz，未验证达标", "Vote sent; observed ${fmt(modeHz)}Hz, not verified")
                    }
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    lastState = t("RMS 无法访问（${e.javaClass.simpleName}）", "RMS unavailable (${e.javaClass.simpleName})")
                    render()
                }
            }
        }.start()
    }
    private fun restoreAutomatic() {
        if (brand.id == "oppo") {
            val all = vendorPrefs.getStringSet("oplus_armed_packages", emptySet()).orEmpty()
            if (all.isNotEmpty()) { releaseOplusAll(); return }
            val target = vendorPrefs.getString("oplus_pkg", null)
            if (target != null) { releaseOplus(target); return }
            val common = restorePublicBackupIfAny()
            lastState = if (common.isNotEmpty()) common else t(
                "没有本工具记录的请求或系统设置备份；不会修改其他工具的设置。",
                "No saved app requests or settings backup; other tools' settings were not modified.")
            render()
        } else if (brand.id == "vivo") {
            Thread {
                val ok = try { VivoRmsBridge().release() } catch (_: Exception) { false }
                runOnUiThread {
                    lastState = if (ok) t("已发送 RMS 释放请求", "RMS release requested")
                        else t("无法释放 RMS；可重启设备清除临时锁定", "Could not release RMS; reboot clears temporary locks")
                    render()
                }
            }.start()
        } else {
            if (!Settings.System.canWrite(this)) { requestWritePermission(); return }
            val result = refreshController.restore()
            lastState = if (result.success) t("已恢复备份的设置", "Previous preferences restored")
                else t("未能恢复；可能没有保存的备份", "Could not restore; no backup may exist")
            render()
        }
    }
    private fun requestWritePermission() {
        AlertDialog.Builder(this).setTitle(t("修改系统设置", "Modify system settings"))
            .setMessage(t("仅通用偏好写入需要此权限。授权并不代表可以绕过厂商刷新率限制。",
                "Only the public preference requires this permission. Granting it cannot bypass vendor limits."))
            .setPositiveButton(t("打开授权", "Open permission page")) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { notice(t("未能打开授权页面", "Could not open permission page")) }
            }.setNegativeButton(t("取消", "Cancel"), null).show()
    }
    private fun toggleOverlay() {
        if (RefreshOverlayService.isRunning) {
            stopService(Intent(this, RefreshOverlayService::class.java))
            loop.postDelayed({ render() }, 250L); return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this).setTitle(t("需要悬浮窗权限", "Overlay permission needed"))
                .setMessage(t("允许高刷工具在其他应用上层显示系统报告的屏幕 Hz，不读取其他应用内容。",
                    "Allow HighHz to display OS-reported Hz above other apps. It does not read app content."))
                .setPositiveButton(t("去授权", "Grant")) { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { notice(t("无法打开授权设置", "Permission settings unavailable")) }
                }.setNegativeButton(t("取消", "Cancel"), null).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 80)
            return
        }
        startOverlay()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 80 && Settings.canDrawOverlays(this)) startOverlay()
    }
    private fun startOverlay() {
        try {
            startForegroundService(Intent(this, RefreshOverlayService::class.java).apply {
                action = RefreshOverlayService.ACTION_START
            })
            loop.postDelayed({ render() }, 350L)
        } catch (_: Exception) { notice(t("无法启动悬浮服务", "Could not start floating monitor")) }
    }
    private fun refreshValues() {
        if (!::refreshController.isInitialized) return
        val s = refreshController.snapshot()
        snapshot = s
        currentHzView?.text = fmt(s.activeRate)
        statusView?.text = lastState ?: t("尚未提交刷新率请求", "No refresh request submitted")
        monitorView?.text = if (RefreshOverlayService.isRunning) t("● 监控运行中", "● Monitor active")
            else t("○ 尚未开启", "○ Not running")
    }
    private fun shareReport() {
        val s = refreshController.snapshot()
        val report = buildString {
            appendLine("Android Refresh Rate Tool 0.6.6 BETA / Device compatibility")
            appendLine("Brand adapter: ${brand.display} (${brand.level})")
            appendLine("Device: ${BrandSupport.deviceLabel()}")
            appendLine("Android: ${s.androidVersion}")
            appendLine("Reported modes: ${s.rates.joinToString { fmt(it) + " Hz" }}")
            appendLine("Display mode now: ${fmt(s.activeRate)} Hz")
            appendLine("Peak preference: ${s.peakSetting ?: "unset"}")
            appendLine("Min preference: ${s.minSetting ?: "unset"}")
            appendLine("Battery saver: ${s.batterySaver}")
            appendLine("Write settings: ${s.canWriteSettings}")
            appendLine("Recent request: ${lastState ?: "none"}")
            appendLine("OPlus target package: ${oppoTargetPackage ?: "none"}")
            appendLine("OPlus mode: ${if (oppoGlobal) "all installed apps" else "one app"}")
            appendLine("OPlus saved all-app requests: ${vendorPrefs.getStringSet("oplus_armed_packages", emptySet()).orEmpty().size}")
            appendLine("OPlus saved single-app request: ${vendorPrefs.getString("oplus_pkg", "none")}")
            appendLine("Public settings backup available: ${s.backupExists}")
            appendLine("Language preference: ${LocaleChoice.option(this@MainActivity)}")
            appendLine("OPlus method: read-only DEX transaction constant from device ROM; reflection fallback; no blind trial calls")
            appendLine("Display Hz does not equal actual game FPS.")
        }
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
        }, t("分享诊断报告", "Share diagnostics"))) }
        catch (_: Exception) { notice(t("未找到可分享的应用", "No share target")) }
    }
    private fun notice(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
