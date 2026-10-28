/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlyPlayedVisibilityTest {
  @Test
  fun disabledGlobalToggleHidesStoredItemsWithoutMutatingThem() {
    val storedItems = listOf("first", "second")

    assertEquals(emptyList<String>(), visibleRecentlyPlayedItems(enabled = false, items = storedItems))
    assertEquals(storedItems, visibleRecentlyPlayedItems(enabled = true, items = storedItems))
    assertEquals(listOf("first", "second"), storedItems)
  }

  @Test
  fun statisticsRequireBothOptInPreferences() {
    assertFalse(shouldTrackWatchStatistics(recentlyPlayedEnabled = false, watchStatisticsEnabled = true))
    assertFalse(shouldTrackWatchStatistics(recentlyPlayedEnabled = true, watchStatisticsEnabled = false))
    assertTrue(shouldTrackWatchStatistics(recentlyPlayedEnabled = true, watchStatisticsEnabled = true))
  }
}
