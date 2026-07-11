package net.triton.frigateviewer.core.crash

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CRASH_LOG_FILE_NAME = "last_crash.txt"

/**
 * Installed as the process-wide default uncaught-exception handler in [net.triton.frigateviewer.FrigateViewerApp].
 * Writes the crash synchronously — no coroutines, no DataStore — because the process may be
 * killed immediately after [uncaughtException] returns. Then delegates to [defaultHandler] so
 * the OS's normal crash dialog / process-kill behavior is unaffected.
 */
class CrashHandler(
    private val appContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        runCatching { writeCrashLog(appContext, throwable) }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun writeCrashLog(
        context: Context,
        throwable: Throwable,
    ) {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(context.filesDir, CRASH_LOG_FILE_NAME).writeText("Crashed at $timestamp\n\n$stackTrace")
    }
}

/** Reads/clears the crash report [CrashHandler] persists. Called from Compose, off the main thread. */
object CrashReportStore {
    /** Returns the last crash's details and deletes the file, so it's shown at most once. Null if none. */
    suspend fun readAndClear(context: Context): String? =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, CRASH_LOG_FILE_NAME)
            if (!file.exists()) return@withContext null
            val text = runCatching { file.readText() }.getOrNull()
            file.delete()
            text
        }
}
