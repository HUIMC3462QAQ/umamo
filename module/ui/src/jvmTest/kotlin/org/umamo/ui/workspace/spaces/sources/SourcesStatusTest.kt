package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the status each row's glyph reads as in the table, and when a file's presence is asked about again.
 * Which glyph and tint a status draws with is pinned without a composition, in SourcesRowVisualTest.
 */
@OptIn(ExperimentalTestApi::class)
class SourcesStatusTest {
	/** A file reads present or missing by what the probe answers for its path. */
	@Test
	fun aFileReadsItsPresenceFromTheProbe() =
		runComposeUiTest {
			val harness = mountSources()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.BODY))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.FACE))
		}

	/** A layer reads bound, bound by name, unbound, ignored, or under review; a tile on no page reads unplaced. */
	@Test
	fun aLayerAndATileReadTheirBinding() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.EYE)

			assertTrue(sourcesRowHasSlot(harness.text.sourcesBound, SourcesNames.HAIR))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesBoundByName, SourcesNames.EYE))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnbound, SourcesNames.SKETCH))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesIgnored, SourcesNames.NOTES))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesNeedsReview, SourcesNames.BROW_OLD))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnplaced, SourcesNames.EYE_ART))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnbound, harness.text.sourcesUnboundArt), "the group's heading carries its status")
		}

	/** A file the probe cannot answer for reads unknown, never missing. */
	@Test
	fun aFileTheProbeCannotAnswerForReadsUnknown() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.BODY] = null
				harness.sourcesViewState.refreshSerial++
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnknown, SourcesNames.BODY))
			assertFalse(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.BODY))
		}

	/** A file's presence is remembered: a change on disk shows only once something asks again. */
	@Test
	fun presenceIsNotAskedAboutOnEveryComposition() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.BODY] = false
				harness.sourcesViewState.query = SourcesNames.BODY
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.BODY), "the table composed again and asked nothing")
		}

	/** The header's refresh asks about every file again. */
	@Test
	fun aRefreshProbesAgain() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.BODY] = false
				harness.sourcePresenceByPath[SourcesPaths.FACE] = true
				harness.sourcesViewState.refreshSerial++
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.BODY))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.FACE))
		}

	/** The watcher's serial asks about every file again, so a file that came back shows without a click. */
	@Test
	fun theWatchersSerialProbesAgain() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.FACE] = true
				harness.sourceWatchSerial.value += 1
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.FACE))
		}
}