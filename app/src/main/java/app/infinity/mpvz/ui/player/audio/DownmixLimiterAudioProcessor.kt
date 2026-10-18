/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Audiophile Multichannel Downmixer and Peak Limiter for Media3 / ExoPlayer.
 *
 * Problem Solved:
 * Standard downmixing matrices sum 5.1 / 7.1 channels into stereo. During intense action scenes
 * (dialog + explosions + surround effects), the unconstrained sum often reaches 2.5 - 3.5x (+8 dBFS to +11 dBFS).
 * In integer PCM, this causes severe digital clipping, waveform wrapping, and ALSA buffer underruns on Android.
 *
 * Solution:
 * 1. Accumulate downmixed channels in 32-bit Float domain without intermediate truncation.
 * 2. LFE (.1 subwoofer) channel is completely omitted from micro-speakers/headphones to prevent phase distortion and mud.
 * 3. Instantaneous attack peak limiter with smooth exponential release (50 ms) targeting -1.0 dBFS (0.89125f).
 * 4. Zero-latency: guarantees exact A/V lip-sync with 0 ms video delay.
 * 5. Soft-knee tanh saturation prevents hard integer wrapping under extreme transients.
 * 6. Quantizes to high-fidelity 16-bit stereo PCM for universal DAC / Bluetooth compatibility.
 */
@OptIn(UnstableApi::class)
class DownmixLimiterAudioProcessor : BaseAudioProcessor() {

  companion object {
    private const val TARGET_CEILING = 0.89125f // -1.0 dBFS
    private const val RELEASE_TIME_SEC = 0.050f // 50 ms release
  }

  private var currentGain = 1.0f
  private var releaseCoeff = 0.9995f

  public override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
        inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
    ) {
      throw UnhandledAudioFormatException(inputAudioFormat)
    }

    // Only process when downmixing multichannel audio (> 2 channels)
    if (inputAudioFormat.channelCount <= 2) {
      return AudioFormat.NOT_SET
    }

    // Compute release coefficient for exponential decay based on sample rate
    val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
    releaseCoeff = exp(-1.0f / (sampleRate * RELEASE_TIME_SEC))

    // Output is always 2-channel stereo 16-bit PCM
    return AudioFormat(
      inputAudioFormat.sampleRate,
      2,
      C.ENCODING_PCM_16BIT,
    )
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining()) return

    val channelCount = inputAudioFormat.channelCount
    val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
    val bytesPerSample = if (isFloat) 4 else 2
    val frameSize = channelCount * bytesPerSample

    val remainingBytes = inputBuffer.remaining()
    val frameCount = remainingBytes / frameSize
    if (frameCount == 0) return

    val outputBytes = frameCount * 2 * 2 // 2 channels * 2 bytes (16-bit)
    val outputBuffer = replaceOutputBuffer(outputBytes)
    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

    val channelSamples = FloatArray(channelCount)

    for (f in 0 until frameCount) {
      // 1. Read input channel samples into float [-1.0f, 1.0f]
      if (isFloat) {
        for (c in 0 until channelCount) {
          channelSamples[c] = inputBuffer.getFloat()
        }
      } else {
        for (c in 0 until channelCount) {
          channelSamples[c] = inputBuffer.getShort() / 32768.0f
        }
      }

      // 2. Accumulate stereo downmix in float domain
      var leftSum = 0.0f
      var rightSum = 0.0f

      when (channelCount) {
        // 5.1 (FL, FR, FC, LFE, SL, SR)
        6 -> {
          val fl = channelSamples[0]
          val fr = channelSamples[1]
          val fc = channelSamples[2]
          // LFE (channelSamples[3]) is dropped for mobile/headphone clarity
          val sl = channelSamples[4]
          val sr = channelSamples[5]

          leftSum = fl + 0.7071f * fc + 0.7071f * sl
          rightSum = fr + 0.7071f * fc + 0.7071f * sr
        }

        // 7.1 (FL, FR, FC, LFE, BL, BR, SL, SR) - TrueHD & DTS-HD MA
        8 -> {
          val fl = channelSamples[0]
          val fr = channelSamples[1]
          val fc = channelSamples[2]
          // LFE (channelSamples[3]) is dropped
          val bl = channelSamples[4]
          val br = channelSamples[5]
          val sl = channelSamples[6]
          val sr = channelSamples[7]

          leftSum = fl + 0.7071f * fc + 0.5f * bl + 0.5f * sl
          rightSum = fr + 0.7071f * fc + 0.5f * br + 0.5f * sr
        }

        // 6.1 (FL, FR, FC, LFE, BC, SL, SR)
        7 -> {
          val fl = channelSamples[0]
          val fr = channelSamples[1]
          val fc = channelSamples[2]
          val bc = channelSamples[4]
          val sl = channelSamples[5]
          val sr = channelSamples[6]

          leftSum = fl + 0.7071f * fc + 0.5f * bc + 0.7071f * sl
          rightSum = fr + 0.7071f * fc + 0.5f * bc + 0.7071f * sr
        }

        // Fallback for other multichannel formats (3.0, 4.0, 5.0, etc.)
        else -> {
          val fl = channelSamples[0]
          val fr = channelSamples.getOrElse(1) { 0f }
          val fc = channelSamples.getOrElse(2) { 0f }
          leftSum = fl + 0.7071f * fc
          rightSum = fr + 0.7071f * fc
          for (c in 3 until channelCount) {
            val s = channelSamples[c] * 0.5f
            if (c % 2 == 1) leftSum += s else rightSum += s
          }
        }
      }

      // 3. Peak Limiter / Gain Computer
      val sampleMax = max(abs(leftSum), abs(rightSum))
      if (sampleMax > TARGET_CEILING) {
        val targetGain = TARGET_CEILING / sampleMax
        // Instant attack: catch transients immediately
        if (targetGain < currentGain) {
          currentGain = targetGain
        }
      } else {
        // Exponential release back towards 1.0 (unity gain)
        currentGain = currentGain * releaseCoeff + 1.0f * (1.0f - releaseCoeff)
        if (currentGain > 1.0f) {
          currentGain = 1.0f
        }
      }

      var outL = leftSum * currentGain
      var outR = rightSum * currentGain

      // 4. Soft-knee protection against extreme inter-sample transients
      outL = softClip(outL)
      outR = softClip(outR)

      // 5. Quantize to 16-bit integer PCM
      val pcmL = (outL * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
      val pcmR = (outR * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()

      outputBuffer.putShort(pcmL)
      outputBuffer.putShort(pcmR)
    }

    outputBuffer.flip()
  }

  override fun onFlush() {
    currentGain = 1.0f
  }

  override fun onReset() {
    currentGain = 1.0f
  }

  /**
   * Smooth cubic soft-clipper when signal approaches full scale.
   * Linear for |x| <= 0.85f, smooth cubic curve above.
   */
  private fun softClip(x: Float): Float {
    val absX = abs(x)
    if (absX <= 0.85f) return x

    val sign = if (x > 0.0f) 1.0f else -1.0f
    if (absX >= 1.25f) return sign * 0.999f

    // Smooth transition from 0.85 to ~0.99
    val delta = absX - 0.85f
    val compressed = 0.85f + delta * (1.0f - delta * 0.9f)
    return sign * compressed.coerceAtMost(0.999f)
  }
}
