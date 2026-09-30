package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Object overlay's modal transform ([ObjectModalTransform]) over a real session, without
 * Compose: a Grab previews the whole drawable and commits it as one registered TransformDrawables step,
 * a selection with nothing projectable drops the latch, and a cancel commits nothing.
 */
class ObjectModalTransformTest {
	/** Where the pointer rests as the gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** The transform under test, plus every model it pushed. */
	private class Rig(val session: EditorSession, val transform: ObjectModalTransform, val pushed: MutableList<PuppetModel>)

	/**
	 * Latches [kind] in the left area over [session] and begins the gesture the way the overlay's effect does.
	 *
	 * @param EditorSession session The session, in Object mode with a selection.
	 * @param MeshOperatorKind kind The operator.
	 * @return Rig The transform and its pushes.
	 */
	private fun latched(session: EditorSession, kind: MeshOperatorKind = MeshOperatorKind.Grab): Rig {
		val pushed = ArrayList<PuppetModel>()
		val transform = ObjectModalTransform(LEFT_AREA_ID, session, { model -> pushed.add(model) })
		session.beginObjectOperator(kind, LEFT_AREA_ID)
		assertEquals(kind, session.activeObjectOperator.value?.kind, "the session latched the operator")
		transform.gesture.lastPointer = gestureStart
		transform.begin(kind)
		return Rig(session, transform, pushed)
	}

	/** A Grab previews the whole drawable, and the confirm commits it as one registered step. */
	@Test
	fun aGrabPreviewsThenCommitsOneStep() {
		val session = gizmoObjectSession()
		val stepsBefore = session.historyView.value.steps.size
		val rig = latched(session)

		assertTrue(rig.transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE))
		assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "a preview commits nothing")
		rig.transform.confirm()

		assertEquals(listOf(10f, 0f, 30f, 0f, 30f, 20f, 10f, 20f), rigPositionsOf(session, RIG_QUAD))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals(LEFT_AREA_ID, record.areaId)
		assertIs<MeshChange.TransformDrawables>(record.change)
		assertNull(session.activeObjectOperator.value)
		assertTrue(rig.transform.end())
	}

	/** A selection whose drawables cannot be projected captures nothing and drops the latch. */
	@Test
	fun aLatchOverNothingProjectableDrops() {
		val rig = latched(gizmoObjectSession(selected = listOf(RIG_HIDDEN)))

		assertNull(rig.session.activeObjectOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertFalse(rig.transform.end())
	}

	/** A cancel commits nothing; the teardown is still the caller's. */
	@Test
	fun aCancelCommitsNothing() {
		val session = gizmoObjectSession()
		val stepsBefore = session.historyView.value.steps.size
		val rig = latched(session)
		rig.transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE)

		rig.transform.cancel()

		assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0])
		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertNull(session.activeObjectOperator.value)
		assertTrue(rig.transform.end(), "the gesture was live, so the caller resyncs")
	}

	/** Another area's latch drives nothing here. */
	@Test
	fun anotherAreasLatchDrivesNothing() {
		val session = gizmoObjectSession()
		val pushed = ArrayList<PuppetModel>()
		val transform = ObjectModalTransform(LEFT_AREA_ID, session, { model -> pushed.add(model) })
		session.beginObjectOperator(MeshOperatorKind.Grab, "right")

		assertFalse(transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE))
		assertTrue(pushed.isEmpty())
	}

	private companion object {
		const val LEFT_AREA_ID = "left"
	}
}