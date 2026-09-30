package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera

/**
 * The one box-select gesture every selecting surface runs - the 2D viewport and the UV editor, Edit and
 * Object mode, armed (Blender's B) and not.  Armed differs only in how the box starts (every primary press
 * starts one, under the crosshairs the overlay draws) and in disarming afterward; everything else is one
 * set of rules:
 *   - A primary press starts the box - un-armed, unless the surface's press selects something first
 *     ([pressSelects]: Edit mode picks the element under the pointer).
 *   - A drag rubber-bands and the release applies the box (Shift adds).  Under the click threshold the
 *     release is a click: un-armed, the surface decides what it means ([onClick]: Object mode picks, Edit
 *     mode clears); armed, it only disarms.  An armed box disarms after any release.
 *   - Mid-drag, any right-click - Shift included - abandons the box, and disarms an armed one.  No cursor
 *     is placed.
 *   - With no box in flight, an armed right-click (Shift included) disarms; an un-armed Shift+RightClick
 *     places the space's cursor ([placeCursor]).
 *   - A cancelled release abandons the box, nothing landing: Compose ends a gesture whose pointer input is
 *     torn down (the overlay leaving composition) with a synthetic release that arrives already consumed,
 *     where a real release reaching an overlay never is.
 *   - A box whose armed state changes under it (the tool armed or cleared mid-drag) is abandoned; the
 *     overlay abandons it too, through [cancel], when a transform, the circle tool, Zoom Region, or another
 *     area takes over mid-drag, and on Escape.
 *
 * While a box is in flight the session's viewportGestureActive flag is up: it keeps navigation from also
 * panning, routes Escape to the gesture cancel, and holds undo until the release.  Only primary-driven
 * events and right-clicks are consumed, so middle-drag pan and wheel zoom fall through to the navigation
 * layer beneath.
 *
 * @param EditorSession session The session owning the select-tool latch and the gesture flag.
 * @param MarqueeSelectController<*> marquee The rubber-band this flow drives; its applyBox lands the box.
 * @param Function placeCursor Places the space's cursor at a Shift+RightClick, given the unprojected point.
 * @param Function onClick The un-armed sub-threshold click, given the release event and its change.
 * @param Function pressSelects Selects what is under an un-armed press, if the surface does that, and
 *   says whether it did (then no box starts); by default nothing does.
 * @param Function onBoxBegin Runs at the press that starts a box (the viewport's Object mode refreshes its
 *   selection anchors here); defaults to nothing.
 */
internal class BoxSelectFlow(
	private val session: EditorSession,
	private val marquee: MarqueeSelectController<*>,
	private val placeCursor: (Float, Float) -> Unit,
	private val onClick: (PointerEvent, PointerInputChange) -> Unit,
	private val pressSelects: (PointerEvent, PointerInputChange, ViewportCamera, IntSize) -> Boolean = { _, _, _, _ -> false },
	private val onBoxBegin: () -> Unit = {},
) {
	// Whether a box this flow started is in flight, and whether it started armed.  Plain vars, not snapshot
	// state: only the pointer loop and the cancel paths read them, never composition - the rubber-band the
	// draw pass observes lives in the marquee.
	private var boxing = false
	private var startedArmed = false

	/**
	 * Handles one pointer event while nothing else owns the area (no transform, no circle tool): the rules
	 * on the class.
	 *
	 * @param PointerEvent event The full pointer event (buttons and modifiers).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param Boolean armed True while Box select is armed in this area.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	fun handleEvent(event: PointerEvent, change: PointerInputChange, armed: Boolean, camera: ViewportCamera, size: IntSize) {
		if (boxing && armed != startedArmed) {
			cancel()
		}
		when (event.type) {
			PointerEventType.Press ->
				if (event.buttons.isSecondaryPressed) {
					if (boxing) {
						cancel()
						if (armed) {
							session.clearSelectTool()
						}
						change.consume()
					} else if (armed) {
						session.clearSelectTool()
						change.consume()
					} else if (event.keyboardModifiers.isShiftPressed) {
						// Shift+RightClick places the space's cursor at the pointer (Blender's gesture); the
						// Cursor pivot mode and the snap / mirror commands anchor on it.
						val (unprojectedX, unprojectedY) = screenToWorld(change.position.x, change.position.y, camera, size)
						placeCursor(unprojectedX, unprojectedY)
						change.consume()
					}
				} else if (event.buttons.isPrimaryPressed && !event.buttons.isTertiaryPressed && !boxing) {
					if (!armed && pressSelects(event, change, camera, size)) {
						change.consume()
					} else {
						onBoxBegin()
						marquee.beginBox(change.position)
						boxing = true
						startedArmed = armed
						session.setViewportGestureActive(true)
						change.consume()
					}
				}

			PointerEventType.Move ->
				if (boxing && marquee.dragBox(change.position)) {
					change.consume()
				}

			PointerEventType.Release ->
				if (boxing) {
					if (change.isConsumed) {
						cancel()
						return
					}
					val boxRelease = marquee.releaseBox(change.position, event.keyboardModifiers.isShiftPressed, camera, size)
					boxing = false
					session.setViewportGestureActive(false)
					if (boxRelease != BoxRelease.None) {
						if (boxRelease == BoxRelease.Click && !armed) {
							onClick(event, change)
						}
						if (armed) {
							// Armed Box-select is one-shot: disarm after the drag (or a bare click).
							session.clearSelectTool()
						}
						change.consume()
					}
				}

			else -> {}
		}
	}

	/**
	 * Abandons an in-flight box, dropping the rubber-band without touching the selection and lowering the
	 * gesture flag; a no-op when none is in flight, so callers invoke it unconditionally.
	 */
	fun cancel() {
		if (!boxing) {
			return
		}
		marquee.cancel()
		boxing = false
		session.setViewportGestureActive(false)
	}
}