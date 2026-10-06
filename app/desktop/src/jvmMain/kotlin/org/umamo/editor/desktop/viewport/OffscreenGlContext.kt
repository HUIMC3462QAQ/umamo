package org.umamo.editor.desktop.viewport

import org.lwjgl.system.Configuration
import org.umamo.editor.desktop.PACKAGED_VERSION_PROPERTY

internal fun createOffscreenGlContext(): OffscreenGlContext {
	configureLwjglNatives()
	return GlfwOffscreenGlContext()
}

/**
 * Sets how LWJGL finds and checks its native libraries on this OS, before anything loads them - the viewport's
 * render thread and the headless self-check alike, so both load them the same way.
 */
internal fun configureLwjglNatives() {
	val osName = System.getProperty("os.name").orEmpty().lowercase()
	if (osName.contains("mac") || osName.contains("darwin")) {
		// https://javadoc.lwjgl.org/org/lwjgl/glfw/package-summary.html#using-glfw-on-macos-heading
		Configuration.GLFW_LIBRARY_NAME.set("glfw_async")
		// The app bundle's native libraries are re-signed when it is packaged (ad-hoc today, with the Developer ID
		// once signing lands), so their bytes never match the reference hashes LWJGL ships, and its check would
		// only print a false "incompatible Java and native library versions" error.  A jar or a development run
		// loads them unmodified and keeps the check.
		if (System.getProperty(PACKAGED_VERSION_PROPERTY) != null) {
			Configuration.DISABLE_HASH_CHECKS.set(true)
		}
	}
}