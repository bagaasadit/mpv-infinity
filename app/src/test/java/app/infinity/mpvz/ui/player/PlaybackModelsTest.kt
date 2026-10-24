/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackModelsTest {
  @Test
  fun nativePreferencePassesThroughForExtensionlessStreamVideo() {
    val item =
      PlaybackItem(
        stableId = "stream-item",
        originalUri = "opaque-stream-id",
        title = "Episode 1",
      )

    assertEquals(DeclaredPlaybackMediaKind.UNKNOWN, item.declaredMediaKind())
    assertEquals(DeclaredPlaybackMediaKind.VIDEO, item.declaredMediaKind(videoHint = true))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, item))
  }

  @Test
  fun knownAudioMetadataOutranksStreamVideoHint() {
    val item =
      PlaybackItem(
        stableId = "stream-audio",
        originalUri = "opaque-audio-id",
        title = "Audio item",
        mimeType = "audio/mp4",
      )

    assertEquals(DeclaredPlaybackMediaKind.AUDIO, item.declaredMediaKind(videoHint = true))
  }

  @Test
  fun autoStillUsesMpvForSdrAndNativeForHdr() {
    val sdrItem =
      PlaybackItem(
        stableId = "stream-sdr",
        originalUri = "opaque-sdr-id",
        title = "Episode 1",
        mimeType = "video/mp4",
      )
    val hdrItem = sdrItem.copy(hdrMetadata = true)

    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, sdrItem))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, hdrItem))
  }

  @Test
  fun streamMimeTypeExtraClassifiesExtensionlessVideo() {
    val mimeType = resolvePlaybackMimeType(intentMimeType = null, mimeTypeExtra = "video/mp4")
    val item =
      PlaybackItem(
        stableId = "stream-item",
        originalUri = "opaque-stream-id",
        title = "Episode 1",
        mimeType = mimeType,
      )

    assertEquals(DeclaredPlaybackMediaKind.VIDEO, item.declaredMediaKind())
  }

  @Test
  fun intentMimeTypeRemainsAuthoritativeWhenPresent() {
    assertEquals("video/webm", resolvePlaybackMimeType("video/webm", "video/mp4"))
  }

  @Test
  fun blankMimeTypesAreIgnored() {
    assertEquals("video/mp4", resolvePlaybackMimeType("  ", "video/mp4"))
    assertNull(resolvePlaybackMimeType(null, " "))
  }

  @Test
  fun dolbyVisionAndHdrTokensResolveToNativeInAutoMode() {
    val dvScene =
      PlaybackItem(
        stableId = "dv-item",
        originalUri = "https://debrid.stream/d78a9c8f",
        title = "Movie.2024.2160p.UHD.BluRay.x265.10bit.TrueHD.7.1.Atmos.DV.mkv",
        mimeType = "video/x-matroska",
      )
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, dvScene))

    val doviScene =
      PlaybackItem(
        stableId = "dovi-item",
        originalUri = "https://debrid.stream/d78a9c8f",
        title = "Show.S02E05.DoVi.2160p.mkv",
        mimeType = "video/x-matroska",
      )
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, doviScene))

    val hdr10Plus =
      PlaybackItem(
        stableId = "hdr10-item",
        originalUri = "https://debrid.stream/d78a9c8f",
        title = "Film.2023.HDR10+.mkv",
        mimeType = "video/x-matroska",
      )
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, hdr10Plus))
  }

  @Test
  fun falsePositiveWordsDoNotTriggerNativeRoutingInAutoMode() {
    val adventure =
      PlaybackItem(
        stableId = "adv-item",
        originalUri = "https://debrid.stream/d78a9c8f",
        title = "Adventure.Time.S01E01.1080p.mkv",
        mimeType = "video/x-matroska",
      )
    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, adventure))

    val dvdRip =
      PlaybackItem(
        stableId = "dvd-item",
        originalUri = "https://debrid.stream/d78a9c8f",
        title = "Classic.Show.DVDRip.x264.mkv",
        mimeType = "video/x-matroska",
      )
    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, dvdRip))
  }
}
