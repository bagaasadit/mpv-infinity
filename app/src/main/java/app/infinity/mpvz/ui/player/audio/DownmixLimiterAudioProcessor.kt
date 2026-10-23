/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.audio

import android.util.Log
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
 * Audiophile Multichannel Downmixer, Headroom Attenuator, Binaural Crossfeed & Dithered Quantizer.
 * Engineered for In-Ear Monitors (IEM), Audiophile DACs, and Mobile Transducers.
 *
 * Capabilities:
 * 1. Accumulate downmixed channels in 32-bit Float domain with calibrated headroom to prevent clipping.
 * 2. LFE (.1 subwoofer) channel mixing (default off, toggleable for cinematic visceral bass on IEMs).
 * 3. Dynamic Bauer Stereophonic-to-Binaural (BS2B) IIR Crossfeed (default off, toggleable for IEM soundstage).
 * 4. Center-channel dialogue clarity weighting multiplier.
 * 5. High-headroom transparent linear peak limiter (-0.1 dBFS / 0.9885f ceiling) WITHOUT non-linear tanh saturation.
 * 6. High-precision TPDF (Triangular Probability Density Function) dither when quantizing to 16-bit PCM.
 * 7. Zero-allocation loop: pre-allocated arrays eliminate GC pressure on realtime audio thread.
 * 8. Universal input support: 16-bit, 24-bit, 32-bit Integer PCM and 32-bit Float PCM.
 */
@OptIn(UnstableApi::class)
class DownmixLimiterAudioProcessor : BaseAudioProcessor() {

  companion object {
    private const val TAG = "DownmixLimiter"
    private const val TARGET_CEILING_PCM16 = 0.8414f // -1.5 dBFS safe ceiling for mobile DSP / Xiaomi Atmos EQ
    private const val TARGET_CEILING_FLOAT = 0.8414f // -1.5 dBFS
    private const val ATTACK_TIME_SEC = 0.0025f // 2.5 ms smooth lookahead attack
    private const val RELEASE_TIME_SEC = 0.050f // 50 ms release
    private const val LOOKAHEAD_TIME_SEC = 0.0025f // 2.5 ms lookahead delay window

    // BS2B Filter Constants (Default 700Hz, -3.7dB feed)
    private const val BS2B_FC = 700.0
    private const val BS2B_FEED_GAIN = 0.65f
  }

  // Configuration Switches (Default OFF per audiophile & stability requirements)
  var enableLfeMixing: Boolean = false
  var enableBinauralCrossfeed: Boolean = false
  var enableStereoCrossfeed: Boolean = false

  var dialogueBoostGain: Float = 1.0f
  var outputFloatingPoint: Boolean = false

  private var currentGain = 1.0f
  private var attackCoeff = 0.9917f
  private var releaseCoeff = 0.9995f

  // Ring buffer for lookahead delay line
  private var lookaheadFrames = 120
  private var delayBufferL = FloatArray(120)
  private var delayBufferR = FloatArray(120)
  private var delayIndex = 0

  // Pre-allocated array to eliminate GC allocation churn on the realtime audio thread
  private var channelSamples = FloatArray(16)

  // BS2B IIR Filter State Variables
  private var crossfeedHistL = 0.0f
  private var crossfeedHistR = 0.0f
  private var bs2bAlpha = 0.084f // Filter coefficient at 48kHz

  // Fast PRNG state for zero-allocation TPDF dithering
  private var ditherState: Int = 0x1234567

