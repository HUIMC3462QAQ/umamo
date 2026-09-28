package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two filters over the list: the header's search, and the one that keeps only what drives the
 * selection.  Both are view state, so neither may touch the document.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersFilterTest {
	/**
	 * Selects the fixture's one drawable, which is keyed on Body X.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 */
	private fun selectTheDrawable(harness: ParametersPanelHarness) {
		val target = SelectionTarget.Drawable(PanelIds.drawable)
		harness.session.setSelection(Selection(setOf(target), target))
	}

	/** The search matches the name a row shows. */
	@Test
	fun theSearchMatchesAName() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "breath" }
			waitForIdle()

			assertTrue(showsText(PanelNames.BREATH))
			assertFalse(showsText(PanelNames.BODY_X))
			assertEquals(1, countOfDescription(harness.text.reorderHandle), "a group with nothing left to show goes too")
		}

	/** The search matches the id as well, which is how a rigger coming from Cubism knows the axis. */
	@Test
	fun theSearchMatchesAnId() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "MouthForm" }
			waitForIdle()

			assertTrue(showsText(PanelNames.SMILE_SHAPE))
			assertTrue(showsText(PanelNames.FACE), "the group it sits in stays as its heading")
			assertFalse(showsText(PanelNames.EYE_OPEN))
		}

	/** A search opens a folded group for as long as it runs, and gives the fold back when it ends. */
	@Test
	fun aSearchOpensAFoldedGroupAndGivesTheFoldBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			assertFalse(showsText(PanelNames.ARM))

			runOnIdle { harness.viewState.query = "arm" }
			waitForIdle()
			assertTrue(showsText(PanelNames.ARM), "a match must not hide inside a folded group")

			runOnIdle { harness.viewState.query = "" }
			waitForIdle()
			assertFalse(showsText(PanelNames.ARM), "the group folds again once the search is over")
			assertTrue(harness.viewState.expandedGroups.isEmpty(), "a search must not write the fold it overrides")
		}

	/** Surrounding blanks are not part of what was searched for. */
	@Test
	fun theSearchIgnoresSurroundingBlanks() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "  breath  " }
			waitForIdle()
			assertTrue(showsText(PanelNames.BREATH))

			runOnIdle { harness.viewState.query = "   " }
			waitForIdle()
			assertEquals(PanelRows.COUNT, countOfDescription(harness.text.reorderHandle), "a blank search is no search")
		}

	/** The selection filter keeps the parameters that drive what is selected. */
	@Test
	fun theSelectionFilterKeepsWhatDrivesTheSelection() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle {
				selectTheDrawable(harness)
				harness.viewState.showOnlySelected = true
			}
			waitForIdle()

			assertTrue(showsText(PanelNames.BODY_X))
			assertEquals(1, countOfDescription(harness.text.reorderHandle))
		}

	/** With nothing selected the filter is inert, so the panel is never mysteriously empty. */
	@Test
	fun theSelectionFilterIsInertWithNothingSelected() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.showOnlySelected = true }
			waitForIdle()

			assertEquals(PanelRows.COUNT, countOfDescription(harness.text.reorderHandle))
		}

	/** Both filters restrict, so a row has to pass each of them. */
	@Test
	fun theTwoFiltersIntersect() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle {
				selectTheDrawable(harness)
				harness.viewState.showOnlySelected = true
				harness.viewState.query = "breath"
			}
			waitForIdle()
			assertEquals(0, countOfDescription(harness.text.reorderHandle), "Breath does not drive the selection")

			runOnIdle { harness.viewState.query = "body" }
			waitForIdle()
			assertTrue(showsText(PanelNames.BODY_X))
			assertEquals(1, countOfDescription(harness.text.reorderHandle))
		}

	/** A pad is one control, so it stays when either of its axes matches. */
	@Test
	fun aPadStaysWhenEitherAxisMatches() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "Angle Y" }
			waitForIdle()

			assertTrue(showsText(PanelNames.ANGLE_X), "the pad shows both its axes or neither")
			assertTrue(showsText(PanelNames.ANGLE_Y))
			assertEquals(1, countOfDescription(harness.text.reorderHandle))
		}

	/** A filter is a view of the document, never an edit to it. */
	@Test
	fun aFilterLeavesTheDocumentAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val modelBefore = harness.session.model.value
			val cursorBefore = harness.historyCursor

			runOnIdle {
				harness.viewState.query = "breath"
				harness.viewState.showOnlySelected = true
			}
			waitForIdle()

			assertTrue(harness.session.model.value === modelBefore)
			assertEquals(cursorBefore, harness.historyCursor)
		}
}