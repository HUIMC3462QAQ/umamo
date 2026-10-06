package org.umamo.editor.android

import android.app.Application

/**
 * The app's [Application], whose only job is to install [CrashLog] before anything else can fail.
 *
 * It has to be here rather than in an Activity: the whole point is to catch a crash in the startup path,
 * and an Activity's own `onCreate` runs after the failure it would need to report.
 */
class UmamoApplication : Application() {
	override fun onCreate() {
		super.onCreate()
		CrashLog.install(this)
	}
}