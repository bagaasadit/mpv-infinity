/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.buffer

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import app.infinity.mpvz.utils.device.AdaptiveBufferManager
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class OffHeapStreamConfig(
  val capacityBytes: Long,
  val slabSizeBytes: Int = DEFAULT_SLAB_SIZE_BYTES,
  val backWindowBytes: Long = minOf(DEFAULT_BACK_WINDOW_BYTES, capacityBytes / 4L),
  val minGuaranteedForwardBytes: Long = minOf(DEFAULT_MIN_GUARANTEED_FORWARD_BYTES, capacityBytes / 4L),
  val promotionMinSequentialBytes: Int = DEFAULT_PROMOTION_MIN_SEQUENTIAL_BYTES,
  val promotionMinRemainingBytes: Long = DEFAULT_PROMOTION_MIN_REMAINING_BYTES,
  val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
  val forwardCacheHitGraceBytes: Long = DEFAULT_FORWARD_CACHE_HIT_GRACE_BYTES,
  val reconnectBaseDelayMs: Long = DEFAULT_RECONNECT_BASE_DELAY_MS,
) {
  companion object {
    const val DEFAULT_SLAB_SIZE_BYTES = 8 * 1024 * 1024
    const val DEFAULT_BACK_WINDOW_BYTES = 32L * 1024L * 1024L
    const val DEFAULT_MIN_GUARANTEED_FORWARD_BYTES = 32L * 1024L * 1024L
    const val DEFAULT_PROMOTION_MIN_SEQUENTIAL_BYTES = 1 * 1024 * 1024
    const val DEFAULT_PROMOTION_MIN_REMAINING_BYTES = 16L * 1024L * 1024L
    const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 6
    const val DEFAULT_FORWARD_CACHE_HIT_GRACE_BYTES = 512L * 1024L
    const val DEFAULT_RECONNECT_BASE_DELAY_MS = 500L
  }
}

fun interface SystemMemoryGuard {
  fun canAllocateNewSlab(forwardBufferedBytes: Long): Boolean
}

class AndroidSystemMemoryGuard(
  context: Context,
  private val minGuaranteedForwardBytes: Long,
  private val minFreeSystemRamMb: Long = 512L,
  private val queryIntervalMs: Long = 500L,
) : SystemMemoryGuard {
  private val appContext = context.applicationContext ?: context

  @Volatile
  private var lastQueryElapsedMs = 0L

  @Volatile
  private var lastAllowed = true

  override fun canAllocateNewSlab(forwardBufferedBytes: Long): Boolean {
    if (forwardBufferedBytes < minGuaranteedForwardBytes) return true
    val now = System.currentTimeMillis()
    if (now - lastQueryElapsedMs < queryIntervalMs) return lastAllowed
    val status = AdaptiveBufferManager.querySystemMemoryStatus(appContext)
    val allowed = !status.isLowMemory && status.availableRamMb >= minFreeSystemRamMb
    lastQueryElapsedMs = now
    lastAllowed = allowed
    return allowed
  }
}

internal data class SessionAttachResult(
  val seekEpoch: Long,
  val totalResourceLength: Long,
  val resolvedUrl: String,
  val responseHeaders: Map<String, List<String>>,
)

