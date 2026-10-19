/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.preferences

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AudioChannels
import app.infinity.mpvz.preferences.AudioPlayerOrientation
import app.infinity.mpvz.preferences.AudioPreferences
import app.infinity.mpvz.preferences.AudioVisualizerStyle
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.preferences.components.SwitchPreference
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.LocalShowSettingsBackArrow
import app.infinity.mpvz.ui.utils.currentMpvConfigOverrideOptions
import app.infinity.mpvz.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.TextFieldPreference
import org.koin.compose.koinInject

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue


@Serializable
object AudioPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val backstack = LocalBackStack.current
    val preferences = koinInject<AudioPreferences>()
    val configOwnedOptions = currentMpvConfigOverrideOptions()
    val playerPreferences = koinInject<PlayerPreferences>()
    val notificationPermissionLauncher =
      rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
      ) { _ -> }


    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_audio),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backstack.popSafely() }) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(AudioPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_appearance))
          }

          item {
            PreferenceCard {
              val audioVisualizerStyle by preferences.audioVisualizerStyle.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_visualizer_style_title),
                value = audioVisualizerStyle,
                onValueChange = { preferences.audioVisualizerStyle.set(it) },
                values = AudioVisualizerStyle.entries,
                valueToText = { AnnotatedString(resources.getString(it.title)) },
                title = { Text(stringResource(R.string.pref_audio_visualizer_style_title)) },
                summary = {
                  Column {
                    Text(
                      stringResource(audioVisualizerStyle.title),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    if (audioVisualizerStyle == AudioVisualizerStyle.Galaxy ||
                      audioVisualizerStyle == AudioVisualizerStyle.Cuboid
                    ) {
                      Text(
                        text =
                          if (audioVisualizerStyle == AudioVisualizerStyle.Cuboid) {
                            "Inspired by the Cuboid Warptunnel concept by Niklas Knaack"
                          } else {
                            stringResource(R.string.pref_audio_visualizer_galaxy_credit)
                          },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = TextDecoration.Underline,
                        modifier =
                          Modifier.clickable {
                            context.startActivity(
                              Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                  if (audioVisualizerStyle == AudioVisualizerStyle.Cuboid) {
                                    "https://codepen.io/NiklasKnaack/pen/WyWqja"
                                  } else {
                                    "https://codepen.io/Zain-Raza-the-sasster/pen/ByBeKqa"
                                  },
                                ),
                              ),
                            )
                          },
                      )
                    }
                  }
                },
              )

              PreferenceDivider()
              val audioOrientation by preferences.audioOrientation.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_orientation_title),
                value = audioOrientation,
                onValueChange = { preferences.audioOrientation.set(it) },
                values = AudioPlayerOrientation.entries,
                valueToText = { AnnotatedString(resources.getString(it.titleRes)) },
                title = { Text(stringResource(R.string.pref_audio_orientation_title)) },
                summary = {
                  Text(
                    stringResource(audioOrientation.titleRes),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val audioPaletteBackground by preferences.audioPaletteBackground.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_palette_background_title),
                value = audioPaletteBackground,
                onValueChange = { preferences.audioPaletteBackground.set(it) },
                title = { Text(stringResource(R.string.pref_audio_palette_background_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_palette_background_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val audioAmbientMode by preferences.audioAmbientMode.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_ambient_mode_title),
                value = audioAmbientMode,
                onValueChange = { preferences.audioAmbientMode.set(it) },
                title = { Text(stringResource(R.string.pref_audio_ambient_mode_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_ambient_mode_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val audioStandbyMode by preferences.audioStandbyMode.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_standby_mode_title),
                value = audioStandbyMode,
                onValueChange = { preferences.audioStandbyMode.set(it) },
                title = { Text(stringResource(R.string.pref_audio_standby_mode_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_standby_mode_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
              val audioStandbyDelaySeconds by preferences.audioStandbyDelaySeconds.collectAsState()
              SliderPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_standby_mode_title),
                value = audioStandbyDelaySeconds.toFloat().coerceIn(1f, 60f),
                onValueChange = { preferences.audioStandbyDelaySeconds.set(it.toInt().coerceIn(1, 60)) },
                title = { Text("Standby delay") },
                valueRange = 1f..60f,
                summary = { Text("Enter standby after $audioStandbyDelaySeconds seconds of inactivity", color = MaterialTheme.colorScheme.outline) },
                onSliderValueChange = { preferences.audioStandbyDelaySeconds.set(it.toInt().coerceIn(1, 60)) },
                sliderValue = audioStandbyDelaySeconds.toFloat().coerceIn(1f, 60f),
              )

              PreferenceDivider()
              val audioWavySeekbar by preferences.audioWavySeekbar.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_wavy_seekbar_title),
                value = audioWavySeekbar,
                onValueChange = { preferences.audioWavySeekbar.set(it) },
                title = { Text(stringResource(R.string.pref_audio_wavy_seekbar_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_wavy_seekbar_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_playback))
          }

          item {
            PreferenceCard {
              val preferredLanguages by preferences.preferredLanguages.collectAsState()
              TextFieldPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_preferred_languages),
                value = preferredLanguages,
                enabled = "alang" !in configOwnedOptions,
                onValueChange = { preferences.preferredLanguages.set(it) },
                textToValue = { input ->
                  input
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .joinToString(",")
                },
                title = { Text(stringResource(R.string.pref_preferred_languages)) },
                summary = {
                  if (preferredLanguages.isNotBlank()) {
                    Text(
                      preferredLanguages,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  } else {
                    Text(
                      stringResource(R.string.not_set_video_default),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
                textField = { value, onValueChange, _ ->
                  Column {
                    Text(stringResource(R.string.pref_audio_preferred_language))
                    TextField(
                      value,
                      onValueChange,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                },
              )

              PreferenceDivider()
              val audioPitchCorrection by preferences.audioPitchCorrection.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_pitch_correction_title),
                value = audioPitchCorrection,
                enabled = "audio-pitch-correction" !in configOwnedOptions,
                onValueChange = { preferences.audioPitchCorrection.set(it) },
                title = { Text(stringResource(R.string.pref_audio_pitch_correction_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_pitch_correction_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val volumeNormalization by preferences.volumeNormalization.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_volume_normalization_title),
                value = volumeNormalization,
                enabled = "af" !in configOwnedOptions,
                onValueChange = { preferences.volumeNormalization.set(it) },
                title = { Text(stringResource(R.string.pref_audio_volume_normalization_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_volume_normalization_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val drcEnabled by preferences.drcEnabled.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_drc_title),
                value = drcEnabled,
                enabled = "af" !in configOwnedOptions,
                onValueChange = { preferences.drcEnabled.set(it) },
                title = { Text(stringResource(R.string.pref_audio_drc_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_drc_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val audioBackgroundPlayback by preferences.audioBackgroundPlayback.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_background_playback_title),
                value = audioBackgroundPlayback,
                onValueChange = { enabled ->
                  preferences.audioBackgroundPlayback.set(enabled)
                  if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                      PackageManager.PERMISSION_GRANTED
                    ) {
                      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                  }
                },
                title = { Text(stringResource(R.string.pref_audio_background_playback_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_background_playback_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val autoplayNextAudio by playerPreferences.autoplayNextAudio.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_autoplay_next_audio_title),
                value = autoplayNextAudio,
                onValueChange = playerPreferences.autoplayNextAudio::set,
                title = { Text(stringResource(R.string.pref_autoplay_next_audio_title)) },
                summary = {
                  Text(
                    if (autoplayNextAudio) {
                      stringResource(R.string.pref_autoplay_next_audio_summary)
                    } else {
                      stringResource(R.string.pref_autoplay_next_audio_summary_disabled)
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val audioChannel by preferences.audioChannels.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_channels),
                value = audioChannel,
                onValueChange = { preferences.audioChannels.set(it) },
                values = AudioChannels.entries,
                enabled = setOf("audio-channels", "af").none(configOwnedOptions::contains),
                valueToText = { AnnotatedString(resources.getString(it.title)) },
                title = { Text(text = stringResource(id = R.string.pref_audio_channels)) },
                summary = {
                  Text(
                    text = stringResource(audioChannel.title),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val volumeBoostCap by preferences.volumeBoostCap.collectAsState()
              SliderPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_volume_boost_cap),
                value = volumeBoostCap.toFloat(),
                enabled = "volume-max" !in configOwnedOptions,
                onValueChange = { preferences.volumeBoostCap.set(it.toInt()) },
                title = { Text(stringResource(R.string.pref_audio_volume_boost_cap)) },
                valueRange = 0f..200f,
                summary = {
                  Text(
                    if (volumeBoostCap == 0) {
                      stringResource(R.string.generic_disabled)
                    } else {
                      volumeBoostCap.toString()
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onSliderValueChange = { preferences.volumeBoostCap.set(it.toInt()) },
                sliderValue = volumeBoostCap.toFloat(),
              )


            }
          }
        }
      }
    }


  }
}
