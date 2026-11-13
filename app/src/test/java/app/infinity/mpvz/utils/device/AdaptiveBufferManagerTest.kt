/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.device

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBufferManagerTest {

  @Test
  fun mpvBufferFollowsPhysicalRamTiers() {
    assertEquals(128, AdaptiveBufferManager.computeMpvBufferMb(totalRamMb = 3072, isLowRam = false))
    assertEquals(128, AdaptiveBufferManager.computeMpvBufferMb(totalRamMb = 12288, isLowRam = true))
    assertEquals(256, AdaptiveBufferManager.computeMpvBufferMb(totalRamMb = 4096, isLowRam = false))
    assertEquals(384, AdaptiveBufferManager.computeMpvBufferMb(totalRamMb = 6144, isLowRam = false))
    assertEquals(512, AdaptiveBufferManager.computeMpvBufferMb(totalRamMb = 8192, isLowRam = false))
    assertEquals(1024, AdaptiveBufferManager.computeMpvBufferMb(totalRamMb = 12288, isLowRam = false))
  }

  @Test
  fun media3DalvikQueueRemainsLeanAcrossAllHeapSizes() {
    assertEquals(32, AdaptiveBufferManager.computeMedia3DalvikMb(maxHeapMb = 192, isLowRam = false))
    assertEquals(32, AdaptiveBufferManager.computeMedia3DalvikMb(maxHeapMb = 512, isLowRam = true))
    assertEquals(48, AdaptiveBufferManager.computeMedia3DalvikMb(maxHeapMb = 256, isLowRam = false))
    assertEquals(48, AdaptiveBufferManager.computeMedia3DalvikMb(maxHeapMb = 384, isLowRam = false))
    assertEquals(64, AdaptiveBufferManager.computeMedia3DalvikMb(maxHeapMb = 512, isLowRam = false))
  }

  @Test
  fun media3OffHeapStreamBufferScalesWithPhysicalRam() {
    assertEquals(0, AdaptiveBufferManager.computeMedia3StreamMb(totalRamMb = 3072, isLowRam = false))
    assertEquals(0, AdaptiveBufferManager.computeMedia3StreamMb(totalRamMb = 12288, isLowRam = true))
    assertEquals(128, AdaptiveBufferManager.computeMedia3StreamMb(totalRamMb = 4096, isLowRam = false))
    assertEquals(256, AdaptiveBufferManager.computeMedia3StreamMb(totalRamMb = 6144, isLowRam = false))
    assertEquals(384, AdaptiveBufferManager.computeMedia3StreamMb(totalRamMb = 8192, isLowRam = false))
    assertEquals(576, AdaptiveBufferManager.computeMedia3StreamMb(totalRamMb = 12288, isLowRam = false))
  }

  @Test
  fun flagshipCombinedBudgetGives640MbWhileKeepingDalvikAt64Mb() {
    val dalvikMb = AdaptiveBufferManager.computeMedia3DalvikMb(maxHeapMb = 512, isLowRam = false)
    val offHeapMb = AdaptiveBufferManager.computeMedia3StreamMb(totalRamMb = 12288, isLowRam = false)
    assertEquals(64, dalvikMb)
    assertEquals(576, offHeapMb)
    assertEquals(640, dalvikMb + offHeapMb)
  }

  @Test
  fun mpvReadaheadAndBackBytesScaleProportionally() {
    assertEquals(180, AdaptiveBufferManager.getMpvReadaheadSecs(1024))
    assertEquals(120, AdaptiveBufferManager.getMpvReadaheadSecs(512))
    assertEquals(90, AdaptiveBufferManager.getMpvReadaheadSecs(384))
    assertEquals(60, AdaptiveBufferManager.getMpvReadaheadSecs(256))
    assertEquals(30, AdaptiveBufferManager.getMpvReadaheadSecs(128))
    assertEquals(256, AdaptiveBufferManager.getMpvBackBytesMb(1024))
    assertEquals(16, AdaptiveBufferManager.getMpvBackBytesMb(32))
  }
}
