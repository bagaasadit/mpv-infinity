/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.TrackNode
import app.infinity.mpvz.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

sealed class SubtitleItem {
  data class Track(
    val node: TrackNode,
  ) : SubtitleItem()

  data class Header(
    val title: String,
  ) : SubtitleItem()

  object Divider : SubtitleItem()

  object Off : SubtitleItem()
}

@Composable
fun SubtitlesSheet(
  tracks: ImmutableList<TrackNode>,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  subtitleSelectionIndicator: (Int) -> String?,
  onAddSubtitle: () -> Unit,
  onOpenSubtitleSettings: () -> Unit,
  onOpenSubtitleDelay: () -> Unit,
  onRemoveSubtitle: (Int) -> Unit,
  onOpenOnlineSearch: () -> Unit,
  onDismissRequest: () -> Unit,
  subtitlesOff: Boolean = false,
  onDisableSubtitles: () -> Unit = {},
  delayControlEnabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val items =
    remember(tracks, subtitlesOff) {
      val list = mutableListOf<SubtitleItem>()
      list.add(SubtitleItem.Off)
      val internal = tracks.filter { it.external != true }
      val external = tracks.filter { it.external == true }

      if (internal.isNotEmpty() || external.isNotEmpty()) {
        list.add(SubtitleItem.Header(if (internal.isNotEmpty()) "Embedded Subtitles" else "Local Subtitles"))
        list.addAll(internal.map { SubtitleItem.Track(it) })
        if (internal.isNotEmpty() && external.isNotEmpty()) {
          list.add(SubtitleItem.Header("External Subtitles"))
        }
        list.addAll(external.map { SubtitleItem.Track(it) })
      }

      list.toImmutableList()
    }

  PlayerSheet(onDismissRequest) {
    Column(modifier) {
      AddTrackRow(
        stringResource(R.string.player_sheets_add_ext_sub),
        onAddSubtitle,
        actions = {
          IconButton(onClick = onOpenOnlineSearch) {
            Icon(Icons.RoundedFilled.Search, null)
          }
          IconButton(onClick = onOpenSubtitleSettings) {
            Icon(Icons.RoundedFilled.Palette, null)
          }
          IconButton(onClick = onOpenSubtitleDelay, enabled = delayControlEnabled) {
            Icon(Icons.RoundedFilled.AvTimer, null)
          }
        },
      )
      LazyColumn {
        items(
          items,
          key = { item ->
            when (item) {
              is SubtitleItem.Track -> item.node.id
              is SubtitleItem.Header -> item.title
              is SubtitleItem.Off -> "off"
              is SubtitleItem.Divider -> "divider"
            }
          },
          contentType = { item -> item.javaClass.simpleName },
        ) { item ->
          when (item) {
            is SubtitleItem.Track -> {
              val track = item.node
              SubtitleTrackRow(
                title = getTrackTitle(track),
                isSelected = isSubtitleSelected(track.id),
                selectionIndicator = subtitleSelectionIndicator(track.id),
                isExternal = track.external == true,
                onToggle = { onToggleSubtitle(track.id) },
                onRemove = { onRemoveSubtitle(track.id) },
              )
            }
            is SubtitleItem.Header -> {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = item.title,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
            is SubtitleItem.Off -> {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDisableSubtitles)
                    .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
              ) {
                Checkbox(checked = subtitlesOff, onCheckedChange = { onDisableSubtitles() })
                Text(
                  stringResource(R.string.player_sheets_off),
                  fontWeight = if (subtitlesOff) FontWeight.Bold else FontWeight.Normal,
                  modifier = Modifier.weight(1f),
                )
              }
            }
            SubtitleItem.Divider -> {
              HorizontalDivider(
                modifier =
                  Modifier.padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                  ),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
              )
            }
          }
        }
        item {
          Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
        }
      }
    }
  }
}

@Composable
fun SubtitleTrackRow(
  title: String,
  isSelected: Boolean,
  selectionIndicator: String?,
  isExternal: Boolean,
  onToggle: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onToggle)
        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
    Text(title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))

    if (selectionIndicator != null) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      ) {
        Text(
          text = selectionIndicator,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    if (isExternal) {
      IconButton(onClick = onRemove) { Icon(Icons.RoundedFilled.Delete, contentDescription = null) }
    }
  }
}
