package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.edit.SelectionTarget

/*
 * The measurements every outliner row shares.  The row body lays its slots out by these, and
 * outlinerContentWidth adds the same slots up to fix every row to one width, so the two must agree: a
 * slot added to the row is a term added to the sum here.
 */

/** Per-depth indentation, matching the Parameters space's folder indent. */
internal val OUTLINER_INDENT_PER_DEPTH = 12.dp

/** Fixed width of the disclosure-chevron slot (kept in sync with the math for the click region). */
internal val OUTLINER_CHEVRON_WIDTH = 14.dp

/** Fixed width of the type-icon slot. */
internal val OUTLINER_ICON_WIDTH = 16.dp

/** Row height, shared by every outliner row. */
internal val OUTLINER_ROW_HEIGHT = 22.dp

/** Fixed width of the trailing restriction indicator slot. */
internal val OUTLINER_RESTRICTION_SLOT_WIDTH = 16.dp

/**
 * Measures the single width every row is fixed to: the wider of the viewport and the longest row (its
 * indent + chevron + icon + gap + the measured label + the trailing restriction slots its row kind and
 * the active restriction toggles compose).  Fixing all rows to one width is what keeps the selection /
 * hover backgrounds full-width and the horizontal scroll range constant as rows scroll in and out.
 * Run once per visible-row set (memoised by the caller), not per frame.
 *
 * @param List rows The visible rows.
 * @param TextMeasurer measurer Measures label widths.
 * @param Density density For dp <-> px conversion.
 * @param Dp viewportWidth The available width - the floor for the result.
 * @param TextStyle bodyStyle The label style for non-drawable rows.
 * @param TextStyle drawableStyle The label style for drawable rows.
 * @param Boolean showSelectableColumn Whether the pointer restriction column renders.
 * @param Boolean showVisibilityColumn Whether the eye restriction column renders.
 * @return Dp The shared row width.
 */
internal fun outlinerContentWidth(
	rows: List<OutlinerRow>,
	measurer: TextMeasurer,
	density: Density,
	viewportWidth: Dp,
	bodyStyle: TextStyle,
	drawableStyle: TextStyle,
	showSelectableColumn: Boolean,
	showVisibilityColumn: Boolean,
): Dp =
	with(density) {
		val basePx = 4.dp.toPx()
		val perDepthPx = OUTLINER_INDENT_PER_DEPTH.toPx()
		val fixedPx = OUTLINER_CHEVRON_WIDTH.toPx() + OUTLINER_ICON_WIDTH.toPx() + 4.dp.toPx()
		val restrictionSlotPx = OUTLINER_RESTRICTION_SLOT_WIDTH.toPx()
		val trailingSpacerPx = 6.dp.toPx()
		val trailingPx = 8.dp.toPx()
		var maxPx = viewportWidth.toPx()
		for (row in rows) {
			val style = if (row.node.target is SelectionTarget.Drawable) drawableStyle else bodyStyle
			val labelPx = measurer.measure(row.node.label, style).size.width.toFloat()
			val hasTarget = row.node.target != null
			val showEye = row.node.target is SelectionTarget.Part || row.node.target is SelectionTarget.Drawable
			// Must mirror the row body's trailing slots exactly: a selectable slot for every real row when
			// that column is on, an eye slot for parts / drawables when that column is on, and the trailing
			// spacer every real row keeps regardless.
			val trailingSlotsPx =
				(if (showSelectableColumn && hasTarget) restrictionSlotPx else 0f) +
					(if (showVisibilityColumn && showEye) restrictionSlotPx else 0f) +
					(if (hasTarget) trailingSpacerPx else 0f)
			val rowPx = basePx + perDepthPx * row.depth + fixedPx + labelPx + trailingSlotsPx + trailingPx
			if (rowPx > maxPx) {
				maxPx = rowPx
			}
		}
		maxPx.toDp()
	}