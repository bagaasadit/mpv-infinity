/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.MultiChoiceSegmentedButton
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.ControlsAnimationStyle
import app.infinity.mpvz.ui.player.NavigationAnimStyle
import app.infinity.mpvz.ui.player.VideoOpenAnimation
import app.infinity.mpvz.ui.preferences.components.SwitchPreference
import app.infinity.mpvz.ui.theme.DarkMode
import app.infinity.mpvz.ui.theme.LocalThemeTransitionState
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.LocalShowSettingsBackArrow
import app.infinity.mpvz.ui.utils.popSafely
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import org.koin.compose.koinInject

@Serializable
object AppearancePreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<AppearancePreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val backstack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val themeTransition = LocalThemeTransitionState.current

    val darkMode by preferences.darkMode.collectAsState()
    val amoledMode by preferences.amoledMode.collectAsState()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_appearance_title),
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
          rememberSettingsSearchList(AppearancePreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          // ── Theme ─────────────────────────────────────────────
          item {
            PreferenceSectionHeader(
              title = stringResource(id = R.string.pref_appearance_category_theme),
              modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_title),
            )
          }

          item {
            PreferenceCard {
              Column(modifier = Modifier.padding(vertical = 8.dp)) {
                MultiChoiceSegmentedButton(
                  choices =
                    DarkMode.entries
                      .map { stringResource(it.titleRes) }
                      .toImmutableList(),
                  selectedIndices = persistentListOf(DarkMode.entries.indexOf(darkMode)),
                  onClick = { index, position ->
                    if (themeTransition?.isAnimating != true) {
                      themeTransition?.startTransition(position)
                      scope.launch {
                        delay(50)
                        preferences.darkMode.set(DarkMode.entries[index])
                      }
                    }
                  },
                )
              }

              PreferenceDivider()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_amoled_mode_title),
                value = amoledMode,
                onValueChange = { newValue ->
                  if (themeTransition?.isAnimating != true) {
                    themeTransition?.startTransition(Offset.Zero)
                    scope.launch {
                      delay(50)
                      preferences.amoledMode.set(newValue)
                    }
                  }
                },
                title = {
                  Text(text = stringResource(id = R.string.pref_appearance_amoled_mode_title))
                },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_appearance_amoled_mode_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                enabled = darkMode != DarkMode.Light,
              )
            }
          }

          // ── Animations ────────────────────────────────────────
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_animations))
          }

          item {
            PreferenceCard {
              val controlsAnimStyle by playerPreferences.controlsAnimStyle.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_anim_controls_style_title),
                value = controlsAnimStyle,
                onValueChange = playerPreferences.controlsAnimStyle::set,
                values = ControlsAnimationStyle.entries,
                valueToText = { AnnotatedString(it.displayName) },
                title = { Text(stringResource(R.string.pref_anim_controls_style_title)) },
                summary = {
                  Text(
                    controlsAnimStyle.displayName,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val videoOpenAnim by playerPreferences.videoOpenAnimation.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_anim_video_open_title),
                value = videoOpenAnim,
                onValueChange = playerPreferences.videoOpenAnimation::set,
                values = VideoOpenAnimation.entries,
                valueToText = { AnnotatedString(it.displayName) },
                title = { Text(stringResource(R.string.pref_anim_video_open_title)) },
                summary = {
                  Text(
                    videoOpenAnim.displayName,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val appNavStyle by playerPreferences.appNavStyle.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_anim_screen_nav_style_title),
                value = appNavStyle,
                onValueChange = playerPreferences.appNavStyle::set,
                values = NavigationAnimStyle.entries,
                valueToText = { AnnotatedString(it.displayName) },
                title = { Text(stringResource(R.string.pref_anim_screen_nav_style_title)) },
                summary = { Text(appNavStyle.displayName, color = MaterialTheme.colorScheme.outline) },
              )

              PreferenceDivider()

              val animSpeed by playerPreferences.animationSpeed.collectAsState()
              SliderPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_anim_speed_title),
                value = animSpeed,
                onValueChange = { playerPreferences.animationSpeed.set(it) },
                title = { Text(stringResource(R.string.pref_anim_speed_title)) },
                valueRange = 0.25f..2.5f,
                summary = {
                  val label =
                    when {
                      animSpeed < 0.6f ->
                        stringResource(
                          R.string.pref_anim_speed_very_fast,
                          animSpeed,
                        )
                      animSpeed < 0.9f -> stringResource(R.string.pref_anim_speed_fast, animSpeed)
                      animSpeed < 1.1f -> stringResource(R.string.pref_anim_speed_normal, animSpeed)
                      animSpeed < 1.6f -> stringResource(R.string.pref_anim_speed_slow, animSpeed)
                      else -> stringResource(R.string.pref_anim_speed_very_slow, animSpeed)
                    }
                  Text(label, color = MaterialTheme.colorScheme.outline)
                },
                onSliderValueChange = { playerPreferences.animationSpeed.set(it) },
                sliderValue = animSpeed,
              )
            }
          }
        }
      }
    }
  }
}
