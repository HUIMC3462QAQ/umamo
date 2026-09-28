package org.umamo.ui.viewport.uv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.gizmo.drawCursorMarker
import org.umamo.ui.viewport.gizmo.worldToScreen

/**
 * The UV cursor marker (the texture-space 2D cursor), the UV editor's twin of [org.umamo.ui.viewport.viewport2d.Cursor2dOverlay]:
 * the same crosshair at the cursor's display-space position, present in both modes and drawn above
 * the gizmo chrome for viewport parity.  Draw-only; placement stays a gizmo gesture
 * (Shift+RightClick, handled by the mode's own overlay in both modes).
 *
 * The cursor is stored in ATLAS coordinates, so the shown surface's frame is what puts it in the
 * right place: over a page that is the plain texel mapping, and over a source layer it also carries
 * the drawable's placement.  One cursor either way - the same point on the art, wherever it is seen.
 *
 * @param EditorSession session The session whose UV cursor this overlay draws.
 * @param UvEditFrame frame The shown surface's texel size plus its conversion from stored coordinates.
 * @param ViewportCamera? camera The displayed frame's camera; null skips drawing.
 * @param Int widthPx The area width in px.
 * @param Int heightPx The area height in px.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun UvCursorOverlay(
	session: EditorSession,
	frame: UvEditFrame,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val cursor by session.uvCursor.collectAsState()
	val cursorColors = LocalUmamoColors.current
	val cursorToDraw = cursor
	if (cursorToDraw == null || camera == null) {
		return
	}
	val (cursorDisplayX, cursorDisplayY) = frame.displayAt(cursorToDraw.u, cursorToDraw.v)
	Canvas(modifier = modifier.fillMaxSize()) {
		drawCursorMarker(
			center = worldToScreen(cursorDisplayX, cursorDisplayY, camera, IntSize(widthPx, heightPx)),
			tint = cursorColors.viewportBadgeText,
		)
	}
}