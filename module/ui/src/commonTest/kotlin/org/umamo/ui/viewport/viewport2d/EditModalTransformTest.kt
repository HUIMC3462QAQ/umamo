package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.floatValue
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Edit overlay's modal transform ([EditModalTransform]) over a real session, without Compose:
 * what a latch captures and when it drops, what a drive previews, what a confirm commits and registers,
 * and when the wheel and a mid-gesture proportional change apply.
 */
class EditModalTransformTest {
	/** Where the pointer rests as the gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** The transform under test, plus every model it pushed. */
	private class Rig(val session: EditorSession, val transform: EditModalTransform, val pushed: MutableList<PuppetModel>)

	/**
	 * A transform for the left area over [session], recording its pushes.
	 *
	 * @param EditorSession session The session.
	 * @return Rig The transform and its pushes.
	 */
	private fun rigOver(session: EditorSession): Rig {
		val pushed = ArrayList<PuppetModel>()
		return Rig(session, EditModalTransform(LEFT_AREA_ID, session, { model -> pushed.add(model) }), pushed)
	}

	/**
	 * Latches [kind] in the left area and begins the gesture the way the overlay's effect does.
	 *
	 * @param MeshOperatorKind kind The operator.
	 * @param Offset pointer Where the pointer rests at the latch.
	 * @param Boolean suppressProportional True for a latch that takes no weights.
	 */
	private fun Rig.latch(kind: MeshOperatorKind, pointer: Offset = gestureStart, suppressProportional: Boolean = false) {
		session.beginMeshOperator(kind, LEFT_AREA_ID, suppressProportional)
		assertEquals(kind, session.activeMeshOperator.value?.kind, "the session latched the operator")
		transform.gesture.lastPointer = pointer
		transform.begin(kind, geometriesOf(session), session.meshSelection.value)
	}

	/**
	 * The session meshes' live geometry, as the overlay composes it.
	 *
	 * @param EditorSession session The session.
	 * @return List The geometry.
	 */
	private fun geometriesOf(session: EditorSession): List<EditMeshGeometry> =
		editMeshGeometries(session.model.value, session.meshSelection.value.drawableIds)

	/**
	 * One drive at [pointer].
	 *
	 * @param Offset pointer The virtual pointer.
	 * @return Boolean Whether a preview was driven.
	 */
	private fun Rig.driveTo(pointer: Offset): Boolean = transform.drivePreview(pointer, RIG_CAMERA, RIG_AREA_SIZE)

