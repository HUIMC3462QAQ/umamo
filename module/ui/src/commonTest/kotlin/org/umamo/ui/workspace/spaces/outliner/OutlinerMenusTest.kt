package org.umamo.ui.workspace.spaces.outliner

import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.commands.registerAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins a row's context menu: the entries each kind of row offers, in order, and that each entry runs the
 * very callback handed in, so a menu action and the inline affordance beside it cannot drift apart.
 */
class OutlinerMenusTest {
	private val labels =
		OutlinerLabels(
			root = "Puppet",
			armature = "Armature",
			expand = "Expand",
			collapse = "Collapse",
			selectHierarchy = "Select Hierarchy",
			visibility = "Toggle Visibility",
			selectable = "Toggle Selectability",
			rename = "Rename",
			delete = "Delete",
			deleteHierarchy = "Delete Hierarchy",
		)
	private val part = SelectionTarget.Part(PartId("head"))
	private val drawable = SelectionTarget.Drawable(DrawableId("eye"))
	private val deformer = SelectionTarget.Deformer(DeformerId("warp"))

	/** The calls a menu made, by the name of the callback, with the cascade flag a delete carried. */
	private val calls = mutableListOf<String>()

	private fun itemsFor(target: SelectionTarget, registry: CommandRegistry = CommandRegistry()): List<MenuItem> =
		outlinerRowMenuItems(
			target,
			labels,
			registry,
			onToggleVisibility = { calls += "visibility" },
			onToggleSelectable = { calls += "selectable" },
			onStartRename = { calls += "rename" },
			onRequestDelete = { cascade -> calls += "delete:$cascade" },
		)

	/**
	 * The entries' labels in order, a separator as null.
	 *
	 * @param List items The menu.
	 * @return List<String?> The labels.
	 */
	private fun labelsOf(items: List<MenuItem>): List<String?> = items.map { item -> (item as? MenuItem.Action)?.label }

	/**
	 * The action labelled [label].
	 *
	 * @param List items The menu.
	 * @param String label The label.
	 * @return MenuItem.Action The entry.
	 */
	private fun actionOf(items: List<MenuItem>, label: String): MenuItem.Action =
		items.filterIsInstance<MenuItem.Action>().first { action -> action.label == label }

	/** A part offers every entry: both toggles, and a delete that ungroups beside one that cascades. */
	@Test
	fun aPartOffersEveryEntryInOrder() {
		assertEquals(
			listOf("Select Hierarchy", null, "Toggle Visibility", "Toggle Selectability", "Rename", null, "Delete", "Delete Hierarchy"),
			labelsOf(itemsFor(part)),
		)
	}

	/** A drawable has no hierarchy, so it has the single Delete. */
	@Test
	fun aDrawableHasASingleDelete() {
		assertEquals(
			listOf("Select Hierarchy", null, "Toggle Visibility", "Toggle Selectability", "Rename", null, "Delete"),
			labelsOf(itemsFor(drawable)),
		)
	}

	/** A deformer has no visibility flag, so its menu has no visibility entry. */
	@Test
	fun aDeformerHasNoVisibilityEntry() {
		assertEquals(
			listOf("Select Hierarchy", null, "Toggle Selectability", "Rename", null, "Delete"),
			labelsOf(itemsFor(deformer)),
		)
	}

	/** Select Hierarchy dispatches the row through the registry, so the palette shares the edit. */
	@Test
	fun selectHierarchyDispatchesTheRowThroughTheRegistry() {
		val registry = CommandRegistry()
		var received: Any? = null
		registry.registerAll(listOf(Command("outliner.selectHierarchy", title = null) { argument -> received = argument }))

		actionOf(itemsFor(part, registry), "Select Hierarchy").onSelect()

		assertEquals(part, received)
	}

	/** Each inline entry runs the very callback handed in; Delete ungroups and Delete Hierarchy cascades. */
	@Test
	fun eachEntryRunsItsCallback() {
		val items = itemsFor(part)

		actionOf(items, "Toggle Visibility").onSelect()
		actionOf(items, "Toggle Selectability").onSelect()
		actionOf(items, "Rename").onSelect()
		actionOf(items, "Delete").onSelect()
		actionOf(items, "Delete Hierarchy").onSelect()

		assertEquals(listOf("visibility", "selectable", "rename", "delete:false", "delete:true"), calls)
	}
}