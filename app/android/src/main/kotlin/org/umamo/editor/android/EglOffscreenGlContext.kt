package org.umamo.editor.android

import android.opengl.GLES20
import org.umamo.editor.desktop.viewport.OffscreenGlContext
import org.umamo.render.gles.GlesOffscreenContext

/**
 * Binds the EGL context to the viewport stack's context seam.
 *
 * A thin adapter rather than `GlesOffscreenContext` implementing [OffscreenGlContext] itself: that class
 * lives in `:render`, which must not depend on `:viewport` (the dependency runs the other way), and the
 * context is a host concern anyway - it is the EGL twin of the desktop host's `GlfwOffscreenGlContext`,
 * which sits in `app/desktop` for the same reason.
 *
 * [finish] is the one method the seam adds that EGL itself has no equivalent for: it is a GL call, so it
 * goes to `android.opengl` here rather than through the context wrapper.
 */
internal class EglOffscreenGlContext : OffscreenGlContext {
	private val egl = GlesOffscreenContext()

	override val backendName: String
		get() = egl.backendName

	override fun createAndMakeCurrent(): Boolean = egl.createAndMakeCurrent()

	override fun failureReason(): String? = egl.failureReason()

	override fun describeContext(): String = egl.describeContext()

	override fun finish() {
		GLES20.glFinish()
	}

	override fun destroy() {
		egl.destroy()
	}
}