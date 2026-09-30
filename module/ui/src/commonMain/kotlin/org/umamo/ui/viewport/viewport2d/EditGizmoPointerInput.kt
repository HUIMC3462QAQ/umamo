package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.handleIdleMeshSelectionEvent

/**
 * The Edit overlay's pointer loop.  Every event records the pointer, then goes to exactly one of three
 * branches: the modal transform while an operator is latched here, the circle brush while it is armed
 * here, and otherwise the idle element selection.  While another area owns a gesture (or a UV operator
 * runs, which never belongs to a viewport), or Zoom Region is armed here, the loop takes nothing.
 *
 * It runs for the life of its pointerInput, keyed on the area, and keeps the arguments it started with:
 * each is fixed for the area's life or a State holder read per event.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session owning the latches and the selection.
 * @param EditModalTransform modalTransform The area's modal transform (its gesture state and commit side).
 * @param MarqueeSelectController<MeshSelection> marquee The area's box / circle machinery.
 * @param State<List<EditMeshGeometry>> liveGeometryState The session meshes' live geometry.
 * @param State<ViewportCamera> liveCamera The area camera.
 * @param State<IntSize> liveSize The area size in pixels.
 */
internal suspend fun PointerInputScope.editGizmoPointerLoop(
	areaId: String,
	session: EditorSession,
	modalTransform: EditModalTransform,
	marquee: MarqueeSelectController<MeshSelection>,
	liveGeometryState: State<List<EditMeshGeometry>>,
	liveCamera: State<ViewportCamera>,
	liveSize: State<IntSize>,
) {
	val gesture = modalTransform.gesture
	awaitPointerEventScope {
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			gesture.lastPointer = change.position
			val latchedOperator = session.activeMeshOperator.value
			val latchedTool = session.activeSelectTool.value
			// A gesture belongs to its initiating area: while another viewport's operator or tool is
			// live - or a UV operator, which can never belong to a viewport area - this overlay is
			// fully inert (no drive, no picks, no marquee).  Escape and Enter stay global through
			// the shell ladder, and navigation (pan / zoom) still falls through.
			if ((latchedOperator != null && latchedOperator.areaId != areaId) ||
				(latchedTool != null && latchedTool.areaId != areaId) ||
				session.activeUvOperator.value != null
			) {
				continue
			}
			val operator = latchedOperator
			val selectTool = latchedTool
			val activeCamera = liveCamera.value
			val size = liveSize.value
			// Zoom Region armed for this area: the top-level region overlay owns the drag. This gizmo's
			// idle branch would otherwise also start a box (it does not check isConsumed), so yield.
			if (session.zoomRegionArmedArea.value == areaId) {
				continue
			}
			if (operator != null) {
				// MODAL: the shared controller drives the transform over the captured shape and
				// swallows every event (stale discard, virtual-pointer drive, cursor wrap,
				// RMB-cancel / LMB-confirm, proportional-radius scroll via the target).
				gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTransform, activeCamera, size, gesture.areaScreenOrigin)
			} else if (selectTool is ActiveSelectTool.Circle) {
				// CIRCLE SELECT: the shared controller paints / erases / commits the stroke and
				// consumes every event (paired with the navigation gate so MMB / wheel do not
				// also pan / zoom); see MarqueeSelectController.handleCircleEvent.
				marquee.handleCircleEvent(event, change, selectTool.radiusPx, activeCamera, size)
			} else {
				// IDLE: element selection, shared with the UV editor (Shift+RightClick places the
				// viewport's 2D cursor here).  Only primary-driven events are consumed, so middle-drag
				// pan and wheel zoom fall through; armed Box-select boxes on any press and disarms.
				handleIdleMeshSelectionEvent(
					event = event,
					change = change,
					session = session,
					geometries = liveGeometryState.value.map { it.gizmo },
					marquee = marquee,
					boxArmed = selectTool is ActiveSelectTool.BoxArmed,
					camera = activeCamera,
					size = size,
					placeCursor = session::setCursor2d,
				)
			}
		}
	}
}