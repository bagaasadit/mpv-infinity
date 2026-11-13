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
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OffHeapReadAheadBufferTest {

  private class TrackingSlabAllocator : OffHeapSlabAllocator {
    val totalAllocations = AtomicInteger(0)
    val openSlabCount = AtomicInteger(0)

    override fun allocate(capacityBytes: Int): OffHeapSlab {
      totalAllocations.incrementAndGet()
      openSlabCount.incrementAndGet()
      val delegate = DirectByteBufferSlab(capacityBytes)
      val closedOnce = AtomicBoolean(false)
      return object : OffHeapSlab by delegate {
        override fun close() {
          if (closedOnce.compareAndSet(false, true)) {
            openSlabCount.decrementAndGet()
            delegate.close()
          }
        }
      }
    }
  }

  private class ScriptedConnection(
    private val payload: ByteArray,
    override val rangeStart: Long,
    override val resolvedUrl: String = "https://cdn.example/remux.mkv",
    override val supportsRange: Boolean = true,
    private val maxBytesBeforeDrop: Int = Int.MAX_VALUE,
    private val dropWithIoError: Boolean = false,
    private val chunkSizeLimit: Int = 512,
    private val blockAtStartLatch: CountDownLatch? = null,
    private val readEnteredLatch: CountDownLatch? = null,
  ) : HttpStreamConnection {

    override val responseCode: Int = if (rangeStart > 0L) 206 else 200
    override val responseMessage: String = if (rangeStart > 0L) "Partial Content" else "OK"
    override val responseHeaders: Map<String, List<String>> = emptyMap()
    override val contentLength: Long = payload.size.toLong() - rangeStart
    override val totalResourceLength: Long = payload.size.toLong()

    private var cursor = rangeStart.toInt()
    private var deliveredOnConn = 0

    @Volatile
    private var aborted = false

    @Volatile
    var abortCount = 0
      private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      readEnteredLatch?.countDown()
      blockAtStartLatch?.let { latch ->
        while (!aborted && !latch.await(20, TimeUnit.MILLISECONDS)) {
          if (Thread.currentThread().isInterrupted) throw IOException("Interrupted")
        }
      }
      if (aborted) throw IOException("Connection aborted")
      if (deliveredOnConn >= maxBytesBeforeDrop) {
        if (dropWithIoError) throw IOException("Simulated TCP reset at cursor=$cursor")
        return -1
      }
      if (cursor >= payload.size) return -1
      val maxAllowed = minOf(length, chunkSizeLimit, maxBytesBeforeDrop - deliveredOnConn, payload.size - cursor)
      System.arraycopy(payload, cursor, buffer, offset, maxAllowed)
      cursor += maxAllowed
      deliveredOnConn += maxAllowed
      return maxAllowed
    }

    override fun abort() {
      aborted = true
      abortCount++
      blockAtStartLatch?.countDown()
    }

    override fun close() {
      aborted = true
      blockAtStartLatch?.countDown()
    }
  }

  private fun deterministicPayload(sizeBytes: Int): ByteArray {
    return ByteArray(sizeBytes) { i -> ((i * 31 + (i ushr 7)) and 0xFF).toByte() }
  }

  private fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) return
      Thread.sleep(10)
    }
    fail("Condition not satisfied within ${timeoutMs}ms")
  }

  private fun readAllFromSession(
    session: OffHeapStreamSession,
    epoch: Long,
    startOffset: Long,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(777)
    var pos = startOffset
    while (true) {
      val n = session.readAt(epoch, pos, buf, 0, buf.size)
      if (n == C.RESULT_END_OF_INPUT) break
      assertTrue("Expected positive bytes read, got $n", n > 0)
      out.write(buf, 0, n)
      pos += n
    }
    return out.toByteArray()
  }

  @Test
  fun httpHeaderParserParses206And200ResponsesAccurately() {
    val partial = HttpHeaderParser.parseRangeMetadata(
      responseCode = 206,
      requestedOffset = 1000L,
      contentRangeHeader = "bytes 1000-4999/100000",
      contentLengthHeader = "4000",
    )
    assertEquals(1000L, partial.rangeStart)
    assertEquals(4000L, partial.contentLength)
    assertEquals(100_000L, partial.totalResourceLength)

    val unknownTotal = HttpHeaderParser.parseRangeMetadata(
      responseCode = 206,
      requestedOffset = 500L,
      contentRangeHeader = "bytes 500-1499/*",
      contentLengthHeader = null,
    )
    assertEquals(500L, unknownTotal.rangeStart)
    assertEquals(1000L, unknownTotal.contentLength)
    assertEquals(C.LENGTH_UNSET.toLong(), unknownTotal.totalResourceLength)

    val fullOk = HttpHeaderParser.parseRangeMetadata(
      responseCode = 200,
      requestedOffset = 0L,
      contentRangeHeader = null,
      contentLengthHeader = "53687091200",
    )
    assertEquals(0L, fullOk.rangeStart)
    assertEquals(53687091200L, fullOk.contentLength)
    assertEquals(53687091200L, fullOk.totalResourceLength)
  }

  @Test
  fun bypassAndWarmupPromotionRulesProtectMkvProbesAndSubtitles() {
    val unb = C.LENGTH_UNSET.toLong()
    assertTrue(
      ReadAheadHttpDataSource.shouldBypassRequest(
        sessionEnabled = false,
        httpMethod = DataSpec.HTTP_METHOD_GET,
        scheme = "https",
        pathOrUrl = "/movie.mkv",
        urlString = "https://cdn/movie.mkv",
        declaredLength = unb,
      ),
    )
    assertTrue(
      ReadAheadHttpDataSource.shouldBypassRequest(
        sessionEnabled = true,
        httpMethod = DataSpec.HTTP_METHOD_GET,
        scheme = "file",
        pathOrUrl = "/sdcard/movie.mkv",
        urlString = "file:///sdcard/movie.mkv",
        declaredLength = unb,
      ),
    )
    assertTrue(
      ReadAheadHttpDataSource.shouldBypassRequest(
        sessionEnabled = true,
        httpMethod = DataSpec.HTTP_METHOD_POST,
        scheme = "https",
        pathOrUrl = "/movie.mkv",
        urlString = "https://cdn/movie.mkv",
        declaredLength = unb,
      ),
    )
    assertTrue(
      ReadAheadHttpDataSource.shouldBypassRequest(
        sessionEnabled = true,
        httpMethod = DataSpec.HTTP_METHOD_GET,
        scheme = "https",
        pathOrUrl = "/master.m3u8",
        urlString = "https://cdn/master.m3u8?token=1",
        declaredLength = unb,
      ),
    )
    assertTrue(
      ReadAheadHttpDataSource.shouldBypassRequest(
        sessionEnabled = true,
        httpMethod = DataSpec.HTTP_METHOD_GET,
        scheme = "https",
        pathOrUrl = "/sub.ass",
        urlString = "https://cdn/sub.ass",
        declaredLength = unb,
      ),
    )
    assertTrue(
      ReadAheadHttpDataSource.shouldBypassRequest(
        sessionEnabled = true,
        httpMethod = DataSpec.HTTP_METHOD_GET,
        scheme = "https",
        pathOrUrl = "/movie.mkv",
        urlString = "https://cdn/movie.mkv",
        declaredLength = 512L * 1024L,
      ),
    )
    assertFalse(
      ReadAheadHttpDataSource.shouldBypassRequest(
        sessionEnabled = true,
        httpMethod = DataSpec.HTTP_METHOD_GET,
        scheme = "https",
        pathOrUrl = "/Remux.2160p.mkv",
        urlString = "https://cdn/Remux.2160p.mkv?auth=xyz",
        declaredLength = unb,
      ),
    )

    // Initial MKV header probe (< 1MB read): never promote
    assertFalse(
      ReadAheadHttpDataSource.shouldPromoteWarmup(
        warmupBytesRead = 128L * 1024L,
        minSequentialBytes = 1024 * 1024,
        endBound = 60_000_000_000L,
        currentPos = 128L * 1024L,
        minRemainingBytes = 16L * 1024L * 1024L,
        declaredSpecLength = unb,
      ),
    )
    // EOF Cues read (<= 16MB remaining even after >= 1MB): never promote
    assertFalse(
      ReadAheadHttpDataSource.shouldPromoteWarmup(
        warmupBytesRead = 2L * 1024L * 1024L,
        minSequentialBytes = 1024 * 1024,
        endBound = 60_000_000_000L,
        currentPos = 59_990_000_000L,
        minRemainingBytes = 16L * 1024L * 1024L,
        declaredSpecLength = unb,
      ),
    )
    // Sustained cluster playback (> 1MB sequential and > 16MB remaining): promote!
    assertTrue(
      ReadAheadHttpDataSource.shouldPromoteWarmup(
        warmupBytesRead = 1024L * 1024L,
        minSequentialBytes = 1024 * 1024,
        endBound = 60_000_000_000L,
        currentPos = 2L * 1024L * 1024L,
        minRemainingBytes = 16L * 1024L * 1024L,
        declaredSpecLength = unb,
      ),
    )
  }

  @Test
  fun ringWrapAroundPreservesExactBytesAcrossMultipleSlabsAndFreesOnRelease() {
    val payload = deterministicPayload(12 * 1024)
    val allocator = TrackingSlabAllocator()
    val opener = HttpStreamOpener { _, _, offset ->
      ScriptedConnection(payload = payload, rangeStart = offset)
    }
    val config = OffHeapStreamConfig(
      capacityBytes = 4096L,
      slabSizeBytes = 1024,
      backWindowBytes = 1024L,
      minGuaranteedForwardBytes = 1024L,
      reconnectBaseDelayMs = 1L,
    )
    val manager = OffHeapStreamSessionManager(
      config = config,
      opener = opener,
      slabAllocator = allocator,
      memoryGuard = SystemMemoryGuard { true },
    )

    val session = manager.getOrCreateSession("https://cdn.example/movie.mkv")
    val attach = session.seekAndAttach(0L, emptyMap())
    val actual = readAllFromSession(session, attach.seekEpoch, 0L)

    assertArrayEquals(payload, actual)
    // Only 4 slabs max were needed to stream a 12KB file via circular slab reuse
    assertEquals(4, allocator.totalAllocations.get())
    assertEquals(4, allocator.openSlabCount.get())

    manager.releaseAll()
    assertEquals(0, allocator.openSlabCount.get())
    assertEquals(0L, manager.allocatedOffHeapBytes())
  }

  @Test
  fun rewindCacheHitInsideBackWindowReattachesWithZeroHttpRequests() {
    val payload = deterministicPayload(8192)
    val allocator = TrackingSlabAllocator()
    val openCount = AtomicInteger(0)
    val opener = HttpStreamOpener { _, _, offset ->
      openCount.incrementAndGet()
      ScriptedConnection(payload = payload, rangeStart = offset)
    }
    val config = OffHeapStreamConfig(
      capacityBytes = 8192L,
      slabSizeBytes = 1024,
      backWindowBytes = 2048L,
      minGuaranteedForwardBytes = 1024L,
      reconnectBaseDelayMs = 1L,
    )
    val session = OffHeapStreamSession(
      url = "https://cdn.example/cachehit.mkv",
      config = config,
      opener = opener,
      slabAllocator = allocator,
      memoryGuard = SystemMemoryGuard { true },
    )

    try {
      val initialConn = opener.open("https://cdn.example/cachehit.mkv", emptyMap(), 512L)
      val promoted = session.promoteWarmupConnection(initialConn, 512L, emptyMap())
      assertNotNull(promoted)
      assertEquals(1, openCount.get())

      // Consumer reads 3000 bytes then detaches (simulating ExoPlayer audio track switch)
      val firstRead = ByteArray(3000)
      var pos = 512L
      var copied = 0
      while (copied < firstRead.size) {
        val n = session.readAt(promoted!!.seekEpoch, pos, firstRead, copied, firstRead.size - copied)
        assertTrue(n > 0)
        pos += n
        copied += n
      }
      session.detachConsumer()

      // Pump finishes filling up to 8192 in background
      waitUntil { session.bufferedAheadBytes() + pos >= payload.size }

      // Rewind by 1000 bytes (still well inside backWindow 2048) -> MUST be RAM HIT with 0 HTTP opens!
      val rewindPos = pos - 1000L
      val hit = session.tryAttachBuffered(rewindPos)
      assertNotNull("Expected cache HIT within off-heap ring window", hit)
      assertEquals(1, openCount.get())

      val afterRewind = readAllFromSession(session, hit!!.seekEpoch, rewindPos)
      val expectedTail = payload.copyOfRange(rewindPos.toInt(), payload.size)
      assertArrayEquals(expectedTail, afterRewind)
      assertEquals(1, openCount.get())
    } finally {
      session.release()
    }
  }

  @Test
  fun farSeekAbortsOldConnectionAndReusesSlabsWithoutReallocation() {
    val payload = deterministicPayload(16 * 1024)
    val allocator = TrackingSlabAllocator()
    val firstConnEnteredRead = CountDownLatch(1)
    val firstConnHold = CountDownLatch(1)
    var firstConnRef: ScriptedConnection? = null
    val initialPhase = AtomicBoolean(true)
    val opener = HttpStreamOpener { _, _, offset ->
      if (offset == 0L && initialPhase.get()) {
        ScriptedConnection(
          payload = payload,
          rangeStart = 0L,
          maxBytesBeforeDrop = 2048,
          chunkSizeLimit = 1024,
          readEnteredLatch = firstConnEnteredRead,
          blockAtStartLatch = firstConnHold,
        ).also { firstConnRef = it }
      } else {
        ScriptedConnection(payload = payload, rangeStart = offset)
      }
    }
    val config = OffHeapStreamConfig(
      capacityBytes = 4096L,
      slabSizeBytes = 1024,
      backWindowBytes = 1024L,
      minGuaranteedForwardBytes = 1024L,
      reconnectBaseDelayMs = 1L,
    )
    val session = OffHeapStreamSession(
      url = "https://cdn.example/seek.mkv",
      config = config,
      opener = opener,
      slabAllocator = allocator,
      memoryGuard = SystemMemoryGuard { true },
    )

    try {
      // Blocked pump on initial socket must be aborted cleanly in <1s on far seek.
      session.seekAndAttach(0L, emptyMap())
      assertTrue(firstConnEnteredRead.await(2, TimeUnit.SECONDS))

      initialPhase.set(false)
      val sought = session.seekAndAttach(12_345L, emptyMap())
      assertTrue("Expected blocked connection to be aborted on far seek", (firstConnRef?.abortCount ?: 0) >= 1)
      val tail = readAllFromSession(session, sought.seekEpoch, 12_345L)
      assertArrayEquals(payload.copyOfRange(12_345, payload.size), tail)
      val slabsAllocatedAfterTail = allocator.totalAllocations.get()
      assertEquals(4, slabsAllocatedAfterTail)

      // Seeking far back to 0 must reuse all 4 existing slabs with 0 extra slab allocations!
      val soughtBack = session.seekAndAttach(0L, emptyMap())
      val fullReplay = readAllFromSession(session, soughtBack.seekEpoch, 0L)
      assertArrayEquals(payload, fullReplay)
      assertEquals(slabsAllocatedAfterTail, allocator.totalAllocations.get())
      assertEquals(4, allocator.openSlabCount.get())
    } finally {
      firstConnHold.countDown()
      session.release()
    }
  }

  @Test
  fun prematureEofAndIoExceptionReconnectViaRangeTransparently() {
    val payload = deterministicPayload(10 * 1024)
    val requestedOffsets = CopyOnWriteArrayList<Long>()
    val attempt = AtomicInteger(0)

    val opener = HttpStreamOpener { _, _, offset ->
      requestedOffsets.add(offset)
      when (attempt.getAndIncrement()) {
        0 -> ScriptedConnection(
          payload = payload,
          rangeStart = offset,
          maxBytesBeforeDrop = 2500,
          dropWithIoError = true,
        )
        1 -> ScriptedConnection(
          payload = payload,
          rangeStart = offset,
          maxBytesBeforeDrop = 3000,
          dropWithIoError = false, // premature EOF before content length
        )
        else -> ScriptedConnection(payload = payload, rangeStart = offset)
      }
    }

    val config = OffHeapStreamConfig(
      capacityBytes = 4096L,
      slabSizeBytes = 1024,
      backWindowBytes = 1024L,
      minGuaranteedForwardBytes = 1024L,
      reconnectBaseDelayMs = 1L,
    )
    val allocator = TrackingSlabAllocator()
    val session = OffHeapStreamSession(
      url = "https://cdn.example/flaky.mkv",
      config = config,
      opener = opener,
      slabAllocator = allocator,
      memoryGuard = SystemMemoryGuard { true },
    )

    try {
      val attach = session.seekAndAttach(0L, emptyMap())
      val fullRead = readAllFromSession(session, attach.seekEpoch, 0L)
      assertArrayEquals(payload, fullRead)
      assertEquals(listOf(0L, 2500L, 5500L), requestedOffsets)
    } finally {
      session.release()
    }
  }

  @Test
  fun permanentHttp404StopsRetryImmediatelyAndPropagatesError() {
    val payload = deterministicPayload(8192)
    val openAttempts = AtomicInteger(0)
    val opener = HttpStreamOpener { _, _, offset ->
      if (openAttempts.getAndIncrement() == 0) {
        ScriptedConnection(
          payload = payload,
          rangeStart = offset,
          maxBytesBeforeDrop = 1024,
          dropWithIoError = true,
        )
      } else {
        throw HttpOpenException(
          responseCode = 404,
          responseMessage = "Not Found",
          headers = emptyMap(),
          url = "https://cdn.example/gone.mkv",
          message = "HTTP 404",
        )
      }
    }

    val config = OffHeapStreamConfig(
      capacityBytes = 4096L,
      slabSizeBytes = 1024,
      reconnectBaseDelayMs = 1L,
    )
    val session = OffHeapStreamSession(
      url = "https://cdn.example/gone.mkv",
      config = config,
      opener = opener,
      slabAllocator = TrackingSlabAllocator(),
      memoryGuard = SystemMemoryGuard { true },
    )

    try {
      val attach = session.seekAndAttach(0L, emptyMap())
      val buf = ByteArray(1024)
      assertEquals(1024, readExact(session, attach.seekEpoch, 0L, buf))
      try {
        session.readAt(attach.seekEpoch, 1024L, buf, 0, buf.size)
        fail("Expected IOException from HTTP 404 terminal failure")
      } catch (expected: IOException) {
        assertTrue(expected is HttpOpenException)
        assertEquals(2, openAttempts.get())
      }
    } finally {
      session.release()
    }
  }

  @Test
  fun memoryGuardDefersOptionalSlabExpansionWithoutBlockingEssentialBuffer() {
    val payload = deterministicPayload(6 * 1024)
    val allowOptional = AtomicBoolean(false)
    val allocator = TrackingSlabAllocator()
    val opener = HttpStreamOpener { _, _, offset ->
      ScriptedConnection(payload = payload, rangeStart = offset)
    }
    val config = OffHeapStreamConfig(
      capacityBytes = 4096L,
      slabSizeBytes = 1024,
      backWindowBytes = 1024L,
      minGuaranteedForwardBytes = 1024L,
      reconnectBaseDelayMs = 1L,
    )
    val guard = SystemMemoryGuard { forwardBytes ->
      forwardBytes < 1024L || allowOptional.get()
    }
    val session = OffHeapStreamSession(
      url = "https://cdn.example/lowram.mkv",
      config = config,
      opener = opener,
      slabAllocator = allocator,
      memoryGuard = guard,
    )

    try {
      val attach = session.seekAndAttach(0L, emptyMap())
      // Essential 1 slab (1024B) must still fill even while optional expansion is blocked!
      waitUntil { session.bufferedAheadBytes() >= 1024L }
      assertEquals(1, allocator.openSlabCount.get())

      // Reading consumer advances past guaranteed window while opening optional flag
      allowOptional.set(true)
      val actual = readAllFromSession(session, attach.seekEpoch, 0L)
      assertArrayEquals(payload, actual)
    } finally {
      session.release()
    }
  }

  @Test
  fun consumerInterruptDuringBlockedReadThrowsInterruptedIoException() {
    val payload = deterministicPayload(8192)
    val holdNetwork = CountDownLatch(1)
    val opener = HttpStreamOpener { _, _, offset ->
      ScriptedConnection(
        payload = payload,
        rangeStart = offset,
        blockAtStartLatch = holdNetwork,
      )
    }
    val session = OffHeapStreamSession(
      url = "https://cdn.example/interrupt.mkv",
      config = OffHeapStreamConfig(capacityBytes = 4096L, slabSizeBytes = 1024, reconnectBaseDelayMs = 1L),
      opener = opener,
      slabAllocator = TrackingSlabAllocator(),
      memoryGuard = SystemMemoryGuard { true },
    )

    try {
      val attach = session.seekAndAttach(0L, emptyMap())
      val caughtInterrupted = AtomicBoolean(false)
      val readerFinished = CountDownLatch(1)
      val readerThread = Thread {
        try {
          session.readAt(attach.seekEpoch, 0L, ByteArray(1024), 0, 1024)
        } catch (_: InterruptedIOException) {
          caughtInterrupted.set(Thread.currentThread().isInterrupted)
        } finally {
          readerFinished.countDown()
        }
      }
      readerThread.start()
      Thread.sleep(30)
      readerThread.interrupt()
      assertTrue(readerFinished.await(2, TimeUnit.SECONDS))
      assertTrue("Expected InterruptedIOException with interrupt flag preserved", caughtInterrupted.get())
    } finally {
      holdNetwork.countDown()
      session.release()
    }
  }

  @Test
  fun budgetedManagerEnforcesCombinedLimitAcrossConcurrentSessions() {
    val allocator = TrackingSlabAllocator()
    val payload = deterministicPayload(8192)
    val opener = HttpStreamOpener { _, _, offset ->
      ScriptedConnection(payload = payload, rangeStart = offset)
    }
    val manager = OffHeapStreamSessionManager(
      config = OffHeapStreamConfig(capacityBytes = 4096L, slabSizeBytes = 1024, backWindowBytes = 1024L),
      opener = opener,
      slabAllocator = allocator,
      memoryGuard = SystemMemoryGuard { true },
      maxConcurrentSessions = 2,
    )

    try {
      val video = manager.getOrCreateSession("https://cdn.example/video.mkv")
      val audio = manager.getOrCreateSession("https://cdn.example/audio.mka")
      video.seekAndAttach(0L, emptyMap())
      audio.seekAndAttach(0L, emptyMap())
      waitUntil { manager.allocatedOffHeapBytes() > 0L }
      assertTrue(manager.allocatedOffHeapBytes() <= 4096L)

      // Opening a 3rd distinct URL evicts the oldest session and keeps slabs bounded
      val third = manager.getOrCreateSession("https://cdn.example/movie2.mkv")
      third.seekAndAttach(0L, emptyMap())
      assertTrue(manager.allocatedOffHeapBytes() <= 4096L)
    } finally {
      manager.releaseAll()
      assertEquals(0L, manager.allocatedOffHeapBytes())
      assertEquals(0, allocator.openSlabCount.get())
    }
  }

  private fun readExact(
    session: OffHeapStreamSession,
    epoch: Long,
    startOffset: Long,
    dst: ByteArray,
  ): Int {
    var pos = startOffset
    var filled = 0
    while (filled < dst.size) {
      val n = session.readAt(epoch, pos, dst, filled, dst.size - filled)
      if (n <= 0) break
      pos += n
      filled += n
    }
    return filled
  }
}
