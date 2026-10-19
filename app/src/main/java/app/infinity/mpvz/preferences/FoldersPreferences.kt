/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import app.infinity.mpvz.preferences.preference.PreferenceStore

/**
 * Preferences for media scanning and storage folder management
 */
class FoldersPreferences(
  preferenceStore: PreferenceStore,
) {
  // Root folder from which all app storage paths are derived
  val baseStorageFolder = preferenceStore.getString("base_storage_folder", "")

  val includeNoMediaFolders = preferenceStore.getBoolean("include_nomedia_folders", false)
  val hiddenFolderMarkerNames =
    preferenceStore.getStringSet("hidden_folder_marker_names", setOf(".nomedia"))
}
