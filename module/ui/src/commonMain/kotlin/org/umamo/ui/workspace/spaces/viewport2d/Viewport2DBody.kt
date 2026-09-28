package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.viewport2d.ViewportSpaceCamera
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalAreaCameraHub
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.LocalViewportHost

/*
 * The 2D viewport space: the body (this file), its header controls, and the chrome floating over the
 * surface (ViewportToolbarOverlay.kt, ViewportSidebarDrawer.kt).  The surface itself is injected through
 * ViewportHost and built in org.umamo.ui.viewport.viewport2d.
 */

/**
 * The 2D viewport body: the injected [LocalViewportHost]'s rendered surface, with the floating chrome (the
 * left tool toolbar and the right sidebar drawer) overlaid.
 *
 * The grid, the axes, and the canvas are the renderer's, drawn through its camera, for an empty document
 * as for a full one - the editor always has a document, so there is no state in which this would stand
 * in for them with a drawing of its own.  The one case with no host is a platform that has no puppet
 * renderer yet (Android until its GLES service lands); there the area is the plain viewport backdrop
 * color and nothing else, because a painted grid that cannot pan or zoom would promise a work surface
 * that is not there.
 *
 * @param AreaScope scope The hosting area context (its id keys the host's GL surface).
 */
@Composable
internal fun Viewport2DBody(scope: AreaScope) {
	val host = LocalViewportHost.current
	val chrome = LocalViewportChrome.current
	// Register this viewport area's camera controller for its lifetime, into the same per-area hub the UV
	// editor registers into, so the shell's view commands (Fit / 1:1 / zoom / Frame Selected) resolve THIS
	// area when the pointer last touched it.  Only when a render service and session exist - with no
	// viewport nothing registers, matching the hidden view commands.
	val service = LocalPuppetViewportService.current
	val session = LocalEditorSession.current
	val areaCameraHub = LocalAreaCameraHub.current
	DisposableEffect(scope.areaId, service, session, areaCameraHub) {
		if (service != null && session != null && areaCameraHub != null) {
			areaCameraHub.register(scope.areaId, ViewportSpaceCamera(service, session, scope.areaId))
			onDispose { areaCameraHub.unregister(scope.areaId) }
		} else {
			onDispose { }
		}
	}
	Box(modifier = Modifier.fillMaxSize()) {
		if (host != null) {
			host.Viewport2D(scope.areaId, Modifier.fillMaxSize())
		} else {
			Box(modifier = Modifier.fillMaxSize().background(LocalUmamoColors.current.viewportGridBackground))
		}
		if (chrome.showToolbar) {
			ViewportToolbarOverlay(
				modifier =
					Modifier
						.align(Alignment.CenterStart)
						.padding(start = 6.dp),
			)
		}
		ViewportSidebarDrawer(modifier = Modifier.align(Alignment.CenterEnd))
	}
}