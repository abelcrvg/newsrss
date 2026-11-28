package com.abelcrvg.newsrss.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.FontFamily

/** Persistent reader/display preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("newsrss_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() {
            val stored = prefs.getString(KEY_THEME, null)
            if (stored == null && prefs.contains(LEGACY_DARK_MODE)) {
                return if (prefs.getBoolean(LEGACY_DARK_MODE, false)) ThemeMode.DARK else ThemeMode.LIGHT
            }
            return runCatching { ThemeMode.valueOf(stored ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM)
        }
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** Font scale applied only to article reading text. Interface chrome stays untouched. */
    var readerFontScale: Float
        get() = prefs.getFloat(KEY_READER_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_READER_SCALE, value.coerceIn(0.85f, 1.5f)).apply()

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
        private const val KEY_THEME = "theme_mode"
        private const val LEGACY_DARK_MODE = "dark_mode"
        private const val KEY_READER_SCALE = "reader_font_scale"
        private const val KEY_FONT = "font_family"
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
