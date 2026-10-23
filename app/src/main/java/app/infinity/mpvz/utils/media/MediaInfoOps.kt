/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaInfoOps {
  private const val BASIC_METADATA_CACHE_ENTRIES = 512
  private val basicMetadataCache = LruCache<String, VideoMetadata>(BASIC_METADATA_CACHE_ENTRIES)
  private val videoCodecCache = LruCache<String, VideoCodecDescriptor>(BASIC_METADATA_CACHE_ENTRIES)

  private fun basicMetadataCacheKey(
    uri: Uri,
    fileName: String,
  ): String {
    if (uri.scheme.equals("file", ignoreCase = true)) {
      val file = uri.path?.let(::File)
      if (file != null) {
        return "${file.absolutePath}|${file.length()}|${file.lastModified()}"
      }
    }
    return "${uri}|$fileName"
  }

  suspend fun getMediaInfo(
    context: Context,
    uri: Uri,
    fileName: String,
  ): Result<MediaInfoData> =
    withContext(Dispatchers.IO) {
      runCatching {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
          val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
          val bitRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
          val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
          val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName

          val durationMs = durationStr?.toLongOrNull() ?: 0L
          val durationSeconds = durationMs / 1000

          val generalInfo = GeneralInfo(
            completeName = fileName,
            format = mimeType,
            formatVersion = "",
            fileSize = "",
            duration = "${durationSeconds / 60} min ${durationSeconds % 60} s",
            overallBitRate = bitRateStr?.let { "${it.toLongOrNull()?.div(1000) ?: it} kb/s" },
            frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
            title = title,
            encodedDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
            writingApplication = "",
            writingLibrary = "Android MediaMetadataRetriever",
          )

          val videoStreams = listOf(
            VideoStreamInfo(
              streamIndex = 0,
              id = "1",
              format = mimeType,
              formatInfo = "",
              formatProfile = "",
              codecId = mimeType,
              duration = "${durationSeconds}s",
              bitRate = bitRateStr?.let { "${it.toLongOrNull()?.div(1000) ?: it} kb/s" },
              width = widthStr?.let { "${it} pixels" },
              height = heightStr?.let { "${it} pixels" },
              displayAspectRatio = if (widthStr != null && heightStr != null && heightStr != "0") {
                val w = widthStr.toFloatOrNull() ?: 0f
                val h = heightStr.toFloatOrNull() ?: 1f
                String.format(java.util.Locale.US, "%.3f", w / h)
              } else null,
              frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.let { "$it FPS" },
              frameRateMode = "",
              colorSpace = "",
              chromaSubsampling = "",
              bitDepth = "",
              bitsPixelFrame = "",
              streamSize = "",
              encodingLibrary = "",
              defaultStream = "",
              forcedStream = "",
              hdrFormat = "",
              maxCLL = "",
              maxFALL = "",
            )
          )

          MediaInfoData(
            general = generalInfo,
            videoStreams = videoStreams,
            audioStreams = emptyList(),
            textStreams = emptyList(),
          )
        } finally {
          retriever.release()
        }
      }
    }

  suspend fun generateTextOutput(
    context: Context,
    uri: Uri,
    fileName: String,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
          val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
          val bitRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
          val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
          val durationMs = durationStr?.toLongOrNull() ?: 0L

          buildString {
            appendLine("=".repeat(60))
            appendLine("MEDIA INFO - $fileName")
            appendLine("=".repeat(60))
            appendLine("Format: $mimeType")
            if (widthStr != null && heightStr != null) {
              appendLine("Resolution: ${widthStr}x${heightStr}")
            }
            appendLine("Duration: ${durationMs / 1000} seconds (${durationMs / 60000}m ${(durationMs % 60000) / 1000}s)")
            if (bitRateStr != null) {
              appendLine("Bitrate: ${bitRateStr.toLongOrNull()?.div(1000) ?: bitRateStr} kbps")
            }
            appendLine("=".repeat(60))
            appendLine("Generated by mpvZero (Ultralight Native Engine)")
            appendLine("=".repeat(60))
          }
        } finally {
          retriever.release()
        }
      }
    }

  suspend fun extractBasicMetadata(
    context: Context,
    uri: Uri,
    fileName: String,
  ): Result<VideoMetadata> =
    withContext(Dispatchers.IO) {
      val cacheKey = basicMetadataCacheKey(uri, fileName)
      synchronized(basicMetadataCache) {
        basicMetadataCache.get(cacheKey)
      }?.let { cached -> return@withContext Result.success(cached) }

      val result = runCatching {
        val fallback = extractRetrieverMetadata(context, uri)
        fallback ?: VideoMetadata(0L, 0L, 0, 0, 0f, false)
      }

      result.getOrNull()?.let { metadata ->
        if (metadata.durationMs > 0L || metadata.width > 0 || metadata.height > 0) {
          synchronized(basicMetadataCache) {
            basicMetadataCache.put(cacheKey, metadata)
          }
        }
      }
      result
    }

  suspend fun extractVideoCodec(
    context: Context,
    uri: Uri,
    fileName: String,
  ): VideoCodecDescriptor =
    withContext(Dispatchers.IO) {
      val cacheKey = basicMetadataCacheKey(uri, fileName)
      synchronized(videoCodecCache) {
        videoCodecCache.get(cacheKey)
      }?.let { return@withContext it }

      val descriptor = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
          VideoCodecSupportInspector.descriptor(format = mime, codecId = mime)
        } finally {
          retriever.release()
        }
      }.getOrDefault(VideoCodecDescriptor("UNKNOWN", ""))

      synchronized(videoCodecCache) {
        videoCodecCache.put(cacheKey, descriptor)
      }
      descriptor
    }

  private fun extractRetrieverMetadata(
    context: Context,
    uri: Uri,
  ): VideoMetadata? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(context, uri)
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        val codecDesc = VideoCodecSupportInspector.descriptor(format = mime, codecId = mime)
        VideoMetadata(
          sizeBytes = 0L,
          durationMs =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
              ?.toLongOrNull() ?: 0L,
          width =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
              ?.toIntOrNull() ?: 0,
          height =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
              ?.toIntOrNull() ?: 0,
          fps =
            retriever
              .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
              ?.toFloatOrNull() ?: 0f,
          hasEmbeddedSubtitles = false,
          videoCodec = codecDesc.label,
          videoCodecMimeType = codecDesc.mimeType,
        )
      } finally {
        retriever.release()
      }
    }.getOrNull()

  data class VideoMetadata(
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val hasEmbeddedSubtitles: Boolean,
    val subtitleCodec: String = "",
    val videoCodec: String = "",
    val videoCodecMimeType: String = "",
  )

  suspend fun getRotation(
    context: Context,
    uri: Uri,
    fileName: String = "",
  ): Int =
    withContext(Dispatchers.IO) {
      runCatching {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
          rotationStr?.toIntOrNull() ?: 0
        } finally {
          retriever.release()
        }
      }.getOrDefault(0)
    }

  data class GeneralInfo(
    val completeName: String? = null,
    val format: String? = null,
    val formatVersion: String? = null,
    val fileSize: String? = null,
    val duration: String? = null,
    val overallBitRate: String? = null,
    val frameRate: String? = null,
    val title: String? = null,
    val encodedDate: String? = null,
    val writingApplication: String? = null,
    val writingLibrary: String? = null,
  )

  data class VideoStreamInfo(
    val streamIndex: Int,
    val id: String? = null,
    val format: String? = null,
    val formatInfo: String? = null,
    val formatProfile: String? = null,
    val codecId: String? = null,
    val duration: String? = null,
    val bitRate: String? = null,
    val width: String? = null,
    val height: String? = null,
    val displayAspectRatio: String? = null,
    val frameRate: String? = null,
    val frameRateMode: String? = null,
    val colorSpace: String? = null,
    val chromaSubsampling: String? = null,
    val bitDepth: String? = null,
    val bitsPixelFrame: String? = null,
    val streamSize: String? = null,
    val encodingLibrary: String? = null,
    val defaultStream: String? = null,
    val forcedStream: String? = null,
    val hdrFormat: String? = null,
    val maxCLL: String? = null,
    val maxFALL: String? = null,
  )

  data class AudioStreamInfo(
    val streamIndex: Int,
    val id: String? = null,
    val format: String? = null,
    val formatInfo: String? = null,
    val codecId: String? = null,
    val duration: String? = null,
    val bitRate: String? = null,
    val channels: String? = null,
    val channelLayout: String? = null,
    val samplingRate: String? = null,
    val frameRate: String? = null,
    val compressionMode: String? = null,
    val delay: String? = null,
    val streamSize: String? = null,
    val title: String? = null,
    val language: String? = null,
    val defaultStream: String? = null,
    val forcedStream: String? = null,
  )

  data class TextStreamInfo(
    val streamIndex: Int,
    val id: String? = null,
    val format: String? = null,
    val muxingMode: String? = null,
    val codecId: String? = null,
    val codecIdInfo: String? = null,
    val duration: String? = null,
    val bitRate: String? = null,
    val frameRate: String? = null,
    val countOfElements: String? = null,
    val streamSize: String? = null,
    val title: String? = null,
    val language: String? = null,
    val defaultStream: String? = null,
    val forcedStream: String? = null,
  )

  data class MediaInfoData(
    val general: GeneralInfo,
    val videoStreams: List<VideoStreamInfo>,
    val audioStreams: List<AudioStreamInfo>,
    val textStreams: List<TextStreamInfo>,
  ) {
    companion object {
      fun empty() =
        MediaInfoData(
          general = GeneralInfo(),
          videoStreams = emptyList(),
          audioStreams = emptyList(),
          textStreams = emptyList(),
        )
    }
  }
}
