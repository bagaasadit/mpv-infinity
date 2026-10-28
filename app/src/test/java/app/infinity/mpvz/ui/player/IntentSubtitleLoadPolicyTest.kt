/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSubtitleLoadPolicyTest {

  @Test
  fun emptyInputsReturnEmptyList() {
    val result = IntentSubtitleLoadPolicy.entriesToLoad(
      subtitles = emptyList<String>(),
      enabledSubtitles = emptyList<String>(),
      hasEnabledSubtitleExtra = false,
    )
    assertTrue(result.isEmpty())
  }

  @Test
  fun withoutEnabledExtraAllSubtitlesLoadedUnselected() {
    val subs = listOf("https://subs.org/eng.srt", "https://subs.org/ind.srt")
    val result = IntentSubtitleLoadPolicy.entriesToLoad(
      subtitles = subs,
      enabledSubtitles = emptyList(),
      hasEnabledSubtitleExtra = false,
    )

    assertEquals(2, result.size)
    assertEquals("https://subs.org/eng.srt", result[0].value)
    assertEquals(0, result[0].metadataIndex)
    assertFalse(result[0].select)

    assertEquals("https://subs.org/ind.srt", result[1].value)
    assertEquals(1, result[1].metadataIndex)
    assertFalse(result[1].select)
  }

  @Test
  fun withEnabledExtraMatchingSubtitleIsSelected() {
    val subs = listOf(
      "https://subs.org/spa.srt",
      "https://subs.org/eng.srt",
      "https://subs.org/fre.srt",
    )
    val enabled = listOf("https://subs.org/eng.srt")
    val result = IntentSubtitleLoadPolicy.entriesToLoad(
      subtitles = subs,
      enabledSubtitles = enabled,
      hasEnabledSubtitleExtra = true,
    )

    assertEquals(3, result.size)
    assertFalse(result[0].select)
    assertTrue(result[1].select) // eng.srt is selected
    assertEquals(1, result[1].metadataIndex)
    assertFalse(result[2].select)
  }

  @Test
  fun enabledSubtitleNotInSubtitlesListIsAppendedWithIndexMinusOne() {
    val subs = listOf("https://subs.org/eng.srt")
    val enabled = listOf("https://subs.org/external_forced.srt")
    val result = IntentSubtitleLoadPolicy.entriesToLoad(
      subtitles = subs,
      enabledSubtitles = enabled,
      hasEnabledSubtitleExtra = true,
    )

    assertEquals(2, result.size)
    assertEquals("https://subs.org/eng.srt", result[0].value)
    assertFalse(result[0].select)

    assertEquals("https://subs.org/external_forced.srt", result[1].value)
    assertTrue(result[1].select)
    assertEquals(-1, result[1].metadataIndex)
  }

  @Test
  fun duplicatesInSubtitlesListAreDeduplicated() {
    val subs = listOf(
      "https://subs.org/eng.srt",
      "https://subs.org/eng.srt",
      "https://subs.org/ind.srt",
    )
    val result = IntentSubtitleLoadPolicy.entriesToLoad(
      subtitles = subs,
      enabledSubtitles = emptyList(),
      hasEnabledSubtitleExtra = false,
    )

    assertEquals(2, result.size)
    assertEquals("https://subs.org/eng.srt", result[0].value)
    assertEquals(0, result[0].metadataIndex)
    assertEquals("https://subs.org/ind.srt", result[1].value)
    assertEquals(2, result[1].metadataIndex)
  }

  @Test
  fun multipleEnabledSubtitlesAreBothMarkedSelected() {
    val subs = listOf(
      "https://subs.org/eng.srt",
      "https://subs.org/ind.srt",
      "https://subs.org/jpn.srt",
    )
    val enabled = listOf("https://subs.org/eng.srt", "https://subs.org/jpn.srt")
    val result = IntentSubtitleLoadPolicy.entriesToLoad(
      subtitles = subs,
      enabledSubtitles = enabled,
      hasEnabledSubtitleExtra = true,
    )

    assertEquals(3, result.size)
    assertTrue(result[0].select)
    assertFalse(result[1].select)
    assertTrue(result[2].select)
  }
}
