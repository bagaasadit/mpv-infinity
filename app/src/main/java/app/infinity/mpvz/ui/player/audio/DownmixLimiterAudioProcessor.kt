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
import kotlin.math.tanh

/**
 * Audiophile Multichannel Downmixer, Binaural Crossfeed & Peak Limiter for Media3 / ExoPlayer.
 * Engineered for In-Ear Monitors (IEM), Audiophile DACs, and Mobile Transducers.
 *
 * Capabilities:
 * 1. Accumulate downmixed channels in 32-bit Float domain without intermediate truncation.
 * 2. LFE (.1 subwoofer) channel mixing (+0.5f / -6 dB) restored for cinematic visceral bass on IEMs (ITU-R BS.775).
 * 3. Dynamic Bauer Stereophonic-to-Binaural (BS2B) IIR Crossfeed to eliminate in-head localization fatigue on IEMs.
 * 4. Center-channel dialogue clarity weighting multiplier.
 * 5. High-headroom transparent peak limiter (-0.1 dBFS / 0.9885f ceiling) with soft-knee saturation.
 * 6. True 32-bit Float PCM output (C.ENCODING_PCM_FLOAT) support for bit-perfect external DACs and Android AudioFlinger.
 * 7. Zero-allocation loop: pre-allocated arrays eliminate GC pressure on realtime audio thread.
 * 8. Universal input support: 16-bit, 24-bit, 32-bit Integer PCM and 32-bit Float PCM.
 */
@OptIn(UnstableApi::class)
class DownmixLimiterAudioProcessor : BaseAudioProcessor() {

  companion object {
    private const val TARGET_CEILING_PCM16 = 0.89125f // -1.0 dBFS
    private const val TARGET_CEILING_FLOAT = 0.9885f // -0.1 dBFS
    private const val RELEASE_TIME_SEC = 0.050f // 50 ms release

    // BS2B Filter Constants (Default 700Hz, -3.7dB feed)
    private const val BS2B_FC = 700.0
    private const val BS2B_FEED_GAIN = 0.65f
  }

  // Configuration Switches
  var enableLfeMixing: Boolean = true
  var enableBinauralCrossfeed: Boolean = true
  var dialogueBoostGain: Float = 1.0f
  var outputFloatingPoint: Boolean = false

  private var currentGain = 1.0f
  private var releaseCoeff = 0.9995f

  // Pre-allocated array to eliminate GC allocation churn on the realtime audio thread
  private var channelSamples = FloatArray(16)

  // BS2B IIR Filter State Variables
  private var crossfeedHistL = 0.0f
  private var crossfeedHistR = 0.0f
  private var bs2bAlpha = 0.084f // Filter coefficient at 48kHz

