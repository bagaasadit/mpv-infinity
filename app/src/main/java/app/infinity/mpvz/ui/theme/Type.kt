/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

// Use system native typography (pure, lightweight, zero overhead)
val RobotoFlex = FontFamily.SansSerif
val GoogleSansRounded = FontFamily.SansSerif

val SystemTypography = Typography()
val AppTypography = SystemTypography

fun fontFamilyForText(text: String): FontFamily = FontFamily.SansSerif
fun localeRequiresSystemFont(locale: Locale): Boolean = true

// ═══════════════════════════════════════════════════════════
// Material 3 Expressive Typography Extensions
// Bumps font weights one step heavier for expressive emphasis
// ═══════════════════════════════════════════════════════════

data class EmphasizedTypography(
  val displayLarge: TextStyle,
  val displayMedium: TextStyle,
  val displaySmall: TextStyle,
  val headlineLarge: TextStyle,
  val headlineMedium: TextStyle,
  val headlineSmall: TextStyle,
  val titleLarge: TextStyle,
  val titleMedium: TextStyle,
  val titleSmall: TextStyle,
  val bodyLarge: TextStyle,
  val bodyMedium: TextStyle,
  val bodySmall: TextStyle,
  val labelLarge: TextStyle,
  val labelMedium: TextStyle,
  val labelSmall: TextStyle,
)

val AppEmphasizedTypography =
  EmphasizedTypography(
    displayLarge = AppTypography.displayLarge.copy(fontWeight = FontWeight.Black),
    displayMedium = AppTypography.displayMedium.copy(fontWeight = FontWeight.Black),
    displaySmall = AppTypography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLarge = AppTypography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = AppTypography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = AppTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = AppTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = AppTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = AppTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmall = AppTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = AppTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = AppTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
  )

val LocalEmphasizedTypography = staticCompositionLocalOf { AppEmphasizedTypography }
