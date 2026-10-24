/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.media

import android.util.Log
import app.infinity.mpvz.preferences.MpvConfigControlledFeatures
import app.infinity.mpvz.preferences.MpvConfigOverridePolicy
import app.infinity.mpvz.ui.player.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Simple utility for automatically loading subtitle files
 * Finds subtitles in the same directory that match the video filename
 */
object SubtitleOps {
  private const val TAG = "SubtitleOps"

  suspend fun autoloadSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long = -1L,
    expectedGeneration: Long? = null,
    selectFirst: Boolean = true,
  ) = withContext(Dispatchers.IO) {
    try {
      if (!isGenerationCurrent(expectedGeneration)) return@withContext

      // Skip file descriptor URIs (these don't have a parent directory concept)
      if (videoFilePath.startsWith("fd://")) return@withContext

      // For content:// URIs, we can't autoload (no direct filesystem access to parent directory)
      if (videoFilePath.startsWith("content://")) return@withContext

      if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.SUBTITLE_DISCOVERY)) return@withContext

      // Check if this is a network stream (http, https, rtmp, etc.)
      val isNetworkStream = videoFilePath.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))

      if (isNetworkStream) {
        Log.d(TAG, "Skipping direct network subtitle autoload for: $videoFilePath")
        return@withContext
      } else {
        // For local files, scan the directory
        autoloadLocalSubtitles(videoFilePath, videoFileName, expectedGeneration, selectFirst)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error loading subtitles", e)
    }
  }

  private suspend fun autoloadLocalSubtitles(
    videoFilePath: String,
    videoFileName: String,
    expectedGeneration: Long?,
    selectFirst: Boolean,
  ) {
    val videoFile = File(videoFilePath)
    val videoDirectory = videoFile.parentFile ?: return
    val baseName = videoFileName.substringBeforeLast('.')

    val subtitles =
      videoDirectory.listFiles()?.filter { file ->
        file.isFile &&
          isSubtitleFile(file.name) &&
          file.nameWithoutExtension.startsWith(baseName, ignoreCase = true)
      } ?: emptyList()

    if (subtitles.isNotEmpty()) {
      withContext(Dispatchers.Main) {
        subtitles.forEachIndexed { index, subtitle ->
          if (!isGenerationCurrent(expectedGeneration)) return@forEachIndexed
          // MPV command format: sub-add <url> [<flags> [<title>]]
          // Keep tracks available while respecting the user's current subtitle-off state.
          val flag = if (index == 0 && selectFirst) "select" else "auto"
          val added =
            expectedGeneration?.let { generation ->
              PlaybackSession.commandForGeneration(
                generation,
                "sub-add",
                subtitle.absolutePath,
                flag,
                subtitle.name,
              )
            } ?: run {
              PlaybackSession.command("sub-add", subtitle.absolutePath, flag, subtitle.name)
              true
            }
          if (!added) return@forEachIndexed
          Log.d(TAG, "Loaded local subtitle: ${subtitle.name} (flag=$flag)")
        }
      }
    }
  }

  private fun isSubtitleFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return extension in
      setOf(
        // Common & modern
        "srt",
        "vtt",
        "ass",
        "ssa",
        // DVD / Blu-ray
        "sub",
        "idx",
        "sup",
        // Streaming / XML / Professional
        "xml",
        "ttml",
        "dfxp",
        "itt",
        "ebu",
        "imsc",
        "usf",
        // Online platforms
        "sbv",
        "srv1",
        "srv2",
        "srv3",
        "json",
        // Legacy & niche
        "sami",
        "smi",
        "mpl",
        "pjs",
        "stl",
        "rt",
        "psb",
        "cap",
        // Broadcast captions
        "scc",
        "vttx",
        // Karaoke / lyrics
        "lrc",
        "krc",
        // Fallback / raw text
        "txt",
        "pgs",
      )
  }

  private fun isGenerationCurrent(expectedGeneration: Long?): Boolean =
    expectedGeneration == null || PlaybackSession.isCurrentGeneration(expectedGeneration)
}
