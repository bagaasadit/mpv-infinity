/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Lightweight native Compose script & config editor replacing the heavy
 * Sora/TextMate editor dependency (~3.5 MB) while keeping full mpv.conf editing capability.
 */
@Composable
fun MpvScriptEditor(
  content: String,
  onContentChange: (String) -> Unit,
  language: String,
  modifier: Modifier = Modifier,
) {
  TextField(
    value = content,
    onValueChange = onContentChange,
    modifier = modifier.fillMaxSize(),
    textStyle = TextStyle(
      fontFamily = FontFamily.Monospace,
      fontSize = 14.sp,
      color = MaterialTheme.colorScheme.onSurface,
      lineHeight = 20.sp,
    ),
    colors = TextFieldDefaults.colors(
      focusedContainerColor = MaterialTheme.colorScheme.surface,
      unfocusedContainerColor = MaterialTheme.colorScheme.surface,
      disabledContainerColor = MaterialTheme.colorScheme.surface,
      focusedIndicatorColor = Color.Transparent,
      unfocusedIndicatorColor = Color.Transparent,
      disabledIndicatorColor = Color.Transparent,
      cursorColor = MaterialTheme.colorScheme.primary,
    ),
    placeholder = {
      Text(
        text = when (language) {
          "mpv.conf" -> "# Enter mpv configuration here\n# e.g.:\n# hwdec=auto\n# vo=gpu-next"
          "input.conf" -> "# Enter key bindings here\n# e.g.:\n# RIGHT seek 5"
          else -> "-- Enter script code here"
        },
        style = TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 14.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
          lineHeight = 20.sp,
        ),
      )
    },
  )
}