	/** A latch whose selection emptied before the capture drops the operator and captures nothing. */
	@Test
	fun aLatchOverNothingDrops() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA_ID)

		rig.transform.begin(MeshOperatorKind.Grab, geometriesOf(rig.session), MeshSelectionOps.clear(rig.session.meshSelection.value))

		assertNull(rig.session.activeMeshOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertFalse(rig.transform.end(), "there was no gesture to tear down")
	}

	/** A Grab previews without committing, and the confirm commits the preview as one registered step. */
	@Test
	fun aGrabPreviewsThenCommitsOneStep() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		val session = rig.session
		val stepsBefore = session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		assertTrue(rig.driveTo(Offset(240f, 150f)))
		assertEquals(10f, rig.pushed.single().drawables.first { drawable -> drawable.id == RIG_QUAD }.mesh!!.positions[0], "the preview moved vertex 0")
		assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "a preview commits nothing")

		rig.transform.confirm()

		assertEquals(listOf(10f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals(LEFT_AREA_ID, assertNotNull(session.adjustableOperation.value).areaId)
		assertNull(session.activeMeshOperator.value)
		assertTrue(rig.transform.end(), "the gesture is torn down by the caller's effect")
		assertFalse(rig.transform.end(), "and only once")
	}

	/** A confirm before any drive has no preview to commit: no step, no registration, and the latch clears. */
	@Test
	fun aConfirmBeforeAnyDriveCommitsNothing() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		val stepsBefore = rig.session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		rig.transform.confirm()

		assertEquals(stepsBefore, rig.session.historyView.value.steps.size)
		assertNull(rig.session.adjustableOperation.value)
		assertNull(rig.session.activeMeshOperator.value)
	}

	/** A Vertex Slide over an active edge has no vertex to slide: it begins, then drops the latch. */
	@Test
	fun aSlideWithNoActiveVertexBeginsThenDrops() {
		val session = gizmoEditSession()
		session.setMeshSelectMode(MeshSelectMode.Edge)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, RIG_QUAD, MeshElement.Edge(0, 1)))
		val rig = rigOver(session)

		rig.latch(MeshOperatorKind.VertexSlide)

		assertNull(session.activeMeshOperator.value)
		assertTrue(rig.transform.end(), "the gesture began first, so its teardown resyncs the renderer")
	}

	/** A slide confirm registers the Factor row at the landed factor. */
	@Test
	fun aSlideRegistersItsFactor() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.VertexSlide, rigScreenOf(0f, 0f))

		rig.driveTo(Offset(200f, 112f))
		rig.transform.confirm()

		assertEquals(10f, rigPositionsOf(rig.session, RIG_QUAD)[0])
		val record = assertNotNull(rig.session.adjustableOperation.value)
		assertEquals(0.5f, record.parameters.floatValue(TransformParameterKeys.SLIDE_FACTOR, -1f), 1e-4f)
	}

	/** The wheel grows the radius one step and re-drives the preview at once. */
	@Test
	fun theWheelResizesTheRadiusAndReDrives() {
		val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(Offset(240f, 150f))

		rig.transform.onScroll(-1f, RIG_CAMERA, RIG_AREA_SIZE)

		assertEquals(10f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(session.proportionalEdit.value).radiusWorld, 1e-4f)
		assertEquals(2, rig.pushed.size, "the scroll drove a second preview")
	}

	/** The wheel leaves the radius alone for a slide and for a latch that takes no weights. */
	@Test
	fun theWheelIgnoresASlideAndASuppressedLatch() {
		for (suppressed in listOf(false, true)) {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			val rig = rigOver(session)
			if (suppressed) {
				rig.latch(MeshOperatorKind.Grab, suppressProportional = true)
			} else {
				rig.latch(MeshOperatorKind.VertexSlide, rigScreenOf(0f, 0f))
			}

			rig.transform.onScroll(-1f, RIG_CAMERA, RIG_AREA_SIZE)

			assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld, "suppressed = $suppressed")
			assertTrue(rig.pushed.isEmpty(), "suppressed = $suppressed")
		}
	}

	/** Proportional turned on mid-gesture weights a Grab's neighbors, and never a slide's or a suppressed latch's. */
	@Test
	fun aMidGestureProportionalChangeWeightsOnlyAWeightedGesture() {
		val halo = ProportionalEditState(ProportionalFalloff.Linear, 30f)
		for ((kind, suppressed, weighted) in listOf(
			Triple(MeshOperatorKind.Grab, false, true),
			Triple(MeshOperatorKind.VertexSlide, false, false),
			Triple(MeshOperatorKind.Grab, true, false),
		)) {
			val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			rig.latch(kind, rigScreenOf(0f, 0f), suppressed)
			val entry = assertNotNull(rig.transform.gesture.capture).transform.entries.single()
			assertTrue(entry.influence.isEmpty(), "the latch took no weights with proportional off")

			rig.transform.reapplyProportional(halo)

			assertEquals(weighted, entry.influence.isNotEmpty(), "$kind, suppressed = $suppressed")
		}
	}

	/** Another area's latch drives nothing here. */
	@Test
	fun anotherAreasLatchDrivesNothing() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginMeshOperator(MeshOperatorKind.Grab, "right")

		assertFalse(rig.driveTo(Offset(240f, 150f)))
		assertTrue(rig.pushed.isEmpty())
	}

	private companion object {
		const val LEFT_AREA_ID = "left"
	}
}