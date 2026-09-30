package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformAxisConstraint
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what recomposes the viewport gizmo overlays.  The pointer, the live preview, and the modal HUD's
 * inputs are read where they are drawn, not where the overlay composes, so hovering, driving a gesture,
 * and changing the proportional radius or the axis mid-gesture redraw the chrome and run no composable.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class EditGizmoRecompositionTest {
	/**
	 * Runs [body] with the viewport2d package's composable runs counted, and takes the counter off again
	 * whatever happens: the tracer is one per process.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun counting(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
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
	private fun assertNothingRan(counter: ComposableRunCounter, what: String) {
		assertEquals(emptyMap(), counter.namedRuns(), "$what ran no composable")
		assertEquals(0, counter.lambdaRuns(), "$what ran no composable lambda")
	}

	/** The counter has to see the overlays compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheOverlaysCompose() =
		counting { counter ->
			mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))

			assertTrue(counter.runsOf("ViewportEditGizmoOverlay") >= 2, "one per area")
			assertTrue(counter.runsOf("ViewportObjectGizmoOverlay") >= 2, "one per area")
		}

	/** Hovering over an idle overlay moves only what the chrome draws. */
	@Test
	fun aHoverRunsNothing() =
		counting { counter ->
			mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(200f, 150f), Offset(210f, 150f), Offset(220f, 160f)))

			assertNothingRan(counter, "a hover")
		}

	/** Driving a modal transform previews through the render service and runs no composable. */
	@Test
	fun aModalDriveRunsNothing() =
		counting { counter ->
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			fixture.session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(210f, 150f), Offset(220f, 155f), Offset(240f, 150f)))

			assertTrue(fixture.service.pushedModels.isNotEmpty(), "the moves did drive")
			assertNothingRan(counter, "a modal drive")
		}

	/** A proportional radius or axis change mid-gesture redraws the HUD and runs no composable. */
	@Test
	fun aHudChangeMidGestureRunsNothing() =
		counting { counter ->
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			counter.reset()

			session.setProportionalRadius(20f)
			waitForIdle()
			session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
			waitForIdle()

			assertNothingRan(counter, "a radius and an axis change")
		}

	private companion object {
		const val VIEWPORT2D_PACKAGE_PREFIX = "org.umamo.ui.viewport.viewport2d."
		const val FIXTURE_FUNCTION = "mountGizmoOverlays"
	}
}