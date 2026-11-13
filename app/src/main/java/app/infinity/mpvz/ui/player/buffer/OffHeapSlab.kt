/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.buffer

import android.os.Build
import android.os.SharedMemory
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-capacity off-heap memory block used as a ring segment by [OffHeapStreamSession].
 * Callers must ensure external synchronization between [read]/[write] and [close].
 */
interface OffHeapSlab : Closeable {
  val capacityBytes: Int
  val isClosed: Boolean

  fun write(slabOffset: Int, src: ByteArray, srcOffset: Int, length: Int)

  fun read(slabOffset: Int, dst: ByteArray, dstOffset: Int, length: Int)
}

fun interface OffHeapSlabAllocator {
  fun allocate(capacityBytes: Int): OffHeapSlab
}

internal class DirectByteBufferSlab(
  override val capacityBytes: Int,
) : OffHeapSlab {
  private var buffer: ByteBuffer? = ByteBuffer.allocateDirect(capacityBytes)

  @Volatile
  override var isClosed: Boolean = false
    private set

  override fun write(slabOffset: Int, src: ByteArray, srcOffset: Int, length: Int) {
    if (length == 0) return
    val target = checkNotNull(buffer?.takeIf { !isClosed }) { "DirectByteBufferSlab is closed" }
    require(slabOffset >= 0 && slabOffset + length <= capacityBytes) {
      "Write out of bounds: offset=$slabOffset len=$length cap=$capacityBytes"
    }
    val view = target.duplicate()
    view.position(slabOffset)
    view.put(src, srcOffset, length)
  }

  override fun read(slabOffset: Int, dst: ByteArray, dstOffset: Int, length: Int) {
    if (length == 0) return
    val source = checkNotNull(buffer?.takeIf { !isClosed }) { "DirectByteBufferSlab is closed" }
    require(slabOffset >= 0 && slabOffset + length <= capacityBytes) {
      "Read out of bounds: offset=$slabOffset len=$length cap=$capacityBytes"
    }
    val view = source.duplicate()
    view.position(slabOffset)
    view.get(dst, dstOffset, length)
  }

  override fun close() {
    isClosed = true
    buffer = null
  }
}

@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class SharedMemorySlab private constructor(
  private val sharedMemory: SharedMemory,
  private val mapping: ByteBuffer,
  override val capacityBytes: Int,
) : OffHeapSlab {

  @Volatile
  override var isClosed: Boolean = false
    private set

  override fun write(slabOffset: Int, src: ByteArray, srcOffset: Int, length: Int) {
    if (length == 0) return
    check(!isClosed) { "SharedMemorySlab is closed" }
    require(slabOffset >= 0 && slabOffset + length <= capacityBytes) {
      "Write out of bounds: offset=$slabOffset len=$length cap=$capacityBytes"
    }
    val view = mapping.duplicate()
    view.position(slabOffset)
    view.put(src, srcOffset, length)
  }

  override fun read(slabOffset: Int, dst: ByteArray, dstOffset: Int, length: Int) {
    if (length == 0) return
    check(!isClosed) { "SharedMemorySlab is closed" }
    require(slabOffset >= 0 && slabOffset + length <= capacityBytes) {
      "Read out of bounds: offset=$slabOffset len=$length cap=$capacityBytes"
    }
    val view = mapping.duplicate()
    view.position(slabOffset)
    view.get(dst, dstOffset, length)
  }

  override fun close() {
    if (isClosed) return
    isClosed = true
    runCatching { SharedMemory.unmap(mapping) }
    runCatching { sharedMemory.close() }
  }

  companion object {
    fun create(name: String, capacityBytes: Int): SharedMemorySlab {
      val shm = SharedMemory.create(name, capacityBytes)
      return try {
        val mapped = shm.mapReadWrite()
        SharedMemorySlab(shm, mapped, capacityBytes)
      } catch (t: Throwable) {
        runCatching { shm.close() }
        throw t
      }
    }
  }
}

/**
 * Allocates off-heap slabs backed by Android [SharedMemory] (memfd/ashmem, deterministically
 * unmapped & closed on release) on API 27+, falling back cleanly to direct native ByteBuffers
 * on API 26, JVM unit tests, or if an OEM blocks ashmem creation.
 */
object AndroidOffHeapSlabAllocator : OffHeapSlabAllocator {
  private const val TAG = "mpvZero-OffHeapSlab"
  private val slabSeq = AtomicInteger(0)

  override fun allocate(capacityBytes: Int): OffHeapSlab {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      val name = "mpvz_buf_${slabSeq.incrementAndGet()}"
      try {
        return SharedMemorySlab.create(name, capacityBytes)
      } catch (t: Throwable) {
        Log.w(TAG, "SharedMemory allocation failed ($capacityBytes bytes), falling back to direct buffer", t)
      }
    }
    return DirectByteBufferSlab(capacityBytes)
  }
}
