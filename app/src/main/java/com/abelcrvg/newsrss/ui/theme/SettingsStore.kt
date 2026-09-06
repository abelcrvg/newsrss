package com.abelcrvg.newsrss.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.FontFamily

/** Persistent reader/display preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("newsrss_settings", Context.MODE_PRIVATE)

    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK, value).apply()

    var fontScale: Float
        get() = prefs.getFloat(KEY_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SCALE, value.coerceIn(0.85f, 1.35f)).apply()

    var fontFamilyName: String
        get() = prefs.getString(KEY_FONT, "Sans") ?: "Sans"
        set(value) = prefs.edit().putString(KEY_FONT, value).apply()

    fun fontFamily(): FontFamily = when (fontFamilyName) {
        "Serif" -> FontFamily.Serif
        "Mono" -> FontFamily.Monospace
        "Cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

    companion object {
        private const val KEY_DARK = "dark_mode"
        private const val KEY_SCALE = "font_scale"
        private const val KEY_FONT = "font_family"
    }
}
