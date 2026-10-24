/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.ytdlp

import android.content.Context
import java.io.File

data class YtdlpPlaylistEntry(
  val id: String?,
  val url: String,
  val title: String,
  val artist: String?,
  val thumbnailUrl: String?,
  val durationSeconds: Int,
)

data class YtdlpPlaylistMetadata(
  val sourceUrl: String,
  val title: String,
  val entries: List<YtdlpPlaylistEntry>,
)

object YtdlpManager {
  fun getYtdlDir(context: Context): File = File(context.filesDir, "ytdl").apply { if (!exists()) mkdirs() }
  fun getExecutablePath(context: Context): String = ""

  fun requiresYtdlp(source: String): Boolean = false
  fun isPotentialPlaylistUrl(source: String): Boolean = false
  fun canonicalPlaylistSource(source: String): String = source

  fun playPlaylist(context: Context, playlist: YtdlpPlaylistMetadata) {}
  suspend fun extractPlaylist(context: Context, source: String, onProgress: (String) -> Unit = {}): YtdlpPlaylistMetadata? = null

  suspend fun prepareForPlayback(
    context: Context,
    source: String,
    onProgress: (String) -> Unit = {},
  ): Boolean = true

  suspend fun resolveDirectMediaUrl(
    context: Context,
    source: String,
    onProgress: (String) -> Unit = {},
  ): String? = null

  suspend fun ensureRuntimeInstalled(context: Context, onLog: (String) -> Unit = {}): Boolean = false
  suspend fun copyAssets(context: Context) {}

  suspend fun runInstall(context: Context, onLog: (String) -> Unit = {}): Boolean = false
  suspend fun runUpdate(context: Context, onLog: (String) -> Unit = {}): Boolean = false
  suspend fun runUpdateToNightly(context: Context, onLog: (String) -> Unit = {}): Boolean = false

  fun isInstalled(context: Context): Boolean = false
}
