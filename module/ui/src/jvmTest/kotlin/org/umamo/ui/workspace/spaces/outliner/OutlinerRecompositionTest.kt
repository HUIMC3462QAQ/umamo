package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.popupShows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what recomposes the outliner.  A row reads its own hover, so resting on it runs that row's body
 * and nothing around it; and everything the space hands a row compares equal when nothing about that row
 * changed, so a selection made elsewhere runs only the rows whose flags it changed.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class OutlinerRecompositionTest {
	/**
	 * Runs [body] with the outliner's body runs counted, and takes the counter off again whatever happens:
	 * the tracer is one per process.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun counting(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
		val counter = ComposableRunCounter(OUTLINER_PACKAGE_PREFIX)
		Composer.setTracer(counter)
		try {
			runComposeUiTest { body(counter) }
		} finally {
			Composer.setTracer(null)
		}
	}

	/** The counter has to see the outliner compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheOutlinerCompose() =
		counting { counter ->
			mountOutliner()

			assertTrue(counter.runsOf("OutlinerSpace") >= 1)
			assertEquals(OPEN_ROWS, counter.runsOf("OutlinerRowView"), "one per row the list shows")
			assertEquals(OPEN_ROWS, counter.runsOf("OutlinerRowBody"))
		}

	/** Resting on a row runs that row's body once, and neither its frame, its slots, nor the space. */
	@Test
	fun aHoverRunsOnlyTheHoveredRowsBody() =
		counting { counter ->
			mountOutliner()
			counter.reset()

			hoverAt(rowBox(OutlinerNames.HEAD).center)

			assertEquals(1, counter.runsOf("OutlinerRowBody"), "the hovered row must really have recomposed")
			assertEquals(0, counter.runsOf("OutlinerRowView"))
			assertEquals(0, counter.runsOf("OutlinerSpace"))
			assertEquals(0, counter.runsOf("ChevronSlot"))
			assertEquals(0, counter.runsOf("OutlinerIconSlot"))
		}

	/**
	 * A selection made elsewhere runs the selected row and the part folder that now holds the selection, and
	 * no other.  The reveal that follows opens only what is closed, so with the row's branches open it writes
	 * no fold and the rows are not built again: what runs is what the selection changed.
	 */
	@Test
	fun anOutsideSelectionRunsOnlyTheRowsWhoseFlagsChanged() =
		counting { counter ->
			val harness = mountOutliner()
			clickAt(slotPoint(harness.text.expand, OutlinerNames.HEAD))
			counter.reset()

			runOnIdle {
				val eye = SelectionTarget.Drawable(OutlinerIds.eye)
				harness.session.setSelection(Selection(setOf(eye), eye))
			}
			waitForIdle()

			assertTrue(counter.runsOf("OutlinerSpace") >= 1, "the space must really have recomposed")
			assertEquals(2, counter.runsOf("OutlinerRowView"), "the selected row and its folder")
			assertEquals(2, counter.runsOf("OutlinerRowBody"))
			assertEquals(mapOf(OutlinerRowKeys.HEAD to true), harness.outlinerViewState.expanded.toMap(), "the reveal wrote no fold")
		}

	/**
	 * Opening a branch runs the branch's own row and the rows it brings in, and none of the rows above it.
	 * The rows are built again for every fold, and a row built again for the same node equals the one
	 * before it.
	 */
	@Test
	fun aFoldRunsTheFoldedRowAndTheRowsItShows() =
		counting { counter ->
			val harness = mountOutliner()
			counter.reset()

			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.LIMBS) }
			waitForIdle()

			assertTrue(outlinerShows(OutlinerNames.CHEST), "the branch must really have opened")
			assertEquals(3, counter.runsOf("OutlinerRowView"), "the last branch's row and the two rows it shows")
			assertEquals(3, counter.runsOf("OutlinerRowBody"))
		}

	/**
	 * The rows under an opened branch move down the list, and a row draws its guides by where it sits, so
	 * they run with the branch; the rows above it still do not.
	 */
	@Test
	fun aFoldRunsTheRowsItMovesAndNoneAbove() =
		counting { counter ->
			val harness = mountOutliner()
			counter.reset()

			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.HEAD) }
			waitForIdle()

			assertTrue(outlinerShows(OutlinerNames.EYE), "the branch must really have opened")
			assertEquals(5, counter.runsOf("OutlinerRowView"), "the branch's row, the two rows it shows, and the two it moved down")
		}

	/** Closing a branch at the end of the list runs that branch's row alone. */
	@Test
	fun closingTheLastBranchRunsItsRowAlone() =
		counting { counter ->
			val harness = mountOutliner()
			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.LIMBS) }
			waitForIdle()
			counter.reset()

			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.LIMBS) }
			waitForIdle()

			assertEquals(1, counter.runsOf("OutlinerRowView"))
		}

	/** A rested hover pops the row's art preview through the space, and runs no other row for it. */
	@Test
	fun aRestedHoverPopsThePreviewWithoutRunningOtherRows() =
		counting { counter ->
			mountOutliner(thumbnails = StubThumbnails)
			counter.reset()

			hoverAt(rowBox(OutlinerNames.HEAD).center)

			assertTrue(popupShows(OutlinerNames.HEAD), "the preview must really have popped")
			assertTrue(counter.runsOf("OutlinerSpace") >= 1, "the space draws the preview")
			assertEquals(1, counter.runsOf("OutlinerRowBody"), "only the hovered row")
			assertEquals(0, counter.runsOf("OutlinerRowView"))
		}

	private companion object {
		const val OUTLINER_PACKAGE_PREFIX = "org.umamo.ui.workspace.spaces.outliner."

		/** The rows the list shows as the fixture opens: the root and its four children. */
		const val OPEN_ROWS = 5
	}
}