package com.vanker.vrefresh

import android.os.Build
import java.util.Locale

enum class SupportLevel { EXPERIMENTAL, PUBLIC_API, DIAGNOSTICS }

data class BrandProfile(
    val id: String,
    val display: String,
    val level: SupportLevel,
    val detailZh: String,
    val detailEn: String
)

/** Brand identification is not a claim that undocumented vendor settings work. */
object BrandSupport {
    val profiles = listOf(
        BrandProfile("vivo", "vivo / iQOO", SupportLevel.EXPERIMENTAL,
            "RMS 私有接口已由用户在 vivo V2618A 的 ADB 环境验证 185Hz。App 内直接调用仍需逐机验证。",
            "RMS 185 Hz was verified via ADB on vivo V2618A; direct in-app access still needs device testing."),
        BrandProfile("xiaomi", "Xiaomi / REDMI / POCO", SupportLevel.PUBLIC_API,
            "可检测公开模式并提交系统高刷偏好。HyperOS 私有强制控制尚未实现。",
            "Detects public modes and requests a system preference. HyperOS private pinning is not implemented."),
        BrandProfile("oppo", "OPPO / OnePlus / realme", SupportLevel.EXPERIMENTAL,
            "OPPO Pad Mini 外部工具实测 144Hz。本工具提供实验性的全部应用请求和单应用控制，自动识别 ROM 交易号；本 App 尚未实测成功。",
            "144Hz verified on Pad Mini using an external tool. Experimental all-installed-app and per-app requests use ROM-derived transaction IDs; This app's vendor control remains firmware-dependent; test on each device."),
        BrandProfile("samsung", "Samsung Galaxy", SupportLevel.PUBLIC_API,
            "支持 Android 通用设置尝试，One UI 私有调度未适配。",
            "Android public preferences only; One UI private scheduling is not supported."),
        BrandProfile("pixel", "Google Pixel", SupportLevel.PUBLIC_API,
            "支持 Android 通用刷新率偏好，不保证覆盖系统动态刷新率策略。",
            "Uses Android public preferences; cannot bypass dynamic policy."),
        BrandProfile("honor", "HONOR", SupportLevel.PUBLIC_API,
            "支持公开模式检测和系统偏好，MagicOS 厂商私有控制待适配。",
            "Public detection and preference; MagicOS private control pending."),
        BrandProfile("huawei", "HUAWEI (Android)", SupportLevel.PUBLIC_API,
            "仅限可安装 Android APK 的机型；鸿蒙原生系统需单独开发。",
            "Android APK-capable devices only; native HarmonyOS requires a separate app."),
        BrandProfile("moto", "Motorola / Lenovo", SupportLevel.PUBLIC_API,
            "支持公开模式检测和系统偏好，私有控制待适配。",
            "Public detection and preference; private mode control pending."),
        BrandProfile("asus", "ASUS / ROG", SupportLevel.PUBLIC_API,
            "支持公开模式检测和系统偏好，游戏模式可能优先控制刷新率。",
            "Public detection and preference; gaming policy may override it."),
        BrandProfile("other", "Other Android", SupportLevel.DIAGNOSTICS,
            "可读取系统公开的屏幕刷新模式，暂不保证强制控制。",
            "Reads public display modes; no universal forced refresh rate is guaranteed.")
    )

    /** Prefer the actual device brand in the UI; OPlus is only the shared service family. */
    fun currentBrandLabel(): String {
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase(Locale.ROOT)
        return when {
            brand.contains("iqoo") -> "iQOO"
            brand.contains("vivo") -> "vivo"
            brand.contains("oneplus") -> "OnePlus"
            brand.contains("realme") -> "realme"
            brand.contains("oppo") -> "OPPO"
            brand.contains("redmi") -> "REDMI"
            brand.contains("poco") -> "POCO"
            brand.contains("xiaomi") -> "Xiaomi"
            brand.contains("samsung") -> "Samsung"
            brand.contains("google") -> "Google Pixel"
            brand.contains("honor") -> "HONOR"
            brand.contains("huawei") -> "HUAWEI"
            brand.contains("motorola") -> "Motorola"
            brand.contains("lenovo") -> "Lenovo"
            brand.contains("asus") -> "ASUS"
            else -> Build.BRAND.ifBlank { Build.MANUFACTURER }
        }
    }

    fun deviceLabel(): String {
        val model = Build.MODEL.orEmpty()
        return if (currentBrandLabel() == "OPPO" && model.equals("OPD2515", true))
            "OPPO Pad Mini (OPD2515)" else "${currentBrandLabel()} $model".trim()
    }

    fun current(): BrandProfile {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
        return when {
            brand.contains("vivo") || brand.contains("iqoo") -> profiles[0]
            listOf("xiaomi", "redmi", "poco").any { brand.contains(it) } -> profiles[1]
            listOf("oppo", "oneplus", "realme").any { brand.contains(it) } -> profiles[2]
            brand.contains("samsung") -> profiles[3]
            brand.contains("google") -> profiles[4]
            brand.contains("honor") -> profiles[5]
            brand.contains("huawei") -> profiles[6]
            listOf("motorola", "lenovo").any { brand.contains(it) } -> profiles[7]
            listOf("asus", "rog").any { brand.contains(it) } -> profiles[8]
            else -> profiles.last()
        }
    }
}
