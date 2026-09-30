package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession

/**
 * Collects the keymap commands the Object overlay executes for its area: today the geometry-dependent
 * Shift+S snaps over the selected drawables' centroids.  Only the pointer's own area executes: every open
 * 2D viewport collects the same flow, and an ungated request would commit once per viewport.  The handler
 * ignores the area - a snap acts on the model - so the payload's id is purely the election.  The body is
 * the plain handler in SessionRequestHandlers.kt; this is only the routing.
 *
 * Runs until its caller's effect is cancelled.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session whose request flows to collect.
 */
internal suspend fun collectObjectGizmoRequests(areaId: String, session: EditorSession) {
	session.snapRequests.collect { request ->
		if (session.mode.value != EditorMode.Object || request.areaId != areaId) {
			return@collect
		}
		handleObjectSnapRequest(session, request.kind)
	}
}