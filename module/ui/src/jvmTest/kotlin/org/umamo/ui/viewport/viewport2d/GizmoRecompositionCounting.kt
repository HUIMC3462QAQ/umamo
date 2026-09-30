package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import kotlin.test.assertEquals

/** The package whose composables the gizmo recomposition tests count. */
private const val VIEWPORT2D_PACKAGE_PREFIX = "org.umamo.ui.viewport.viewport2d."

/** The fixture function whose own body and lambdas are not counted. */
private const val FIXTURE_FUNCTION = "mountGizmoOverlays"

/**
 * Runs [body] with the viewport2d package's composable runs counted, and takes the counter off again
 * whatever happens: the tracer is one per process.
 *
 * @param Function body The case, handed the counter.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
internal fun countingGizmoRuns(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
	val counter = ComposableRunCounter(VIEWPORT2D_PACKAGE_PREFIX, FIXTURE_FUNCTION)
	Composer.setTracer(counter)
	try {
		runComposeUiTest { body(counter) }
	} finally {
		Composer.setTracer(null)
	}
}

/**
 * Asserts nothing of the package ran since the counter was last reset.
 *
 * @param ComposableRunCounter counter The counter.
 * @param String what What the case did, for the message.
 */
internal fun assertNothingRan(counter: ComposableRunCounter, what: String) {
	assertEquals(emptyMap(), counter.namedRuns(), "$what ran no composable")
	assertEquals(0, counter.lambdaRuns(), "$what ran no composable lambda")
}