/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.components

import androidx.compose.runtime.Stable

/**
 * State holder for item selection in lists and grids.
 */
@Stable
data class SelectionState<ID>(
  val selectedIds: Set<ID> = emptySet(),
  val lastSelectedId: ID? = null,
) {
  val isInSelectionMode: Boolean
    get() = selectedIds.isNotEmpty()

  val selectedCount: Int
    get() = selectedIds.size

  val isSingleSelection: Boolean
    get() = selectedIds.size == 1

  fun isSelected(id: ID): Boolean = selectedIds.contains(id)

  fun toggle(id: ID): SelectionState<ID> {
    val isCurrentlySelected = selectedIds.contains(id)
    val nextSelectedIds =
      if (isCurrentlySelected) {
        selectedIds - id
      } else {
        selectedIds + id
      }
    return copy(
      selectedIds = nextSelectedIds,
      lastSelectedId =
        if (isCurrentlySelected) {
          if (lastSelectedId == id) nextSelectedIds.lastOrNull() else lastSelectedId
        } else {
          id
        },
    )
  }

  fun select(id: ID): SelectionState<ID> =
    copy(
      selectedIds = selectedIds + id,
      lastSelectedId = id,
    )

  fun clear(): SelectionState<ID> = copy(selectedIds = emptySet(), lastSelectedId = null)

  fun selectAll(ids: List<ID>): SelectionState<ID> =
    copy(
      selectedIds = ids.toSet(),
      lastSelectedId = ids.lastOrNull(),
    )

  fun invertSelection(ids: List<ID>): SelectionState<ID> {
    val allIds = ids.toSet()
    val invertedIds = allIds - selectedIds
    return copy(
      selectedIds = invertedIds,
      lastSelectedId = invertedIds.lastOrNull(),
    )
  }
}
