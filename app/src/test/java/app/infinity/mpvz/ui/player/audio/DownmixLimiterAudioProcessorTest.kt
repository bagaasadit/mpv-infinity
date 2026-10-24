/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DownmixLimiterAudioProcessorTest {

  private val processor = DownmixLimiterAudioProcessor()

  @Test
  fun stereoPassesThroughWhenCrossfeedIsDefaultDisabled() {
    // Crossfeed and LFE default to disabled
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    assertEquals(AudioFormat.NOT_SET, processor.configure(stereoFormat))
  }

  @Test
  fun stereoIsProcessedWhenCrossfeedIsEnabled() {
    val enabledProcessor = DownmixLimiterAudioProcessor().apply {
      enableStereoCrossfeed = true
      enableBinauralCrossfeed = true
    }
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    val configured = enabledProcessor.configure(stereoFormat)
    assertEquals(48000, configured.sampleRate)
    assertEquals(2, configured.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)
  }

  @Test
  fun stereoCrossfeedBleedsChannelsAcrossEars() {
    val crossfeedProcessor = DownmixLimiterAudioProcessor().apply {
      enableStereoCrossfeed = true
      enableBinauralCrossfeed = true
    }
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    crossfeedProcessor.configure(stereoFormat)
    crossfeedProcessor.flush()

    // Left channel at half amplitude, right channel silent. After crossfeed the right ear must
    // receive some signal instead of staying at exact digital silence.
    val frameCount = 4800
    val inputBytes = ByteBuffer.allocate(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) {
      inputBytes.putShort(16384.toShort())
      inputBytes.putShort(0)
    }
    inputBytes.flip()

    crossfeedProcessor.queueInput(inputBytes)
    val output = crossfeedProcessor.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var maxRight = 0
    while (output.hasRemaining()) {
      output.get() // left
      val right = kotlin.math.abs(output.get().toInt())
      if (right > maxRight) maxRight = right
    }
    assertTrue("Crossfeed must bleed left into right, got maxRight=$maxRight", maxRight > 0)
  }

  @Test
  fun stereoLimiterPreventsClipping() {
    val crossfeedLimiterProcessor = DownmixLimiterAudioProcessor().apply {
      enableStereoCrossfeed = true
    }
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    crossfeedLimiterProcessor.configure(stereoFormat)
    crossfeedLimiterProcessor.flush()

    val frameCount = 480
    val inputBytes = ByteBuffer.allocate(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) {
      inputBytes.putShort(32767.toShort())
      inputBytes.putShort(32767.toShort())
    }
    inputBytes.flip()

    crossfeedLimiterProcessor.queueInput(inputBytes)
    val output = crossfeedLimiterProcessor.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var maxObserved = 0
    var lastObserved = 0
    while (output.hasRemaining()) {
      val sample = output.get().toInt()
      if (sample > maxObserved) maxObserved = sample
      lastObserved = sample
    }
    assertTrue("Stereo output must stay inside 16-bit range, got $maxObserved", maxObserved <= 32767)
    assertTrue("Limiter must constrain settled stereo amplitude to ceiling, got $lastObserved", lastObserved <= 27600)
  }

  @Test
  fun monoPassesThroughUnchanged() {
    val monoFormat = AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)
    val configured = processor.configure(monoFormat)
    assertEquals(AudioFormat.NOT_SET, configured)
  }

  @Test
  fun sixChannelConfiguresToStereo16Bit() {
    val format51 = AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
    val configured = processor.configure(format51)
    assertEquals(48000, configured.sampleRate)
    assertEquals(2, configured.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)
  }

  @Test
  fun eightChannelConfiguresToStereo16Bit() {
    val format71 = AudioFormat(48000, 8, C.ENCODING_PCM_16BIT)
    val configured = processor.configure(format71)
    assertEquals(48000, configured.sampleRate)
    assertEquals(2, configured.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)
  }

  @Test
  fun twentyFourBitAndThirtyTwoBitPcmConfiguresToStereo16Bit() {
    val format24 = AudioFormat(48000, 6, C.ENCODING_PCM_24BIT)
    val configured24 = processor.configure(format24)
    assertEquals(48000, configured24.sampleRate)
    assertEquals(2, configured24.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, configured24.encoding)

    val format32 = AudioFormat(96000, 8, C.ENCODING_PCM_32BIT)
    val configured32 = processor.configure(format32)
    assertEquals(96000, configured32.sampleRate)
    assertEquals(2, configured32.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, configured32.encoding)
  }

  @Test
  fun stereoTwentyFourBitWithCrossfeedConvertsToSafeSixteenBit() {
    val crossfeedProcessor = DownmixLimiterAudioProcessor().apply {
      enableStereoCrossfeed = true
    }
    val stereo24 = AudioFormat(48000, 2, C.ENCODING_PCM_24BIT)
    val configured = crossfeedProcessor.configure(stereo24)
    assertEquals(48000, configured.sampleRate)
    assertEquals(2, configured.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)

    crossfeedProcessor.flush()
    val frameCount = 100
    // 2 channels * 3 bytes = 6 bytes per frame
    val inputBytes = ByteBuffer.allocate(frameCount * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) {
      // Max positive 24-bit PCM: 0x7FFFFF (8388607)
      inputBytes.put(0xFF.toByte())
      inputBytes.put(0xFF.toByte())
      inputBytes.put(0x7F.toByte())
      // Right channel
      inputBytes.put(0xFF.toByte())
      inputBytes.put(0xFF.toByte())
      inputBytes.put(0x7F.toByte())
    }
    inputBytes.flip()

    crossfeedProcessor.queueInput(inputBytes)
    val output = crossfeedProcessor.output.order(ByteOrder.LITTLE_ENDIAN)
    assertTrue("Output buffer must have data", output.hasRemaining())
    assertEquals(frameCount * 2 * 2, output.remaining())

    val outputShorts = output.asShortBuffer()
    var maxObserved = 0
    while (outputShorts.hasRemaining()) {
      val sample = outputShorts.get().toInt()
      if (sample > maxObserved) maxObserved = sample
    }
    assertTrue("16-bit output sample must not exceed 32767, got $maxObserved", maxObserved <= 32767)
  }

  @Test
  fun multichannelConfiguresToStereoFloatWhenRequested() {
    val floatProcessor = DownmixLimiterAudioProcessor().apply {
      outputFloatingPoint = true
    }
    val format51 = AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)
    val configured = floatProcessor.configure(format51)
    assertEquals(48000, configured.sampleRate)
    assertEquals(2, configured.channelCount)
    assertEquals(C.ENCODING_PCM_FLOAT, configured.encoding)
  }

  @Test
  fun partialFrameBoundaryDoesNotHang() {
    val format51 = AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
    processor.configure(format51)
    processor.flush()

    // 1 frame = 6 channels * 2 bytes = 12 bytes. Send only 7 bytes (partial frame)
    val partialBytes = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
    processor.queueInput(partialBytes)

    // Position must have advanced to limit so caller is not trapped in busy-loop
    assertFalse("Partial input buffer must be consumed to avoid infinite busy-loop", partialBytes.hasRemaining())
  }

  @Test
  fun limitingPreventsClippingOnExtremeMultichannelSum() {
    val format51 = AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
    processor.configure(format51)
    processor.flush()

    // 500 frames where ALL 6 channels are at max positive amplitude (+32767)
    // Unconstrained sum would be: 32767 + 0.7071*32767 + 0.7071*32767 = 2.414 * 32767 = ~79,106
    // Which without limiter would wrap around in 16-bit integer and cause loud static/crackle.
    val frameCount = 500
    val inputBytes = ByteBuffer.allocate(frameCount * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (f in 0 until frameCount) {
      for (c in 0 until 6) {
        inputBytes.putShort(32767.toShort())
      }
    }
    inputBytes.flip()

    processor.queueInput(inputBytes)
    val output = processor.output
    assertTrue("Output buffer must have data", output.hasRemaining())

    val outputShorts = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var maxObserved = 0
    var minObserved = 0
    var lastObserved = 0
    while (outputShorts.hasRemaining()) {
      val sample = outputShorts.get().toInt()
      if (sample > maxObserved) maxObserved = sample
      if (sample < minObserved) minObserved = sample
      lastObserved = sample
    }

    // Must be bounded within 16-bit limits without clipping or wrapping
    assertTrue("Max sample ($maxObserved) must be <= 32767", maxObserved <= 32767)
    assertTrue("Min sample ($minObserved) must be >= -32768", minObserved >= -32768)
    // Settled sustained amplitude must be reduced to ceiling (~-1.5 dBFS = ~27570)
    assertTrue("Settled output ($lastObserved) must be <= 27600", lastObserved <= 27600)
  }

  @Test
  fun limitingPreventsCeilingOvershootAndOverflowOnExtreme71BassSignal() {
    val processor71 = DownmixLimiterAudioProcessor()
    val format71 = AudioFormat(48000, 8, C.ENCODING_PCM_16BIT)
    processor71.configure(format71)
    processor71.flush()

    // 1000 frames of coherent maximum positive bass across all 8 channels (FL, FR, FC, LFE, BL, BR, SL, SR)
    val frameCount = 1000
    val inputBytes = ByteBuffer.allocate(frameCount * 8 * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (f in 0 until frameCount) {
      for (c in 0 until 8) {
        inputBytes.putShort(32767.toShort())
      }
    }
    inputBytes.flip()

    processor71.queueInput(inputBytes)
    val output = processor71.output
    assertTrue("Output buffer must have data", output.hasRemaining())

    val outputShorts = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var maxObserved = 0
    var minObserved = 0
    var lastObserved = 0
    while (outputShorts.hasRemaining()) {
      val sample = outputShorts.get().toInt()
      if (sample > maxObserved) maxObserved = sample
      if (sample < minObserved) minObserved = sample
      lastObserved = sample
    }

    // Zero overshoot over -1.5 dBFS ceiling (27571) plus dither margin
    assertTrue("Max 7.1 sample ($maxObserved) must not overshoot ceiling 27580", maxObserved <= 27580)
    assertTrue("Min 7.1 sample ($minObserved) must not undershoot ceiling -27580", minObserved >= -27580)
    assertTrue("Settled 7.1 output ($lastObserved) must be <= 27600", lastObserved <= 27600)
  }

  @Test
  fun limitingPreventsCeilingOvershootAndOverflowOnExtreme51BassSignal() {
    val processor51 = DownmixLimiterAudioProcessor()
    val format51 = AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
    processor51.configure(format51)
    processor51.flush()

    // 1000 frames of coherent maximum positive bass across all 6 channels
    val frameCount = 1000
    val inputBytes = ByteBuffer.allocate(frameCount * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (f in 0 until frameCount) {
      for (c in 0 until 6) {
        inputBytes.putShort(32767.toShort())
      }
    }
    inputBytes.flip()

    processor51.queueInput(inputBytes)
    val output = processor51.output
    assertTrue("Output buffer must have data", output.hasRemaining())

    val outputShorts = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var maxObserved = 0
    var minObserved = 0
    var lastObserved = 0
    while (outputShorts.hasRemaining()) {
      val sample = outputShorts.get().toInt()
      if (sample > maxObserved) maxObserved = sample
      if (sample < minObserved) minObserved = sample
      lastObserved = sample
    }

    // Zero overshoot over -1.5 dBFS ceiling (27571) plus dither margin
    assertTrue("Max 5.1 sample ($maxObserved) must not overshoot ceiling 27580", maxObserved <= 27580)
    assertTrue("Min 5.1 sample ($minObserved) must not undershoot ceiling -27580", minObserved >= -27580)
    assertTrue("Settled 5.1 output ($lastObserved) must be <= 27600", lastObserved <= 27600)
  }

  @Test
  fun dialogueBoostScalesCenterChannelGain() {
    val format51 = AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)

    val baselineProcessor = DownmixLimiterAudioProcessor().apply {
      dialogueBoostGain = 1.0f
    }
    baselineProcessor.configure(format51)
    baselineProcessor.flush()

    // 150 frames to pass through the 120-frame lookahead delay line
    val frameCount = 150
    val inputBaseline = ByteBuffer.allocate(frameCount * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) {
      inputBaseline.putShort(0)
      inputBaseline.putShort(0)
      inputBaseline.putShort(10000.toShort())
      inputBaseline.putShort(0)
      inputBaseline.putShort(0)
      inputBaseline.putShort(0)
    }
    inputBaseline.flip()

    baselineProcessor.queueInput(inputBaseline)
    val baseOutput = baselineProcessor.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var baseSampleL = 0
    while (baseOutput.hasRemaining()) {
      val sample = baseOutput.get().toInt()
      baseOutput.get() // right
      if (sample > baseSampleL) baseSampleL = sample
    }

    val lightProcessor = DownmixLimiterAudioProcessor().apply {
      dialogueBoostGain = 1.41f
    }
    lightProcessor.configure(format51)
    lightProcessor.flush()

    val inputLight = ByteBuffer.allocate(frameCount * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) {
      inputLight.putShort(0)
      inputLight.putShort(0)
      inputLight.putShort(10000.toShort())
      inputLight.putShort(0)
      inputLight.putShort(0)
      inputLight.putShort(0)
    }
    inputLight.flip()

    lightProcessor.queueInput(inputLight)
    val lightOutput = lightProcessor.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var lightSampleL = 0
    while (lightOutput.hasRemaining()) {
      val sample = lightOutput.get().toInt()
      lightOutput.get() // right
      if (sample > lightSampleL) lightSampleL = sample
    }

    assertTrue("Light dialogue boost sample ($lightSampleL) must be greater than baseline ($baseSampleL)", lightSampleL > baseSampleL)
    val ratio = lightSampleL.toFloat() / baseSampleL.toFloat()
    assertEquals("Ratio must be approximately 1.41", 1.41f, ratio, 0.05f)
  }
}
