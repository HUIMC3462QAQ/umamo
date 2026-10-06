package org.umamo.editor.desktop.viewport

/**
 * An off-screen GL 3.3+ core context owned by a single render thread. The offscreen viewport renderer
 * ([OffscreenRenderEngine]) renders only to framebuffer objects, so a context needs no visible window and no
 * presentable drawable - just a current GL context on the thread that issues the draw and read-back calls.
 *
 * Threading contract: [createAndMakeCurrent], [describeContext], and [destroy] all run on the one render
 * thread, in that order across the context's life; the context is never migrated to another thread.
 */
public interface OffscreenGlContext {
	/**
	 * A short backend label for the startup log line (for example "GLFW"), so the log
	 * records which path the running OS actually took.
	 */
	val backendName: String

	/**
	 * Creates the context and makes it current on the calling (render) thread. On failure returns false so
	 * the caller degrades to a blank viewport rather than crashing - the same graceful fallback the engine
	 * already applies when GL is unavailable - and holds nothing: whatever the attempt made before it failed is
	 * released again.
	 *
	 * @return Boolean True on success (a context is now current), false to degrade to a blank viewport.
	 */
	fun createAndMakeCurrent(): Boolean

	/**
	 * Why the last [createAndMakeCurrent] failed, in the backend's own words, for the log line a bug report
	 * reads; null when it has not failed.
	 *
	 * @return String? The failure's description, or null.
	 */
	fun failureReason(): String?

	/**
	 * The renderer / version / vendor / GLSL of the current context, for the startup diagnostic. Valid only
	 * after a successful [createAndMakeCurrent].
	 *
	 * @return String The context description.
	 */
	fun describeContext(): String

	/**
	 * Releases the context on the render thread. Must NOT call glFinish - the engine issues one glFinish
	 * barrier before disposing its GL resources, and this runs last, after that barrier.  Harmless when
	 * nothing is held, which is how the engine can call it after a failed [createAndMakeCurrent] too.
	 */
	fun destroy()

	/**
	 * Blocks until the GPU has finished the work issued on this context.
	 *
	 * The engine issues exactly one of these before it disposes its GL objects, and the collaborators'
	 * dispose() paths must NOT call it: resources have to be freed while the context is still current,
	 * and the wait belongs at the one place that orders teardown.
	 */
	fun finish()
}