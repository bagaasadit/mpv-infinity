/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import androidx.annotation.StringRes
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.getEnum

class AudioPreferences(
  preferenceStore: PreferenceStore,
) {
  val preferredLanguages = preferenceStore.getString("audio_preferred_languages")
  val defaultAudioDelay = preferenceStore.getInt("audio_delay_default")
  val pickerPath = preferenceStore.getString("audio_picker_path")
  val audioPitchCorrection = preferenceStore.getBoolean("audio_pitch_correction", true)
  val audioChannels = preferenceStore.getEnum("audio_channels", AudioChannels.AutoSafe)
  val volumeBoostCap = preferenceStore.getInt("audio_volume_boost_cap", 30)
  val backgroundPlayback = preferenceStore.getBoolean("automatic_background_playback", false)
  /** Audio-player-only background playback; video retains [backgroundPlayback]. */
  val audioBackgroundPlayback = preferenceStore.getBoolean("audio_player_background_playback", false)
  val volumeNormalization = preferenceStore.getBoolean("audio_volume_normalization", false)
  val drcEnabled = preferenceStore.getBoolean("audio_drc_enabled", false)
  val audioOrientation = preferenceStore.getEnum("audio_player_orientation", AudioPlayerOrientation.Auto)
  val audioAmbientMode = preferenceStore.getBoolean("audio_ambient_mode", true)
  val dialogueBoost = preferenceStore.getEnum("audio_dialogue_boost", DialogueBoost.Off)


  init {
    // Consolidate the old audio-only screen-lock switch into the single global setting.
    val legacyScreenLockPlayback = preferenceStore.getBoolean("play_audio_after_screen_lock", false)
    if (legacyScreenLockPlayback.get()) backgroundPlayback.set(true)
    if (legacyScreenLockPlayback.isSet()) legacyScreenLockPlayback.delete()
  }
}

enum class AudioPlayerOrientation(
  @StringRes val titleRes: Int,
) {
  Auto(R.string.pref_audio_channels_auto),
  Portrait(R.string.pref_player_orientation_portrait),
  Landscape(R.string.pref_player_orientation_landscape),
}

enum class AudioChannels(
  @StringRes val title: Int,
  val property: String,
  val value: String,
) {
  /** Let mpv prefer the source layout when the output device reports that it is supported. */
  Auto(R.string.pref_audio_channels_auto, "audio-channels", "auto"),
  /** Use the system-preferred layout and safely fall back to stereo. This is mpv's default. */
  AutoSafe(R.string.pref_audio_channels_auto_safe, "audio-channels", "auto-safe"),
  Mono(R.string.pref_audio_channels_mono, "audio-channels", "mono"),
  Stereo(R.string.pref_audio_channels_stereo, "audio-channels", "stereo"),
  ReverseStereo(R.string.pref_audio_channels_stereo_reversed, "af", "pan=[stereo|c0=c1|c1=c0]"),
  Surround51(R.string.pref_audio_channels_5_1, "audio-channels", "5.1"),
  Surround71(R.string.pref_audio_channels_7_1, "audio-channels", "7.1"),
}

enum class DialogueBoost(
  val gain: Float,
  @StringRes val titleRes: Int,
) {
  Off(1.0f, R.string.pref_dialogue_boost_off),
  Light(1.41f, R.string.pref_dialogue_boost_light),
  Medium(1.78f, R.string.pref_dialogue_boost_medium),
}
