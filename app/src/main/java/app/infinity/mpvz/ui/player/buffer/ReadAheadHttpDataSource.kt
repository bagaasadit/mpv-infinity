/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.buffer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.util.Locale

/**
 * [DataSource] backed by [OffHeapStreamSessionManager].
 *
 * Small probes, playlists, subtitles, and bounded requests (<= 4MB) pass through to [upstreamFactory]
 * cleanly with zero slab overhead. Large HTTP video streams start immediately in zero-copy warmup
 * mode and promote into the off-heap [android.os.SharedMemory] ring buffer once sequential playback
 * passes 1MB with >16MB remaining, turning track switches and short rewinds into instant RAM hits.
 */
@OptIn(UnstableApi::class)
class ReadAheadHttpDataSource(
  private val upstreamFactory: DataSource.Factory,
  private val sessionManager: OffHeapStreamSessionManager,
  private val opener: HttpStreamOpener,
  private val defaultPropertiesSnapshot: () -> Map<String, String>,
) : BaseDataSource(/* isNetwork = */ true) {

  private enum class Mode {
    NONE,
    BYPASS,
    WARMUP,
    SESSION,
  }

  private var mode = Mode.NONE
  private var opened = false
  private var openedDataSpec: DataSpec? = null
  private var currentUri: Uri? = null
  private var currentHeaders: Map<String, List<String>> = emptyMap()
  private var mergedRequestHeaders: Map<String, String> = emptyMap()

  private var activeDelegate: DataSource? = null
  private var warmupConnection: HttpStreamConnection? = null
  private var warmupSequentialBytes: Long = 0L
  private var warmupEndBound: Long = C.LENGTH_UNSET.toLong()

  private var attachedSession: OffHeapStreamSession? = null
  private var attachedEpoch: Long = 0L
  private var currentPos: Long = 0L
  private var bytesRemainingToReturn: Long = C.LENGTH_UNSET.toLong()

  override fun open(dataSpec: DataSpec): Long {
    mode = Mode.NONE
    openedDataSpec = dataSpec
    currentUri = dataSpec.uri
    currentHeaders = emptyMap()
    mergedRequestHeaders = buildMergedHeaders(dataSpec)
    currentPos = dataSpec.position
    bytesRemainingToReturn = C.LENGTH_UNSET.toLong()

    val urlString = dataSpec.uri.toString()
    if (shouldBypass(dataSpec, urlString)) {
      transferInitializing(dataSpec)
      val delegate = upstreamFactory.createDataSource()
      activeDelegate = delegate
      val length = delegate.open(dataSpec)
      mode = Mode.BYPASS
      opened = true
      bytesRemainingToReturn = length
      transferStarted(dataSpec)
      return length
    }

    transferInitializing(dataSpec)
    val session = sessionManager.getOrCreateSession(urlString)

    // 1. Check for instant off-heap RAM HIT within the current ring window.
    session.tryAttachBuffered(dataSpec.position)?.let { hit ->
      attachedSession = session
      attachedEpoch = hit.seekEpoch
      mode = Mode.SESSION
      opened = true
      currentHeaders = hit.responseHeaders
      updateResolvedUri(hit.resolvedUrl, dataSpec.uri)
      val returnLen = computeReturnLength(dataSpec, C.LENGTH_UNSET.toLong(), hit.totalResourceLength)
      bytesRemainingToReturn = returnLen
      transferStarted(dataSpec)
      return returnLen
    }

    // 2. If session was previously promoted for playback and consumer sought outside window,
    // abort old stream cleanly and restart off-heap pump from new offset reusing slabs.
    if (session.hasPromotedBefore()) {
      val attached = try {
        session.seekAndAttach(dataSpec.position, mergedRequestHeaders)
      } catch (t: Throwable) {
        throw mapOpenError(t, dataSpec)
      }
      attachedSession = session
      attachedEpoch = attached.seekEpoch
      mode = Mode.SESSION
      opened = true
      currentHeaders = attached.responseHeaders
      updateResolvedUri(attached.resolvedUrl, dataSpec.uri)
      val returnLen = computeReturnLength(dataSpec, C.LENGTH_UNSET.toLong(), attached.totalResourceLength)
      bytesRemainingToReturn = returnLen
      transferStarted(dataSpec)
      return returnLen
    }

    // 3. Initial probe/prepare phase: open directly without allocating off-heap slabs or threads yet.
    val conn = try {
      opener.open(urlString, mergedRequestHeaders, dataSpec.position)
    } catch (t: Throwable) {
      throw mapOpenError(t, dataSpec)
    }

    warmupConnection = conn
    attachedSession = session
    warmupSequentialBytes = 0L
    warmupEndBound = when {
      dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.position + dataSpec.length
      conn.totalResourceLength >= 0L -> conn.totalResourceLength
      else -> C.LENGTH_UNSET.toLong()
    }
    mode = Mode.WARMUP
    opened = true
    currentHeaders = conn.responseHeaders
    updateResolvedUri(conn.resolvedUrl, dataSpec.uri)

    val returnLen = computeReturnLength(dataSpec, conn.contentLength, conn.totalResourceLength)
    bytesRemainingToReturn = returnLen
    transferStarted(dataSpec)
    return returnLen
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (bytesRemainingToReturn == 0L) return C.RESULT_END_OF_INPUT
    val maxToRead = if (bytesRemainingToReturn > 0L) {
      minOf(length.toLong(), bytesRemainingToReturn).toInt()
    } else {
      length
    }

    return try {
      when (mode) {
        Mode.BYPASS -> readFromDelegate(buffer, offset, maxToRead)
        Mode.WARMUP -> readFromWarmupAndMaybePromote(buffer, offset, maxToRead)
        Mode.SESSION -> readFromSession(buffer, offset, maxToRead)
        Mode.NONE -> throw IOException("DataSource not opened")
      }
    } catch (dse: DataSourceException) {
      throw dse
    } catch (ioe: IOException) {
      val spec = openedDataSpec ?: throw ioe
      throw HttpDataSource.HttpDataSourceException.createForIOException(
        ioe,
        spec,
        HttpDataSource.HttpDataSourceException.TYPE_READ,
      )
    }
  }

  override fun getUri(): Uri? {
    return when (mode) {
      Mode.BYPASS -> activeDelegate?.uri ?: currentUri
      else -> currentUri
    }
  }

  override fun getResponseHeaders(): Map<String, List<String>> {
    return when (mode) {
      Mode.BYPASS -> activeDelegate?.responseHeaders ?: emptyMap()
      else -> currentHeaders
    }
  }

  override fun close() {
    var closeError: IOException? = null
    try {
      activeDelegate?.let { delegate ->
        activeDelegate = null
        delegate.close()
      }
      warmupConnection?.let { conn ->
        warmupConnection = null
        conn.abort()
        conn.close()
      }
      attachedSession?.let { session ->
        attachedSession = null
        if (mode == Mode.SESSION) {
          session.detachConsumer()
        }
      }
    } catch (ioe: IOException) {
      closeError = ioe
    } finally {
      mode = Mode.NONE
      openedDataSpec = null
      currentUri = null
      currentHeaders = emptyMap()
      if (opened) {
        opened = false
        transferEnded()
      }
    }
    closeError?.let { throw it }
  }

  private fun readFromDelegate(buffer: ByteArray, offset: Int, length: Int): Int {
    val delegate = activeDelegate ?: return C.RESULT_END_OF_INPUT
    val n = delegate.read(buffer, offset, length)
    if (n > 0) {
      if (bytesRemainingToReturn > 0L) bytesRemainingToReturn -= n
      bytesTransferred(n)
    } else if (n == C.RESULT_END_OF_INPUT) {
      bytesRemainingToReturn = 0L
    }
    return n
  }

  private fun readFromWarmupAndMaybePromote(buffer: ByteArray, offset: Int, length: Int): Int {
    val conn = warmupConnection ?: return C.RESULT_END_OF_INPUT
    val n = conn.read(buffer, offset, length)
    if (n < 0) {
      bytesRemainingToReturn = 0L
      return C.RESULT_END_OF_INPUT
    }
    if (n > 0) {
      currentPos += n
      warmupSequentialBytes += n
      if (bytesRemainingToReturn > 0L) bytesRemainingToReturn -= n
      bytesTransferred(n)

      val cfg = sessionManager.config
      if (shouldPromoteWarmup(
          warmupBytesRead = warmupSequentialBytes,
          minSequentialBytes = cfg.promotionMinSequentialBytes,
          endBound = warmupEndBound,
          currentPos = currentPos,
          minRemainingBytes = cfg.promotionMinRemainingBytes,
          declaredSpecLength = openedDataSpec?.length ?: C.LENGTH_UNSET.toLong(),
        )
      ) {
        val session = attachedSession
        if (session != null) {
          val promoted = session.promoteWarmupConnection(
            connection = conn,
            currentOffset = currentPos,
            reqHeaders = mergedRequestHeaders,
          )
          if (promoted != null) {
            warmupConnection = null
            mode = Mode.SESSION
            attachedEpoch = promoted.seekEpoch
          }
        }
      }
    }
    return n
  }

  private fun readFromSession(buffer: ByteArray, offset: Int, length: Int): Int {
    val session = attachedSession ?: return C.RESULT_END_OF_INPUT
    var n = session.readAt(attachedEpoch, currentPos, buffer, offset, length)
    if (n == OffHeapStreamSession.READ_NEED_SEEK) {
      val reattached = session.seekAndAttach(currentPos, mergedRequestHeaders)
      attachedEpoch = reattached.seekEpoch
      n = session.readAt(attachedEpoch, currentPos, buffer, offset, length)
      if (n == OffHeapStreamSession.READ_NEED_SEEK) {
        throw IOException("Off-heap ring position invalidated twice at $currentPos")
      }
    }
    if (n == C.RESULT_END_OF_INPUT) {
      bytesRemainingToReturn = 0L
      return C.RESULT_END_OF_INPUT
    }
    if (n > 0) {
      currentPos += n
      if (bytesRemainingToReturn > 0L) bytesRemainingToReturn -= n
      bytesTransferred(n)
    }
    return n
  }

  private fun shouldBypass(dataSpec: DataSpec, urlString: String): Boolean {
    return shouldBypassRequest(
      sessionEnabled = sessionManager.isEnabled,
      httpMethod = dataSpec.httpMethod,
      scheme = runCatching { dataSpec.uri.scheme }.getOrNull(),
      pathOrUrl = runCatching { dataSpec.uri.path }.getOrNull() ?: urlString,
      urlString = urlString,
      declaredLength = dataSpec.length,
    )
  }

  private fun buildMergedHeaders(dataSpec: DataSpec): Map<String, String> {
    val defaults = defaultPropertiesSnapshot()
    val extra = dataSpec.httpRequestHeaders
    if (defaults.isEmpty() && extra.isEmpty()) return emptyMap()
    val merged = LinkedHashMap<String, String>(defaults.size + extra.size)
    merged.putAll(defaults)
    merged.putAll(extra)
    return merged
  }

  private fun computeReturnLength(
    dataSpec: DataSpec,
    connContentLength: Long,
    totalResourceLength: Long,
  ): Long {
    if (dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec.length
    if (totalResourceLength >= 0L) return maxOf(0L, totalResourceLength - dataSpec.position)
    if (connContentLength >= 0L) return connContentLength
    return C.LENGTH_UNSET.toLong()
  }

  private fun updateResolvedUri(resolvedUrl: String, fallback: Uri) {
    val parsed = if (resolvedUrl.isNotBlank()) runCatching { Uri.parse(resolvedUrl) }.getOrNull() else null
    currentUri = parsed ?: fallback
  }

  private fun mapOpenError(t: Throwable, dataSpec: DataSpec): DataSourceException {
    return when (t) {
      is DataSourceException -> t
      is HttpOpenException -> {
        if (t.responseCode == 416) {
          DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        } else {
          HttpDataSource.InvalidResponseCodeException(
            t.responseCode,
            t.responseMessage,
            t,
            t.headers,
            dataSpec,
            ByteArray(0),
          )
        }
      }
      is IOException -> HttpDataSource.HttpDataSourceException.createForIOException(
        t,
        dataSpec,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
      )
      else -> HttpDataSource.HttpDataSourceException.createForIOException(
        IOException(t),
        dataSpec,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
      )
    }
  }

  companion object {
    private const val MAX_BYPASS_BOUNDED_BYTES = 4L * 1024L * 1024L
    private val BYPASS_EXTENSIONS = setOf(
      ".m3u8",
      ".mpd",
      ".srt",
      ".ass",
      ".ssa",
      ".vtt",
      ".sub",
      ".ttml",
      ".idx",
      ".sup",
      ".json",
      ".xml",
      ".nfo",
    )

    internal fun shouldBypassRequest(
      sessionEnabled: Boolean,
      httpMethod: Int,
      scheme: String?,
      pathOrUrl: String,
      urlString: String,
      declaredLength: Long,
    ): Boolean {
      if (!sessionEnabled) return true
      if (httpMethod != DataSpec.HTTP_METHOD_GET) return true

      val resolvedScheme = scheme ?: urlString.substringBefore("://", "")
      if (!resolvedScheme.equals("http", ignoreCase = true) &&
        !resolvedScheme.equals("https", ignoreCase = true)
      ) {
        return true
      }

      if (declaredLength != C.LENGTH_UNSET.toLong() && declaredLength <= MAX_BYPASS_BOUNDED_BYTES) {
        return true
      }

      val cleanPath = pathOrUrl
        .substringBefore('?')
        .substringBefore('#')
        .lowercase(Locale.US)
      return BYPASS_EXTENSIONS.any { ext -> cleanPath.endsWith(ext) }
    }

    internal fun shouldPromoteWarmup(
      warmupBytesRead: Long,
      minSequentialBytes: Int,
      endBound: Long,
      currentPos: Long,
      minRemainingBytes: Long,
      declaredSpecLength: Long,
    ): Boolean {
      if (warmupBytesRead < minSequentialBytes) return false
      val hasEnoughRemaining = endBound < 0L || (endBound - currentPos) > minRemainingBytes
      val isLargeOrUnboundedSpec = declaredSpecLength == C.LENGTH_UNSET.toLong() ||
        declaredSpecLength > minRemainingBytes
      return hasEnoughRemaining && isLargeOrUnboundedSpec
    }
  }
}

@OptIn(UnstableApi::class)
class ReadAheadHttpDataSourceFactory(
  private val upstreamFactory: DataSource.Factory,
  private val sessionManager: OffHeapStreamSessionManager,
  private val opener: HttpStreamOpener,
) : DataSource.Factory {

  @Volatile
  private var defaultProperties: Map<String, String> = emptyMap()

  fun setDefaultRequestProperties(properties: Map<String, String>) {
    defaultProperties = if (properties.isEmpty()) emptyMap() else LinkedHashMap(properties)
    if (upstreamFactory is HttpDataSource.Factory) {
      upstreamFactory.setDefaultRequestProperties(properties)
    }
  }

  override fun createDataSource(): DataSource {
    return ReadAheadHttpDataSource(
      upstreamFactory = upstreamFactory,
      sessionManager = sessionManager,
      opener = opener,
      defaultPropertiesSnapshot = { defaultProperties },
    )
  }
}
