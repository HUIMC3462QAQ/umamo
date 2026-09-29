package org.umamo.ui.workspace.spaces.outliner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what a fold press does to the outliner's open branches.  During a search every branch shows open
 * whatever its fold says, so a press then has nothing to fold, and must not write a fold the rigger cannot
 * see change.
 */
class OutlinerViewStateTest {
	private val branch = "part:body"

	/** A press opens a closed branch and closes an open one. */
	@Test
	fun aFoldPressFlipsTheBranch() {
		val viewState = OutlinerViewState()
		assertFalse(viewState.isOpen(branch), "a branch other than the root starts closed")

		viewState.toggleFold(branch)
		assertTrue(viewState.isOpen(branch))

		viewState.toggleFold(branch)
		assertFalse(viewState.isOpen(branch))
	}

	/** The root starts open, so its first press closes it. */
	@Test
	fun theRootsFirstPressClosesIt() {
		val viewState = OutlinerViewState()

		viewState.toggleFold(OUTLINER_ROOT_ID)

		assertFalse(viewState.isOpen(OUTLINER_ROOT_ID))
	}

	/** During a search a press writes no fold, so the branch is as it was once the search ends. */
	@Test
	fun aFoldPressDuringASearchWritesNothing() {
		val viewState = OutlinerViewState()
		viewState.query = "arm"

		viewState.toggleFold(branch)
		viewState.toggleFold(OUTLINER_ROOT_ID)

		assertTrue(viewState.expanded.isEmpty())
		viewState.query = ""
		assertFalse(viewState.isOpen(branch))
		assertTrue(viewState.isOpen(OUTLINER_ROOT_ID))
	}

	/** Blanks are no search, so a press folds as usual. */
	@Test
	fun aBlankQueryIsNoSearch() {
		val viewState = OutlinerViewState()
		viewState.query = "   "

		assertFalse(viewState.searching)
		viewState.toggleFold(branch)
		assertTrue(viewState.isOpen(branch))
	}
}