/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
  val verticalScroll = rememberScrollState()
  val horizontalScroll = rememberScrollState()

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
      .verticalScroll(verticalScroll)
      .horizontalScroll(horizontalScroll)
      .padding(16.dp),
  ) {
    BasicTextField(
      value = content,
      onValueChange = onContentChange,
      modifier = Modifier.fillMaxWidth(),
      textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 20.sp,
      ),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
  }
}
