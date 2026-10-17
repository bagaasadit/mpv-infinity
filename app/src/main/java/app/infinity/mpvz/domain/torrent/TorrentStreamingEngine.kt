/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.domain.torrent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight stub for [TorrentStreamingEngine] in pure external player mode.
 * Eliminates the heavy native libtorrent4j dependency (~25-35 MB) while keeping
 * player interfaces completely intact.
 */
class TorrentStreamingEngine(
  context: Context,
) {
  private val _state = MutableStateFlow<TorrentStreamingState>(TorrentStreamingState.Idle)
  val state: StateFlow<TorrentStreamingState> = _state.asStateFlow()

  suspend fun startStream(request: TorrentStreamRequest): TorrentStreamResult {
    throw UnsupportedOperationException("Torrent streaming is not supported in pure player mode")
  }

  suspend fun prepareTorrent(source: String): TorrentCatalog {
    throw UnsupportedOperationException("Torrent streaming is not supported in pure player mode")
  }

  fun stopStream() {
    _state.value = TorrentStreamingState.Idle
  }
}
