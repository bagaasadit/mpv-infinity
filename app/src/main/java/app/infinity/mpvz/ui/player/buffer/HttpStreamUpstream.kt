/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.buffer

import androidx.media3.common.C
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Open HTTP byte-stream connection supporting fast cancellation via [abort].
 */
interface HttpStreamConnection : Closeable {
  val responseCode: Int
  val responseMessage: String
  val resolvedUrl: String
  val responseHeaders: Map<String, List<String>>
  val contentLength: Long
  val totalResourceLength: Long
  val rangeStart: Long
  val supportsRange: Boolean

  @Throws(IOException::class)
  fun read(buffer: ByteArray, offset: Int, length: Int): Int

  fun abort()
}

fun interface HttpStreamOpener {
  @Throws(IOException::class)
  fun open(url: String, headers: Map<String, String>, startOffset: Long): HttpStreamConnection
}

class HttpOpenException(
  val responseCode: Int,
  val responseMessage: String,
  val headers: Map<String, List<String>>,
  val url: String,
  message: String,
  cause: Throwable? = null,
) : IOException(message, cause)

internal data class ParsedRangeMetadata(
  val rangeStart: Long,
  val contentLength: Long,
  val totalResourceLength: Long,
)

internal object HttpHeaderParser {
  private val CONTENT_RANGE_WITH_LENGTH =
    Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

  fun parseRangeMetadata(
    responseCode: Int,
    requestedOffset: Long,
    contentRangeHeader: String?,
    contentLengthHeader: String?,
  ): ParsedRangeMetadata {
    val headerLen = contentLengthHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: C.LENGTH_UNSET.toLong()

    if (responseCode == 206 && !contentRangeHeader.isNullOrBlank()) {
      val match = CONTENT_RANGE_WITH_LENGTH.find(contentRangeHeader.trim())
      if (match != null) {
        val start = match.groupValues[1].toLongOrNull() ?: requestedOffset
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].let { token ->
          if (token == "*") C.LENGTH_UNSET.toLong() else token.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
        }
        val spanLen = if (end != null && end >= start) {
          end - start + 1L
        } else {
          headerLen
        }
        val resolvedContentLen = if (headerLen != C.LENGTH_UNSET.toLong()) headerLen else spanLen
        return ParsedRangeMetadata(
          rangeStart = start,
          contentLength = resolvedContentLen,
          totalResourceLength = total,
        )
      }
    }

    val start = if (responseCode == 206) requestedOffset else 0L
    val totalLen = if (responseCode == 200) {
      headerLen
    } else if (start == 0L && headerLen != C.LENGTH_UNSET.toLong()) {
      headerLen
    } else {
      C.LENGTH_UNSET.toLong()
    }
    return ParsedRangeMetadata(
      rangeStart = start,
      contentLength = headerLen,
      totalResourceLength = totalLen,
    )
  }
}

/**
 * OkHttp-backed [HttpStreamOpener] that preserves byte offsets with `Accept-Encoding: identity`
 * and uses [Call.cancel] for sub-millisecond stream abort on seek or player stop.
 */
class OkHttpStreamOpener(
  private val okHttpClient: OkHttpClient,
) : HttpStreamOpener {

  override fun open(url: String, headers: Map<String, String>, startOffset: Long): HttpStreamConnection {
    val reqBuilder = Request.Builder().url(url).get()
    var hasAcceptEncoding = false

    for ((key, value) in headers) {
      if (key.equals("Range", ignoreCase = true)) continue
      if (key.equals("Accept-Encoding", ignoreCase = true)) {
        hasAcceptEncoding = true
      }
      reqBuilder.header(key, value)
    }
    if (!hasAcceptEncoding) {
      reqBuilder.header("Accept-Encoding", "identity")
    }
    if (startOffset > 0L) {
      reqBuilder.header("Range", "bytes=$startOffset-")
    }

    val call = okHttpClient.newCall(reqBuilder.build())
    val response: Response = try {
      call.execute()
    } catch (ioe: IOException) {
      throw ioe
    } catch (t: Throwable) {
      call.cancel()
      throw IOException("Unexpected error opening stream: $url", t)
    }

    val code = response.code
    val msg = response.message
    val resolvedUrl = response.request.url.toString()
    val headerMap = response.headers.toMultimap()

    if (!response.isSuccessful) {
      response.close()
      throw HttpOpenException(
        responseCode = code,
        responseMessage = msg,
        headers = headerMap,
        url = resolvedUrl,
        message = "HTTP $code $msg for $resolvedUrl",
      )
    }

    if (startOffset > 0L && code == 200) {
      response.close()
      throw IOException("Server returned HTTP 200 instead of 206 Partial Content for offset $startOffset")
    }

    val body = response.body

    val contentRange = response.header("Content-Range")
    val contentLengthHeader = response.header("Content-Length")
    val acceptRanges = response.header("Accept-Ranges")?.trim()?.lowercase(Locale.US)
    val parsed = HttpHeaderParser.parseRangeMetadata(
      responseCode = code,
      requestedOffset = startOffset,
      contentRangeHeader = contentRange,
      contentLengthHeader = contentLengthHeader,
    )
    val bodyLen = body.contentLength()
    val effectiveContentLength = if (parsed.contentLength >= 0L) {
      parsed.contentLength
    } else if (bodyLen >= 0L) {
      bodyLen
    } else {
      C.LENGTH_UNSET.toLong()
    }
    val effectiveTotalLength = if (parsed.totalResourceLength >= 0L) {
      parsed.totalResourceLength
    } else if (code == 200 && effectiveContentLength >= 0L) {
      effectiveContentLength
    } else {
      C.LENGTH_UNSET.toLong()
    }
    val supportsRange = code == 206 ||
      !contentRange.isNullOrBlank() ||
      (acceptRanges != null && acceptRanges != "none") ||
      (effectiveTotalLength > 0L && acceptRanges != "none")

    return OkHttpStreamConnection(
      call = call,
      response = response,
      stream = body.byteStream(),
      responseCode = code,
      responseMessage = msg,
      resolvedUrl = resolvedUrl,
      responseHeaders = headerMap,
      contentLength = effectiveContentLength,
      totalResourceLength = effectiveTotalLength,
      rangeStart = parsed.rangeStart,
      supportsRange = supportsRange,
    )
  }

  private class OkHttpStreamConnection(
    private val call: Call,
    private val response: Response,
    private val stream: InputStream,
    override val responseCode: Int,
    override val responseMessage: String,
    override val resolvedUrl: String,
    override val responseHeaders: Map<String, List<String>>,
    override val contentLength: Long,
    override val totalResourceLength: Long,
    override val rangeStart: Long,
    override val supportsRange: Boolean,
  ) : HttpStreamConnection {

    @Volatile
    private var aborted = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (aborted) throw IOException("Stream aborted")
      return stream.read(buffer, offset, length)
    }

    override fun abort() {
      aborted = true
      runCatching { call.cancel() }
      runCatching { response.close() }
    }

    override fun close() {
      runCatching { stream.close() }
      runCatching { response.close() }
    }
  }
}
