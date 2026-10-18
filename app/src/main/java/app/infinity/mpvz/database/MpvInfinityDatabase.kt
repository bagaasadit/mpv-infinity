/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.infinity.mpvz.database.dao.PlaybackStateDao
import app.infinity.mpvz.database.dao.PlaylistDao
import app.infinity.mpvz.database.dao.RecentlyPlayedDao
import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.database.entities.PlaylistEntity
import app.infinity.mpvz.database.entities.PlaylistItemEntity
import app.infinity.mpvz.database.entities.RecentlyPlayedEntity

@Database(
  entities = [
    PlaybackStateEntity::class,
    RecentlyPlayedEntity::class,
    PlaylistEntity::class,
    PlaylistItemEntity::class,
  ],
  version = 25,
  exportSchema = true,
)
abstract class MpvInfinityDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun recentlyPlayedDao(): RecentlyPlayedDao

  abstract fun playlistDao(): PlaylistDao
}
