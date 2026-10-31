package com.sirveillanceai.mixokeys

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * Finds out why the last run ended when the app is closed suddenly, and shows it on the next launch.
 *  - Kotlin/Java crashes: the stack trace is written to a file by an uncaught-exception handler.
 *  - Native crashes or the system killing the app for memory: Android's exit record (Android 11+).
 *  - Where it happened: a breadcrumb written before each heavy step, with memory use at that moment.
 */
object CrashReport {
    private fun crashFile(c: Context) = File(c.filesDir, "last-crash.txt")
    private fun crumbFile(c: Context) = File(c.filesDir, "running.txt")
    private const val PREFS = "crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile(app).writeText("Thread ${t.name}\n" + e.stackTraceToString().take(6000)) }
            previous?.uncaughtException(t, e)
        }
    }

    fun memLine(context: Context): String {
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "app java %d/%d MB · app native %d MB · phone free %d of %d MB%s".format(
            (rt.totalMemory() - rt.freeMemory()) shr 20, rt.maxMemory() shr 20,
            Debug.getNativeHeapAllocatedSize() shr 20,
            mi.availMem shr 20, mi.totalMem shr 20, if (mi.lowMemory) " (LOW)" else "",
        )
    }

    /** Record the step being worked on. Written synchronously so it survives a sudden kill. */
    fun mark(context: Context, where: String) {
        runCatching { crumbFile(context).writeText("$where\n${memLine(context)}") }
    }

    fun finished(context: Context) { crumbFile(context).delete() }

    /** A report of the previous run if it ended suddenly, or null. Clears it once read. */
    fun takeReport(context: Context): String? {
        val parts = mutableListOf<String>()
        val crumb = crumbFile(context)
        if (crumb.exists()) parts += "It stopped during: " + crumb.readText()
        val crash = crashFile(context)
        if (crash.exists()) parts += "Error:\n" + crash.readText()

        if (Build.VERSION.SDK_INT >= 30 && parts.isNotEmpty()) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull()
            if (info != null && info.timestamp != prefs.getLong("seen", 0)) {
                prefs.edit().putLong("seen", info.timestamp).apply()
                parts += "Android says: %s%s · memory at the end %d MB (pss) / %d MB (rss)".format(
                    reasonName(info.reason), info.description?.let { " — $it" } ?: "", info.pss shr 10, info.rss shr 10,
                )
            }
        }
        crumb.delete(); crash.delete()
        if (parts.isEmpty()) return null
        parts += "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Runtime.getRuntime().availableProcessors()} cores"
        return parts.joinToString("\n\n")
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "closed by the system because the phone ran out of memory"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash (inside the model runtime)"
        ApplicationExitInfo.REASON_CRASH -> "app crash"
        ApplicationExitInfo.REASON_ANR -> "app not responding"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal (often memory)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "code $r"
    }
}