internal class OffHeapStreamSession(
  val url: String,
  private val config: OffHeapStreamConfig,
  private val opener: HttpStreamOpener,
  private val slabAllocator: OffHeapSlabAllocator,
  private val memoryGuard: SystemMemoryGuard,
) {
  companion object {
    private const val TAG = "mpvZero-OffHeapSession"
    internal const val READ_NEED_SEEK = -2
  }

  private val slabSizeBytes = config.slabSizeBytes.coerceAtLeast(1024)
  private val slabCount = maxOf(1, (config.capacityBytes / slabSizeBytes).toInt())
  private val slabs = arrayOfNulls<OffHeapSlab>(slabCount)

  private val lock = ReentrantLock()
  private val dataAvailable = lock.newCondition()
  private val spaceAvailable = lock.newCondition()
  private val controlSignal = lock.newCondition()

  private var closed = false
  private var generation = 0L
  private var seekEpoch = 0L
  private var windowStart = 0L
  private var writePos = 0L
  private var readPos = 0L
  private var eos = false
  private var terminalError: IOException? = null

  private var resolvedUrl = url
  private var responseHeaders: Map<String, List<String>> = emptyMap()
  private var requestHeaders: Map<String, String> = emptyMap()
  private var totalResourceLength: Long = C.LENGTH_UNSET.toLong()
  private var supportsRange: Boolean = false

  private var consumerAttached = false
  private var hasBeenPromoted = false
  private var activelyDownloading = false

  private var pendingConnection: HttpStreamConnection? = null
  private var pendingConnectionGen = -1L

  @Volatile
  private var activeConnection: HttpStreamConnection? = null

  private var pumpRunning = false
  private var pumpBlockingOpen = false
  private var latestWorkerToken = 0L
  private var lastUsedMs = System.currentTimeMillis()

  fun lastUsedAtMs(): Long = lock.withLock { lastUsedMs }

  fun isConsumerAttached(): Boolean = lock.withLock { consumerAttached }

  fun hasPromotedBefore(): Boolean = lock.withLock { hasBeenPromoted }

  fun bufferedAheadBytes(): Long = lock.withLock {
    if (closed || !hasBeenPromoted) 0L else (writePos - readPos).coerceAtLeast(0L)
  }

  fun isActivelyDownloading(): Boolean = lock.withLock { !closed && activelyDownloading }

  fun knownTotalLength(): Long = lock.withLock { totalResourceLength }

  fun lastResolvedUrl(): String = lock.withLock { resolvedUrl }

  /**
   * Attempts to attach at [offset] purely from existing off-heap ring contents (no HTTP call).
   * Returns null if [offset] falls outside the live ring window (requiring [seekAndAttach]).
   */
  fun tryAttachBuffered(offset: Long): SessionAttachResult? {
    lock.withLock {
      if (closed || !hasBeenPromoted) return null
      lastUsedMs = System.currentTimeMillis()
      val withinWindow = offset in windowStart..writePos && (offset < writePos || eos)
      val withinNearAhead = pumpRunning &&
        !eos &&
        terminalError == null &&
        offset > writePos &&
        offset <= writePos + config.forwardCacheHitGraceBytes &&
        (totalResourceLength < 0L || offset <= totalResourceLength)

      if (!withinWindow && !withinNearAhead) return null
      readPos = minOf(offset, writePos)
      consumerAttached = true
      spaceAvailable.signalAll()
      return SessionAttachResult(
        seekEpoch = seekEpoch,
        totalResourceLength = totalResourceLength,
        resolvedUrl = resolvedUrl,
        responseHeaders = responseHeaders,
      )
    }
  }

  /**
   * Promotes an already-open warmup [connection] (whose stream cursor sits at [currentOffset])
   * into the background off-heap ring pump without opening a second HTTP connection.
   */
  fun promoteWarmupConnection(
    connection: HttpStreamConnection,
    currentOffset: Long,
    reqHeaders: Map<String, String>,
  ): SessionAttachResult? {
    return lock.withLock {
      if (closed || hasBeenPromoted) {
        return@withLock null
      }
      lastUsedMs = System.currentTimeMillis()
      hasBeenPromoted = true
      generation++
      seekEpoch++
      val targetGen = generation

      windowStart = currentOffset
      writePos = currentOffset
      readPos = currentOffset
      eos = false
      terminalError = null

      resolvedUrl = connection.resolvedUrl
      responseHeaders = connection.responseHeaders
      requestHeaders = reqHeaders.toMap()
      totalResourceLength = connection.totalResourceLength
      supportsRange = connection.supportsRange
      consumerAttached = true

      pendingConnection = connection
      pendingConnectionGen = targetGen
      ensurePumpStartedLocked()

      SessionAttachResult(
        seekEpoch = seekEpoch,
        totalResourceLength = totalResourceLength,
        resolvedUrl = resolvedUrl,
        responseHeaders = responseHeaders,
      )
    }
  }

  /**
   * Aborts any active transfer at a mismatched position, opens a fresh upstream connection
   * at [offset] (failing fast on HTTP 4xx/5xx/416), and restarts the off-heap pump reusing slabs.
   */
  @Throws(IOException::class)
  fun seekAndAttach(offset: Long, reqHeaders: Map<String, String>): SessionAttachResult {
    var staleActive: HttpStreamConnection? = null
    var stalePending: HttpStreamConnection? = null
    val targetGen: Long

    lock.withLock {
      if (closed) throw IOException("OffHeapStreamSession is released")
      lastUsedMs = System.currentTimeMillis()
      generation++
      targetGen = generation
      stalePending = pendingConnection
      pendingConnection = null
      staleActive = activeConnection
      activeConnection = null
      activelyDownloading = false
      spaceAvailable.signalAll()
      controlSignal.signalAll()
      dataAvailable.signalAll()
    }

    stalePending?.abort()
    stalePending?.close()
    staleActive?.abort()

    val newConn = opener.open(url, reqHeaders, offset)
    if (offset > 0L && newConn.rangeStart != offset) {
      newConn.abort()
      newConn.close()
      throw IOException("Server returned unexpected range start ${newConn.rangeStart} for requested offset $offset")
    }
    val attachOffset = if (newConn.rangeStart == 0L && offset == 0L) 0L else offset

    var abortedBecauseSuperseded = false
    val result = lock.withLock {
      if (closed || generation != targetGen) {
        abortedBecauseSuperseded = true
        throw IOException("Seek superseded by newer operation")
      }
      hasBeenPromoted = true
      seekEpoch++
      windowStart = attachOffset
      writePos = attachOffset
      readPos = attachOffset
      eos = false
      terminalError = null

      resolvedUrl = newConn.resolvedUrl
      responseHeaders = newConn.responseHeaders
      requestHeaders = reqHeaders.toMap()
      if (newConn.totalResourceLength >= 0L) {
        totalResourceLength = newConn.totalResourceLength
      }
      supportsRange = newConn.supportsRange
      consumerAttached = true

      pendingConnection = newConn
      pendingConnectionGen = targetGen
      ensurePumpStartedLocked()

      SessionAttachResult(
        seekEpoch = seekEpoch,
        totalResourceLength = totalResourceLength,
        resolvedUrl = resolvedUrl,
        responseHeaders = responseHeaders,
      )
    }

    if (abortedBecauseSuperseded) {
      newConn.abort()
      newConn.close()
    }
    return result
  }

  fun detachConsumer() {
    lock.withLock {
      consumerAttached = false
      lastUsedMs = System.currentTimeMillis()
    }
  }

  /**
   * Reads from off-heap slabs at [offset], waiting if the background pump is currently fetching.
   * Returns positive byte count, [C.RESULT_END_OF_INPUT] (-1), or [READ_NEED_SEEK] (-2).
   */
  @Throws(IOException::class)
  fun readAt(
    expectedEpoch: Long,
    offset: Long,
    dst: ByteArray,
    dstOffset: Int,
    length: Int,
  ): Int {
    if (length == 0) return 0
    lock.withLock {
      if (closed) throw IOException("OffHeapStreamSession is released")
      if (expectedEpoch != seekEpoch) throw IOException("Stream seek superseded active reader")
      lastUsedMs = System.currentTimeMillis()

      if (offset < windowStart) {
        return READ_NEED_SEEK
      }

      while (!closed && expectedEpoch == seekEpoch && offset >= writePos && !eos && terminalError == null) {
        if (totalResourceLength in 0L..offset) {
          return C.RESULT_END_OF_INPUT
        }
        try {
          dataAvailable.await()
        } catch (ie: InterruptedException) {
          Thread.currentThread().interrupt()
          throw InterruptedIOException("OffHeapStreamSession read interrupted")
        }
      }

      if (closed) throw IOException("OffHeapStreamSession is released")
      if (expectedEpoch != seekEpoch) throw IOException("Stream seek superseded active reader")
      if (offset < windowStart) return READ_NEED_SEEK

      if (offset >= writePos) {
        terminalError?.let { err -> throw err }
        if (eos || (totalResourceLength in 0L..offset)) {
          return C.RESULT_END_OF_INPUT
        }
        return READ_NEED_SEEK
      }

      val maxAvail = minOf(length.toLong(), writePos - offset).toInt()
      var remaining = maxAvail
      var cursor = offset
      var outPos = dstOffset

      while (remaining > 0) {
        val globalSlab = cursor / slabSizeBytes
        val slot = (globalSlab % slabCount).toInt()
        val slabOffset = (cursor % slabSizeBytes).toInt()
        val slab = slabs[slot] ?: break
        val chunk = minOf(remaining, slabSizeBytes - slabOffset)
        slab.read(slabOffset, dst, outPos, chunk)
        cursor += chunk
        outPos += chunk
        remaining -= chunk
      }

      val copied = maxAvail - remaining
      if (copied > 0) {
        readPos = maxOf(readPos, cursor)
        spaceAvailable.signalAll()
      }
      return copied
    }
  }

  fun release() {
    var stalePending: HttpStreamConnection?
    var staleActive: HttpStreamConnection?
    lock.withLock {
      if (closed) return
      closed = true
      generation++
      consumerAttached = false
      activelyDownloading = false
      stalePending = pendingConnection
      pendingConnection = null
      staleActive = activeConnection
      activeConnection = null
      for (i in slabs.indices) {
        slabs[i]?.close()
        slabs[i] = null
      }
      dataAvailable.signalAll()
      spaceAvailable.signalAll()
      controlSignal.signalAll()
    }
    stalePending?.abort()
    stalePending?.close()
    staleActive?.abort()
  }

  private fun ensurePumpStartedLocked() {
    if (!pumpRunning || pumpBlockingOpen) {
      val token = ++latestWorkerToken
      pumpRunning = true
      pumpBlockingOpen = false
      val thread = Thread(
        { runPumpWorker(token) },
        "mpvz-offheap-pump-$token",
      ).apply {
        isDaemon = true
      }
      thread.start()
    } else {
      controlSignal.signalAll()
      spaceAvailable.signalAll()
    }
  }

  private fun runPumpWorker(workerToken: Long) {
    while (true) {
      val myGen: Long
      val initialConn: HttpStreamConnection
      val streamUrl: String
      val reqHeaders: Map<String, String>
      val knownTotal: Long
      val rangeSupported: Boolean

      lock.withLock {
        if (closed || workerToken != latestWorkerToken) {
          if (workerToken == latestWorkerToken) {
            pumpRunning = false
            activelyDownloading = false
          }
          return
        }
        val nextConn = pendingConnection
        if (nextConn == null || pendingConnectionGen != generation) {
          pumpRunning = false
          activelyDownloading = false
          return
        }
        pendingConnection = null
        myGen = generation
        initialConn = nextConn
        streamUrl = resolvedUrl
        reqHeaders = requestHeaders
        knownTotal = totalResourceLength
        rangeSupported = supportsRange
        activelyDownloading = true
      }

      drainGeneration(
        myGen = myGen,
        workerToken = workerToken,
        initialConn = initialConn,
        streamUrl = streamUrl,
        reqHeaders = reqHeaders,
        initialTotalLength = knownTotal,
        rangeSupported = rangeSupported,
      )
    }
  }

  private fun drainGeneration(
    myGen: Long,
    workerToken: Long,
    initialConn: HttpStreamConnection,
    streamUrl: String,
    reqHeaders: Map<String, String>,
    initialTotalLength: Long,
    rangeSupported: Boolean,
  ) {
    var conn: HttpStreamConnection? = initialConn
    var knownTotal = initialTotalLength
    var canRange = rangeSupported
    var retryCount = 0
    var activeReconnectUrl = streamUrl
    var fellBackToOriginUrl = (streamUrl == url)
    val stagingBuf = ByteArray(128 * 1024)

    try {
      while (true) {
        lock.withLock {
          if (closed || generation != myGen || workerToken != latestWorkerToken) return
        }

        if (conn == null) {
          val reconnectOffset = lock.withLock { writePos }
          val backoffMs = config.reconnectBaseDelayMs shl retryCount.coerceAtMost(4)
          lock.withLock {
            if (closed || generation != myGen || workerToken != latestWorkerToken) return
            pumpBlockingOpen = true
            try {
              controlSignal.await(backoffMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
              return
            } finally {
              pumpBlockingOpen = false
            }
            if (closed || generation != myGen || workerToken != latestWorkerToken) return
            pumpBlockingOpen = true
          }

          val reopened = try {
            opener.open(activeReconnectUrl, reqHeaders, reconnectOffset)
          } catch (httpErr: HttpOpenException) {
            val expiredRedirect =
              !fellBackToOriginUrl && httpErr.responseCode in setOf(401, 403, 410)
            if (expiredRedirect) {
              Log.w(
                TAG,
                "Redirected stream URL expired (${httpErr.responseCode}), falling back to origin URL",
              )
              activeReconnectUrl = url
              fellBackToOriginUrl = true
              retryCount++
              continue
            }
            val isPermanent = httpErr.responseCode in 400..499 &&
              httpErr.responseCode != 408 &&
              httpErr.responseCode != 429
            if (isPermanent || retryCount >= config.maxReconnectAttempts) {
              failGeneration(myGen, httpErr)
              return
            }
            retryCount++
            continue
          } catch (ioe: IOException) {
            if (retryCount >= config.maxReconnectAttempts) {
              failGeneration(myGen, ioe)
              return
            }
            retryCount++
            continue
          } finally {
            lock.withLock {
              pumpBlockingOpen = false
            }
          }

          if (reopened.rangeStart != reconnectOffset) {
            reopened.abort()
            reopened.close()
            failGeneration(
              myGen,
              IOException("Server range mismatch on reconnect: expected $reconnectOffset, got ${reopened.rangeStart}"),
            )
            return
          }

          if (reopened.totalResourceLength >= 0L && knownTotal < 0L) {
            knownTotal = reopened.totalResourceLength
            lock.withLock {
              if (generation == myGen) totalResourceLength = knownTotal
            }
          }
          canRange = canRange || reopened.supportsRange
          conn = reopened
        }

        val currentConn = conn ?: continue
        lock.withLock {
          if (closed || generation != myGen || workerToken != latestWorkerToken) {
            currentConn.abort()
            currentConn.close()
            conn = null
            return
          }
          activeConnection = currentConn
          activelyDownloading = true
        }

        var streamError: IOException? = null
        var hitEof = false
        try {
          while (true) {
            val readCount = currentConn.read(stagingBuf, 0, stagingBuf.size)
            if (readCount < 0) {
              hitEof = true
              break
            }
            if (readCount == 0) continue
            retryCount = 0
            if (!writeToRing(myGen, stagingBuf, readCount)) {
              return
            }
          }
        } catch (ioe: IOException) {
          streamError = ioe
        } finally {
          lock.withLock {
            if (activeConnection === currentConn) {
              activeConnection = null
            }
          }
          currentConn.close()
          conn = null
        }

        val shouldExit = lock.withLock {
          closed || generation != myGen || workerToken != latestWorkerToken
        }
        if (shouldExit) return

        val currentWritePos = lock.withLock { writePos }
        if (hitEof && (knownTotal < 0L || currentWritePos >= knownTotal)) {
          lock.withLock {
            if (!closed && generation == myGen) {
              eos = true
              activelyDownloading = false
              dataAvailable.signalAll()
            }
          }
          return
        }

        val err = streamError ?: IOException(
          "Premature stream EOF at $currentWritePos / $knownTotal bytes",
        )
        if (!canRange || retryCount >= config.maxReconnectAttempts) {
          failGeneration(myGen, err)
          return
        }

        retryCount++
        Log.w(
          TAG,
          "Stream interrupted at $currentWritePos/$knownTotal bytes, " +
            "retrying ($retryCount/${config.maxReconnectAttempts})",
        )
        lock.withLock { activelyDownloading = false }
      }
    } finally {
      conn?.let { leftover ->
        leftover.abort()
        leftover.close()
      }
      lock.withLock {
        activelyDownloading = false
      }
    }
  }

  private fun failGeneration(myGen: Long, error: IOException) {
    lock.withLock {
      if (!closed && generation == myGen) {
        terminalError = error
        activelyDownloading = false
        dataAvailable.signalAll()
      }
    }
  }

  private fun writeToRing(myGen: Long, stagingBuf: ByteArray, bytesRead: Int): Boolean {
    var stagingOffset = 0
    while (stagingOffset < bytesRead) {
      var needAllocSlot = -1
      var forwardAheadForGuard = 0L

      lock.withLock {
        while (!closed && generation == myGen) {
          val keepStart = maxOf(windowStart, readPos - config.backWindowBytes)
          val firstNeededSlab = keepStart / slabSizeBytes
          val maxWritableOffset = (firstNeededSlab + slabCount) * slabSizeBytes
          val room = maxWritableOffset - writePos
          if (room > 0L) {
            val globalSlab = writePos / slabSizeBytes
            val slot = (globalSlab % slabCount).toInt()
            val existingSlab = slabs[slot]
            if (existingSlab != null) {
              val evictedWindowFloor = (globalSlab - slabCount + 1L) * slabSizeBytes
              if (evictedWindowFloor > windowStart) {
                windowStart = evictedWindowFloor
              }
              val slabOffset = (writePos % slabSizeBytes).toInt()
              val maxByRoom = minOf(room, Int.MAX_VALUE.toLong()).toInt()
              val chunk = minOf(bytesRead - stagingOffset, slabSizeBytes - slabOffset, maxByRoom)
              existingSlab.write(slabOffset, stagingBuf, stagingOffset, chunk)
              writePos += chunk
              stagingOffset += chunk
              dataAvailable.signalAll()
              break
            } else {
              needAllocSlot = slot
              forwardAheadForGuard = (writePos - readPos).coerceAtLeast(0L)
              break
            }
          }
          try {
            activelyDownloading = false
            spaceAvailable.await()
          } catch (_: InterruptedException) {
            return false
          }
        }
        if (closed || generation != myGen) return false
        activelyDownloading = true
      }

      if (needAllocSlot >= 0) {
        if (!memoryGuard.canAllocateNewSlab(forwardAheadForGuard)) {
          lock.withLock {
            if (closed || generation != myGen) return false
            activelyDownloading = false
            runCatching { spaceAvailable.await(1_000L, TimeUnit.MILLISECONDS) }
            if (closed || generation != myGen) return false
          }
          continue
        }

        val newSlab = try {
          slabAllocator.allocate(slabSizeBytes)
        } catch (t: Throwable) {
          Log.w(TAG, "Off-heap slab allocation deferred (${slabSizeBytes}B)", t)
          lock.withLock {
            if (closed || generation != myGen) return false
            activelyDownloading = false
            runCatching { spaceAvailable.await(1_500L, TimeUnit.MILLISECONDS) }
            if (closed || generation != myGen) return false
          }
          continue
        }

        lock.withLock {
          if (closed || generation != myGen || slabs[needAllocSlot] != null) {
            newSlab.close()
          } else {
            slabs[needAllocSlot] = newSlab
          }
        }
      }
    }
    return true
  }
}

private class BudgetExceededException(message: String) : IOException(message)

internal class BudgetedSlabAllocator(
  private val delegate: OffHeapSlabAllocator,
  private val maxTotalBytes: Long,
) : OffHeapSlabAllocator {
  private val allocatedBytes = AtomicLong(0L)

  fun currentAllocatedBytes(): Long = allocatedBytes.get()

  override fun allocate(capacityBytes: Int): OffHeapSlab {
    while (true) {
      val current = allocatedBytes.get()
      val next = current + capacityBytes.toLong()
      if (next > maxTotalBytes) {
        throw BudgetExceededException("Off-heap budget reached: $current / $maxTotalBytes")
      }
      if (allocatedBytes.compareAndSet(current, next)) break
    }
    return try {
      val inner = delegate.allocate(capacityBytes)
      TrackedSlab(inner, capacityBytes.toLong())
    } catch (t: Throwable) {
      allocatedBytes.addAndGet(-capacityBytes.toLong())
      throw t
    }
  }

  private inner class TrackedSlab(
    private val delegate: OffHeapSlab,
    private val trackedSize: Long,
  ) : OffHeapSlab by delegate {
    private val released = AtomicBoolean(false)

    override fun close() {
      if (released.compareAndSet(false, true)) {
        try {
          delegate.close()
        } finally {
          allocatedBytes.addAndGet(-trackedSize)
        }
      }
    }
  }
}

/**
 * Coordinates live [OffHeapStreamSession] instances across Media3 DataSource reopens,
 * enforcing a shared off-heap RAM ceiling and exposing live buffer metrics to snapshots.
 */
class OffHeapStreamSessionManager(
  internal val config: OffHeapStreamConfig,
  private val opener: HttpStreamOpener,
  slabAllocator: OffHeapSlabAllocator,
  private val memoryGuard: SystemMemoryGuard,
  private val maxConcurrentSessions: Int = 2,
) {
  private val budgetedAllocator = BudgetedSlabAllocator(slabAllocator, config.capacityBytes)
  private val sessionsLock = Any()
  private val sessions = LinkedHashMap<String, OffHeapStreamSession>()

  val capacityBytes: Long
    get() = config.capacityBytes

  val isEnabled: Boolean
    get() = config.capacityBytes > 0L

  internal fun getOrCreateSession(url: String): OffHeapStreamSession {
    synchronized(sessionsLock) {
      sessions[url]?.let { return it }
      if (sessions.size >= maxConcurrentSessions) {
        evictOldestIdleSessionLocked()
      }
      val created = OffHeapStreamSession(
        url = url,
        config = config,
        opener = opener,
        slabAllocator = budgetedAllocator,
        memoryGuard = memoryGuard,
      )
      sessions[url] = created
      return created
    }
  }

  fun totalBufferedAheadBytes(): Long {
    synchronized(sessionsLock) {
      return sessions.values.sumOf { it.bufferedAheadBytes() }
    }
  }

  fun allocatedOffHeapBytes(): Long = budgetedAllocator.currentAllocatedBytes()

  fun isActivelyDownloading(): Boolean {
    synchronized(sessionsLock) {
      return sessions.values.any { it.isActivelyDownloading() }
    }
  }

  fun releaseAll() {
    val snapshot = synchronized(sessionsLock) {
      val list = sessions.values.toList()
      sessions.clear()
      list
    }
    for (session in snapshot) {
      session.release()
    }
  }

  private fun evictOldestIdleSessionLocked() {
    val candidateEntry = sessions.entries
      .filter { !it.value.isConsumerAttached() }
      .minByOrNull { it.value.lastUsedAtMs() }
      ?: sessions.entries.minByOrNull { it.value.lastUsedAtMs() }
      ?: return
    sessions.remove(candidateEntry.key)
    candidateEntry.value.release()
  }
}
