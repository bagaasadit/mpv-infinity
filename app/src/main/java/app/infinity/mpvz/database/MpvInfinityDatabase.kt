/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.infinity.mpvz.database.dao.PlaybackStateDao
import app.infinity.mpvz.database.dao.RecentlyPlayedDao
import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.database.entities.RecentlyPlayedEntity

@Database(
  entities = [
    PlaybackStateEntity::class,
    RecentlyPlayedEntity::class,
  ],
  version = 26,
  exportSchema = true,
)
abstract class MpvInfinityDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun recentlyPlayedDao(): RecentlyPlayedDao
}
