package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.reimport.LayerMatch
import org.umamo.reimport.MatchSignals
import org.umamo.ui.workspace.spaces.outliner.hoverAt
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import org.umamo.ui.workspace.spaces.parameters.popupShows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what recomposes the Sources table.  A change that touches one row runs that row and no other, and
 * a change the table reads but no row shows runs the space and no row at all.
 *
 * No case here edits the model.  The open model reaches the composition through a static local, and a
 * changed static local turns skipping off for everything under its provider, so a model edit runs every
 * row of every space whatever the row is handed.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class SourcesRecompositionTest {
	/**
	 * Runs [body] with the table's body runs counted, and takes the counter off again whatever happens:
	 * the tracer is one per process.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun counting(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
		val counter = ComposableRunCounter(SOURCES_PACKAGE_PREFIX)
		Composer.setTracer(counter)
		try {
			runComposeUiTest { body(counter) }
		} finally {
			Composer.setTracer(null)
		}
	}

	/** The counter has to see the table compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheTableCompose() =
		counting { counter ->
			mountSources()

			assertTrue(counter.runsOf("SourcesSpace") >= 1)
			assertEquals(SOURCES_OPEN_ROWS, counter.runsOf("SourcesRowView"), "one per row the table shows")
			assertEquals(SOURCES_OPEN_ROWS, counter.runsOf("SourcesRowBody"))
		}

	/** Resting on a row runs that row's body once, and neither its frame, another row, nor the space. */
	@Test
	fun aHoverRunsOnlyTheHoveredRowsBody() =
		counting { counter ->
			mountSources()
			counter.reset()

			hoverAt(sourcesRowBox(SourcesNames.SKETCH).center)

			assertEquals(1, counter.runsOf("SourcesRowBody"), "the hovered row must really have recomposed")
			assertEquals(0, counter.runsOf("SourcesRowView"), "the frame holds the hover and does not read it")
			assertEquals(0, counter.runsOf("SourcesSpace"), "nothing can preview, so the space hears nothing")
		}

	/**
	 * Opening a row runs that row and the rows it brings in, and none of the rows around it.  The rows are
	 * built again for every fold, and a row built again for the same node equals the one before it.
	 */
	@Test
	fun aFoldRunsTheFoldedRowAndTheRowsItShows() =
		counting { counter ->
			val harness = mountSources()
			counter.reset()

			openRow(harness, SourcesRowKeys.HAIR)

			assertTrue(sourcesShows(SourcesNames.HAIR_ART), "the row must really have opened")
			assertEquals(2, counter.runsOf("SourcesRowView"), "the layer and the one tile under it")
			assertEquals(2, counter.runsOf("SourcesRowBody"))
		}

	/** Closing a row runs that row alone: the rows it moves up skip. */
	@Test
	fun closingARowRunsItAlone() =
		counting { counter ->
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			counter.reset()

			runOnIdle { harness.sourcesViewState.expanded[SourcesRowKeys.HAIR] = false }
			waitForIdle()

			assertTrue(!sourcesShows(SourcesNames.HAIR_ART), "the row must really have closed")
			assertEquals(1, counter.runsOf("SourcesRowView"))
			assertEquals(1, counter.runsOf("SourcesRowBody"))
		}

	/**
	 * A proposal published for one lost layer runs that layer's row and the file's row over it, which holds
	 * it, and no other: a tree built again is equal to the last wherever nothing changed.
	 */
	@Test
	fun aPublishedProposalRunsTheRowItChangesAndTheFileOverIt() =
		counting { counter ->
			val harness = mountSources()
			counter.reset()

			runOnIdle {
				harness.publishedSuggestions.value =
					mapOf((SourcesIds.body to SourcesKeys.BROW_OLD) to LayerMatch(SourcesKeys.BROW, WEAKER_SCORE, MatchSignals(1f, 1f, 1f, 1f, null, hashEqual = false)))
			}
			waitForIdle()

			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space must really have recomposed")
			assertEquals(2, counter.runsOf("SourcesRowView"))
			assertEquals(2, counter.runsOf("SourcesRowBody"))
		}

	/** A rested hover pops the row's art preview through the space, and runs no other row for it. */
	@Test
	fun aRestedHoverPopsThePreviewWithoutRunningOtherRows() =
		counting { counter ->
			mountSources(sourceArt = sourcesFixtureArt())
			counter.reset()

			restAt(sourcesRowBox(SourcesNames.LOOSE_ART).center)

			assertTrue(popupShows(SourcesNames.LOOSE_ART), "the preview must really have popped")
			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space draws the preview")
			assertEquals(1, counter.runsOf("SourcesRowBody"), "only the hovered row")
			assertEquals(0, counter.runsOf("SourcesRowView"))
		}

	/** A selection made elsewhere runs the row whose drawable it selected, and no other. */
	@Test
	fun anOutsideSelectionRunsOnlyTheRowItSelected() =
		counting { counter ->
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			openRow(harness, SourcesRowKeys.HAIR_ART)
			counter.reset()

			runOnIdle {
				val shadow = SelectionTarget.Drawable(SourcesIds.hairShadow)
				harness.session.setSelection(Selection(setOf(shadow), shadow))
			}
			waitForIdle()

			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space must really have recomposed")
			assertEquals(1, counter.runsOf("SourcesRowView"))
			assertEquals(1, counter.runsOf("SourcesRowBody"))
		}

	/** The watcher's serial makes the space ask about every file again; answers that did not change run no row. */
	@Test
	fun aProbeThatChangesNothingRunsNoRow() =
		counting { counter ->
			val harness = mountSources()
			counter.reset()

			runOnIdle { harness.sourceWatchSerial.value += 1 }
			waitForIdle()

			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space must really have recomposed")
			assertEquals(0, counter.runsOf("SourcesRowView"))
			assertEquals(0, counter.runsOf("SourcesRowBody"))
		}

	private companion object {
		const val SOURCES_PACKAGE_PREFIX = "org.umamo.ui.workspace.spaces.sources."

		/** A confidence other than the one the fixture publishes, so the proposal reads as changed. */
		const val WEAKER_SCORE = 0.75f
	}
}