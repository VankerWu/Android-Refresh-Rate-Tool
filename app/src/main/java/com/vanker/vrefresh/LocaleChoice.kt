package com.vanker.vrefresh

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** In-app language choice; also respects Android 13+ system per-app language settings. */
object LocaleChoice {
    private const val PREF = "vrefresh_prefs"
    private const val KEY = "language"
    fun option(context: Context): String = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY, "system") ?: "system"
    /** 'Follow system' means the device-wide locale, not a stale Android per-app override.
     * Android 13+ persists a separate application locale even across APK upgrades.
     */
    fun isChinese(context: Context): Boolean = when (option(context)) {
        "zh" -> true
        "en" -> false
        else -> {
            val locales = if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java)?.systemLocales
            } else {
                android.content.res.Resources.getSystem().configuration.locales
            }
            val systemLanguage = if (locales != null && locales.size() > 0) locales[0].language
                else Locale.getDefault().language
            systemLanguage.lowercase(Locale.ROOT).startsWith("zh")
        }
    }
    fun update(context: Context, newOption: String) {
        require(newOption in listOf("system", "zh", "en"))
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, newOption).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val tags = when (newOption) { "zh" -> "zh-CN"; "en" -> "en"; else -> "" }
            manager.applicationLocales = if (tags.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tags)
        }
    }
}
