/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.ytdlp

import android.content.Context
import app.infinity.mpvz.preferences.SubtitlesPreferences
import app.infinity.mpvz.preferences.YtdlPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  private val _installationInfo = MutableStateFlow<YtdlpInstallationInfo?>(YtdlpInstallationInfo.NotInstalled)
  val installationInfo: StateFlow<YtdlpInstallationInfo?> = _installationInfo.asStateFlow()

  fun getYtdlDir(context: Context): File = File(context.filesDir, "ytdl").apply { if (!exists()) mkdirs() }
  fun getExecutablePath(context: Context): String = ""
  suspend fun refreshInstallationInfo(context: Context): YtdlpInstallationInfo = YtdlpInstallationInfo.NotInstalled

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

  fun setupMpvOptions(
    context: Context,
    ytdlPreferences: YtdlPreferences,
    subtitlesPreferences: SubtitlesPreferences,
  ) {}

  suspend fun runInstall(context: Context, onLog: (String) -> Unit = {}): Boolean = false
  suspend fun runUpdate(context: Context, onLog: (String) -> Unit = {}): Boolean = false
  suspend fun runUpdateToNightly(context: Context, onLog: (String) -> Unit = {}): Boolean = false

  fun isInstalled(context: Context): Boolean = false
}
