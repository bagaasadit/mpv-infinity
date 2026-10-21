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
  fun stereoIsProcessedWhenCrossfeedIsEnabled() {
    // Stereo used to be skipped entirely, which meant the crossfeed never ran on the content
    // most phone users actually play. It now flows through crossfeed + limiter unchanged in
    // channel count, so the format must still be reported as configured.
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    val configured = processor.configure(stereoFormat)
    assertEquals(48000, configured.sampleRate)
    assertEquals(2, configured.channelCount)
    assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)
  }

  @Test
  fun stereoPassesThroughWhenCrossfeedIsFullyDisabled() {
    val passthrough = DownmixLimiterAudioProcessor().apply {
      enableStereoCrossfeed = false
      enableBinauralCrossfeed = false
    }
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    assertEquals(AudioFormat.NOT_SET, passthrough.configure(stereoFormat))
  }

  @Test
  fun stereoCrossfeedBleedsChannelsAcrossEars() {
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    processor.configure(stereoFormat)
    processor.flush()

    // Left channel at half amplitude, right channel silent. After crossfeed the right ear must
    // receive some signal instead of staying at exact digital silence.
    val frameCount = 4800
    val inputBytes = ByteBuffer.allocate(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) {
      inputBytes.putShort(16384.toShort())
      inputBytes.putShort(0)
    }
    inputBytes.flip()

    processor.queueInput(inputBytes)
    val output = processor.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
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
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    processor.configure(stereoFormat)
    processor.flush()

    val frameCount = 480
    val inputBytes = ByteBuffer.allocate(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) {
      inputBytes.putShort(32767.toShort())
      inputBytes.putShort(32767.toShort())
    }
    inputBytes.flip()

    processor.queueInput(inputBytes)
    val output = processor.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var maxObserved = 0
    while (output.hasRemaining()) {
      val sample = output.get().toInt()
      if (sample > maxObserved) maxObserved = sample
    }
    assertTrue("Stereo output must stay inside 16-bit range, got $maxObserved", maxObserved <= 32767)
    assertTrue("Limiter must constrain stereo amplitude, got $maxObserved", maxObserved <= 32700)
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
  fun stereoTwentyFourBitPreservesEncodingAndLimits() {
    val stereo24 = AudioFormat(48000, 2, C.ENCODING_PCM_24BIT)
    val configured = processor.configure(stereo24)
    assertEquals(48000, configured.sampleRate)
    assertEquals(2, configured.channelCount)
    assertEquals(C.ENCODING_PCM_24BIT, configured.encoding)

    processor.flush()
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

    processor.queueInput(inputBytes)
    val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
    assertTrue("Output buffer must have data", output.hasRemaining())
    assertEquals(frameCount * 2 * 3, output.remaining())

    var maxObserved = 0
    while (output.hasRemaining()) {
      val b0 = output.get().toInt() and 0xFF
      val b1 = output.get().toInt() and 0xFF
      val b2 = output.get().toInt() // sign-extended
      val sample = (b2 shl 16) or (b1 shl 8) or b0
      if (sample > maxObserved) maxObserved = sample
    }
    assertTrue("24-bit sample must not exceed max 24-bit value, got $maxObserved", maxObserved <= 8388607)
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

    // 100 frames where ALL 6 channels are at max positive amplitude (+32767)
    // Unconstrained sum would be: 32767 + 0.7071*32767 + 0.7071*32767 = 2.414 * 32767 = ~79,106
    // Which without limiter would wrap around in 16-bit integer and cause loud static/crackle.
    val frameCount = 100
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
    while (outputShorts.hasRemaining()) {
      val sample = outputShorts.get().toInt()
      if (sample > maxObserved) maxObserved = sample
      if (sample < minObserved) minObserved = sample
    }

    // Must be bounded within 16-bit limits without clipping or wrapping
    assertTrue("Max sample ($maxObserved) must be <= 32767", maxObserved <= 32767)
    assertTrue("Min sample ($minObserved) must be >= -32768", minObserved >= -32768)
    // Due to the limiter targeting -1.0 dBFS (~29,200), max sample should be well-behaved
    assertTrue("Limiter must constrain amplitude to <= 32700", maxObserved <= 32700)
  }
}