  public override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
        inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
        inputAudioFormat.encoding != C.ENCODING_PCM_32BIT &&
        inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
    ) {
      throw UnhandledAudioFormatException(inputAudioFormat)
    }

    // Mono has no stereo image to crossfeed, so it stays on the passthrough path.
    // Stereo and above are processed: multichannel is downmixed, stereo goes straight to
    // crossfeed + limiter without any channel reduction.
    if (inputAudioFormat.channelCount < 2) {
      return AudioFormat.NOT_SET
    }

    // Stereo only needs the crossfeed/limiter stage. When that is disabled there is nothing
    // left to do for a 2-channel source, so keep the passthrough path untouched.
    if (inputAudioFormat.channelCount == 2 && !enableStereoCrossfeed && !enableBinauralCrossfeed) {
      return AudioFormat.NOT_SET
    }

    if (channelSamples.size < inputAudioFormat.channelCount) {
      channelSamples = FloatArray(inputAudioFormat.channelCount)
    }

    // Compute dynamic BS2B filter coefficients based on actual input sample rate
    val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000).toDouble()
    val sampleRateF = sampleRate.toFloat()
    val dt = 1.0 / sampleRate
    val rc = 1.0 / (2.0 * Math.PI * BS2B_FC)
    bs2bAlpha = (dt / (rc + dt)).toFloat()

    attackCoeff = exp(-1.0f / (sampleRateF * ATTACK_TIME_SEC))
    releaseCoeff = exp(-1.0f / (sampleRateF * RELEASE_TIME_SEC))

    lookaheadFrames = (sampleRateF * LOOKAHEAD_TIME_SEC).roundToInt().coerceAtLeast(1)
    if (delayBufferL.size < lookaheadFrames) {
      delayBufferL = FloatArray(lookaheadFrames)
      delayBufferR = FloatArray(lookaheadFrames)
    } else {
      delayBufferL.fill(0.0f, 0, lookaheadFrames)
      delayBufferR.fill(0.0f, 0, lookaheadFrames)
    }
    delayIndex = 0

    val outputEncoding = if (outputFloatingPoint) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

    // For safety on Qualcomm AudioTrack HAL, output integer 16-bit unless explicitly requested.
    val targetEncoding =
      if (inputAudioFormat.channelCount == 2 && !outputFloatingPoint && inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
        C.ENCODING_PCM_16BIT
      } else {
        outputEncoding
      }

    val outputFormat = AudioFormat(
      inputAudioFormat.sampleRate,
      2,
      targetEncoding,
    )

    runCatching {
      Log.i(
        TAG,
        "AudioTrack sink format configured: input=${inputAudioFormat.channelCount}ch " +
          "${encodingToString(inputAudioFormat.encoding)} @ ${inputAudioFormat.sampleRate}Hz -> " +
          "output=${outputFormat.channelCount}ch ${encodingToString(outputFormat.encoding)} @ ${outputFormat.sampleRate}Hz",
      )
    }

    return outputFormat
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

    // Serialization must mirror the encoding reported by onConfigure. Stereo keeps the sink's
    // native encoding (16-bit, 24-bit, 32-bit int, or float), so all four cases are handled here
    // rather than assuming the multichannel 16-bit/float pair.
    val outputEncoding = outputAudioFormat.encoding
    val isOutputFloat = outputEncoding == C.ENCODING_PCM_FLOAT
    val outBytesPerSample =
      when (outputEncoding) {
        C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_24BIT -> 3
        else -> 2
      }
    val outputBytes = frameCount * 2 * outBytesPerSample
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
        // 2.0 Stereo: no downmix needed, hand the channels straight to crossfeed + limiter.
        2 -> {
          leftSum = channelSamples[0]
          rightSum = channelSamples[1]
        }

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

      // 2b. Apply calibrated headroom attenuation for multichannel summing to prevent clipping
      val headroomScale =
        when (channelCount) {
          8, 7 -> 0.45f // ~-6.9 dB headroom for 7.1 / 6.1 master audio (loosened by ~2.2 dB)
          6, 5 -> 0.52f // ~-5.7 dB headroom for 5.1 / 5.0 surround (loosened by ~1.9 dB)
          4 -> 0.65f // ~-3.7 dB headroom for 4.0 quad (loosened by ~1.2 dB)
          else -> 1.0f // 2.0 Stereo keeps unity gain
        }
      leftSum *= headroomScale
      rightSum *= headroomScale

      // 3. Bauer Stereophonic-to-Binaural (BS2B) Crossfeed for IEM / Headphone Soundstage
      if (enableBinauralCrossfeed && (channelCount > 2 || enableStereoCrossfeed)) {
        crossfeedHistL += bs2bAlpha * (leftSum - crossfeedHistL)
        crossfeedHistR += bs2bAlpha * (rightSum - crossfeedHistR)

        val binauralL = 0.85f * leftSum + BS2B_FEED_GAIN * crossfeedHistR
        val binauralR = 0.85f * rightSum + BS2B_FEED_GAIN * crossfeedHistL

        leftSum = binauralL
        rightSum = binauralR
      }

      // 4. Smooth Lookahead Peak Limiter with Soft Knee (Transparent, zero overshoot)
      if (leftSum.isNaN() || leftSum.isInfinite()) leftSum = 0f
      if (rightSum.isNaN() || rightSum.isInfinite()) rightSum = 0f

      val targetCeiling = if (isOutputFloat) TARGET_CEILING_FLOAT else TARGET_CEILING_PCM16
      val kneeStart = targetCeiling * 0.7071f
      val sampleMax = max(abs(leftSum), abs(rightSum))

      val rawTargetGain =
        if (sampleMax <= kneeStart) {
          1.0f
        } else if (sampleMax < targetCeiling) {
          val x = (sampleMax - kneeStart) / (targetCeiling - kneeStart)
          val linearTarget = targetCeiling / sampleMax
          val t = x * x * (3.0f - 2.0f * x)
          1.0f - t * (1.0f - linearTarget)
        } else {
          targetCeiling / sampleMax
        }

      if (rawTargetGain < currentGain) {
        currentGain = currentGain * attackCoeff + rawTargetGain * (1.0f - attackCoeff)
      } else {
        currentGain = currentGain * releaseCoeff + 1.0f * (1.0f - releaseCoeff)
        if (currentGain > 1.0f) {
          currentGain = 1.0f
        }
      }

      val delayedL = delayBufferL[delayIndex]
      val delayedR = delayBufferR[delayIndex]
      delayBufferL[delayIndex] = leftSum
      delayBufferR[delayIndex] = rightSum
      delayIndex = (delayIndex + 1) % lookaheadFrames

      val outL = (delayedL * currentGain).coerceIn(-targetCeiling, targetCeiling)
      val outR = (delayedR * currentGain).coerceIn(-targetCeiling, targetCeiling)

      // 5. Quantize / Output Serialization
      when (outputEncoding) {
        C.ENCODING_PCM_FLOAT -> {
          outputBuffer.putFloat(outL)
          outputBuffer.putFloat(outR)
        }

        C.ENCODING_PCM_32BIT -> {
          outputBuffer.putInt((outL * 2147483520.0f).roundToInt())
          outputBuffer.putInt((outR * 2147483520.0f).roundToInt())
        }

        C.ENCODING_PCM_24BIT -> {
          outputBuffer.putPacked24(outL)
          outputBuffer.putPacked24(outR)
        }

        else -> {
          // High-precision TPDF Dither when downscaling float/multichannel to 16-bit PCM
          val needsDither = encoding != C.ENCODING_PCM_16BIT || channelCount > 2 || currentGain < 0.999f
          val ditherL = if (needsDither) nextDither() - nextDither() else 0.0f
          val ditherR = if (needsDither) nextDither() - nextDither() else 0.0f

          val pcmL = (outL * 32767.0f + ditherL).roundToInt().coerceIn(-32768, 32767).toShort()
          val pcmR = (outR * 32767.0f + ditherR).roundToInt().coerceIn(-32768, 32767).toShort()

          outputBuffer.putShort(pcmL)
          outputBuffer.putShort(pcmR)
        }
      }
    }

    outputBuffer.flip()
  }

  override fun onFlush() {
    currentGain = 1.0f
    crossfeedHistL = 0.0f
    crossfeedHistR = 0.0f
    delayBufferL.fill(0.0f, 0, lookaheadFrames)
    delayBufferR.fill(0.0f, 0, lookaheadFrames)
    delayIndex = 0
  }

  override fun onReset() {
    currentGain = 1.0f
    crossfeedHistL = 0.0f
    crossfeedHistR = 0.0f
    delayBufferL.fill(0.0f, 0, lookaheadFrames)
    delayBufferR.fill(0.0f, 0, lookaheadFrames)
    delayIndex = 0
  }

  private fun nextDither(): Float {
    ditherState = ditherState xor (ditherState shl 13)
    ditherState = ditherState xor (ditherState ushr 17)
    ditherState = ditherState xor (ditherState shl 5)
    return ((ditherState and 0x7FFFFFFF).toFloat() / 2147483648.0f) - 0.5f
  }

  private fun encodingToString(encoding: Int): String =
    when (encoding) {
      C.ENCODING_PCM_16BIT -> "PCM_16BIT"
      C.ENCODING_PCM_24BIT -> "PCM_24BIT"
      C.ENCODING_PCM_32BIT -> "PCM_32BIT"
      C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
      else -> "ENCODING_$encoding"
    }

  private fun ByteBuffer.putPacked24(sample: Float) {
    val value = (sample * 8388607.0f).roundToInt().coerceIn(-8388608, 8388607)
    put((value and 0xFF).toByte())
    put(((value ushr 8) and 0xFF).toByte())
    put(((value ushr 16) and 0xFF).toByte())
  }
}
