/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.dialogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.database.entities.PlaylistEntity
import app.infinity.mpvz.database.repository.PlaylistRepository
import app.infinity.mpvz.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PlaylistOption(
  val id: String,
  val name: String,
  val itemCount: Int,
  val subtitle: String? = null,
  val localPlaylist: PlaylistEntity? = null,
)

class AddToPlaylistViewModel :
  ViewModel(),
  KoinComponent {
  private val repository: PlaylistRepository by inject()

  private val _playlistOptions = MutableStateFlow<List<PlaylistOption>>(emptyList())
  val playlistOptions: StateFlow<List<PlaylistOption>> = _playlistOptions.asStateFlow()

  private var observeJob: kotlinx.coroutines.Job? = null

  fun loadPlaylists(isAudio: Boolean?, isJellyfin: Boolean = false) {
    observeJob?.cancel()
    observeJob = viewModelScope.launch(Dispatchers.IO) {
      repository.observeAllPlaylists(isAudio).collectLatest { playlists ->
        _playlistOptions.value =
          playlists
            .sortedWith(
              compareByDescending<PlaylistEntity> { repository.isProtectedPlaylist(it) }
                .thenBy { it.name.lowercase() }
            )
            .map { playlist ->
              PlaylistOption(
                id = playlist.id.toString(),
                name = playlist.name,
                itemCount = repository.getPlaylistItems(playlist.id).size,
                subtitle = "${repository.getPlaylistItems(playlist.id).size} items",
                localPlaylist = playlist,
              )
            }
      }
    }
  }

  suspend fun createAndAdd(
    name: String,
    videos: List<Video>,
    isJellyfin: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    val isAudio = videos.any { it.isAudio }
    val playlistId = repository.createPlaylist(name, isAudio = isAudio).toInt()
    repository.addItemsToPlaylist(playlistId, videos.asPlaylistItems())
  }

  suspend fun addToPlaylist(
    option: PlaylistOption,
    videos: List<Video>,
    isJellyfin: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    val playlistId = option.id.toIntOrNull() ?: return@withContext
    repository.addItemsToPlaylist(playlistId, videos.asPlaylistItems())
  }

  private fun List<Video>.asPlaylistItems(): List<Pair<String, String>> =
    map { video -> video.path to video.displayName }
}
