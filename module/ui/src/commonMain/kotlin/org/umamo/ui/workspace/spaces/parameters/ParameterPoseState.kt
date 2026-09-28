package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.flow.MutableStateFlow
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.LiveParamsHandle

/**
 * The pose as the panel's rows see it: the value each control shows, and the writes a control makes.
 *
 * Stable, so a row handed the same state skips.  The values are snapshot state read one key at a time,
 * which is what keeps a scrub to the rows whose value moved.
 *
 * @param SnapshotStateMap<ParameterId, Float> values The displayed values, one per parameter.
 * @param ParameterPoseWriter writer The lock-gated writes, which echo into [values].
 */
@Stable
internal class ParameterPoseState(
	private val values: SnapshotStateMap<ParameterId, Float>,
	private val writer: ParameterPoseWriter,
) {
	/**
	 * The value a parameter's control shows.  A snapshot read, so it belongs in the scope that draws the
	 * control and nowhere wider.
	 *
	 * @param Parameter parameter The parameter to read.
	 * @return Float The displayed value, or the parameter's default when the panel holds none.
	 */
	fun valueOf(parameter: Parameter): Float = values[parameter.id] ?: parameter.default

	/**
	 * Previews a value, recording no undo step.
	 *
	 * @param ParameterId id The parameter to move.
	 * @param Float newValue The value to show.
	 */
	fun preview(id: ParameterId, newValue: Float) {
		writer.preview(id, newValue)
	}

	/**
	 * Commits the current pose as one undo step, ending a scrub gesture.
	 *
	 * @param Set<ParameterId> ids The parameters the gesture moved.
	 */
	fun commitGesture(ids: Set<ParameterId>) {
		writer.commitGesture(ids)
	}

	/**
	 * A discrete value edit, committed as one step.
	 *
	 * @param ParameterId id The parameter to set.
	 * @param Float newValue The value to set it to.
	 */
	fun commitValue(id: ParameterId, newValue: Float) {
		writer.commitValue(id, newValue)
	}
}

/**
 * Whether the parameters are locked, which they are for as long as the session is in Edit mode.  With no
 * session there is no mode to be in, and nothing is locked.
 *
 * @param EditorSession? session The editing session, or null with none.
 * @return Boolean True while every pose write is to be refused.
 */
@Composable
internal fun rememberParametersLocked(session: EditorSession?): Boolean {
	val editorMode by remember(session) { session?.mode ?: MutableStateFlow(EditorMode.Object) }.collectAsState()
	return editorMode == EditorMode.Edit
}

/**
 * A pose writer for a control that shows no values of its own, rebuilt when the lock changes.
 *
 * @param LiveParamsHandle? liveParams The pose seam, or null with no posable document.
 * @param EditorSession? session The editing session whose mode sets the lock, or null with none.
 * @return ParameterPoseWriter The writer for the lock as it stands.
 */
@Composable
internal fun rememberParameterPoseWriter(liveParams: LiveParamsHandle?, session: EditorSession?): ParameterPoseWriter {
	val parametersLocked = rememberParametersLocked(session)
	return remember(liveParams, parametersLocked) { ParameterPoseWriter(liveParams, parametersLocked) }
}

/**
 * The panel body's pose: the displayed values seeded from the live pose (or the defaults), the two
 * effects that keep them following it, and the writes gated on the lock.
 *
 * @param PuppetModel puppet The open document.
 * @param LiveParamsHandle? liveParams The pose seam, or null with no posable document.
 * @param EditorSession? session The editing session, or null with none.
 * @return ParameterPoseState The pose state, replaced when the parameter set, the seam, or the lock changes.
 */
@Composable
internal fun rememberParameterPoseState(puppet: PuppetModel, liveParams: LiveParamsHandle?, session: EditorSession?): ParameterPoseState {
	// Keyed on WHICH parameters exist, not on the model instance.  Every document edit publishes a new
	// PuppetModel - moving a keyform key, recoloring a drawable - and a map keyed on that is thrown away
	// and rebuilt on each one.  That is churn by itself, and worse: the effects below capture the map, so
	// a replacement orphans their writes and the sliders stop following a live scrub until something else
	// makes the effects run again.  The set of parameters is what this map is about, and it survives an
	// ordinary edit.
	val parameterIds = remember(puppet) { puppet.parameters.map { parameter -> parameter.id } }
	val values =
		remember(parameterIds) {
			mutableStateMapOf<ParameterId, Float>().apply {
				puppet.parameters.forEach { parameter ->
					put(parameter.id, liveParams?.values?.get(parameter.id) ?: parameter.default)
				}
			}
		}
	// Reseed the sliders from the session pose when it changes out from under us - an undo / redo restores
	// a prior pose, and these controls must follow it. Mid-drag previews update [values] directly (the
	// session pose does not move until commit), so this never fights a live scrub; the guard skips
	// unchanged entries so a commit (pose == values already) triggers no writes. An empty fallback flow
	// keeps the collect unconditional when no session is present.
	val pose by remember(session) { session?.pose ?: MutableStateFlow(emptyMap<ParameterId, Float>()) }.collectAsState()
	LaunchedEffect(pose, values) {
		followPose(values, pose)
	}
	// And follow PREVIEWS made from elsewhere - the keyform sheet scrubs the same pose by dragging its
	// track region. A preview deliberately never touches session.pose (that is what keeps a whole drag to
	// one undo step), so the effect above catches up only on release.
	//
	// Through snapshotFlow rather than a composition read: the scrub path writes on every pointer move, and
	// reading it in composition would recompose the whole panel per frame instead of only the sliders
	// whose value actually moved. Edit mode publishes an EMPTY pose, which writes nothing and so cannot
	// disturb the locked panel's displayed values.
	LaunchedEffect(liveParams, values) {
		snapshotFlow { liveParams?.observedValues.orEmpty() }.collect { livePose ->
			followPose(values, livePose)
		}
	}
	val parametersLocked = rememberParametersLocked(session)
	// A scrub still held when the lock engages has previewed a value nothing will commit: the writer it
	// reaches from here on refuses the rest of the drag and its release.  Drop the preview, so a locked
	// panel shows the committed pose and not a value the rig never took.  The renderer needs no such
	// care, since Edit mode hands it a pose of its own.
	LaunchedEffect(parametersLocked, values) {
		if (parametersLocked) {
			followPose(values, pose)
		}
	}
	// The values outlive the lock: a mode change builds a new writer around the same map, so what the
	// sliders show carries across it.
	return remember(values, liveParams, parametersLocked) {
		ParameterPoseState(
			values,
			ParameterPoseWriter(liveParams, parametersLocked) { id, newValue -> values[id] = newValue },
		)
	}
}