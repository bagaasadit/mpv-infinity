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
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMimeTypeTest {
  @Test
  fun intentMimeTypeTakesPrecedence() {
    assertEquals("video/x-mpegurl", resolvePlaybackMimeType("video/x-mpegurl", "audio/mpeg"))
  }

  @Test
  fun metadataMimeTypeIsUsedWhenIntentTypeIsMissingOrBlank() {
    assertEquals("audio/mpeg", resolvePlaybackMimeType(null, "audio/mpeg"))
    assertEquals("audio/mpeg", resolvePlaybackMimeType("  ", " audio/mpeg "))
  }

  @Test
  fun blankMimeTypesResolveToNull() {
    assertNull(resolvePlaybackMimeType(" ", null))
    assertNull(resolvePlaybackMimeType(null, " "))
  }
}
