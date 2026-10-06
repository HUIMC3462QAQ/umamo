package org.umamo.render.gles

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * An off-screen GLES 3.0 context owned by a single render thread - the Android twin of the desktop's
 * `GlfwOffscreenGlContext`.
 *
 * The renderer only ever draws into framebuffer objects, so the context needs no window and no
 * presentable drawable: a 1x1 pbuffer satisfies EGL and nothing is ever presented from it. That is the
 * same shape the desktop backend uses (a hidden 1x1 GLFW window), which is what lets both share the
 * `RenderDevice` seam instead of the window system.
 *
 * Threading contract, identical to the desktop's: [createAndMakeCurrent], [describeContext] and
 * [destroy] all run on the one render thread, in that order across the context's life.
 */
public class GlesOffscreenContext(
	private val surfaceWidth: Int = 1,
	private val surfaceHeight: Int = 1,
) {
	private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var context: EGLContext = EGL14.EGL_NO_CONTEXT
	private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var reason: String? = null

	/** A short backend label for the startup log line, so the log records which path was taken. */
	public val backendName: String
		get() = "EGL"

	/**
	 * Creates the context and makes it current on the calling (render) thread.
	 *
	 * Returns false rather than throwing so the caller can degrade to a blank viewport - the same graceful
	 * fallback the render engine already applies when GL is unavailable. Nothing is held on failure.
	 *
	 * @return Boolean True on success (a context is now current), false to degrade.
	 */
	public fun createAndMakeCurrent(): Boolean {
		reason = null
		display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		if (display == EGL14.EGL_NO_DISPLAY) {
			return fail("eglGetDisplay")
		}
		val version = IntArray(2)
		if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
			display = EGL14.EGL_NO_DISPLAY
			return fail("eglInitialize")
		}
		if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
			return fail("eglBindAPI(ES)")
		}
		val config = chooseConfig() ?: return fail("eglChooseConfig (no ES3/ES2 pbuffer config)")
		// EGL_CONTEXT_CLIENT_VERSION 3 is what makes this an ES 3.0 context; the config's
		// EGL_RENDERABLE_TYPE only has to admit it (see chooseConfig).
		context = EGL14.eglCreateContext(
			display,
			config,
			EGL14.EGL_NO_CONTEXT,
			intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
			0,
		)
		if (context == null || context == EGL14.EGL_NO_CONTEXT) {
			return fail("eglCreateContext(ES3)")
		}
		surface = EGL14.eglCreatePbufferSurface(
			display,
			config,
			intArrayOf(EGL14.EGL_WIDTH, surfaceWidth, EGL14.EGL_HEIGHT, surfaceHeight, EGL14.EGL_NONE),
			0,
		)
		if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
			return fail("eglCreatePbufferSurface")
		}
		if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
			return fail("eglMakeCurrent")
		}
		return true
	}

	/** Why the last [createAndMakeCurrent] failed, in EGL's own words, or null when it has not failed. */
	public fun failureReason(): String? = reason

	/** The renderer / version / vendor / GLSL of the current context. Valid only after a successful create. */
	public fun describeContext(): String {
		val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
		val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
		return "EGL $renderer | $version"
	}

	/**
	 * Releases the context. Must NOT call glFinish: the engine issues its own barrier before disposing GL
	 * resources, and this runs last. Harmless when nothing is held.
	 */
	public fun destroy() {
		if (display == EGL14.EGL_NO_DISPLAY) return
		EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
		if (surface != EGL14.EGL_NO_SURFACE) {
			EGL14.eglDestroySurface(display, surface)
			surface = EGL14.EGL_NO_SURFACE
		}
		if (context != EGL14.EGL_NO_CONTEXT) {
			EGL14.eglDestroyContext(display, context)
			context = EGL14.EGL_NO_CONTEXT
		}
		EGL14.eglTerminate(display)
		EGL14.eglReleaseThread()
		display = EGL14.EGL_NO_DISPLAY
	}

	private fun fail(step: String): Boolean {
		reason = "$step failed (EGL error 0x${Integer.toHexString(EGL14.eglGetError())})"
		destroy()
		return false
	}

	/**
	 * Picks a pbuffer config that can back an ES 3.0 context.
	 *
	 * `EGL_OPENGL_ES3_BIT_KHR` (0x40) is the honest renderable type to ask for, but `android.opengl.EGL14`
	 * does not expose the constant, so it is spelled out here. Drivers that reject it still hand out an
	 * ES2-typed config that an ES3 context is creatable from, which is the fallback - and why the context
	 * creation does not simply trust the config's type.
	 */
	private fun chooseConfig(): EGLConfig? {
		val attribs = intArrayOf(
			EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
			EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_RED_SIZE, 8,
			EGL14.EGL_GREEN_SIZE, 8,
			EGL14.EGL_BLUE_SIZE, 8,
			EGL14.EGL_ALPHA_SIZE, 8,
			EGL14.EGL_NONE,
		)
		val configs = arrayOfNulls<EGLConfig>(1)
		val count = IntArray(1)
		if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
			return configs[0]
		}
		attribs[1] = EGL14.EGL_OPENGL_ES2_BIT
		if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
			return configs[0]
		}
		return null
	}

	private companion object {
		/** `EGL_OPENGL_ES3_BIT_KHR`, absent from `android.opengl.EGL14`'s constants. */
		const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
	}
}