/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.repository.subtitle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object SubtitleArchiveExtractor {
  private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "sub")
  private val extensionPriority =
    mapOf(
      "ass" to 0,
      "ssa" to 1,
      "vtt" to 2,
      "srt" to 3,
      "sub" to 4,
    )

  fun extractBest(
    bytes: ByteArray,
    preferredName: String? = null,
    preferredEpisode: Int? = null,
  ): ExtractedSubtitle? {
    if (bytes.size > MAX_ARCHIVE_BYTES) return null
    return when {
      isZipArchive(bytes) -> extractBestZip(bytes, preferredName, preferredEpisode)
      else -> null
    }
  }

  private fun extractBestZip(
    bytes: ByteArray,
    preferredName: String?,
    preferredEpisode: Int?,
  ): ExtractedSubtitle? {
    val preferredFileName = preferredName.normalizedFileName()
    val candidates = mutableListOf<Candidate>()

    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        try {
          if (entry.isDirectory || entry.name.isJunkArchiveEntry()) continue
          val extension = extensionFromName(entry.name)?.takeIf { it in subtitleExtensions } ?: continue
          val fileName = entry.name.archiveFileName()
          val payload = zip.readBytesBounded(MAX_SUBTITLE_BYTES) ?: continue
          if (payload.isEmpty()) continue

          candidates +=
            Candidate(
              subtitle = ExtractedSubtitle(payload, extension, fileName),
              score =
                archiveEntryScore(
                  fileName,
                  extension,
                  payload.size.toLong(),
                  preferredFileName,
                  preferredEpisode,
                ),
            )
        } finally {
          zip.closeEntry()
        }
      }
    }

    return selectBest(candidates, preferredEpisode)?.subtitle
  }

  fun extensionFromName(value: String?): String? =
    value
      ?.substringBefore("?")
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      ?.substringAfterLast(".", "")
      ?.lowercase(Locale.ROOT)
      ?.takeIf { it.isNotBlank() && it.length <= 5 }

  fun isZipArchive(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 'P'.code.toByte() &&
      bytes[1] == 'K'.code.toByte() &&
      (
        bytes[2] == 3.toByte() ||
          bytes[2] == 5.toByte() ||
          bytes[2] == 7.toByte()
      )

  fun isSupportedArchive(bytes: ByteArray): Boolean = isZipArchive(bytes)

  fun looksLikeHtml(bytes: ByteArray): Boolean {
    val sample =
      bytes
        .take(512)
        .toByteArray()
        .toString(Charsets.UTF_8)
        .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        .lowercase(Locale.ROOT)

    return sample.startsWith("<!doctype html") ||
      sample.startsWith("<html") ||
      sample.startsWith("<style") ||
      sample.contains("<head>") &&
      sample.contains("<body")
  }

  private fun String.isJunkArchiveEntry(): Boolean {
    val normalized = replace('\\', '/')
    val fileName = normalized.substringAfterLast('/')
    return normalized.startsWith("__MACOSX/", ignoreCase = true) ||
      fileName.startsWith("._") ||
      fileName.equals(".DS_Store", ignoreCase = true)
  }

  private fun String.archiveFileName(): String = substringAfterLast('/').substringAfterLast('\\')

  private fun String?.normalizedFileName(): String? =
    this?.archiveFileName()?.lowercase(Locale.ROOT)

  private fun archiveEntryScore(
    fileName: String,
    extension: String,
    size: Long,
    preferredFileName: String?,
    preferredEpisode: Int?,
  ): ArchiveEntryScore =
    ArchiveEntryScore(
      preferredNameMatch = preferredFileName != null && fileName.normalizedFileName() == preferredFileName,
      preferredEpisodeMatch = preferredEpisode != null && fileName.episodeNumbers().contains(preferredEpisode),
      extensionPriority = extensionPriority[extension] ?: Int.MAX_VALUE,
      size = size,
    )

  private fun selectBest(
    candidates: List<Candidate>,
    preferredEpisode: Int?,
  ): Candidate? {
    val eligible =
      if (preferredEpisode != null) {
        candidates.filter { it.score.preferredEpisodeMatch }
      } else {
        candidates
      }
    return eligible.minWithOrNull(archiveEntryComparator { it.score })
  }

  private fun <T> archiveEntryComparator(score: (T) -> ArchiveEntryScore): Comparator<T> =
    compareBy<T> { !score(it).preferredEpisodeMatch }
      .thenBy { !score(it).preferredNameMatch }
      .thenBy { score(it).extensionPriority }
      .thenByDescending { score(it).size }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

  private fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray? {
    val output = BoundedByteArrayOutputStream(maxBytes)
    return runCatching {
      copyTo(output)
      output.toByteArray()
    }.getOrNull()
  }

  private fun String.episodeNumbers(): Set<Int> {
    val fileName = substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', this)
    val numbers = linkedSetOf<Int>()
    Regex("""(?i)\bS\d{1,2}E(\d{1,4})\b""")
      .findAll(fileName)
      .mapNotNullTo(numbers) { it.groupValues[1].toIntOrNull() }
    Regex("""(?i)\b\d{1,2}x(\d{1,4})\b""")
      .findAll(fileName)
      .mapNotNullTo(numbers) { it.groupValues[1].toIntOrNull() }
    Regex("""(?i)(?<![a-z0-9])\d{1,2}(\d{2})(?![a-z0-9])""")
      .findAll(fileName)
      .mapNotNullTo(numbers) { it.groupValues[1].toIntOrNull() }
    Regex("""(?:^|[^\d])0*(\d{1,4})(?=$|[^\d])""")
      .findAll(fileName)
      .mapNotNullTo(numbers) { it.groupValues[1].toIntOrNull() }
    return numbers
  }

  data class ExtractedSubtitle(
    val bytes: ByteArray,
    val extension: String,
    val fileName: String,
  )

  private data class Candidate(
    val subtitle: ExtractedSubtitle,
    val score: ArchiveEntryScore,
  )

  private data class ArchiveEntryScore(
    val preferredNameMatch: Boolean = false,
    val preferredEpisodeMatch: Boolean = false,
    val extensionPriority: Int = Int.MAX_VALUE,
    val size: Long = 0,
  )

  private class BoundedByteArrayOutputStream(
    private val maxBytes: Int,
  ) : OutputStream() {
    private val delegate = ByteArrayOutputStream()

    override fun write(value: Int) {
      ensureCapacity(1)
      delegate.write(value)
    }

    override fun write(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ) {
      ensureCapacity(length)
      delegate.write(buffer, offset, length)
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun ensureCapacity(additionalBytes: Int) {
      if (additionalBytes < 0 || delegate.size().toLong() + additionalBytes > maxBytes) {
        throw IOException("Subtitle archive entry exceeds $maxBytes bytes")
      }
    }
  }

  private const val MAX_SUBTITLE_BYTES = 32 * 1024 * 1024
  private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
}
