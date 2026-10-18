/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
  title: String,
  modifier: Modifier = Modifier,
  colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
  forceHeadlineSmall: Boolean = false,
  isInSelectionMode: Boolean = false,
  selectedCount: Int = 0,
  totalCount: Int = 0,
  onCancelSelection: () -> Unit = {},
  onBackClick: (() -> Unit)? = null,
  onDeleteClick: (() -> Unit)? = null,
  onSelectAll: (() -> Unit)? = null,
  onInvertSelection: (() -> Unit)? = null,
  onDeselectAll: (() -> Unit)? = null,
  useRemoveIcon: Boolean = false,
  additionalActions: @Composable RowScope.() -> Unit = {},
) {
  var showSelectionMenu by remember { mutableStateOf(false) }

  if (isInSelectionMode) {
    TopAppBar(
      modifier = modifier,
      colors = colors,
      title = {
        Text(
          text = if (totalCount > 0) "$selectedCount / $totalCount" else "$selectedCount",
          style = if (forceHeadlineSmall) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        )
      },
      navigationIcon = {
        IconButton(onClick = onCancelSelection) {
          Icon(
            imageVector = Icons.RoundedFilled.Close,
            contentDescription = stringResource(R.string.generic_cancel),
          )
        }
      },
      actions = {
        if (onSelectAll != null || onInvertSelection != null || onDeselectAll != null) {
          IconButton(onClick = { showSelectionMenu = true }) {
            Icon(
              imageVector = Icons.RoundedFilled.MoreVert,
              contentDescription = stringResource(R.string.generic_more),
            )
          }

          DropdownMenu(
            expanded = showSelectionMenu,
            onDismissRequest = { showSelectionMenu = false },
          ) {
            if (onSelectAll != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.generic_select_all)) },
                onClick = {
                  showSelectionMenu = false
                  onSelectAll()
                },
              )
            }
            if (onInvertSelection != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.generic_invert_selection)) },
                onClick = {
                  showSelectionMenu = false
                  onInvertSelection()
                },
              )
            }
            if (onDeselectAll != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.deselect_all)) },
                onClick = {
                  showSelectionMenu = false
                  onDeselectAll()
                },
              )
            }
          }
        }

        if (onDeleteClick != null) {
          IconButton(onClick = onDeleteClick) {
            Icon(
              imageVector = Icons.RoundedFilled.Delete,
              contentDescription = stringResource(R.string.delete),
              tint = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
    )
  } else {
    TopAppBar(
      modifier = modifier,
      colors = colors,
      title = {
        Text(
          text = title,
          style = if (forceHeadlineSmall) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        )
      },
      navigationIcon = {
        if (onBackClick != null) {
          IconButton(onClick = onBackClick) {
            Icon(
              imageVector = Icons.RoundedFilled.ArrowBack,
              contentDescription = stringResource(R.string.back),
            )
          }
        }
      },
      actions = additionalActions,
    )
  }
}
