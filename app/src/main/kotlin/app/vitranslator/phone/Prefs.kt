package app.vitranslator.phone

import android.content.Context

/** The user's choices, kept across runs. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** "ps5" or "xbox" ("" until chosen) */
    var console: String
        get() = p.getString("console", "") ?: ""
        set(v) = p.edit().putString("console", v).apply()

    var game: String
        get() = p.getString("game", "gta6") ?: "gta6"
        set(v) = p.edit().putString("game", v).apply()

    /** the language translated into */
    var language: String
        get() = p.getString("language", "ar") ?: "ar"
        set(v) = p.edit().putString("language", v).apply()

    /** the app's own language ("" for the phone's) */
    var uiLanguage: String
        get() = p.getString("ui_language", "") ?: ""
        set(v) = p.edit().putString("ui_language", v).apply()

    /** dark theme (the desktop's "theme" setting, kept here too for the first frame) */
    var dark: Boolean
        get() = p.getBoolean("dark", true)
        set(v) = p.edit().putBoolean("dark", v).apply()

    /** the live picture in the app (the header's Preview switch) */
    var preview: Boolean
        get() = p.getBoolean("preview", true)
        set(v) = p.edit().putBoolean("preview", v).apply()

    /** "play" or "setup", as last left */
    var lastMode: String
        get() = p.getString("mode", "setup") ?: "setup"
        set(v) = p.edit().putString("mode", v).apply()

    /** the PS5 stream's resolution: 1080 (the default: small subtitles read best) or 720 */
    var psResolution: Int
        get() = p.getInt("ps_resolution", 1080)
        set(v) = p.edit().putInt("ps_resolution", v).apply()

    /** H.265 for the PS5 stream where the phone plays it; false once it showed no picture here */
    var psH265: Boolean
        get() = p.getBoolean("ps_h265", true)
        set(v) = p.edit().putBoolean("ps_h265", v).apply()

    /** which of this edition's own defaults were moved to (see EditionDefaults) */
    var editionDefaults: Int
        get() = p.getInt("edition_defaults", 0)
        set(v) = p.edit().putInt("edition_defaults", v).apply()
}
