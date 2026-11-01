package com.vanker.vrefresh

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import java.util.Locale
import kotlin.math.abs

/** Only uses documented Display and Settings APIs. Never claims vendor limits were bypassed. */
class RefreshController(private val context: Context) {
    private val prefs = context.getSharedPreferences("refresh_backup", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private val peakKey = "peak_refresh_rate"
    private val minKey = "min_refresh_rate"

    data class Snapshot(
        val rates: List<Float>,
        val activeRate: Float?,
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val peakSetting: String?,
        val minSetting: String?,
        val batterySaver: Boolean,
        val canWriteSettings: Boolean,
        val backupExists: Boolean
    )

    data class Result(val success: Boolean, val message: String)

    fun snapshot(): Snapshot {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
        val allRates = display?.supportedModes?.map { it.refreshRate } ?: emptyList()
        val rates = allRates
            .filter { it.isFinite() && it > 0f }
            .sorted()
            .fold(mutableListOf<Float>()) { acc, hz ->
                if (acc.none { abs(it - hz) < 0.35f }) acc.add(hz)
                acc
            }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return Snapshot(
            rates = rates,
            activeRate = display?.mode?.refreshRate,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            peakSetting = read(peakKey),
            minSetting = read(minKey),
            batterySaver = power.isPowerSaveMode,
            canWriteSettings = Settings.System.canWrite(context),
            backupExists = prefs.getBoolean("backup_exists", false)
        )
    }

    private fun read(key: String): String? = try {
        Settings.System.getString(resolver, key)
    } catch (_: RuntimeException) {
        null
    }

    private fun write(key: String, value: String?): Boolean = try {
        Settings.System.putString(resolver, key, value)
    } catch (_: Exception) {
        false
    }

    /** First successful attempt retains originals. Later changes never overwrite the original. */
    private fun ensureBackup() {
        if (prefs.getBoolean("backup_exists", false)) return
        val oldPeak = read(peakKey)
        val oldMin = read(minKey)
        prefs.edit()
            .putBoolean("backup_exists", true)
            .putBoolean("old_peak_exists", oldPeak != null)
            .putBoolean("old_min_exists", oldMin != null)
            .putString("old_peak", oldPeak ?: "")
            .putString("old_min", oldMin ?: "")
            .commit()
    }

    fun apply(targetHz: Float): Result {
        if (!targetHz.isFinite() || targetHz < 60f || targetHz > 300f) {
            return Result(false, "目标必须在 60–300Hz 之间。")
        }
        if (!Settings.System.canWrite(context)) {
            return Result(false, "未获得「修改系统设置」权限。")
        }
        val oldPeak = read(peakKey)
        val oldMin = read(minKey)
        ensureBackup()
        val textHz = String.format(Locale.US, "%.2f", targetHz)
        val peakOk = write(peakKey, textHz)
        val minOk = write(minKey, textHz)
        if (!peakOk || !minOk) {
            // Avoid leaving one of the two settings unexpectedly changed.
            if (peakOk) write(peakKey, oldPeak)
            if (minOk) write(minKey, oldMin)
            return Result(false, "系统拒绝了全部或部分配置，已尝试回滚。此机型可能由厂商私有策略控制。")
        }
        val actualPeak = read(peakKey)?.toFloatOrNull()
        val actualMin = read(minKey)?.toFloatOrNull()
        val stored = actualPeak != null && actualMin != null &&
            abs(actualPeak - targetHz) < 0.6f && abs(actualMin - targetHz) < 0.6f
        return if (stored) {
            Result(true, "已写入 ${textHz}Hz 的系统偏好值。请退出本应用、进入目标 App 实际验证；不代表已经解锁或得到对应 FPS。")
        } else {
            Result(false, "系统没有保留目标值，可能已自动重置或被厂商策略覆盖。")
        }
    }

    fun restore(): Result {
        if (!Settings.System.canWrite(context)) return Result(false, "请先授予「修改系统设置」权限。")
        if (!prefs.getBoolean("backup_exists", false)) return Result(false, "没有找到本工具保存的原始设置。")
        val peak = if (prefs.getBoolean("old_peak_exists", false)) prefs.getString("old_peak", "") else null
        val min = if (prefs.getBoolean("old_min_exists", false)) prefs.getString("old_min", "") else null
        val a = write(peakKey, peak)
        val b = write(minKey, min)
        if (a && b) {
            prefs.edit().remove("backup_exists").remove("old_peak_exists")
                .remove("old_min_exists").remove("old_peak").remove("old_min").apply()
            return Result(true, "已尝试恢复修改前的两个系统刷新率配置，请重新检测。")
        }
        return Result(false, "恢复不完整，原始备份已保留，可重试。")
    }
}
