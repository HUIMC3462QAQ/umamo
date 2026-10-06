package org.umamo.editor.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records an uncaught exception to a file the [ProbeActivity] can show.
 *
 * Why this exists: the editor shell's Android startup is unproven, and a crash there is invisible without
 * a log - `logcat` needs a debug channel, the crash dialog shows no stack, and the app's own log directory
 * is not readable from outside. An in-app recorder turns "it crashes" into a stack trace the user can
 * hand over by copying it out of the probe screen, with no permissions and no adb.
 *
 * The handler chains to whatever was installed before, so the process still dies and logcat still gets its
 * entry - this only adds a copy that survives the process.
 */
internal object CrashLog {
	private const val FILE_NAME = "umamo-crash.txt"
	private const val TAG = "UmamoCrash"

	/** Installs the recorder. Safe to call more than once; the last install wins the chain. */
	fun install(context: Context) {
		val directory = context.filesDir
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try {
				record(directory, thread, error)
			} catch (recording: Throwable) {
				// Never let the recorder replace the real crash with its own.
				Log.e(TAG, "could not record the crash", recording)
			}
			previous?.uncaughtException(thread, error)
		}
	}

	/** The recorded crash, or null when the app has not crashed since the file was last cleared. */
	fun read(context: Context): String? {
		val file = File(context.filesDir, FILE_NAME)
		return if (file.isFile) {
			runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
		} else {
			null
		}
	}

	/** Forgets the recorded crash, so a later probe run does not re-report a stale one. */
	fun clear(context: Context) {
		runCatching { File(context.filesDir, FILE_NAME).delete() }
	}

	private fun record(directory: File, thread: Thread, error: Throwable) {
		val stack = StringWriter().also { writer -> PrintWriter(writer).use { error.printStackTrace(it) } }.toString()
		val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
		val text =
			buildString {
				append("crash at ").append(timestamp).append('\n')
				append("thread: ").append(thread.name).append('\n')
				append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
					.append(android.os.Build.MODEL).append(", Android ").append(android.os.Build.VERSION.RELEASE)
					.append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
				append('\n').append(stack)
			}
		File(directory, FILE_NAME).writeText(text)
		Log.e(TAG, "recorded a crash to $FILE_NAME")
	}
}