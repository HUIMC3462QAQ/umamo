package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.floatValue
import org.umamo.render.pick.PickCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what the Edit-mode gizmo overlay does with the pointer and the session's requests, through the
 * overlay itself: a modal transform previews into the render service and commits one step when
 * confirmed, a gesture belongs to the area it started in, the idle pointer selects, and the requests
 * that need no geometry still answer when there is none to project.
 */
@OptIn(ExperimentalTestApi::class)
class EditGizmoOverlayGestureTest {
	/** Where the pointer rests before a gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = Offset(240f, 150f)

	/** A Grab moved and clicked commits ONE step, registers the strip for its area, and resyncs once. */
	@Test
	fun aClickConfirmsTheGrabAsOneStep() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size

			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			assertTrue(fixture.service.pushedModels.isNotEmpty(), "the move previewed through the render service")
			assertEquals(listOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD), "a preview commits nothing")
			clickIn(LEFT_AREA, tenUnitsRight)

			assertNull(session.activeMeshOperator.value, "the confirm cleared the operator")
			assertEquals(listOf(10f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD), "vertex 0 moved ten units right")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals(LEFT_AREA, assertNotNull(session.adjustableOperation.value).areaId, "the strip shows in the gesture's area")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the teardown resynced the renderer to the commit")
			assertEquals(1, fixture.service.pushedModels.count { pushed -> pushed === session.model.value }, "exactly one resync")
		}

	/** Enter confirms through the session's request, the same commit as a click. */
	@Test
	fun enterConfirmsTheGrab() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			session.requestMeshConfirm()
			waitForIdle()

			assertNull(session.activeMeshOperator.value)
			assertEquals(10f, rigPositionsOf(session, RIG_QUAD)[0])
		}

	/** A right-click cancels: nothing commits, and the renderer goes back to the committed model. */
	@Test
	fun aRightClickCancelsTheGrab() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)

			assertNull(session.activeMeshOperator.value)
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "nothing committed")
			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the renderer is back on the committed model")
		}

	/** The wheel resizes the proportional radius mid-gesture and re-drives the preview without a move. */
	@Test
	fun theWheelResizesTheRadiusAndReDrives() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			val pushesBefore = fixture.service.pushedModels.size

			scrollIn(LEFT_AREA, -1f)

			assertEquals(10f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(session.proportionalEdit.value).radiusWorld, 1e-4f, "wheel-up grows the radius one step")
			assertEquals(pushesBefore + 1, fixture.service.pushedModels.size, "the scroll alone re-drove the preview")
		}

	/** The wheel leaves the radius alone during a Vertex Slide, which takes no weights. */
	@Test
	fun theWheelLeavesTheRadiusAloneDuringASlide() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			moveIn(LEFT_AREA, listOf(rigScreenOf(0f, 0f)))
			session.beginMeshOperator(MeshOperatorKind.VertexSlide, LEFT_AREA)
			waitForIdle()

			scrollIn(LEFT_AREA, -1f)

			assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld)
		}

	/**
	 * A Vertex Slide follows the edge whose far end is nearest the pointer: halfway toward vertex 1, the
	 * vertex lands halfway along that edge, and the strip offers its Factor row.
	 */
	@Test
	fun aSlideLandsOnTheNearestEdge() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(rigScreenOf(0f, 0f)))
			session.beginMeshOperator(MeshOperatorKind.VertexSlide, LEFT_AREA)
			waitForIdle()
			val halfwayTowardVertexOne = Offset(200f, 112f)
			moveIn(LEFT_AREA, listOf(halfwayTowardVertexOne))

			clickIn(LEFT_AREA, halfwayTowardVertexOne)

			assertEquals(listOf(10f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD), "halfway along the edge to vertex 1")
			val record = assertNotNull(session.adjustableOperation.value)
			assertEquals(0.5f, record.parameters.floatValue(TransformParameterKeys.SLIDE_FACTOR, -1f), 1e-4f, "the Factor row holds where it landed")
		}

	/** A Vertex Slide with no active vertex to slide drops the latch and resyncs once. */
	@Test
	fun aSlideWithNoActiveVertexDrops() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.setMeshSelectMode(MeshSelectMode.Edge)
			moveIn(LEFT_AREA, listOf(gestureStart))
			clickIn(LEFT_AREA, Offset(200f, 110f))
			assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), session.meshSelection.value.elementsOf(RIG_QUAD), "the top edge is selected")

			session.beginMeshOperator(MeshOperatorKind.VertexSlide, LEFT_AREA)
			waitForIdle()

			assertNull(session.activeMeshOperator.value, "an edge is no vertex to slide")
			assertEquals(listOf(session.model.value), fixture.service.pushedModels.toList(), "one resync, and no preview")
		}

	/** A gesture latched in the left area leaves the right one inert: moves there drive nothing. */
	@Test
	fun theOtherAreaStaysInert() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()

			moveIn(RIGHT_AREA, listOf(gestureStart, tenUnitsRight))
			clickIn(RIGHT_AREA, tenUnitsRight)

			assertTrue(fixture.service.pushedModels.isEmpty(), "the right area drove no preview")
			assertEquals(MeshOperatorKind.Grab, session.activeMeshOperator.value?.kind, "and did not confirm the left area's gesture")
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0])
		}

	/** An idle drag from empty canvas boxes the vertices it encloses. */
	@Test
	fun anIdleDragBoxSelects() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session

			dragIn(LEFT_AREA, Offset(140f, 95f), listOf(Offset(170f, 115f), Offset(250f, 125f)))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(RIG_QUAD))
		}

	/** An idle click on a vertex selects it. */
	@Test
	fun anIdleClickSelectsTheVertex() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session

			clickIn(LEFT_AREA, rigScreenOf(20f, -20f))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2)), session.meshSelection.value.elementsOf(RIG_QUAD))
		}

	/** The armed Circle tool paints what the brush passes over, committed on release. */
	@Test
	fun aCircleStrokeSelects() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()

			dragIn(LEFT_AREA, rigScreenOf(20f, -20f), listOf(rigScreenOf(0f, -20f)))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2), MeshElement.Vertex(3)), session.meshSelection.value.elementsOf(RIG_QUAD))
		}

	/** Alt+Q over a single other mesh switches the edit to it; over a stack it asks for the picker. */
	@Test
	fun switchObjectFollowsThePick() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))

			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_OTHER, 0f, 0f), PickCandidate(RIG_QUAD, 1f, 0f))
			session.requestSwitchObjectUnderCursor(LEFT_AREA)
			waitForIdle()
			assertEquals(LEFT_AREA, fixture.overlapRequests.single().first, "a stack opens the picker in the asking area")
			assertEquals(gestureStart, fixture.overlapRequests.single().second, "anchored at the host's pointer")

			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_OTHER, 0f, 0f))
			session.requestSwitchObjectUnderCursor(LEFT_AREA)
			waitForIdle()
			assertEquals(RIG_OTHER, session.meshSelection.value.activeDrawableId, "one candidate switches directly")
		}

	/**
	 * With every mesh in the edit behind an unprojectable parent there is nothing to draw, but the
	 * requests still answer - with the notice, once, from the asking area alone.
	 */
	@Test
	fun requestsAnswerWithNoGeometry() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(editing = listOf(RIG_HIDDEN)))
			val session = fixture.session
			val serialBefore = session.notice.value?.serial ?: 0L

			session.requestRip(LEFT_AREA)
			waitForIdle()

			val notice = assertNotNull(session.notice.value)
			assertEquals("notice.edit.noEditableGeometry", notice.messageKey)
			assertEquals(serialBefore + 1, notice.serial, "one notice, not one per area")
		}
}