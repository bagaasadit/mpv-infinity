/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.device

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Automatically computes optimal RAM buffer limits for MPV and Media3 based on device hardware specs.
 * High-RAM flagships get massive buffers, while lower-spec devices get conservative, safe limits.
 */
object AdaptiveBufferManager {
  private const val TAG = "mpvZero-AdaptiveBuffer"
  private const val BYTES_PER_MB = 1024L * 1024L

  internal data class DeviceMemorySpecs(
    val totalRamMb: Int,
    val maxHeapMb: Int,
    val isLowRam: Boolean,
  )

  data class SystemMemoryStatus(
    val availableRamMb: Long,
    val isLowMemory: Boolean,
  )

  @Volatile
  private var cachedSpecs: DeviceMemorySpecs? = null

  internal fun getOrResolveSpecs(context: Context): DeviceMemorySpecs {
    cachedSpecs?.let { return it }
    synchronized(this) {
      cachedSpecs?.let { return it }
      val appCtx = context.applicationContext ?: context
      val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val memInfo = ActivityManager.MemoryInfo()
      am?.getMemoryInfo(memInfo)
      val totalRamMb = (memInfo.totalMem / BYTES_PER_MB).toInt().coerceAtLeast(0)
      val maxHeapMb = getDalvikMaxHeapMb()
      val isLowRam = am?.isLowRamDevice == true

      val resolved = DeviceMemorySpecs(
        totalRamMb = totalRamMb,
        maxHeapMb = maxHeapMb,
        isLowRam = isLowRam,
      )
      cachedSpecs = resolved

      val mpvMb = computeMpvBufferMb(totalRamMb, isLowRam)
      val dalvikMb = computeMedia3DalvikMb(maxHeapMb, isLowRam)
      val streamMb = computeMedia3StreamMb(totalRamMb, isLowRam)
      Log.i(
        TAG,
        "Resolved buffers: totalRAM=${totalRamMb}MB, maxHeap=${maxHeapMb}MB, isLowRam=$isLowRam -> " +
          "MPV=${mpvMb}MB, Media3(Dalvik=${dalvikMb}MB + OffHeapStream=${streamMb}MB)",
      )
      return resolved
    }
  }

  /**
   * Returns total physical system RAM in megabytes.
   */
  fun getTotalRamMb(context: Context): Int = getOrResolveSpecs(context).totalRamMb

  /**
   * Returns maximum Dalvik Java heap size in megabytes.
   */
  fun getDalvikMaxHeapMb(): Int {
    return (Runtime.getRuntime().maxMemory() / BYTES_PER_MB).toInt()
  }

  /**
   * Queries dynamic available system RAM and Android low-memory signal for runtime guard checks.
   */
  fun querySystemMemoryStatus(context: Context): SystemMemoryStatus {
    val appCtx = context.applicationContext ?: context
    val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am == null) {
      return SystemMemoryStatus(availableRamMb = Long.MAX_VALUE, isLowMemory = false)
    }
    val memInfo = ActivityManager.MemoryInfo()
    am.getMemoryInfo(memInfo)
    return SystemMemoryStatus(
      availableRamMb = (memInfo.availMem / BYTES_PER_MB).coerceAtLeast(0L),
      isLowMemory = memInfo.lowMemory,
    )
  }

  /**
   * Computes native RAM buffer in MB for MPV based on device physical RAM.
   * MPV allocates via 64-bit native malloc in Linux user space, unconstrained by Dalvik heap.
   */
  fun getMpvBufferMb(context: Context): Int {
    val specs = getOrResolveSpecs(context)
    return computeMpvBufferMb(specs.totalRamMb, specs.isLowRam)
  }

  internal fun computeMpvBufferMb(totalRamMb: Int, isLowRam: Boolean): Int {
    return when {
      isLowRam || totalRamMb < 3500 -> 128
      totalRamMb < 5000 -> 256
      totalRamMb < 7000 -> 384
      totalRamMb < 10500 -> 512
      else -> 1024
    }
  }

  /**
   * Computes readahead duration in seconds for MPV demuxer based on buffer size.
   */
  fun getMpvReadaheadSecs(bufferMb: Int): Int {
    return when {
      bufferMb >= 1024 -> 180
      bufferMb >= 512 -> 120
      bufferMb >= 384 -> 90
      bufferMb >= 256 -> 60
      else -> 30
    }
  }

  /**
   * Computes demuxer max back bytes in MB for MPV (approx 25% of buffer).
   */
  fun getMpvBackBytesMb(bufferMb: Int): Int {
    return (bufferMb / 4).coerceIn(16, 256)
  }

  /**
   * Computes lean Media3 Dalvik sample-queue buffer target in MB.
   * Heavy HTTP byte caching is offloaded off-heap via [getMedia3StreamBufferMb], keeping Dalvik
   * sample queues lean (32-64MB) to eliminate OutOfMemoryError and GC pauses on 512MB heaps.
   */
  fun getMedia3BufferMb(context: Context): Int {
    val specs = getOrResolveSpecs(context)
    return computeMedia3DalvikMb(specs.maxHeapMb, specs.isLowRam)
  }

  internal fun computeMedia3DalvikMb(maxHeapMb: Int, isLowRam: Boolean): Int {
    return when {
      isLowRam || maxHeapMb <= 192 -> 32
      maxHeapMb <= 256 -> 48
      maxHeapMb <= 384 -> 48
      else -> 64
    }
  }

  /**
   * Computes off-heap native stream ring buffer size in MB for Media3 HTTP/Remux streams.
   * Stored in Android SharedMemory outside Dalvik heap, allocated lazily in 8MB slabs and freed on stop.
   */
  fun getMedia3StreamBufferMb(context: Context): Int {
    val specs = getOrResolveSpecs(context)
    return computeMedia3StreamMb(specs.totalRamMb, specs.isLowRam)
  }

  internal fun computeMedia3StreamMb(totalRamMb: Int, isLowRam: Boolean): Int {
    return when {
      isLowRam || totalRamMb < 3500 -> 0
      totalRamMb < 5000 -> 128
      totalRamMb < 7000 -> 256
      totalRamMb < 10500 -> 384
      else -> 576
    }
  }

  /**
   * Combined effective Media3 RAM buffer (Dalvik sample queue + off-heap stream ring) in MB.
   */
  fun getTotalMedia3BufferMb(context: Context): Int {
    return getMedia3BufferMb(context) + getMedia3StreamBufferMb(context)
  }
}
