/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.infinity.mpvz.R

/**
 * App themes inspired by Aniyomi design
 * Each theme has light and dark color schemes with unique backgrounds
 */
enum class AppTheme(
  @StringRes val titleRes: Int,
  val primaryLight: Color,
  val primaryDark: Color,
  val secondaryLight: Color,
  val secondaryDark: Color,
  val tertiaryLight: Color,
  val tertiaryDark: Color,
  // Background colors - tinted with theme color
  val backgroundLight: Color,
  val backgroundDark: Color,
  val isDynamic: Boolean = false,
) {
  Monochrome(
    titleRes = R.string.theme_monochrome,
    primaryLight = Color(0xFF212121),
    primaryDark = Color(0xFFE0E0E0),
    secondaryLight = Color(0xFF424242),
    secondaryDark = Color(0xFFBDBDBD),
    tertiaryLight = Color(0xFF616161),
    tertiaryDark = Color(0xFF9E9E9E),
    backgroundLight = Color(0xFFFFFFFF),
    backgroundDark = Color(0xFF0A0A0A),
  ),
  Dynamic(
    titleRes = R.string.theme_dynamic,
    primaryLight = Color(0xFF6750A4),
    primaryDark = Color(0xFFD0BCFF),
    secondaryLight = Color(0xFF625B71),
    secondaryDark = Color(0xFFCCC2DC),
    tertiaryLight = Color(0xFF7D5260),
    tertiaryDark = Color(0xFFEFB8C8),
    backgroundLight = Color(0xFFFFFBFF),
    backgroundDark = Color(0xFF1C1B1F),
    isDynamic = true,
  ),
  Nord(
    titleRes = R.string.theme_nord,
    primaryLight = Color(0xFF5E81AC),
    primaryDark = Color(0xFF88C0D0),
    secondaryLight = Color(0xFF4C566A),
    secondaryDark = Color(0xFFD8DEE9),
    tertiaryLight = Color(0xFFB48EAD),
    tertiaryDark = Color(0xFFD8A9C4),
    backgroundLight = Color(0xFFECEFF4),
    backgroundDark = Color(0xFF2E3440),
  ),
  Default(
    titleRes = R.string.theme_default,
    primaryLight = Color(0xFF794F81),
    primaryDark = Color(0xFFE8B5EF),
    secondaryLight = Color(0xFF6A596C),
    secondaryDark = Color(0xFFD6C0D6),
    tertiaryLight = Color(0xFF82524D),
    tertiaryDark = Color(0xFFF5B7B0),
    backgroundLight = Color(0xFFF7F5F8),
    backgroundDark = Color(0xFF161217),
  ),
  ;

  /**
   * Get the light color scheme for this theme
   */
  fun getLightColorScheme(): ColorScheme {
    val softOnColor = Color(0xFFFFF9FC)
    val primaryContainer = primaryLight.copy(alpha = 0.22f).compositeOver(backgroundLight)
    val secondaryContainer = secondaryLight.copy(alpha = 0.22f).compositeOver(backgroundLight)
    val tertiaryContainer = tertiaryLight.copy(alpha = 0.22f).compositeOver(backgroundLight)
    val surfaceVariant = primaryLight.copy(alpha = 0.10f).compositeOver(backgroundLight.darken(0.035f))
    val surfaceDim = backgroundLight.darken(0.075f)
    val surfaceBright = backgroundLight.lighten(0.012f)
    val surfaceContainerLowest = backgroundLight.lighten(0.018f)
    val surfaceContainerLow = primaryLight.copy(alpha = 0.030f).compositeOver(backgroundLight)
    val surfaceContainer = primaryLight.copy(alpha = 0.050f).compositeOver(backgroundLight)
    val surfaceContainerHigh = primaryLight.copy(alpha = 0.075f).compositeOver(backgroundLight)
    val surfaceContainerHighest = primaryLight.copy(alpha = 0.105f).compositeOver(backgroundLight)
    val accentSurfaces =
      listOf(
        backgroundLight,
        surfaceVariant,
        surfaceDim,
        surfaceBright,
        surfaceContainerLowest,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
      )
    val primary = primaryLight.withMinimumContrastAgainst(accentSurfaces)
    val secondary = secondaryLight.withMinimumContrastAgainst(accentSurfaces)
    val tertiary = tertiaryLight.withMinimumContrastAgainst(accentSurfaces)
    return lightColorScheme(
      primary = primary,
      onPrimary = primary.accessibleContentColor(),
      primaryContainer = primaryContainer,
      onPrimaryContainer = primaryContainer.accessibleContentColor(),
      secondary = secondary,
      onSecondary = secondary.accessibleContentColor(),
      secondaryContainer = secondaryContainer,
      onSecondaryContainer = secondaryContainer.accessibleContentColor(),
      tertiary = tertiary,
      onTertiary = tertiary.accessibleContentColor(),
      tertiaryContainer = tertiaryContainer,
      onTertiaryContainer = tertiaryContainer.accessibleContentColor(),
      error = Color(0xFFBA1A1A),
      onError = softOnColor,
      errorContainer = Color(0xFFFFDAD6),
      onErrorContainer = Color(0xFF93000A),
      background = backgroundLight,
      onBackground = Color(0xFF1C1B1F),
      surface = backgroundLight,
      onSurface = Color(0xFF1C1B1F),
      surfaceVariant = surfaceVariant,
      onSurfaceVariant = Color(0xFF49454F),
      outline = secondaryLight.copy(alpha = 0.6f).compositeOver(Color(0xFF79747E)),
      outlineVariant = primaryLight.copy(alpha = 0.20f).compositeOver(Color(0xFFCAC4D0)),
      inverseSurface = backgroundDark,
      inverseOnSurface = Color(0xFFF4EFF4),
      inversePrimary = primaryDark.withMinimumContrastAgainst(backgroundDark),
      surfaceDim = surfaceDim,
      surfaceBright = surfaceBright,
      surfaceContainerLowest = surfaceContainerLowest,
      surfaceContainerLow = surfaceContainerLow,
      surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh,
      surfaceContainerHighest = surfaceContainerHighest,
    )
  }

  /**
   * Get the dark color scheme for this theme
   */
  fun getDarkColorScheme(): ColorScheme {
    val primaryContainer = primaryLight.darken(0.2f)
    val secondaryContainer = secondaryLight.darken(0.2f)
    val tertiaryContainer = tertiaryLight.darken(0.2f)
    val surfaceVariant = primaryDark.copy(alpha = 0.18f).compositeOver(Color(0xFF2A2A2A))
    val surfaceContainerLowest = backgroundDark.darken(0.2f)
    val surfaceContainerLow = primaryDark.copy(alpha = 0.08f).compositeOver(backgroundDark)
    val surfaceContainer = primaryDark.copy(alpha = 0.08f).compositeOver(backgroundDark)
    val surfaceContainerHigh = primaryDark.copy(alpha = 0.12f).compositeOver(backgroundDark)
    val surfaceContainerHighest = primaryDark.copy(alpha = 0.16f).compositeOver(backgroundDark)
    val accentSurfaces =
      listOf(
        backgroundDark,
        surfaceVariant,
        surfaceContainerLowest,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
      )
    val primary = primaryDark.withMinimumContrastAgainst(accentSurfaces)
    val secondary = secondaryDark.withMinimumContrastAgainst(accentSurfaces)
    val tertiary = tertiaryDark.withMinimumContrastAgainst(accentSurfaces)
    return darkColorScheme(
      primary = primary,
      onPrimary = primary.accessibleContentColor(),
      primaryContainer = primaryContainer,
      onPrimaryContainer = primaryContainer.accessibleContentColor(),
      secondary = secondary,
      onSecondary = secondary.accessibleContentColor(),
      secondaryContainer = secondaryContainer,
      onSecondaryContainer = secondaryContainer.accessibleContentColor(),
      tertiary = tertiary,
      onTertiary = tertiary.accessibleContentColor(),
      tertiaryContainer = tertiaryContainer,
      onTertiaryContainer = tertiaryContainer.accessibleContentColor(),
      error = Color(0xFFFFB4AB),
      onError = Color(0xFF690005),
      errorContainer = Color(0xFF93000A),
      onErrorContainer = Color(0xFFFFDAD6),
      background = backgroundDark,
      onBackground = Color(0xFFE6E1E5),
      surface = backgroundDark,
      onSurface = Color(0xFFE6E1E5),
      surfaceVariant = surfaceVariant,
      onSurfaceVariant = Color(0xFFCAC4D0),
      outline = secondaryDark.copy(alpha = 0.5f).compositeOver(Color(0xFF938F99)),
      outlineVariant = primaryDark.copy(alpha = 0.22f).compositeOver(Color(0xFF49454F)),
      inverseSurface = backgroundLight,
      inverseOnSurface = Color(0xFF313033),
      inversePrimary = primaryLight.withMinimumContrastAgainst(backgroundLight),
      surfaceContainerLowest = surfaceContainerLowest,
      surfaceContainerLow = surfaceContainerLow,
      surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh,
      surfaceContainerHighest = surfaceContainerHighest,
    )
  }

  /**
   * Get the AMOLED (pure black) color scheme for this theme
   */
  fun getAmoledColorScheme(): ColorScheme =
    getDarkColorScheme().copy(
      background = Color.Black,
      surface = Color.Black,
      surfaceVariant = primaryDark.copy(alpha = 0.08f).compositeOver(Color(0xFF1A1A1A)),
      surfaceContainer = Color(0xFF0A0A0A),
      surfaceContainerLow = Color(0xFF050505),
      surfaceContainerLowest = Color.Black,
      surfaceContainerHigh = primaryDark.copy(alpha = 0.05f).compositeOver(Color(0xFF151515)),
      surfaceContainerHighest = primaryDark.copy(alpha = 0.08f).compositeOver(Color(0xFF1F1F1F)),
      surfaceDim = Color.Black,
      surfaceBright = primaryDark.copy(alpha = 0.06f).compositeOver(Color(0xFF2A2A2A)),
    )
}

