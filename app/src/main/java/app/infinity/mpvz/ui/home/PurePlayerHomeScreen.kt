/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.preferences.PreferencesScreen
import app.infinity.mpvz.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable

/**
 * Minimalist, dedicated launcher home screen ala mpvKt.
 * Provides quick access to URL playback, file picking, storage browsing, and settings
 * without loading heavy gallery/scanner components.
 */
@Serializable
object PurePlayerHomeScreen : Screen {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    var urlText by remember { mutableStateOf("") }

    val openDocumentLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          runCatching {
            context.contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          }
          val playIntent = Intent(context, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          context.startActivity(playIntent)
        }
      }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Image(
                painter = painterResource(id = R.drawable.ic_launcher_user_logo),
                contentDescription = null,
                modifier = Modifier
                  .size(32.dp)
                  .clip(CircleShape),
              )
              Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(
                  fontWeight = FontWeight.Bold,
                ),
              )
            }
          },
          actions = {
            IconButton(onClick = { backStack.add(PreferencesScreen) }) {
              Icon(
                imageVector = Icons.RoundedFilled.Settings,
                contentDescription = stringResource(R.string.pref_preferences),
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
          ),
        )
      },
      containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 380.dp)
            .padding(horizontal = 28.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            placeholder = {
              Text(
                text = "URL",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MaterialTheme.colorScheme.primary,
              unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
          )

          Spacer(modifier = Modifier.height(20.dp))

          FilledTonalButton(
            onClick = {
              val trimmed = urlText.trim()
              if (trimmed.isNotBlank()) {
                val intent = Intent(context, PlayerActivity::class.java).apply {
                  action = Intent.ACTION_VIEW
                  data = Uri.parse(trimmed)
                  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
              }
            },
            enabled = urlText.isNotBlank(),
            shape = CircleShape,
            modifier = Modifier
              .fillMaxWidth(0.65f)
              .height(48.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Link,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Open url",
              style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Medium,
              ),
            )
          }

          Spacer(modifier = Modifier.height(12.dp))

          OutlinedButton(
            onClick = {
              openDocumentLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
            },
            shape = CircleShape,
            modifier = Modifier
              .fillMaxWidth(0.65f)
              .height(48.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.InsertDriveFile,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Pick a file",
              style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Medium,
              ),
            )
          }
        }
      }
    }
  }
}
