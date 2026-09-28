package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.runtime.model.ParameterLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Edit-mode lock.  Edit mode edits the neutral base mesh and is pinned to the neutral pose, so
 * no pose write may move the session out from under it; the viewport relies on the panel for that.
 *
 * What the lock covers is pose writes only.  A range, a link, a name, a create, and a delete are
 * document edits, and stay available.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersEditLockTest {
	/**
	 * A harness mounted and put in Edit mode.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Boolean showHeader Whether to mount the header strip as well.
	 * @return ParametersPanelHarness The harness, in Edit mode.
	 */
	private fun lockedPanel(test: ComposeUiTest, showHeader: Boolean = false): ParametersPanelHarness {
		val harness = ParametersPanelHarness(showHeader = showHeader)
		test.mountParametersPanel(harness)
		test.runOnIdle { harness.enterEditMode() }
		test.waitForIdle()
		return harness
	}

	/**
	 * Asserts nothing about the pose moved: not the session, not the renderer, not the row, not the history.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Int cursorBefore The history position before the gesture.
	 */
	private fun assertPoseUntouched(test: ComposeUiTest, harness: ParametersPanelHarness, cursorBefore: Int) {
		assertEquals(PANEL_FIXTURE_POSE, harness.session.pose.value, "a locked panel must not write the session's pose")
		// Edit mode hands the renderer an empty pose; a preview would have put an entry in it.
		assertNull(harness.live(PanelIds.bodyX), "a locked panel must not preview")
		assertNull(harness.live(PanelIds.angleX))
		assertEquals(cursorBefore, harness.historyCursor, "a locked panel must not record a step")
		assertTrue(test.showsText(PanelValues.BODY_X), "the rows keep showing the pose Object mode left")
		assertTrue(test.showsText(PanelValues.ANGLE_X))
	}

	/** A slider scrub does nothing. */
	@Test
	fun editModeLocksASliderScrub() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			drag(slider.at(0.6f), listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** A pad drag does nothing. */
	@Test
	fun editModeLocksAPadDrag() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val cursorBefore = harness.historyCursor

			drag(pad.center, listOf(pad.at(0.7f, 0.3f), Offset(pad.right + 40f, pad.top - 40f)))

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** A typed value does nothing. */
	@Test
	fun editModeLocksATypedValue() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField(PanelValues.BODY_X, "7")

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** The reset glyph does nothing. */
	@Test
	fun editModeLocksTheResetGlyph() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val cursorBefore = harness.historyCursor

			clickDescribed(harness.text.reset, index = 4)

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** The header's Reset All does nothing: a locked panel must not be writable from its own header. */
	@Test
	fun editModeLocksResetAll() =
		runComposeUiTest {
			val harness = lockedPanel(this, showHeader = true)
			val cursorBefore = harness.historyCursor

			clickDescribed(harness.text.resetAll)

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** The header's lock follows the mode as the body's does: Reset All is one step again once Edit mode is left. */
	@Test
	fun resetAllFollowsTheModeBothWays() =
		runComposeUiTest {
			val harness = lockedPanel(this, showHeader = true)
			clickDescribed(harness.text.resetAll)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "Edit mode has to lock Reset All")

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor
			clickDescribed(harness.text.resetAll)
			assertEquals(0f, harness.committed(PanelIds.bodyX), "leaving Edit mode has to unlock it again")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.session.undo() }
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			clickDescribed(harness.text.resetAll)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "and entering it again has to lock it again")
		}

	/** The lock follows the mode both ways inside one composition, with no stale answer either way. */
	@Test
	fun theLockFollowsTheModeBothWays() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val pastTheEnd = listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y))

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			drag(slider.at(0.6f), pastTheEnd)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "Edit mode has to lock the scrub")

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor
			drag(slider.at(0.6f), pastTheEnd)
			assertEquals(10f, harness.committed(PanelIds.bodyX), "leaving Edit mode has to unlock it again")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			drag(slider.at(0.6f), listOf(slider.at(0.3f), Offset(slider.left - 40f, slider.center.y)))
			assertEquals(10f, harness.committed(PanelIds.bodyX), "and entering it again has to lock it again")
		}

	/** A range is the document's, not the pose's, so Edit mode leaves it editable. */
	@Test
	fun editModeLeavesTheRangeEditable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField("10.00", "20")

			assertEquals(20f, harness.session.model.value.parameters.first { parameter -> parameter.id == PanelIds.bodyX }.max)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A link and an unlink are document edits too. */
	@Test
	fun editModeLeavesLinkAndUnlinkAvailable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val cursorBefore = harness.historyCursor

			// In list order the link glyphs belong to Eye Open, Smile Shape, and Body X.
			clickDescribed(harness.text.link, index = 2)
			assertTrue(ParameterLink(PanelIds.bodyX, PanelIds.breath) in harness.session.model.value.parameterLinks)
			assertEquals(cursorBefore + 1, harness.historyCursor)

			clickDescribed(harness.text.unlink, index = 0)
			assertFalse(ParameterLink(PanelIds.angleX, PanelIds.angleY) in harness.session.model.value.parameterLinks)
			assertEquals(cursorBefore + 2, harness.historyCursor)
		}

	/** A rename is a document edit. */
	@Test
	fun editModeLeavesRenameAvailable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			doubleClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			// Taken after the double click: a press on a name also targets the row, which is a step of its own.
			val cursorBefore = harness.historyCursor

			typeIntoRenameField("Air")
			pressKey(Key.Enter)

			assertEquals("Air", harness.session.model.value.parameters.first { parameter -> parameter.id == PanelIds.breath }.name)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A create and a delete are document edits. */
	@Test
	fun editModeLeavesCreateAndDeleteAvailable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val parametersBefore = harness.session.model.value.parameters.size
			val cursorBefore = harness.historyCursor

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.addKeyFormParameter)
			assertEquals(parametersBefore + 1, harness.session.model.value.parameters.size)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			pressKey(Key.Escape)

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.deleteParameter)
			assertTrue(harness.session.model.value.parameters.none { parameter -> parameter.id == PanelIds.breath })
		}
}