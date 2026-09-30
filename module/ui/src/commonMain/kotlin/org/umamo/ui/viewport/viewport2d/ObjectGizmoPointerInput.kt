package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ObjectPickController

/**
 * The Object overlay's pointer loop.  Every event records the pointer, then goes to exactly one branch: the
 * modal transform while an operator is latched here, the circle brush or the armed box while that tool is
 * armed here, and otherwise the idle click pick and box.  While another area owns a gesture (or a UV
 * operator runs, which never belongs to a viewport) the loop takes nothing and drops its own idle box.
 *
 * There is no Zoom Region yield (the Edit loop has one): an armed Zoom Region overlay sits above this one,
 * and Compose delivers a press to the top-most sibling that is hit, so a drag started while it is armed
 * never reaches this loop.
 *
 * It runs for the life of its pointerInput, keyed on the area, and keeps the arguments it started with:
 * each is fixed for the area's life or a State holder read per event.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session owning the latches and the selection.
 * @param ObjectModalTransform modalTransform The area's modal transform (its gesture state and commit side).
 * @param MarqueeSelectController<Selection> marquee The area's box / circle machinery.
 * @param ObjectPickController objectPick The area's click pick and box flows.
 * @param State<ViewportCamera> liveCamera The area camera.
 * @param State<IntSize> liveSize The area size in pixels.
 */
internal suspend fun PointerInputScope.objectGizmoPointerLoop(
	areaId: String,
	session: EditorSession,
	modalTransform: ObjectModalTransform,
	marquee: MarqueeSelectController<Selection>,
	objectPick: ObjectPickController,
	liveCamera: State<ViewportCamera>,
	liveSize: State<IntSize>,
) {
	val gesture = modalTransform.gesture
	awaitPointerEventScope {
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			gesture.lastPointer = change.position
			val latchedOperator = session.activeObjectOperator.value
			val latchedTool = session.activeSelectTool.value
			// A gesture belongs to its initiating area: while another viewport's operator or tool is
			// live - or a UV operator, which can never belong to a viewport area - this overlay is
			// fully inert (no drive, no picks, no marquee).  Escape and Enter stay global through
			// the shell ladder, and navigation (pan / zoom) still falls through.
			if ((latchedOperator != null && latchedOperator.areaId != areaId) ||
				(latchedTool != null && latchedTool.areaId != areaId) ||
				session.activeUvOperator.value != null
			) {
				objectPick.cancel()
				continue
			}
			val operator = latchedOperator
			val tool = latchedTool
			val activeCamera = liveCamera.value
			val size = liveSize.value
			// A tool or operator armed mid-drag (via its keymap command) supersedes the un-armed box:
			// drop the rubber-band so its release handler cannot fire into the armed gesture's state
			// (the controller's cancel no-ops when no box is in flight).
			if (operator != null || tool != null) {
				objectPick.cancel()
			}
			// Compose ends a gesture whose pointer input is cancelled - this overlay leaving composition,
			// the pointer taken away - with a synthetic release that arrives already consumed (a real
			// release reaching this loop never is: nothing beneath the overlay takes the pointer first).
			// A cancelled select gesture is abandoned, never landed: the box applies nothing and the
			// stroke goes uncommitted.  A modal transform has its own teardown, so it is left to that.
			if (operator == null && event.type == PointerEventType.Release && change.isConsumed) {
				marquee.discard()
				objectPick.cancel()
				continue
			}
			if (operator != null) {
				// MODAL transform: the shared controller drives every captured drawable over the
				// shared pivot and swallows every event (stale discard, virtual-pointer drive,
				// cursor wrap, RMB-cancel / LMB-confirm).
				gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTransform, activeCamera, size, gesture.areaScreenOrigin)
			} else if (tool is ActiveSelectTool.Circle) {
				// CIRCLE SELECT: the shared controller paints drawables by centroid, previews the
				// stroke through the GPU tint, and consumes every event; see
				// MarqueeSelectController.handleCircleEvent.
				marquee.handleCircleEvent(event, change, tool.radiusPx, activeCamera, size)
			} else if (tool is ActiveSelectTool.BoxArmed) {
				// BOX SELECT (armed): a drag rubber-bands; on release every drawable whose centroid is enclosed is
				// selected (Shift adds).  A right-click or a sub-threshold click just disarms.  One-shot.
				objectPick.handleArmedBoxEvent(event, change, activeCamera, size)
			} else {
				// IDLE (nothing armed): the primary button owns picking here, through the shared
				// controller - a press starts a provisional rubber-band, a drag past the threshold
				// box-selects on release (Shift adds), a sub-threshold release is the click pick
				// (replace / toggle / Alt overlap), Shift+RightClick places the 2D cursor, and a
				// right-click or Escape abandons the drag.  Only primary-driven events are
				// consumed, so middle-drag pan and wheel zoom fall through to the navigation layer.
				objectPick.handleIdleEvent(event, change, activeCamera, size)
			}
		}
	}
}