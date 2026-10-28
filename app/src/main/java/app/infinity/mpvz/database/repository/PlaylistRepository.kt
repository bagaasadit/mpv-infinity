/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.database.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class PlaylistRepository(
  private val context: Context,
) {
  private val prefs by lazy {
    context.getSharedPreferences("media_favorites", Context.MODE_PRIVATE)
  }

  private val favoritesFlow by lazy {
    MutableStateFlow(getFavoritesSet())
  }

  private fun getFavoritesSet(): Set<String> {
    return prefs.getStringSet("favorite_paths", emptySet()) ?: emptySet()
  }

  fun observeIsFavorite(filePath: String, isAudio: Boolean = true): Flow<Boolean> {
    if (filePath.isBlank()) return flowOf(false)
    val clean = normalizeFavoritePath(filePath)
    return favoritesFlow.map { it.contains(clean) }
  }

  suspend fun isFavorite(filePath: String, isAudio: Boolean = true): Boolean {
    if (filePath.isBlank()) return false
    val clean = normalizeFavoritePath(filePath)
    return getFavoritesSet().contains(clean)
  }

  suspend fun toggleFavorite(filePath: String, fileName: String = "", isAudio: Boolean = true): Boolean {
    if (filePath.isBlank()) return false
    val clean = normalizeFavoritePath(filePath)
    val current = getFavoritesSet().toMutableSet()
    val isNowFav = if (current.contains(clean)) {
      current.remove(clean)
      false
    } else {
      current.add(clean)
      true
    }
    prefs.edit().putStringSet("favorite_paths", current).apply()
    favoritesFlow.value = current
    return isNowFav
  }

  private fun normalizeFavoritePath(filePath: String): String =
    if (filePath.startsWith("file://")) Uri.parse(filePath).path ?: filePath else filePath
}
