package app.vitranslator.phone.core

import android.util.Log
import app.vitranslator.phone.Prefs
import org.json.JSONObject

/**
 * This edition's own reading defaults, moved to once: the shared core reads every 250 ms and waits
 * 300 ms for a line to settle (made for a PC); here the lines come as fast as in the camera app
 * ซับไทยขึ้นทีวี, which reads every 120 ms. A timing the player chose (Source › Timing) stays.
 */
object EditionDefaults {
    /** Bumped when this edition's defaults change again. */
    private const val VERSION = 1

    const val INTERVAL_MS = 120
    const val STABLE_MS = 150

    // the shared core's own defaults
    private const val CORE_INTERVAL_MS = 250
    private const val CORE_STABLE_MS = 300

    /** The settings change that moves [cfg] (the core's settings) to this edition's defaults, or null. */
    fun patch(cfg: JSONObject): JSONObject? {
        if (cfg.optBoolean("timing_custom", false)) return null
        val p = JSONObject()
        if (cfg.optInt("interval_ms") == CORE_INTERVAL_MS) p.put("interval_ms", INTERVAL_MS)
        if (cfg.optInt("stable_ms") == CORE_STABLE_MS) p.put("stable_ms", STABLE_MS)
        return p.takeIf { it.length() > 0 }
    }

    /** Once per install (and again when [VERSION] goes up). */
    fun apply(core: Core, prefs: Prefs) {
        if (prefs.editionDefaults >= VERSION) return
        try {
            patch(JSONObject(core.configJson()))?.let { core.updateConfig(it.toString()) }
            prefs.editionDefaults = VERSION
        } catch (e: Exception) {
            Log.w("EditionDefaults", "the faster reading couldn't be set", e)
        }
    }
}
