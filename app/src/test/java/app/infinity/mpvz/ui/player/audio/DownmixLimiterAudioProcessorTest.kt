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
  fun stereoPassesThroughUnchanged() {
    val stereoFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
    val configured = processor.configure(stereoFormat)
    assertEquals(AudioFormat.NOT_SET, configured)
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