  public override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
        inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
        inputAudioFormat.encoding != C.ENCODING_PCM_32BIT &&
        inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
    ) {
      throw UnhandledAudioFormatException(inputAudioFormat)
    }

    // Only process when downmixing multichannel audio (> 2 channels)
    if (inputAudioFormat.channelCount <= 2) {
      return AudioFormat.NOT_SET
    }

    if (channelSamples.size < inputAudioFormat.channelCount) {
      channelSamples = FloatArray(inputAudioFormat.channelCount)
    }

    // Compute dynamic BS2B filter coefficients based on actual input sample rate
    val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000).toDouble()
    val dt = 1.0 / sampleRate
    val rc = 1.0 / (2.0 * Math.PI * BS2B_FC)
    bs2bAlpha = (dt / (rc + dt)).toFloat()

    releaseCoeff = exp(-1.0f / (sampleRate.toFloat() * RELEASE_TIME_SEC))

    val outputEncoding = if (outputFloatingPoint) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

    return AudioFormat(
      inputAudioFormat.sampleRate,
      2,
      outputEncoding,
    )
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining()) return

    val channelCount = inputAudioFormat.channelCount
    val encoding = inputAudioFormat.encoding
    val bytesPerSample = when (encoding) {
      C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
      C.ENCODING_PCM_24BIT -> 3
      else -> 2 // 16-bit
    }
    val frameSize = channelCount * bytesPerSample

    val remainingBytes = inputBuffer.remaining()
    val frameCount = remainingBytes / frameSize
    if (frameCount == 0) {
      inputBuffer.position(inputBuffer.limit())
      return
    }

    val isOutputFloat = outputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
    val bytesPerOutputSample = if (isOutputFloat) 4 else 2
    val outputBytes = frameCount * 2 * bytesPerOutputSample
    val outputBuffer = replaceOutputBuffer(outputBytes)
    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

    val lfeGain = if (enableLfeMixing) 0.5f else 0.0f
    val centerWeight = 0.7071f * dialogueBoostGain

    for (f in 0 until frameCount) {
      // 1. Read input channel samples into float [-1.0f, 1.0f]
      when (encoding) {
        C.ENCODING_PCM_FLOAT -> {
          for (c in 0 until channelCount) {
            channelSamples[c] = inputBuffer.getFloat()
          }
        }
        C.ENCODING_PCM_16BIT -> {
          for (c in 0 until channelCount) {
            channelSamples[c] = inputBuffer.getShort() / 32768.0f
          }
        }
        C.ENCODING_PCM_24BIT -> {
          for (c in 0 until channelCount) {
            val b0 = inputBuffer.get().toInt() and 0xFF
            val b1 = inputBuffer.get().toInt() and 0xFF
            val b2 = inputBuffer.get().toInt() // sign-extended high byte
            val sample = (b2 shl 16) or (b1 shl 8) or b0
            channelSamples[c] = sample / 8388608.0f
          }
        }
        C.ENCODING_PCM_32BIT -> {
          for (c in 0 until channelCount) {
            channelSamples[c] = inputBuffer.getInt() / 2147483648.0f
          }
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
          val lfe = channelSamples[3]
          val sl = channelSamples[4]
          val sr = channelSamples[5]

          leftSum = fl + centerWeight * fc + 0.7071f * sl + lfeGain * lfe
          rightSum = fr + centerWeight * fc + 0.7071f * sr + lfeGain * lfe
        }

        // 7.1 (FL, FR, FC, LFE, BL, BR, SL, SR) - TrueHD & DTS-HD MA
        8 -> {
          val fl = channelSamples[0]
          val fr = channelSamples[1]
          val fc = channelSamples[2]
          val lfe = channelSamples[3]
          val bl = channelSamples[4]
          val br = channelSamples[5]
          val sl = channelSamples[6]
          val sr = channelSamples[7]

          leftSum = fl + centerWeight * fc + 0.5f * bl + 0.7071f * sl + lfeGain * lfe
          rightSum = fr + centerWeight * fc + 0.5f * br + 0.7071f * sr + lfeGain * lfe
        }

        // 6.1 (FL, FR, FC, LFE, BC, SL, SR)
        7 -> {
          val fl = channelSamples[0]
          val fr = channelSamples[1]
          val fc = channelSamples[2]
          val lfe = channelSamples[3]
          val bc = channelSamples[4]
          val sl = channelSamples[5]
          val sr = channelSamples[6]

          leftSum = fl + centerWeight * fc + 0.5f * bc + 0.7071f * sl + lfeGain * lfe
          rightSum = fr + centerWeight * fc + 0.5f * bc + 0.7071f * sr + lfeGain * lfe
        }

        // 5.0 (FL, FR, FC, SL, SR - no LFE)
        5 -> {
          val fl = channelSamples[0]
          val fr = channelSamples[1]
          val fc = channelSamples[2]
          val sl = channelSamples[3]
          val sr = channelSamples[4]

          leftSum = fl + centerWeight * fc + 0.7071f * sl
          rightSum = fr + centerWeight * fc + 0.7071f * sr
        }

        // 4.0 Quadraphonic (FL, FR, BL, BR)
        4 -> {
          val fl = channelSamples[0]
          val fr = channelSamples[1]
          val bl = channelSamples[2]
          val br = channelSamples[3]

          leftSum = fl + 0.7071f * bl
          rightSum = fr + 0.7071f * br
        }

        // Fallback for other multichannel formats (3.0, etc.)
        else -> {
          val fl = channelSamples[0]
          val fr = channelSamples.getOrElse(1) { 0f }
          val fc = channelSamples.getOrElse(2) { 0f }
          leftSum = fl + centerWeight * fc
          rightSum = fr + centerWeight * fc
          for (c in 3 until channelCount) {
            val s = channelSamples[c] * 0.5f
            if (c % 2 == 1) leftSum += s else rightSum += s
          }
        }
      }

      // 3. Bauer Stereophonic-to-Binaural (BS2B) Crossfeed for IEM / Headphone Soundstage
      if (enableBinauralCrossfeed) {
        crossfeedHistL += bs2bAlpha * (leftSum - crossfeedHistL)
        crossfeedHistR += bs2bAlpha * (rightSum - crossfeedHistR)

        val binauralL = 0.85f * leftSum + BS2B_FEED_GAIN * crossfeedHistR
        val binauralR = 0.85f * rightSum + BS2B_FEED_GAIN * crossfeedHistL

        leftSum = binauralL
        rightSum = binauralR
      }

      // 4. Peak Limiter / Gain Computer
      if (leftSum.isNaN() || leftSum.isInfinite()) leftSum = 0f
      if (rightSum.isNaN() || rightSum.isInfinite()) rightSum = 0f

      val targetCeiling = if (isOutputFloat) TARGET_CEILING_FLOAT else TARGET_CEILING_PCM16
      val sampleMax = max(abs(leftSum), abs(rightSum))
      if (sampleMax > targetCeiling) {
        val targetGain = targetCeiling / sampleMax
        // Instant attack: catch transients immediately
        if (targetGain < currentGain) {
          currentGain = targetGain
        } else {
          currentGain = currentGain * releaseCoeff + targetGain * (1.0f - releaseCoeff)
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

      // 5. True continuous soft-knee tanh saturation
      outL = softClipTanh(outL)
      outR = softClipTanh(outR)

      // 6. Quantize / Output Serialization
      if (isOutputFloat) {
        outputBuffer.putFloat(outL)
        outputBuffer.putFloat(outR)
      } else {
        val pcmL = (outL * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
        val pcmR = (outR * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()

        outputBuffer.putShort(pcmL)
        outputBuffer.putShort(pcmR)
      }
    }

    outputBuffer.flip()
  }

  override fun onFlush() {
    currentGain = 1.0f
    crossfeedHistL = 0.0f
    crossfeedHistR = 0.0f
  }

  override fun onReset() {
    currentGain = 1.0f
    crossfeedHistL = 0.0f
    crossfeedHistR = 0.0f
  }

  /**
   * Continuous Soft-Knee Tanh Saturation:
   * Linear for |x| <= 0.85f (-1.4 dBFS).
   * Smoothly and asymptotically approaches ~0.995 without flat-corner clipping.
   */
  private fun softClipTanh(x: Float): Float {
    val absX = abs(x)
    if (absX <= 0.85f) return x

    val sign = if (x > 0.0f) 1.0f else -1.0f
    val excess = (absX - 0.85f) / 0.15f
    val compressed = 0.85f + 0.145f * tanh(excess)
    return sign * compressed
  }
}