// Extension functions for color manipulation
private const val MinimumTextContrast = 4.5f

private fun Color.accessibleContentColor(): Color {
  val blackContrast = contrastRatio(Color.Black)
  val whiteContrast = contrastRatio(Color.White)
  return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun Color.withMinimumContrastAgainst(background: Color): Color =
  withMinimumContrastAgainst(listOf(background))

private fun Color.withMinimumContrastAgainst(backgrounds: List<Color>): Color {
  if (backgrounds.minOf { background -> contrastRatio(background) } >= MinimumTextContrast) return this

  val blackContrast = backgrounds.minOf { background -> Color.Black.contrastRatio(background) }
  val whiteContrast = backgrounds.minOf { background -> Color.White.contrastRatio(background) }
  val target = if (blackContrast >= whiteContrast) Color.Black else Color.White
  var insufficientFraction = 0f
  var sufficientFraction = 1f
  repeat(12) {
    val fraction = (insufficientFraction + sufficientFraction) / 2f
    val candidate = blendToward(target, fraction)
    if (backgrounds.minOf { background -> candidate.contrastRatio(background) } >= MinimumTextContrast) {
      sufficientFraction = fraction
    } else {
      insufficientFraction = fraction
    }
  }
  return blendToward(target, sufficientFraction)
}

private fun Color.contrastRatio(other: Color): Float {
  val relativeLuminance = luminance()
  val otherRelativeLuminance = other.luminance()
  val lighter = maxOf(relativeLuminance, otherRelativeLuminance)
  val darker = minOf(relativeLuminance, otherRelativeLuminance)
  return (lighter + 0.05f) / (darker + 0.05f)
}

private fun Color.blendToward(target: Color, fraction: Float): Color =
  Color(
    red = red + (target.red - red) * fraction,
    green = green + (target.green - green) * fraction,
    blue = blue + (target.blue - blue) * fraction,
    alpha = alpha + (target.alpha - alpha) * fraction,
  )

private fun Color.darken(factor: Float): Color =
  Color(
    red = (red * (1 - factor)).coerceIn(0f, 1f),
    green = (green * (1 - factor)).coerceIn(0f, 1f),
    blue = (blue * (1 - factor)).coerceIn(0f, 1f),
    alpha = alpha,
  )

private fun Color.lighten(factor: Float): Color =
  Color(
    red = (red + (1 - red) * factor).coerceIn(0f, 1f),
    green = (green + (1 - green) * factor).coerceIn(0f, 1f),
    blue = (blue + (1 - blue) * factor).coerceIn(0f, 1f),
    alpha = alpha,
  )

private fun Color.compositeOver(background: Color): Color {
  val bgAlpha = background.alpha
  val fgAlpha = alpha
  val a = fgAlpha + bgAlpha * (1f - fgAlpha)
  return if (a == 0f) {
    Color.Transparent
  } else {
    Color(
      red = (red * fgAlpha + background.red * bgAlpha * (1f - fgAlpha)) / a,
      green = (green * fgAlpha + background.green * bgAlpha * (1f - fgAlpha)) / a,
      blue = (blue * fgAlpha + background.blue * bgAlpha * (1f - fgAlpha)) / a,
      alpha = a,
    )
  }
}
