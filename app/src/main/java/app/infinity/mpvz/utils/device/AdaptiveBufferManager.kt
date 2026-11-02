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

  /**
   * Returns total physical system RAM in megabytes.
   */
  fun getTotalRamMb(context: Context): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am?.getMemoryInfo(memInfo)
    return (memInfo.totalMem / (1024L * 1024L)).toInt()
  }

  /**
   * Returns maximum Dalvik Java heap size in megabytes.
   */
  fun getDalvikMaxHeapMb(): Int {
    return (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
  }

  /**
   * Computes native RAM buffer in MB for MPV based on device physical RAM.
   * MPV allocates via 64-bit native malloc in Linux user space, unconstrained by Dalvik heap.
   *
   * Tiers:
   * - >= 10.5 GB RAM (e.g. 12GB/16GB flagships): 1024 MB (1 GB)
   * - >= 7.0 GB RAM (e.g. 8GB devices): 512 MB
   * - >= 5.0 GB RAM (e.g. 6GB devices): 384 MB
   * - >= 3.5 GB RAM (e.g. 4GB devices): 256 MB
   * - Low RAM / < 3.5 GB: 128 MB
   */
  fun getMpvBufferMb(context: Context): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am?.getMemoryInfo(memInfo)
    val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).toInt()
    val isLowRam = am?.isLowRamDevice == true || memInfo.lowMemory

    val calculated = when {
      isLowRam || totalRamMb < 3500 -> 128
      totalRamMb < 5000 -> 256
      totalRamMb < 7000 -> 384
      totalRamMb < 10500 -> 512
      else -> 1024
    }
    Log.d(TAG, "Resolved MPV buffer: ${calculated}MB (device RAM: ${totalRamMb}MB, isLowRam=$isLowRam)")
    return calculated
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
   * Computes safe Media3 (ExoPlayer) buffer target in MB.
   * Media3 allocates byte[] segments inside the Dalvik Java heap (512MB growth limit on modern Android).
   * We reserve at least 128MB for UI, Compose, Bitmaps, Decoders, and GC headroom.
   *
   * Hard upper bound is strictly 256MB to eliminate any risk of Dalvik OutOfMemoryError.
   */
  fun getMedia3BufferMb(context: Context): Int {
    val maxHeapMb = getDalvikMaxHeapMb()
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val isLowRam = am?.isLowRamDevice == true

    val calculated = when {
      isLowRam || maxHeapMb <= 192 -> 96
      maxHeapMb <= 256 -> 128
      maxHeapMb <= 384 -> 192
      else -> 256
    }
    Log.d(TAG, "Resolved Media3 buffer: ${calculated}MB (maxHeap: ${maxHeapMb}MB, isLowRam=$isLowRam)")
    return calculated
  }
}
