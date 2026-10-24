/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.storage

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale

/**
 * File Type Utilities
 * Handles media file extension detection, MIME types, and durations.
 */
object FileTypeUtils {
  // Video file extensions
  val VIDEO_EXTENSIONS =
    setOf(
      "mp4",
      "mkv",
      "avi",
      "mov",
      "wmv",
      "flv",
      "webm",
      "m4v",
      "3gp",
      "3g2",
      "mpg",
      "mpeg",
      "m2v",
      "ogv",
      "ts",
      "mts",
      "m2ts",
      "vob",
      "divx",
      "xvid",
      "f4v",
      "rm",
      "rmvb",
      "asf",
    )

  val AUDIO_EXTENSIONS =
    setOf(
      "aa",
      "aax",
      "aaxc",
      "mp3",
      "m4a",
      "m4b",
      "m4p",
      "aac",
      "flac",
      "ogg",
      "oga",
      "opus",
      "wav",
      "wave",
      "wma",
      "amr",
      "awb",
      "ac3",
      "eac3",
      "dts",
      "mka",
      "aif",
      "aiff",
      "aifc",
      "ape",
      "mp1",
      "mp2",
      "mpa",
      "mpc",
      "tta",
      "tak",
      "caf",
      "au",
      "snd",
      "ra",
      "spx",
      "weba",
      "3ga",
      "dsf",
      "dff",
      "mlp",
      "truehd",
      "mid",
      "midi",
    )

  /**
   * Checks if a file is a video based on extension
   */
  fun isVideoFile(file: File): Boolean {
    val extension = file.extension.lowercase(Locale.getDefault())
    return VIDEO_EXTENSIONS.contains(extension)
  }

  fun isAudioFile(file: File): Boolean = file.extension.lowercase(Locale.getDefault()) in AUDIO_EXTENSIONS

  fun stripExtension(title: String): String {
    val dotIndex = title.lastIndexOf('.')
    if (dotIndex <= 0) return title
    val ext = title.substring(dotIndex + 1).lowercase(Locale.getDefault())
    return if (ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS || (ext.length in 2..5 && ext.none { it.isWhitespace() })) {
      title.substring(0, dotIndex)
    } else {
      title
    }
  }

  fun getDurationMs(file: File): Long =
    runCatching {
      MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        retriever
          .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          ?.toLongOrNull()
          ?: 0L
      }
    }.getOrDefault(0L)

  /**
   * Gets MIME type from file extension
   */
  fun getMimeTypeFromExtension(extension: String): String =
    when (extension.lowercase()) {
      "mp4" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "webm" -> "video/webm"
      "flv" -> "video/x-flv"
      "wmv" -> "video/x-ms-wmv"
      "m4v" -> "video/x-m4v"
      "3gp" -> "video/3gpp"
      "3g2" -> "video/3gpp2"
      "mpg", "mpeg" -> "video/mpeg"
      "mp1", "mp2", "mp3", "mpa" -> "audio/mpeg"
      "m4a", "m4b", "aac", "m4p" -> "audio/mp4"
      "aa", "aax", "aaxc" -> "audio/audible"
      "flac" -> "audio/flac"
      "ogg", "oga", "opus", "spx" -> "audio/ogg"
      "wav", "wave" -> "audio/wav"
      "wma" -> "audio/x-ms-wma"
      "amr", "awb" -> "audio/amr"
      "ac3" -> "audio/ac3"
      "eac3" -> "audio/eac3"
      "dts" -> "audio/vnd.dts"
      "ape" -> "audio/ape"
      "mka" -> "audio/x-matroska"
      "aif", "aiff", "aifc" -> "audio/aiff"
      "mpc" -> "audio/x-musepack"
      "tta" -> "audio/x-tta"
      "tak" -> "audio/x-tak"
      "caf" -> "audio/x-caf"
      "au", "snd" -> "audio/basic"
      "ra" -> "audio/vnd.rn-realaudio"
      "weba" -> "audio/webm"
      "3ga" -> "audio/3gpp"
      "dsf" -> "audio/x-dsf"
      "dff" -> "audio/x-dff"
      "mlp", "truehd" -> "audio/vnd.dolby.mlp"
      "mid", "midi" -> "audio/midi"
      else -> "video/*"
    }
}
