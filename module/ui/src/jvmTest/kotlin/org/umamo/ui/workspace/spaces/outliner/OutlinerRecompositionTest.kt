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
	 * no other.  The reveal that follows writes each ancestor's fold, the root's too; with the root's fold
	 * already recorded the write changes nothing and the rows stay the same objects, so what runs is what the
	 * selection changed.
	 */
	@Test
	fun anOutsideSelectionRunsOnlyTheRowsWhoseFlagsChanged() =
		counting { counter ->
			val harness = mountOutliner()
			clickAt(slotPoint(harness.text.expand, OutlinerNames.HEAD))
			runOnIdle { harness.outlinerViewState.expanded[OUTLINER_ROOT_ID] = true }
			waitForIdle()
			counter.reset()

			runOnIdle {
				val eye = SelectionTarget.Drawable(OutlinerIds.eye)
				harness.session.setSelection(Selection(setOf(eye), eye))
			}
			waitForIdle()

			assertTrue(counter.runsOf("OutlinerSpace") >= 1, "the space must really have recomposed")
			assertEquals(2, counter.runsOf("OutlinerRowView"), "the selected row and its folder")
			assertEquals(2, counter.runsOf("OutlinerRowBody"))
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