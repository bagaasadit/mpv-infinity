/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVolumeKeyPolicyTest {
  @Test
  fun knownAudioUsesSystemUiBeforeAudioOnlyClassificationCatchesUp() {
    assertTrue(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = false,
        isKnownAudio = true,
        isAudioLaunch = false,
      ),
    )
  }

  @Test
  fun audioLaunchMarkerUsesSystemUiBeforeAudioOnlyClassificationCatchesUp() {
    assertTrue(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = false,
        isKnownAudio = false,
        isAudioLaunch = true,
      ),
    )
  }

  @Test
  fun audioOnlyClassificationStillUsesSystemUi() {
    assertTrue(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = true,
        isKnownAudio = false,
        isAudioLaunch = false,
      ),
    )
  }

  @Test
  fun unrecognizedMediaKeepsTheExistingInAppVolumeSliderPath() {
    assertFalse(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = false,
        isKnownAudio = false,
        isAudioLaunch = false,
      ),
    )
  }
}
