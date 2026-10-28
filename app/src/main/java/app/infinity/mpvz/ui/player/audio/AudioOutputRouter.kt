/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Describes where the current sound is physically going.
 *
 * The DownmixLimiterAudioProcessor applies a binaural crossfeed that is tuned for in-ear
 * monitors. On a phone speaker the same filter narrows the stereo image and makes the mix
 * sound muddier, so the processor has to know which transducer is active before running it.
 */
enum class AudioOutputType {
  /** The phone's own loudspeaker. No crossfeed, no extra LFE energy. */
  Speaker,

  /** Wired headphones, headset, earbuds, or USB audio device. */
  WiredHeadphone,

  /** Bluetooth A2DP / LE audio route. Crossfeed kept gentle because TWS sets often apply their own DSP. */
  Bluetooth,
  ;

  val supportsCrossfeed: Boolean
    get() = this != Speaker

  /** LFE fold-down adds sub-bass that small phone speakers cannot reproduce cleanly. */
  val supportsLfeFoldDown: Boolean
    get() = this != Speaker

  /**
   * Bluetooth sinks normally ship their own virtualisation. Run the crossfeed at a reduced
   * depth so the two stages do not stack into an over-processed, hollow result.
   */
  val crossfeedStrength: Float
    get() =
      when (this) {
        Speaker -> 0f
        WiredHeadphone -> 1f
        Bluetooth -> 0.5f
      }
}

/**
 * Tracks the active audio output route and converts it into a decision the audio processor can use.
 *
 * Routing is observed live: plugging in a headset, unplugging it, or connecting a Bluetooth sink
 * updates [currentOutput] without restarting playback. Listeners are notified on the main thread.
 */
class AudioOutputRouter(
  private val context: Context,
) {
  private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
  private val handler = Handler(Looper.getMainLooper())

  private val _outputRoute = MutableStateFlow(resolve())
  val outputRoute: StateFlow<AudioOutputType> = _outputRoute.asStateFlow()

  /** Last resolved route. Initialized immediately so active device is known at startup. */
  @Volatile
  var currentOutput: AudioOutputType = _outputRoute.value
    private set

  private val listeners = CopyOnWriteArrayList<(AudioOutputType) -> Unit>()
  private var deviceCallback: AudioDeviceCallback? = null

  init {
    ensureCallbackRegistered()
  }

  private fun ensureCallbackRegistered() {
    if (deviceCallback == null) {
      val callback =
        object : AudioDeviceCallback() {
          override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            notifyIfChanged()
          }

          override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            notifyIfChanged()
          }
        }
      deviceCallback = callback
      runCatching { audioManager.registerAudioDeviceCallback(callback, handler) }
        .onFailure { Log.w(TAG, "Unable to register audio device callback", it) }
    }
  }

  /**
   * Re-reads the active route and returns it.
   *
   * Routes are evaluated in priority order: wired and USB outputs win over Bluetooth,
   * and Bluetooth wins over the built-in speaker.
   */
  fun resolve(): AudioOutputType {
    val routed = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
      .getOrElse {
        Log.w(TAG, "Unable to read audio output devices", it)
        return currentOutput
      }

    val types = routed.map { it.type }.toSet()
    val resolved =
      when {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types ||
          AudioDeviceInfo.TYPE_WIRED_HEADSET in types ||
          AudioDeviceInfo.TYPE_USB_HEADSET in types ||
          AudioDeviceInfo.TYPE_USB_DEVICE in types -> AudioOutputType.WiredHeadphone

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types ||
          AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types -> AudioOutputType.Bluetooth

        else -> AudioOutputType.Speaker
      }

    currentOutput = resolved
    return resolved
  }

  /**
   * Starts observing route changes. [onRouteChanged] fires immediately on subscription and whenever
   * the resolved route actually changes.
   */
  fun startObserving(onRouteChanged: (AudioOutputType) -> Unit) {
    ensureCallbackRegistered()
    if (!listeners.contains(onRouteChanged)) {
      listeners.add(onRouteChanged)
    }
    // Immediately inform listener of the currently active route
    onRouteChanged(currentOutput)
  }

  /** Removes a specific observer. */
  fun removeObserver(onRouteChanged: (AudioOutputType) -> Unit) {
    listeners.remove(onRouteChanged)
  }

  /** Detaches all observers and platform callback if needed. */
  fun stopObserving() {
    listeners.clear()
  }

  private fun notifyIfChanged() {
    // Callbacks arrive on the main handler, but re-resolve defensively: the platform can
    // report a stale route for a few milliseconds after a plug event.
    val previous = currentOutput
    val resolved = resolve()
    if (resolved != previous) {
      Log.i(TAG, "Audio output route changed: $previous -> $resolved")
      currentOutput = resolved
      _outputRoute.value = resolved
      listeners.forEach { it(resolved) }
    }
  }

  private companion object {
    const val TAG = "AudioOutputRouter"
  }
}
