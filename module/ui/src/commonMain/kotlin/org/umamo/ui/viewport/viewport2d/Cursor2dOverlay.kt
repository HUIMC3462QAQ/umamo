package org.umamo.ui.viewport.viewport2d

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
 * The world-space 2D cursor marker: the authored dashed-ring crosshair (LocalUmamoIcons.cursor2d,
 * axis-colored arm tips) at its world position, in both modes (it anchors pivots and snaps regardless
 * of mode), projected through the frame camera and drawn at a screen-constant size.
 *
 * The cursor is a control, not HUD chrome - the mode gizmos place it (Shift+RightClick) and the
 * transform pivot and snap commands consume it - so it draws in this overlay of its own, mounted
 * above the gizmo chrome and below the informational HUD.  The layer itself is draw-only (no pointer
 * input), and it projects through the DISPLAYED frame's camera (like every world-anchored overlay
 * drawing) so it never swims against the raster.
 *
 * @param EditorSession session The session whose 2D cursor this overlay draws.
 * @param ViewportCamera? camera The displayed frame's camera (world<->screen); null skips drawing.
 * @param Int widthPx The viewport width in px.
 * @param Int heightPx The viewport height in px.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun Cursor2dOverlay(
	session: EditorSession,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val cursor by session.cursor2d.collectAsState()
	val cursorColors = LocalUmamoColors.current
	val cursorToDraw = cursor
	if (cursorToDraw == null || camera == null) {
		return
	}
	Canvas(modifier = modifier.fillMaxSize()) {
		drawCursorMarker(
			center = worldToScreen(cursorToDraw.worldX, cursorToDraw.worldZ, camera, IntSize(widthPx, heightPx)),
			tint = cursorColors.viewportBadgeText,
		)
	}
}