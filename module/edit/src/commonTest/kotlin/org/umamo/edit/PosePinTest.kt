package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pose while Edit mode pins it.
 *
 * Edit mode edits the neutral state of the base mesh and shows the rig at rest, so the pose Object mode
 * left is held until Edit mode is left.  The session is where that is enforced: a caller asking for a pose
 * move is refused whoever it is, and what it hands over is never trusted - in Edit mode the pose a view
 * has on hand is the rest pose it is being shown, not the rig's.
 */
class PosePinTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val meshId = DrawableId("mesh")
	private val objectModePose = mapOf(angleX to 12f, angleY to -7f)

	/**
	 * A session over two parameters and one meshed drawable, posed off its defaults.
	 *
	 * @return EditorSession The session, in Object mode.
	 */
	private fun session(): EditorSession =
		EditorSession(
			PuppetModel(
				parameters =
					listOf(
						Parameter(angleX, angleX.raw, min = -30f, max = 30f, default = 0f),
						Parameter(angleY, angleY.raw, min = -30f, max = 30f, default = 0f),
					),
				parts = emptyList(),
				deformers = emptyList(),
				drawables =
					listOf(
						Drawable(
							id = meshId,
							name = "mesh",
							parentDeformerId = null,
							blendMode = BlendMode.Normal,
							maskedBy = emptyList(),
							mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2)),
							geometryGrid = null,
						),
					),
				rootChildren = emptyList(),
				rootPartId = null,
			),
			objectModePose,
		)

	/**
	 * The same session once it is in Edit mode.
	 *
	 * @return EditorSession The session, which must really have entered Edit mode.
	 */
	private fun pinnedSession(): EditorSession {
		val editorSession = session()
		val target = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(target), target))
		editorSession.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, editorSession.mode.value, "the fixture must really be in Edit mode")
		return editorSession
	}

	private fun keyAt(keyIndex: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:mesh/GEOMETRY", keyIndex)

	/** The pose is pinned in Edit mode and nowhere else. */
	@Test
	fun editModeAlonePinsThePose() {
		val editorSession = session()
		assertFalse(editorSession.posePinned)

		val target = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(target), target))
		editorSession.setMode(EditorMode.Edit)
		assertTrue(editorSession.posePinned)

		editorSession.setMode(EditorMode.Object)
		assertFalse(editorSession.posePinned)
	}

	/** A pose commit is refused: the pose stays, and nothing is recorded. */
	@Test
	fun aPoseCommitIsRefusedWhilePinned() {
		val editorSession = pinnedSession()
		val cursorBefore = editorSession.historyView.value.cursor

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), objectModePose + (angleX to 25f))

		assertEquals(objectModePose, editorSession.pose.value)
		assertEquals(cursorBefore, editorSession.historyView.value.cursor)
	}

	/**
	 * A commit of the pose a view was showing must not become the rig's pose.  In Edit mode that pose is
	 * the rest pose, which names no parameter at all, so taking it would drop every value the rig held.
	 */
	@Test
	fun theDisplayedRestPoseNeverReplacesThePinnedOne() {
		val editorSession = pinnedSession()

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 25f))
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), emptyMap())

		assertEquals(objectModePose, editorSession.pose.value)
	}

	/** A click on a key selects it, as one step, and leaves the pose where it is pinned. */
	@Test
	fun aKeyClickSelectsAndLeavesThePinnedPose() {
		val editorSession = pinnedSession()
		val cursorBefore = editorSession.historyView.value.cursor

		editorSession.selectKeysAtPose(setOf(keyAt(0)), mapOf(angleX to 25f))

		assertEquals(setOf(keyAt(0)), editorSession.keySelection.value)
		assertEquals(objectModePose, editorSession.pose.value)
		assertEquals(cursorBefore + 1, editorSession.historyView.value.cursor, "the selection is still a step")

		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty())
		assertEquals(objectModePose, editorSession.pose.value)
	}

	/** A click on the key already selected records nothing, pinned or not. */
	@Test
	fun aRepeatedKeyClickRecordsNothingWhilePinned() {
		val editorSession = pinnedSession()
		editorSession.selectKeysAtPose(setOf(keyAt(0)), emptyMap())
		val cursorBefore = editorSession.historyView.value.cursor

		editorSession.selectKeysAtPose(setOf(keyAt(0)), emptyMap())

		assertEquals(cursorBefore, editorSession.historyView.value.cursor)
		assertEquals(objectModePose, editorSession.pose.value)
	}

	/** The pose moves again the moment Edit mode is left. */
	@Test
	fun thePoseMovesAgainOnceEditModeIsLeft() {
		val editorSession = pinnedSession()
		editorSession.setMode(EditorMode.Object)

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), objectModePose + (angleX to 25f))

		assertEquals(25f, editorSession.pose.value[angleX])
		assertEquals(-7f, editorSession.pose.value[angleY])
	}

	/**
	 * A range is the document's, and an edit to it is allowed in Edit mode.  The pose it holds has to stay
	 * inside the range, so that edit still pulls the pinned value in with it.
	 */
	@Test
	fun aRangeEditStillClampsThePinnedPose() {
		val editorSession = pinnedSession()

		editorSession.setParameterRange(angleX, min = -5f, default = 0f, max = 5f)

		assertEquals(5f, editorSession.pose.value[angleX])
		assertEquals(-7f, editorSession.pose.value[angleY])
	}

	/** Undo and redo restore what they recorded, pinned or not. */
	@Test
	fun undoStillRestoresAPoseWhilePinned() {
		val editorSession = session()
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), objectModePose + (angleX to 25f))
		val target = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(target), target))
		editorSession.setMode(EditorMode.Edit)

		editorSession.undo()
		editorSession.undo()
		editorSession.undo()

		assertEquals(objectModePose, editorSession.pose.value)
	}
}