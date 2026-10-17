package app.infinity.mpvz.database.dao

import app.infinity.mpvz.database.entities.Audiobook
import app.infinity.mpvz.database.entities.AudiobookChapterEntity
import app.infinity.mpvz.database.entities.AudiobookEntity
import app.infinity.mpvz.database.entities.AudiobookTrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

open class AudiobookDao {
  open fun observeLibrary(): Flow<List<Audiobook>> = emptyFlow()
  open suspend fun getBook(id: Long): Audiobook? = null
  open fun observeBook(id: Long): Flow<Audiobook?> = emptyFlow()
  open suspend fun findBySource(sourceKey: String): Long? = null
  open suspend fun insertBook(book: AudiobookEntity): Long = -1L
  open suspend fun insertTracks(tracks: List<AudiobookTrackEntity>): List<Long> = emptyList()
  open suspend fun importBook(book: AudiobookEntity, tracks: List<AudiobookTrackEntity>): Long = -1L
  open suspend fun reconcileImportedBook(bookId: Long, sourceKey: String, tracks: List<AudiobookTrackEntity>): Long = -1L
  open suspend fun setInitialTrack(id: Long, trackId: Long) {}
  open suspend fun updateSourceKey(bookId: Long, sourceKey: String) {}
  open suspend fun getTracksForBook(bookId: Long): List<AudiobookTrackEntity> = emptyList()
  open suspend fun deleteTracksNotIn(bookId: Long, uris: List<String>) {}
  open suspend fun updateImportedTrack(trackId: Long, uri: String, position: Int, fileName: String, title: String, durationMs: Long, size: Long) {}
  open suspend fun getCurrentTrackId(bookId: Long): Long? = null
  open suspend fun resetPlaybackToTrack(bookId: Long, trackId: Long) {}
  open suspend fun recalculateBookProgress(bookId: Long, trackId: Long) {}
  open suspend fun saveProgress(bookId: Long, trackId: Long, positionMs: Long, playedAt: Long, reachedEnd: Boolean) {}
  open suspend fun setSpeed(id: Long, speed: Float) {}
  open suspend fun setRewind(id: Long, seconds: Int) {}
  open suspend fun setFinished(id: Long, finished: Boolean) {}
  open suspend fun updateDetails(id: Long, title: String, author: String, narrator: String, series: String, seriesPart: String) {}
  open suspend fun updateDetails(id: Long, title: String, author: String, narrator: String, series: String, seriesPart: String, coverUri: String?) {}
  open suspend fun updateCover(id: Long, coverUri: String?) {}
  open suspend fun fillMetadata(id: Long, author: String, narrator: String, series: String, seriesPart: String, description: String, language: String, publisher: String, publishedYear: String, genre: String, isbn: String, asin: String) {}
  open suspend fun updateDuration(trackId: Long, durationMs: Long) {}
  open suspend fun getChapters(trackId: Long): List<AudiobookChapterEntity> = emptyList()
  open suspend fun deleteBook(id: Long) {}
  open fun observeChapters(bookId: Long): Flow<List<AudiobookChapterEntity>> = emptyFlow()
  open suspend fun insertChapters(chapters: List<AudiobookChapterEntity>) {}
  open suspend fun deleteChapters(trackId: Long) {}
  open suspend fun replaceChapters(trackId: Long, chapters: List<AudiobookChapterEntity>) {}
  open suspend fun getAllBooks(): List<AudiobookEntity> = emptyList()
  open suspend fun getAllTracks(): List<AudiobookTrackEntity> = emptyList()
}
