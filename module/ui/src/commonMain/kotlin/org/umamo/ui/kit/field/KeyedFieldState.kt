package org.umamo.ui.kit.field

import androidx.compose.ui.graphics.Color
import org.umamo.ui.theme.UmamoColors

/*
 * The keyed state of one editable field, and the color that expresses it - Blender's keyframe tinting.
 *
 * What it communicates is not decoration: BetweenKeys says "edit here and it is lost unless you key it",
 * and ModifiedUnkeyed says "that is exactly what has happened".  Without them, manual keying silently
 * discards work on the next scrub, which is the single biggest friction a Cubism migrant hits (Cubism
 * auto-keys, so the situation never arises there).
 */

/** The keyed state of a field, in the order a rigger encounters them. */
enum class KeyedFieldState {
	/** The channel carries no track at all; the field edits a plain static and behaves like any other. */
	None,

	/** The channel is keyed and the pose sits exactly on one of its keys - editing writes that key. */
	OnKey,

	/**
	 * The channel is keyed but the pose is not sitting on one of its keys - an edit here needs an explicit
	 * key to survive.  What matters is the TRACK's own axes, not which parameter happens to be targeted:
	 * targeting decides where an insert lands, the track decides whether the value is stored.
	 */
	BetweenKeys,

	/** An edit has been made and not keyed; it is showing in the viewport and dies on the next scrub. */
	ModifiedUnkeyed,
}

/**
 * The tint for this state, or null for [KeyedFieldState.None] (the field keeps its ordinary colors).
 *
 * @param UmamoColors colors The active scheme.
 * @return Color? The tint, or null when the field is not keyed.
 */
fun KeyedFieldState.tint(colors: UmamoColors): Color? =
	when (this) {
		KeyedFieldState.None -> null
		KeyedFieldState.OnKey -> colors.keyedOnKey
		KeyedFieldState.BetweenKeys -> colors.keyedBetween
		KeyedFieldState.ModifiedUnkeyed -> colors.keyedModified
	}

/**
 * The FILL for this state, or null for [KeyedFieldState.None] (the control keeps its ordinary background).
 *
 * A text control expresses its keyed state by tinting its whole background rather than by drawing an
 * outline: a 1px stroke on a 20dp field is a small thing to read across a column of rows, and the column is
 * how the state is actually used.  Controls whose fill already MEANS something keep the outline instead -
 * a checkbox's fill is its checked state and a color swatch's fill is the user's own color, so neither has a
 * background to spend.  The token is translucent, so the control's own hover fill still shows through.
 *
 * @param UmamoColors colors The active scheme.
 * @return Color? The fill, or null when the field is not keyed.
 */
fun KeyedFieldState.backgroundTint(colors: UmamoColors): Color? =
	when (this) {
		KeyedFieldState.None -> null
		KeyedFieldState.OnKey -> colors.keyedOnKeyBackground
		KeyedFieldState.BetweenKeys -> colors.keyedBetweenBackground
		KeyedFieldState.ModifiedUnkeyed -> colors.keyedModifiedBackground
	}