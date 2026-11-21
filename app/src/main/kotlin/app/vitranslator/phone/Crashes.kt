package app.vitranslator.phone

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * The last crash, offered on the next launch so the user can copy it and send it (the app reports
 * nothing by itself). A crash of the app's own code is written down as it happens; on Android 11+
 * the system's record of how the app last ended also gives a native crash (the PS Remote Play
 * library, a phone's video decoder) or a freeze (ANR), which no handler in the app can see.
 */
object Crashes {
    private const val FILE = "last-crash.txt"
    private const val PREFS = "crashes"
    private const val SEEN = "seen"

    /** From App.onCreate: write down an uncaught exception, then let Android end the app as usual. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                File(app.filesDir, FILE).writeText(header(app) + "Thread: ${thread.name}\n\n" + Log.getStackTraceString(e))
            } catch (ignored: Throwable) {
                // (nothing more to do while crashing)
            }
            before?.uncaughtException(thread, e)
        }
    }

    /** The last crash's report, once (it isn't offered again); null when there is none. */
    fun take(ctx: Context): String? {
        val file = File(ctx.filesDir, FILE)
        var report = try {
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
        file.delete()
        if (Build.VERSION.SDK_INT >= 30) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(SEEN, 0)
            val exits = try {
                ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 10)
            } catch (e: Exception) {
                emptyList()
            }
            // (newest first; a crash of the app's own code is already in the file)
            val last = exits.firstOrNull { it.timestamp > seen && it.reason in REASONS }
            if (report == null && last != null) report = header(ctx) + describe(last)
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(SEEN, maxOf(it, seen)).apply() }
        }
        return report
    }

    private val REASONS = if (Build.VERSION.SDK_INT >= 30) {
        setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
    } else {
        emptySet()
    }

    private fun describe(e: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val kind = when (e.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            else -> "crash"
        }
        val sb = StringBuilder("Ended by: $kind, ${java.util.Date(e.timestamp)}\nProcess: ${e.processName}\n")
        e.description?.let { sb.append("Description: ").append(it).append('\n') }
        // (an ANR's trace is text; a native crash's is a binary tombstone, of no use pasted)
        if (e.reason == ApplicationExitInfo.REASON_ANR) {
            try {
                e.traceInputStream?.bufferedReader()?.use { r -> sb.append('\n').append(r.readText().take(20_000)) }
            } catch (ignored: Exception) {
                // (no trace)
            }
        }
        return sb.toString()
    }

    private fun header(ctx: Context): String {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        return "VI Translator $version (${BuildConfig.FLAVOR})\n" +
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n"
    }
}
