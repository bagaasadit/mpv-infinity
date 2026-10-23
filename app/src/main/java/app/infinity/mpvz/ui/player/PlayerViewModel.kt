/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.AudioManager
import android.net.Uri
import app.infinity.mpvz.domain.network.NetworkPlaybackUri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import `is`.xyz.mpv.MPVNode
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.util.Log
import android.util.LruCache
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.domain.autocrop.AutoCropAnalyzer
import app.infinity.mpvz.domain.autocrop.AutoCropEdges
import app.infinity.mpvz.domain.hdr.HdrToysManager
import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.AudioChannels
import app.infinity.mpvz.preferences.AudioPreferences
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.GesturePreferences
import app.infinity.mpvz.preferences.MpvConfigOverride
import app.infinity.mpvz.preferences.MpvConfigControlledFeatures
import app.infinity.mpvz.preferences.MpvConfigOverridePolicy
import app.infinity.mpvz.preferences.IntroSegmentProvider
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.SubtitlesPreferences
import app.infinity.mpvz.repository.IntroDbLookupOutcome
import app.infinity.mpvz.repository.IntroDbLookupRequest
import app.infinity.mpvz.repository.IntroDbRepository
import app.infinity.mpvz.repository.subtitle.OnlineSubtitle
import app.infinity.mpvz.repository.subtitle.OnlineSubtitleOrchestrator
import app.infinity.mpvz.repository.subtitle.OnlineSubtitleSearchMode
import app.infinity.mpvz.repository.subtitle.OnlineSubtitleSearchRequest
import app.infinity.mpvz.repository.wyzie.WyzieSearchRepository
import app.infinity.mpvz.ui.player.ScriptCurlBridge
import app.infinity.mpvz.ui.player.anime4k.Anime4KUiState
import app.infinity.mpvz.ui.player.anime4k.applyAnime4KShaderChain
import app.infinity.mpvz.ui.player.anime4k.applyAnime4KStabilityOptions
import app.infinity.mpvz.ui.player.anime4k.clearAnime4KShaders
import app.infinity.mpvz.ui.player.anime4k.selectRuntimeStableAnime4K
import app.infinity.mpvz.ui.player.controls.components.sheets.EQ_MAX_DB
import app.infinity.mpvz.ui.player.controls.components.sheets.EQ_MIN_DB
import app.infinity.mpvz.ui.player.controls.components.sheets.EqualizerPreset
import app.infinity.mpvz.ui.player.controls.components.sheets.EqualizerState
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager
import app.infinity.mpvz.ui.player.screenshot.ScreenshotSaver
import app.infinity.mpvz.ui.player.screenshot.ScreenshotSettings
import app.infinity.mpvz.ui.preferences.CustomButton
import app.infinity.mpvz.ui.preferences.CustomButtonScriptLanguage
import app.infinity.mpvz.utils.media.AudioEqualizerManager
import app.infinity.mpvz.utils.media.ChecksumUtils
import app.infinity.mpvz.utils.media.MediaInfoParser
import app.infinity.mpvz.utils.media.ParsedMediaInfo
import app.infinity.mpvz.utils.media.SubtitleHashUtils
import app.infinity.mpvz.utils.media.fileExtension
import dev.vivvvek.seeker.Segment
import app.infinity.mpvz.utils.media.resolveSubtitleLookupDirectories
import app.infinity.mpvz.utils.storage.FileTypeUtils
import `is`.xyz.mpv.FastThumbnails
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.properties.ReadOnlyProperty
import kotlin.random.Random
import kotlin.reflect.KProperty

enum class AutoCropState {
  IDLE,
  ANALYZING,
  APPLIED,
  NO_BARS,
  UNSUPPORTED,
  ERROR,
}

@Suppress("TooManyFunctions")
class PlayerViewModel : ViewModel(),
  KoinComponent {
  private var hostReference = WeakReference<PlayerHost>(null)
  private val host: PlayerHost
    get() = checkNotNull(hostReference.get()) { "Player host is not attached" }

  fun attachHost(host: PlayerHost) {
    hostReference = WeakReference(host)
  }

  fun detachHost(host: PlayerHost) {
    if (hostReference.get() === host) hostReference.clear()
  }
  enum class IntroDbStatusState {
    IDLE,
    LOOKING_UP,
    LOADED,
    NO_SEGMENTS,
    UNRESOLVED,
    ERROR,
    DISABLED,
  }

  data class IntroDbStatus(
    val state: IntroDbStatusState = IntroDbStatusState.IDLE,
    val message: String = "",
    val imdbId: String? = null,
    val segmentCount: Int = 0,
  )

  @Serializable
  private data class IntroMarkerCacheEntry(
    val providerSourceKey: String,
    val outcomeType: String,
    val imdbId: String? = null,
    val message: String = "",
    val segments: List<app.infinity.mpvz.repository.IntroDbSegment> = emptyList(),
    val cachedAtMs: Long = System.currentTimeMillis(),
  )

  private val playerPreferences: PlayerPreferences by inject()
  private val appContext: Context by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()
  private val hdrToysManager: HdrToysManager by inject()
  private val json: Json by inject()
  private val playbackStateDao: app.infinity.mpvz.database.dao.PlaybackStateDao by inject()
  private val wyzieRepository: WyzieSearchRepository by inject()
  private val onlineSubtitleOrchestrator: OnlineSubtitleOrchestrator by inject()
  private val introDbRepository: IntroDbRepository by inject()
  private val introMarkerCachePrefs by lazy {
    appContext.getSharedPreferences(INTRO_MARKER_CACHE_PREFS, Context.MODE_PRIVATE)
  }

  private val initialAnime4KUiState
    get() =
      Anime4KUiState(
        isEnabled = decoderPreferences.enableAnime4K.get(),
        selectedMode = decoderPreferences.anime4kMode.get(),
        usesGpuNext = decoderPreferences.gpuNext.get(),
        usesVulkan = decoderPreferences.useVulkan.get(),
        enableIn4k = decoderPreferences.anime4kIn4k.get(),
      )

  private val anime4KPreferenceState =
    combine(
      decoderPreferences.enableAnime4K.changes(),
      decoderPreferences.anime4kMode.changes(),
      decoderPreferences.gpuNext.changes(),
      decoderPreferences.useVulkan.changes(),
      decoderPreferences.anime4kIn4k.changes(),
    ) { enabled, mode, gpuNext, useVulkan, enableIn4k ->
      Anime4KUiState(
        isEnabled = enabled,
        selectedMode = mode,
        usesGpuNext = gpuNext,
        usesVulkan = useVulkan,
        enableIn4k = enableIn4k,
      )
    }

  val anime4KUiState =
    anime4KPreferenceState
      .combine(PlaybackSession.propInt["video-params/w"]) { state, width ->
        state.copy(videoWidth = width ?: 0)
      }.combine(PlaybackSession.propInt["video-params/h"]) { state, height ->
        state.copy(videoHeight = height ?: 0)
      }.distinctUntilChanged()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = initialAnime4KUiState,
      )

  // HTTP bridge for Lua/JS scripts — executes curl_request payloads via native libcurl
  private val scriptCurlBridge = ScriptCurlBridge(scope = viewModelScope)

  // Playlist items for the playlist sheet
  private val _playlistItems =
    kotlinx.coroutines.flow
      .MutableStateFlow<List<app.infinity.mpvz.ui.player.controls.components.sheets.PlaylistItem>>(
        emptyList(),
      )
  val playlistItems:
    kotlinx.coroutines.flow.StateFlow<List<app.infinity.mpvz.ui.player.controls.components.sheets.PlaylistItem>> =
    _playlistItems
      .asStateFlow()

  private val _onlineSubtitleSearchResults = MutableStateFlow<List<OnlineSubtitle>>(emptyList())
  val onlineSubtitleSearchResults: StateFlow<List<OnlineSubtitle>> = _onlineSubtitleSearchResults.asStateFlow()

  private val _isDownloadingSub = MutableStateFlow(false)
  val isDownloadingSub: StateFlow<Boolean> = _isDownloadingSub.asStateFlow()

  private val _isSearchingSub = MutableStateFlow(false)
  val isSearchingSub: StateFlow<Boolean> = _isSearchingSub.asStateFlow()

  private val _isOnlineSectionExpanded = MutableStateFlow(true)
  val isOnlineSectionExpanded: StateFlow<Boolean> = _isOnlineSectionExpanded.asStateFlow()

  private var playlistMetadataJob: Job? = null
  private var controlsVisibleForPolling = false
  private var seekBarVisibleForPolling = false
  private val skippedSegments = mutableSetOf<SkipSegment>()
  private var chapterDerivedSegments: List<SkipSegment> = emptyList()
  private var introDbSegments: List<app.infinity.mpvz.repository.IntroDbSegment> = emptyList()
  private var introDbSourceKey: String = IntroSegmentProvider.INTRO_DB.sourceKey
  private var introLookupJob: Job? = null
  private val introKeywordPatterns =
    listOf(
      // English/general
      "intro",
      "opening",
      "opening theme",
      "theme song",
      "title song",
      "creditless opening",
      "clean opening",
      "cold open",
      "prologue",
      "prelude",
      "op",
      "op1",
      "op2",
      "op3",
      "ncop",
      "ncop1",
      "ncop2",
      "nco",
      // Japanese
      "オープニング",
      "オープニングテーマ",
      "主題歌",
      "主題歌op",
      "ノンクレジットop",
      "ノンテロップop",
      "ノンクレop",
      "前期op",
      "後期op",
      "冒頭",
    )

  private val outroKeywordPatterns =
    listOf(
      // English/general
      "outro",
      "ending",
      "ending theme",
      "end credits",
      "credits",
      "credit roll",
      "epilogue",
      "postlude",
      "ed",
      "ed1",
      "ed2",
      "ed3",
      "nced",
      "nced1",
      "nced2",
      "nce",
      "preview",
      "next episode",
      // Japanese
      "エンディング",
      "エンディングテーマ",
      "エンドロール",
      "次回予告",
      "ノンクレジットed",
      "ノンテロップed",
      "ノンクレed",
      "前期ed",
      "後期ed",
      "予告",
      "終幕",
    )

  private val recapKeywordPatterns =
    listOf(
      "recap",
      "summary",
      "story so far",
      "previously on",
      "last time",
      "digest",
      "catch up",
      "振り返り",
      "前回まで",
      "これまで",
      "総集編",
      "おさらい",
    )

  private val creditsKeywordPatterns =
    listOf(
      "credits",
      "end credits",
      "credit roll",
      "rolling credits",
      "staff roll",
      "エンドロール",
      "クレジット",
    )

  private val previewKeywordPatterns =
    listOf(
      "preview",
      "next episode",
      "next week on",
      "up next",
      "teaser",
      "次回予告",
      "予告",
      "次回",
    )

  private val _skipSegments = MutableStateFlow<List<SkipSegment>>(emptyList())
  val skipSegments: StateFlow<List<SkipSegment>> = _skipSegments.asStateFlow()

  @Volatile private var skipSegmentsSnapshot: List<SkipSegment> = emptyList()

  private val _currentSkippableSegment = MutableStateFlow<SkipSegment?>(null)
  val currentSkippableSegment: StateFlow<SkipSegment?> = _currentSkippableSegment.asStateFlow()
  private val _showSkipChipAuto = MutableStateFlow(false)
  val showSkipChipAuto: StateFlow<Boolean> = _showSkipChipAuto.asStateFlow()
  private var pendingIntroLookupTitle: String? = null

  private val _introDbStatus =
    MutableStateFlow(
      if (playerPreferences.enableIntroDb.get()) {
        IntroDbStatus()
      } else {
        IntroDbStatus(
          state = IntroDbStatusState.DISABLED,
          message = "Online skip markers are disabled",
        )
      },
    )
  val introDbStatus: StateFlow<IntroDbStatus> = _introDbStatus.asStateFlow()

  // Media Search / Autocomplete
  private val _mediaSearchResults =
    MutableStateFlow<List<app.infinity.mpvz.repository.wyzie.WyzieTmdbResult>>(emptyList())
  val mediaSearchResults: StateFlow<List<app.infinity.mpvz.repository.wyzie.WyzieTmdbResult>> =
    _mediaSearchResults
      .asStateFlow()

  private val _isSearchingMedia = MutableStateFlow(false)
  val isSearchingMedia: StateFlow<Boolean> = _isSearchingMedia.asStateFlow()

  // TV Show Details
  private val _selectedTvShow = MutableStateFlow<app.infinity.mpvz.repository.wyzie.WyzieTvShowDetails?>(null)
  val selectedTvShow: StateFlow<app.infinity.mpvz.repository.wyzie.WyzieTvShowDetails?> = _selectedTvShow.asStateFlow()

  private val _isFetchingTvDetails = MutableStateFlow(false)
  val isFetchingTvDetails: StateFlow<Boolean> = _isFetchingTvDetails.asStateFlow()

  // Season / Episode
  private val _selectedSeason = MutableStateFlow<app.infinity.mpvz.repository.wyzie.WyzieSeason?>(null)
  val selectedSeason: StateFlow<app.infinity.mpvz.repository.wyzie.WyzieSeason?> = _selectedSeason.asStateFlow()

  private val _seasonEpisodes = MutableStateFlow<List<app.infinity.mpvz.repository.wyzie.WyzieEpisode>>(emptyList())
  val seasonEpisodes: StateFlow<List<app.infinity.mpvz.repository.wyzie.WyzieEpisode>> = _seasonEpisodes.asStateFlow()

  private val _isFetchingEpisodes = MutableStateFlow(false)
  val isFetchingEpisodes: StateFlow<Boolean> = _isFetchingEpisodes.asStateFlow()

  private val _selectedEpisode = MutableStateFlow<app.infinity.mpvz.repository.wyzie.WyzieEpisode?>(null)
  val selectedEpisode: StateFlow<app.infinity.mpvz.repository.wyzie.WyzieEpisode?> = _selectedEpisode.asStateFlow()

  fun toggleOnlineSection() {
    _isOnlineSectionExpanded.value = !_isOnlineSectionExpanded.value
  }

  // Cache for video metadata to avoid re-extracting — LruCache handles bounds + thread-safety
  private val metadataCache = object : android.util.LruCache<String, Pair<String, String>>(100) {}
  private val playbackStateDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val renderPrepDispatcher = Dispatchers.Default.limitedParallelism(1)
  private var autoCropJob: Job? = null
  private var autoCropReadinessJob: Job? = null
  private var autoCropAnalyzedGeneration = -1L
  private var autoCropApplied = false
  // Memory-only analysis cache. This is not playback history and does not enable auto-crop.
  private val autoCropResultCache = LruCache<String, AutoCropEdges>(AUTO_CROP_CACHE_CAPACITY)
  private val _autoCropState = MutableStateFlow(AutoCropState.IDLE)
  val autoCropState: StateFlow<AutoCropState> = _autoCropState.asStateFlow()

  private data class AutoCropSamples(
    val edges: List<AutoCropEdges>,
    val framesWereRotated: Boolean,
  )

  private fun updateMetadataCache(
    key: String,
    value: Pair<String, String>,
  ) {
    metadataCache.put(key, value)
  }

  // MPV-backed scalar state. Keep these initialized before any coroutine can read them.
  private val _paused = MutableStateFlow<Boolean?>(null)
  val paused: Boolean? get() = _paused.value

  private val _pos = MutableStateFlow<Int?>(null)
  val pos: Int? get() = _pos.value

  private val _duration = MutableStateFlow<Int?>(null)
  val duration: Int? get() = _duration.value

  private val _volumeBoostCap = MutableStateFlow<Int?>(null)
  private val volumeBoostCap: Int? get() = _volumeBoostCap.value

  private val _isMpvCoreReady = MutableStateFlow(false)
  private var mpvStateCollectorsJob: Job? = null

  // High-precision position and duration for smooth seekbar
  private val _precisePosition = MutableStateFlow(0f)
  val precisePosition = _precisePosition.asStateFlow()

  private val _preciseDuration = MutableStateFlow(0f)
  val preciseDuration = _preciseDuration.asStateFlow()

  private fun parseTracks(node: MPVNode?): List<TrackNode> {
    val fromNode = runCatching { node?.toObject<List<TrackNode>>(json) }.getOrNull()
    if (!fromNode.isNullOrEmpty()) {
      return fromNode
    }
    val trackCount = PlaybackSession.getPropertyInt("track-list/count") ?: 0
    if (trackCount <= 0) return emptyList()
    val fallbackList = mutableListOf<TrackNode>()
    for (i in 0 until trackCount) {
      val id = PlaybackSession.getPropertyInt("track-list/$i/id") ?: continue
      val type = PlaybackSession.getPropertyString("track-list/$i/type") ?: continue
      val title =
        PlaybackSession.getPropertyString("track-list/$i/title")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/title")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/TITLE")
      val lang =
        PlaybackSession.getPropertyString("track-list/$i/lang")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/language")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/lang")
      val selected = PlaybackSession.getPropertyBoolean("track-list/$i/selected")
      val external = PlaybackSession.getPropertyBoolean("track-list/$i/external")
      val default = PlaybackSession.getPropertyBoolean("track-list/$i/default")
      val forced = PlaybackSession.getPropertyBoolean("track-list/$i/forced")
      val codec = PlaybackSession.getPropertyString("track-list/$i/codec")
      val codecDesc = PlaybackSession.getPropertyString("track-list/$i/codec-desc")
      val hlsBitrate = PlaybackSession.getPropertyInt("track-list/$i/hls-bitrate")?.toLong()
      val programId = PlaybackSession.getPropertyInt("track-list/$i/program-id")?.toLong()
      val demuxW = PlaybackSession.getPropertyInt("track-list/$i/demux-w")?.toLong()
      val demuxH = PlaybackSession.getPropertyInt("track-list/$i/demux-h")?.toLong()
      val demuxFps = PlaybackSession.getPropertyDouble("track-list/$i/demux-fps")
      val demuxBitrate = PlaybackSession.getPropertyInt("track-list/$i/demux-bitrate")?.toLong()
      val externalFilename = PlaybackSession.getPropertyString("track-list/$i/external-filename")
      val image = PlaybackSession.getPropertyBoolean("track-list/$i/image")
      val albumArt = PlaybackSession.getPropertyBoolean("track-list/$i/albumart")
      fallbackList.add(
        TrackNode(
          id = id,
          type = type,
          title = title,
          lang = lang,
          selected = selected,
          external = external,
          default = default,
          forced = forced,
          codec = codec,
          codecDesc = codecDesc,
          hlsBitrate = hlsBitrate,
          programId = programId,
          demuxW = demuxW,
          demuxH = demuxH,
          demuxFps = demuxFps,
          demuxBitrate = demuxBitrate,
          externalFilename = externalFilename,
          image = image,
          albumArt = albumArt,
        ),
      )
    }
    return fallbackList
  }

  // These MPV-backed state flows must be initialized before any init block collects them.
  private val nativeSubtitleTracks = MutableStateFlow<List<TrackNode>>(emptyList())
  private val nativeExternalSubtitleTracks = MutableStateFlow<List<TrackNode>>(emptyList())
  private val nativeAudioTracks = MutableStateFlow<List<TrackNode>>(emptyList())
  private val nativeEngineActive = MutableStateFlow(false)
  private val nativeChapters = MutableStateFlow<List<Segment>>(emptyList())
  private var nativeSubtitleToggleListener: ((Int) -> Unit)? = null
  private var nativeExternalSubtitleToggleListener: ((Int) -> Unit)? = null
  private var nativeAudioToggleListener: ((Int) -> Unit)? = null
  fun setNativeEngineActive(active: Boolean) {
    nativeEngineActive.value = active
  }

  fun setNativeTracks(snapshot: NativePlaybackSnapshot) {
    nativeSubtitleTracks.value = snapshot.subtitleTracks.mapIndexedNotNull { index, track ->
      if (track.external) return@mapIndexedNotNull null
      TrackNode(
        // Keep the ID based on the source snapshot index. PlayerActivity resolves the ID back
        // through snapshot.subtitleTracks; using the filtered index selected the wrong track
        // whenever an external subtitle appeared before an embedded one.
        id = -(index + 1),
        type = "sub",
        title = track.label,
        lang = track.language,
        selected = track.selected,
        external = false,
      )
    }
    val externalSelection = snapshot.subtitleTracks
      .filter { it.external }
      .mapNotNull { track -> track.formatId?.removePrefix("external:")?.let { it to track.selected } }
      .toMap()
    if (externalSelection.isNotEmpty()) {
      nativeExternalSubtitleTracks.value = nativeExternalSubtitleTracks.value.map { track ->
        track.externalFilename?.let { rawUri ->
          externalSelection[rawUri]?.let { selected -> track.copy(selected = selected) } ?: track
        } ?: track
      }
    }
    nativeAudioTracks.value = snapshot.audioTracks.mapIndexed { index, track ->
      TrackNode(
        id = -1001 - index,
        type = "audio",
        title = track.label,
        lang = track.language,
        selected = track.selected,
        external = false,
      )
    }
    // Media3 does not expose Matroska Chapters for every extractor/source. Do not erase a chapter
    // list already obtained from mpv while the native engine is still preparing its tracks.
    if (snapshot.chapters.isNotEmpty()) {
      nativeChapters.value = snapshot.chapters.map { chapter ->
        Segment(chapter.title, chapter.startSeconds)
      }
    }
  }

  fun setNativeSubtitleTracks(tracks: List<NativeTrack>) {
    setNativeTracks(NativePlaybackSnapshot(subtitleTracks = tracks))
  }

  fun setNativeSubtitleToggleListener(listener: ((Int) -> Unit)?) {
    nativeSubtitleToggleListener = listener
  }

  fun setNativeExternalSubtitleToggleListener(listener: ((Int) -> Unit)?) {
    nativeExternalSubtitleToggleListener = listener
  }

  fun registerNativeExternalSubtitle(uri: Uri, fileName: String, selected: Boolean) {
    val uriString = uri.toString()
    if (nativeExternalSubtitleTracks.value.any { it.externalFilename == uriString }) {
      setNativeExternalSubtitleSelected(
        nativeExternalSubtitleTracks.value.first { it.externalFilename == uriString }.id,
        selected,
      )
      return
    }
    val id = -10_000 - nativeExternalSubtitleTracks.value.size
    nativeExternalSubtitleTracks.value = nativeExternalSubtitleTracks.value + TrackNode(
      id = id,
      type = "sub",
      title = fileName,
      selected = selected,
      external = true,
      externalFilename = uriString,
    )
  }

  fun setNativeExternalSubtitleSelected(id: Int, selected: Boolean) {
    nativeExternalSubtitleTracks.value = nativeExternalSubtitleTracks.value.map { track ->
      if (track.id == id) track.copy(selected = selected) else track
    }
  }

  fun clearNativeExternalSubtitles() {
    nativeExternalSubtitleTracks.value = emptyList()
  }



  fun setNativeAudioToggleListener(listener: ((Int) -> Unit)?) {
    nativeAudioToggleListener = listener
  }

  private val allTracks: StateFlow<List<TrackNode>> =
    PlaybackSession.propNode["track-list"]
      .map { node -> parseTracks(node).toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val subtitleTracks: StateFlow<List<TrackNode>> =
    combine(allTracks, nativeSubtitleTracks, nativeExternalSubtitleTracks, nativeEngineActive) { tracks, nativeTracks, externalTracks, nativeActive ->
      if (nativeActive) nativeTracks + externalTracks else tracks.filter { it.isSubtitle }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val audioTracks: StateFlow<List<TrackNode>> =
    combine(allTracks, nativeAudioTracks, nativeEngineActive) { tracks, nativeTracks, nativeActive ->
      if (nativeActive) nativeTracks else tracks.filter { it.isAudio }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val videoQualityTracks: StateFlow<List<TrackNode>> =
    combine(allTracks, PlaybackSession.state) { tracks, session ->
      val item = session.currentItem
      val isNetworkStream =
        sequenceOf(item?.originalUri, item?.playableUri)
          .filterNotNull()
          .any { uri -> uri.startsWith("http://", true) || uri.startsWith("https://", true) }
      if (!isNetworkStream) {
        return@combine persistentListOf()
      }

      tracks
        .asSequence()
        .filter { track -> track.isVideo && !track.isAlbumArtwork }
        .sortedByDescending(TrackNode::isSelected)
        .distinctBy { track ->
          listOf(
            track.programId,
            track.programIds,
            track.demuxW,
            track.demuxH,
            track.demuxFps,
            track.effectiveBitrate,
            track.codec,
            track.effectiveTitle,
          )
        }.sortedWith(
          compareByDescending<TrackNode>(::videoQualityDimension)
            .thenByDescending(::videoPixelCount)
            .thenByDescending { track -> track.demuxFps ?: 0.0 }
            .thenByDescending { track -> track.effectiveBitrate ?: 0L },
        ).toList()
        .toImmutableList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val showVideoQualitySelector: StateFlow<Boolean> =
    videoQualityTracks
      .map { qualityTracks -> qualityTracks.isNotEmpty() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  fun selectVideoQuality(track: TrackNode) {
    if (currentItemRequiresYtdlp() && !MpvConfigOverridePolicy.isOwnedByMpvConf("ytdl-format")) {
      val selectedAudio = pairedYtdlTrack(track, TrackNode::isAudio)
        ?: allTracks.value.firstOrNull { candidate -> candidate.isAudio && candidate.isSelected }
      val selector = buildYtdlFormatSelector(videoTrack = track, audioTrack = selectedAudio)
      if (selector != null && host.reloadCurrentYtdlFormat(selector)) return
    }

    val selectedProgramIds = track.effectiveProgramIds.toSet()
    if (selectedProgramIds.isNotEmpty()) {
      allTracks.value
        .asSequence()
        .filter(TrackNode::isAudio)
        .filter { audioTrack -> audioTrack.effectiveProgramIds.any(selectedProgramIds::contains) }
        .sortedByDescending(TrackNode::isSelected)
        .firstOrNull()
        ?.let { audioTrack -> PlaybackSession.setPropertyInt("aid", audioTrack.id) }
    }
    PlaybackSession.setPropertyInt("vid", track.id)
  }

  fun selectAudioTrack(track: TrackNode) {
    if (nativeEngineActive.value) {
      nativeAudioToggleListener?.invoke(track.id)
      return
    }
    if (getTrackSelectionId("aid") == track.id) {
      setTrackSelectionId("aid", null)
      return
    }

    if (currentItemRequiresYtdlp() &&
      !MpvConfigOverridePolicy.isOwnedByMpvConf("ytdl-format") &&
      ytdlFormatId(track) != null
    ) {
      val selectedVideo = pairedYtdlTrack(track, TrackNode::isVideo)
        ?: allTracks.value.firstOrNull { candidate -> candidate.isVideo && candidate.isSelected }
      val selector = buildYtdlFormatSelector(videoTrack = selectedVideo, audioTrack = track)
      if (selector != null && host.reloadCurrentYtdlFormat(selector)) return
    }

    setTrackSelectionId("aid", track.id)
  }

  private fun currentItemRequiresYtdlp(): Boolean {
    val item = PlaybackSession.state.value.currentItem
    return sequenceOf(item?.originalUri, item?.playableUri)
      .filterNotNull()
      .any(YtdlpManager::requiresYtdlp)
  }

  private fun pairedYtdlTrack(
    track: TrackNode,
    matchesType: (TrackNode) -> Boolean,
  ): TrackNode? {
    val programIds = track.effectiveProgramIds.toSet()
    val formatId = track.ytdlFormatId ?: return null
    if (programIds.isEmpty()) return null
    return allTracks.value.firstOrNull { candidate ->
      matchesType(candidate) &&
        candidate.ytdlFormatId == formatId &&
        candidate.effectiveProgramIds.any(programIds::contains)
    }
  }

  private fun buildYtdlFormatSelector(
    videoTrack: TrackNode?,
    audioTrack: TrackNode?,
  ): String? {
    val videoFormatId = videoTrack?.let(::ytdlFormatId)
    val audioFormatId = audioTrack?.let(::ytdlFormatId)
    val videoSelector =
      when {
        videoFormatId != null -> videoFormatId
        videoTrack != null && videoQualityDimension(videoTrack) > 0L ->
          "bestvideo[height<=?${videoQualityDimension(videoTrack)}]"
        videoTrack != null -> "bestvideo"
        else -> null
      }

    return when {
      videoSelector != null && videoFormatId != null && videoFormatId == audioFormatId -> "$videoSelector/best"
      videoSelector != null -> "$videoSelector+${audioFormatId ?: "bestaudio"}/best"
      audioFormatId != null -> "$audioFormatId/best"
      else -> null
    }
  }

  private fun ytdlFormatId(track: TrackNode): String? = track.ytdlFormatId

  private fun videoQualityDimension(track: TrackNode): Long {
    val width = track.demuxW?.takeIf { it > 0L }
    val height = track.demuxH?.takeIf { it > 0L }
    return when {
      width != null && height != null -> minOf(width, height)
      height != null -> height
      width != null -> width
      else -> QUALITY_HEIGHT_REGEX.find(track.effectiveTitle.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }
  }

  private fun videoPixelCount(track: TrackNode): Long =
    (track.demuxW ?: 0L).coerceAtLeast(0L) * (track.demuxH ?: 0L).coerceAtLeast(0L)

  val isAudioOnly: StateFlow<Boolean> =
    combine(
      allTracks,
      PlaybackSession.propString["path"],
      PlaybackSession.propString["stream-open-filename"],
      PlaybackSession.state,
    ) { tracks, path, streamPath, session ->
      val currentPath = path?.takeIf { it.isNotBlank() } ?: streamPath
      val queuedItem = session.currentItem
      val itemDeclaresAudio =
        queuedItem?.mimeType?.startsWith("audio/", ignoreCase = true) == true ||
          (queuedItem != null && (
            queuedItem.originalUri.contains("/Audio/", ignoreCase = true) ||
            queuedItem.originalUri.contains("includeItemTypes=Audio", ignoreCase = true) ||
            queuedItem.playableUri.contains("/Audio/", ignoreCase = true)
          )) ||
          sequenceOf(queuedItem?.originalUri, queuedItem?.playableUri, queuedItem?.title)
            .filterNotNull()
            .any { candidate -> candidate.fileExtension() in FileTypeUtils.AUDIO_EXTENSIONS }
      val isFileAudioExt =
        currentPath?.let { p ->
          val ext = p.fileExtension()
          ext in FileTypeUtils.AUDIO_EXTENSIONS
        } ?: false
      val isFileVideoExt =
        (currentPath?.let { it.fileExtension() in FileTypeUtils.VIDEO_EXTENSIONS } ?: false) ||
          sequenceOf(queuedItem?.originalUri, queuedItem?.playableUri, queuedItem?.title)
            .filterNotNull()
            .any { candidate -> candidate.fileExtension() in FileTypeUtils.VIDEO_EXTENSIONS }

      val hasRealVideo = tracks.any { it.isVideo && !it.isAlbumArtwork }
      val detectedAudio =
        when {
          // A real video track or a known video container always wins. During demux startup mpv
          // can briefly expose only the audio track; treating that transient state as final made
          // MP4/MKV files permanently switch to the audio-player UI.
          hasRealVideo || isFileVideoExt -> false
          itemDeclaresAudio || isFileAudioExt -> true
          tracks.isNotEmpty() -> tracks.any { it.isAudio }
          else -> false
        }
      detectedAudio
    }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val hasAlbumArt: StateFlow<Boolean> =
    allTracks
      .map { tracks -> tracks.any { it.isAlbumArtwork } }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  private val mpvChapters: StateFlow<List<dev.vivvvek.seeker.Segment>> =
    PlaybackSession.propNode["chapter-list"]
      .map { node ->
        runCatching { node?.toObject<List<ChapterNode>>(json) }.getOrNull()?.map { it.toSegment() }?.toImmutableList()
          ?: persistentListOf()
      }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val chapters: StateFlow<List<dev.vivvvek.seeker.Segment>> =
    combine(mpvChapters, nativeChapters, nativeEngineActive) { mpv, native, nativeActive ->
      when {
        nativeActive && native.isNotEmpty() -> native
        else -> mpv
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  // Audio player UI state
  val albumArtBounds = MutableStateFlow<android.graphics.Rect?>(null)
  val showVisualizerInAudioPlayer = MutableStateFlow(false)
  val equalizerState = MutableStateFlow(EqualizerState())
  private val audioEqualizerManager = AudioEqualizerManager()
  private var equalizerMpvDebounceJob: Job? = null

  fun setEqualizerEnabled(enabled: Boolean) {
    equalizerState.value = equalizerState.value.copy(isEnabled = enabled)
    applyEqualizerMpvFilters(immediate = true)
  }

  fun applyEqualizerPreset(preset: EqualizerPreset) {
    if (preset == EqualizerPreset.CUSTOM) return
    equalizerState.value =
      equalizerState.value.copy(
        currentPreset = preset,
        bandGains = preset.gains,
      )
    applyEqualizerMpvFilters(immediate = true)
  }

  fun setEqualizerBandGain(
    index: Int,
    gainDb: Int,
  ) {
    val currentGains = equalizerState.value.bandGains.toMutableList()
    if (index in currentGains.indices && currentGains[index] != gainDb) {
      currentGains[index] = gainDb.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
      equalizerState.value =
        equalizerState.value.copy(
          currentPreset = EqualizerPreset.CUSTOM,
          bandGains = currentGains,
        )
      applyEqualizerMpvFilters(immediate = false)
    }
  }




  fun setEqualizerVolumeBoost(db: Int) {
    if (equalizerState.value.volumeBoostDb != db) {
      equalizerState.value = equalizerState.value.copy(volumeBoostDb = db.coerceIn(0, 10))
      applyEqualizerMpvFilters(immediate = false)
    }
  }

  fun applyEqualizerMpvFilters(immediate: Boolean = false) {
    val state = equalizerState.value

    if (isNativeEngineActive()) {
      // 1. Native Media3 pipeline: Use Android Hardware AudioFx exclusively
      audioEqualizerManager.updateState(
        enabled = state.isEnabled,
        bandGains = state.bandGains,
        volumeBoostDb = state.volumeBoostDb,
      )
    } else {
      // 2. MPV Lib pipeline: Disable Hardware AudioFx to prevent Double EQ stacking (+12dB distortion)
      audioEqualizerManager.updateState(
        enabled = false,
        bandGains = emptyList(),
        volumeBoostDb = 0,
      )

      // Changing MPV "af" filter property during playback causes MPV to recreate audio filter graph.
      // Debouncing while dragging prevents audio stutter/breaking.
      equalizerMpvDebounceJob?.cancel()
      if (immediate) {
        updateMpvAfProperty(state)
      } else {
        equalizerMpvDebounceJob =
          viewModelScope.launch(Dispatchers.Default) {
            delay(150)
            updateMpvAfProperty(state)
          }
      }
    }
  }

  private fun getCustomMpvAf(): String {
    val confFile = File(appContext.filesDir, "mpv.conf")
    val text = if (confFile.exists()) {
      runCatching { confFile.readText() }.getOrDefault("")
    } else {
      advancedPreferences.mpvConf.get()
    }
    if (text.isBlank()) return ""
    val afFilters = text.lines()
      .map { it.trim() }
      .filter { !it.startsWith("#") && (it.startsWith("af=") || it.startsWith("af =") || it.startsWith("af-add=") || it.startsWith("af-add =") || it.startsWith("af-append=") || it.startsWith("af-append =")) }
      .map { line -> line.substringAfter("=").trim() }
      .filter { it.isNotBlank() }
    return afFilters.joinToString(",")
  }

  private fun updateMpvAfProperty(state: EqualizerState) {
    val filterList = mutableListOf<String>()

    // 1. Preserve custom af filters from mpv.conf
    val customAf = getCustomMpvAf()
    if (customAf.isNotBlank()) {
      filterList.add(customAf)
    }

    // 2. Dynamic Range Compression (DRC) / Night Mode
    if (audioPreferences.drcEnabled.get()) {
      filterList.add("lavfi=[acompressor=threshold=-20dB:ratio=4:attack=5:release=50:makeup=2]")
    }

    // 3. Volume Normalization (dynaudnorm)
    if (audioPreferences.volumeNormalization.get()) {
      filterList.add("dynaudnorm")
    }

    // 4. Reverse Stereo filter
    if (audioPreferences.audioChannels.get() == AudioChannels.ReverseStereo) {
      filterList.add("pan=[stereo|c0=c1|c1=c0]")
    }

    // 5. Equalizer filters (if enabled)
    if (state.isEnabled) {
      val maxGain = state.bandGains.maxOrNull() ?: 0
      if (maxGain > 0) {
        filterList.add("volume=volume=${-maxGain}dB")
      }
      val freqs = listOf(60, 230, 910, 3600, 14000)
      for (i in 0 until 5) {
        val gain = state.bandGains.getOrElse(i) { 0 }
        if (gain != 0) {
          filterList.add("equalizer=f=${freqs[i]}:width_type=o:width=1.5:g=$gain")
        }
      }
      if (state.volumeBoostDb > 0) {
        filterList.add("volume=volume=${state.volumeBoostDb}dB")
      }
    }

    val afString = filterList.joinToString(",")
    PlaybackSession.setPropertyString("af", afString)
  }

  fun updateAlbumArtBounds(rect: android.graphics.Rect?) {
    albumArtBounds.value = rect
  }

  fun toggleAudioVisualizer(): Boolean = false

  fun getAudioPropertiesData(): List<app.infinity.mpvz.ui.player.controls.components.sheets.AudioPropertyItem> {
    val title =
      currentMediaTitle.takeIf { it.isNotBlank() }
        ?: PlaybackSession.getPropertyString("metadata/by-key/Title")
        ?: PlaybackSession.getPropertyString("media-title")
        ?: "Unknown Title"

    val artist =
      PlaybackSession.getPropertyString("metadata/by-key/Artist")
        ?: PlaybackSession.getPropertyString("metadata/by-key/ARTIST")
        ?: PlaybackSession.getPropertyString("metadata/by-key/album_artist")
        ?: "Unknown Artist"

    val album =
      PlaybackSession.getPropertyString("metadata/by-key/Album")
        ?: PlaybackSession.getPropertyString("metadata/by-key/ALBUM")
        ?: "Unknown Album"

    val codec = PlaybackSession.getPropertyString("audio-codec-name")?.uppercase() ?: "Unknown"
    val samplerateInt = PlaybackSession.getPropertyInt("audio-params/samplerate") ?: 0
    val sampleRateStr =
      if (samplerateInt >
        0
      ) {
        String.format(java.util.Locale.US, "%.1f kHz", samplerateInt / 1000f)
      } else {
        "Unknown"
      }

    val channelsInt = PlaybackSession.getPropertyInt("audio-params/channel-count") ?: 0
    val channelsStr =
      when (channelsInt) {
        1 -> "Mono (1.0)"
        2 -> "Stereo (2.0)"
        6 -> "5.1 Surround"
        8 -> "7.1 Surround"
        else -> if (channelsInt > 0) "$channelsInt Channels" else "Unknown"
      }

    val bitrateInt = PlaybackSession.getPropertyInt("audio-bitrate") ?: 0
    val bitrateStr = if (bitrateInt > 0) "${bitrateInt / 1000} kbps" else "Variable / Unknown"

    val path = PlaybackSession.getPropertyString("path") ?: PlaybackSession.getPropertyString("stream-open-filename") ?: ""
    val fileSizeStr =
      if (path.isNotBlank() && !path.startsWith("content://") && !path.startsWith("http")) {
        runCatching {
          val bytes = java.io.File(path.removePrefix("file://")).length()
          if (bytes > 0) String.format(java.util.Locale.US, "%.2f MB", bytes / (1024f * 1024f)) else ""
        }.getOrDefault("")
      } else {
        ""
      }

    val formatExt =
      path
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .uppercase()

    return buildList {
      add(
        app.infinity.mpvz.ui.player.controls.components.sheets
          .AudioPropertyItem("Title", title),
      )
      add(
        app.infinity.mpvz.ui.player.controls.components.sheets
          .AudioPropertyItem("Artist", artist),
      )
      add(
        app.infinity.mpvz.ui.player.controls.components.sheets
          .AudioPropertyItem("Album", album),
      )
      add(
        app.infinity.mpvz.ui.player.controls.components.sheets.AudioPropertyItem(
          "Format / Codec",
          if (formatExt.isNotBlank()) "$formatExt ($codec)" else codec,
        ),
      )
      add(
        app.infinity.mpvz.ui.player.controls.components.sheets
          .AudioPropertyItem("Sample Rate", sampleRateStr),
      )
      add(
        app.infinity.mpvz.ui.player.controls.components.sheets
          .AudioPropertyItem("Bitrate", bitrateStr),
      )
      add(
        app.infinity.mpvz.ui.player.controls.components.sheets
          .AudioPropertyItem("Channels", channelsStr),
      )
      if (fileSizeStr.isNotBlank()) {
        add(
          app.infinity.mpvz.ui.player.controls.components.sheets
            .AudioPropertyItem("File Size", fileSizeStr),
        )
      }
      if (path.isNotBlank()) {
        add(
          app.infinity.mpvz.ui.player.controls.components.sheets
            .AudioPropertyItem("File Location", path),
        )
      }
    }
  }

  // Audio state
  val maxVolume = (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  val currentVolume = MutableStateFlow((appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC))
  val currentVolumePercent = MutableStateFlow(systemVolumeToPercent(currentVolume.value))

  // UI state
  private val _controlsShown = MutableStateFlow(false)
  val controlsShown: StateFlow<Boolean> = _controlsShown.asStateFlow()

  private val _seekBarShown = MutableStateFlow(false)
  val seekBarShown: StateFlow<Boolean> = _seekBarShown.asStateFlow()

  private val _areControlsLocked = MutableStateFlow(false)
  val areControlsLocked: StateFlow<Boolean> = _areControlsLocked.asStateFlow()

  val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
  val isBrightnessSliderShown = MutableStateFlow(false)
  val isVolumeSliderShown = MutableStateFlow(false)
  val volumeSliderTimestamp = MutableStateFlow(0L)
  val brightnessSliderTimestamp = MutableStateFlow(0L)
  val currentBrightness =
    MutableStateFlow(
      runCatching {
        Settings.System
          .getFloat(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .normalize(0f, 255f, 0f, 1f)
      }.getOrElse { 0f },
    )

  val sheetShown = MutableStateFlow(Sheets.None)
  val isPlaylistSwipeActive = MutableStateFlow(false)
  val playlistSwipeOffset = MutableStateFlow(0f)
  val panelShown = MutableStateFlow(Panels.None)
  private val _videoOpenAnimationState = MutableStateFlow(VideoOpenAnimationState())
  val videoOpenAnimationState: StateFlow<VideoOpenAnimationState> = _videoOpenAnimationState.asStateFlow()

  // Seek state — combined to allow atomic updates and reduce flow count
  data class SeekState(
    val text: String? = null,
    val amount: Int = 0,
    val isForwards: Boolean = false,
  )

  private val _seekState = MutableStateFlow(SeekState())
  val seekState: StateFlow<SeekState> = _seekState.asStateFlow()


  // Frame navigation
  private val _currentFrame = MutableStateFlow(0)
  val currentFrame: StateFlow<Int> = _currentFrame.asStateFlow()

  private val _totalFrames = MutableStateFlow(0)
  val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()

  private val _isFrameNavigationExpanded = MutableStateFlow(false)
  val isFrameNavigationExpanded: StateFlow<Boolean> = _isFrameNavigationExpanded.asStateFlow()

  private val _isSnapshotLoading = MutableStateFlow(false)
  val isSnapshotLoading: StateFlow<Boolean> = _isSnapshotLoading.asStateFlow()

  // Video zoom
  private val _videoZoom = MutableStateFlow(0f)
  val videoZoom: StateFlow<Float> = _videoZoom.asStateFlow()

  // Video aspect ratio (persisted in player preferences)
  private val _videoAspect = MutableStateFlow(VideoAspect.Fit)
  val videoAspect: StateFlow<VideoAspect> = _videoAspect.asStateFlow()

  // Current aspect ratio value (for custom ratios and tracking)
  private val _currentAspectRatio = MutableStateFlow(-1.0)
  val currentAspectRatio: StateFlow<Double> = _currentAspectRatio.asStateFlow()

  // Timer
  private var timerJob: Job? = null
  private val _remainingTime = MutableStateFlow(0)
  val remainingTime: StateFlow<Int> = _remainingTime.asStateFlow()

  // Media title for subtitle association
  var currentMediaTitle: String = ""
  private var lastAutoSelectedMediaTitle: String? = null
  private val _videoHash = MutableStateFlow<String?>(null)
  val videoHash: StateFlow<String?> = _videoHash.asStateFlow()
  private var videoHashJob: Job? = null

  @Volatile
  private var videoHashGeneration = 0

  // External subtitle tracking
  private val _externalSubtitles = mutableListOf<String>()
  val externalSubtitles: List<String> get() = _externalSubtitles.toList()

  // Mutex to prevent race-condition duplicates when scan adds multiple subtitle URIs concurrently
  private val subtitleAddMutex = Mutex()

  // Mapping from mpv internal path/URI to the original source URI (resolves deletion issues)
  private val mpvPathToUriMap = mutableMapOf<String, String>()

  fun calculateVideoHash(uri: Uri) {
    _videoHash.value = null
    val generation = ++videoHashGeneration
    videoHashJob?.cancel()
    videoHashJob =
      viewModelScope.launch(Dispatchers.IO) {
        val hash = SubtitleHashUtils.computeHash(appContext, uri)
        if (videoHashGeneration == generation) {
          _videoHash.value = hash
        }
        Log.d(TAG, "Computed video hash for $uri: ${hash ?: "unavailable"}")
      }
  }

  // Repeat and Shuffle state
  private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
  val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

  private val _shuffleEnabled = MutableStateFlow(false)
  val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

  // A-B Loop state — combined for atomic updates
  data class ABLoopState(
    val a: Double? = null,
    val b: Double? = null,
    val isExpanded: Boolean = false,
  )

  private val _abLoopState = MutableStateFlow(ABLoopState())
  val abLoopState: StateFlow<ABLoopState> = _abLoopState.asStateFlow()

  // Transform state (mirror + flip) — combined, saves 1 StateFlow object
  data class TransformState(
    val isMirrored: Boolean = false,
    val isVerticalFlipped: Boolean = false,
  )

  private val _transformState = MutableStateFlow(TransformState())
  val transformState: StateFlow<TransformState> = _transformState.asStateFlow()

  private val _hdrScreenMode = MutableStateFlow(initialHdrScreenMode())
  val hdrScreenMode: StateFlow<HdrScreenMode> = _hdrScreenMode.asStateFlow()

  private val _isGpuNextEnabled = MutableStateFlow(decoderPreferences.gpuNext.get())
  private val _isVulkanEnabled = MutableStateFlow(decoderPreferences.useVulkan.get())
  val isLinearHdrAvailable: StateFlow<Boolean> =
    combine(_isGpuNextEnabled, _isVulkanEnabled) { gpuNext, vulkan -> gpuNext && vulkan }
      .stateIn(viewModelScope, SharingStarted.Eagerly, _isGpuNextEnabled.value && _isVulkanEnabled.value)

  private val _isHdrScreenOutputPipelineReady = MutableStateFlow(isHdrScreenOutputAvailable())
  val isHdrScreenOutputPipelineReady: StateFlow<Boolean> = _isHdrScreenOutputPipelineReady.asStateFlow()

  private val _isHdrScreenOutputEnabled =
    MutableStateFlow(_isHdrScreenOutputPipelineReady.value && _hdrScreenMode.value != HdrScreenMode.OFF)
  val isHdrScreenOutputEnabled: StateFlow<Boolean> = _isHdrScreenOutputEnabled.asStateFlow()

  // ==================== Ambience Mode ======================================
  private val _isAmbientEnabled = MutableStateFlow(playerPreferences.isAmbientEnabled.get())
  val isAmbientEnabled: StateFlow<Boolean> = _isAmbientEnabled.asStateFlow()

  private val _ambientStyle = MutableStateFlow(playerPreferences.ambientStyle.get())
  val ambientStyle: StateFlow<AmbientStyle> = _ambientStyle.asStateFlow()

  private val _ambientBlurSamples = MutableStateFlow(playerPreferences.ambientBlurSamples.get())
  val ambientBlurSamples: StateFlow<Int> = _ambientBlurSamples.asStateFlow()

  private val _ambientMaxRadius = MutableStateFlow(playerPreferences.ambientMaxRadius.get())
  val ambientMaxRadius: StateFlow<Float> = _ambientMaxRadius.asStateFlow()

  private val _ambientGlowIntensity = MutableStateFlow(playerPreferences.ambientGlowIntensity.get())
  val ambientGlowIntensity: StateFlow<Float> = _ambientGlowIntensity.asStateFlow()

  private val _ambientSatBoost = MutableStateFlow(playerPreferences.ambientSatBoost.get())
  val ambientSatBoost: StateFlow<Float> = _ambientSatBoost.asStateFlow()

  private val _ambientVignetteStrength = MutableStateFlow(playerPreferences.ambientVignetteStrength.get())
  val ambientVignetteStrength: StateFlow<Float> = _ambientVignetteStrength.asStateFlow()

  private val _ambientWarmth = MutableStateFlow(playerPreferences.ambientWarmth.get())
  val ambientWarmth: StateFlow<Float> = _ambientWarmth.asStateFlow()

  private val _ambientFadeCurve = MutableStateFlow(playerPreferences.ambientFadeCurve.get())
  val ambientFadeCurve: StateFlow<Float> = _ambientFadeCurve.asStateFlow()

  private val _ambientOpacity = MutableStateFlow(playerPreferences.ambientOpacity.get())
  val ambientOpacity: StateFlow<Float> = _ambientOpacity.asStateFlow()

  @Volatile private var lastAmbientScaleX = -1.0
  @Volatile private var lastAmbientScaleY = -1.0
  private var ambientDebounceJob: kotlinx.coroutines.Job? = null
  private val ambientScheduleLock = Any()
  private val ambientRenderLock = Any()
  private val ambientUpdateGeneration = AtomicLong()
  private val _isAmbientLifecycleActive = MutableStateFlow(false)
  val isAmbientLifecycleActive: StateFlow<Boolean> = _isAmbientLifecycleActive.asStateFlow()
  private val ambientShaderSeq = AtomicLong()
  @Volatile private var ambientShaderFile: java.io.File? = null

  /**
  * Caches the [AmbientGlowShaderSpec] that was last compiled into a GLSL file.
   * When [updateAmbientStretch] is called but every parameter is identical to
   * the previously compiled spec, the expensive string-build + file-write +
   * MPV shader-reload cycle is skipped entirely.
   *
   * Using the spec data class (instead of the raw GLSL String) as the cache
   * key avoids allocating the multi-KB shader string and running
   * buildSpiralTapTable trig math before the early-return guard fires.
   *
   * @Volatile: written on renderPrepDispatcher (background), read and nulled on
   * the main thread in disableAmbientShader() / restartAmbientIfActive().
   */
  @Volatile private var lastCompiledSpec: AmbientGlowShaderSpec? = null

  /**
   * Latest device thermal headroom reading ([0f] = at thermal limit, [1f] = cool).
   * Sampled every 10 s by the thermal-monitor coroutine and used to cap the ambient
   * shader sample budget before the SoC enters hard throttling.
   */
  @Volatile private var thermalHeadroom: Float = 1.0f

  private val _isAmbientBatterySaver = MutableStateFlow(playerPreferences.ambientBatterySaver.get())
  val isAmbientBatterySaver: StateFlow<Boolean> = _isAmbientBatterySaver.asStateFlow()
  private var ambientWasOnBattery = false
  private var ambientPreBatterySaverSamples: Int = 18
  private var ambientPreBatterySaverRadius: Float = 0.18f
  private var ambientPreBatterySaverIntensity: Float = 1.4f
  private var ambientPreBatterySaverSatBoost: Float = 1.2f
  private var ambientPreBatterySaverVignette: Float = 0.5f
  private var ambientPreBatterySaverWarmth: Float = 0.0f
  private var ambientPreBatterySaverFadeCurve: Float = 1.5f
  private var ambientPreBatterySaverOpacity: Float = 1.0f
  private var batteryReceiver: BroadcastReceiver? = null
  private var androidSystemInfoBridgeJob: Job? = null

  // ==================== Custom Buttons ====================

  data class CustomButtonState(
    val id: String,
    val label: String,
    val isLeft: Boolean,
  )

  private val _customButtons = MutableStateFlow<List<CustomButtonState>>(emptyList())
  val customButtons: StateFlow<List<CustomButtonState>> = _customButtons.asStateFlow()
  private var customButtonsSetupJob: Job? = null
  private val customButtonsLoadMutex = Mutex()

  @Volatile
  private var isMpvReadyForCustomButtons = false

  @Volatile
  private var customButtonsScriptPaths: Map<CustomButtonScriptLanguage, String> = emptyMap()
  private val legacyCustomButtonsLoadedFlagProperty = "user-data/Mpv∞/custombuttons_loaded"
  private val legacyCustomButtonsVersionProperty = "user-data/Mpv∞/custombuttons_version"
  private val customButtonScriptTargets =
    listOf(
      CustomButtonScriptTarget(
        language = CustomButtonScriptLanguage.LUA,
        fileName = "custombuttons.lua",
        loadedFlagProperty = "user-data/Mpv∞/custombuttons_lua_loaded",
        versionProperty = "user-data/Mpv∞/custombuttons_lua_version",
      ),
      CustomButtonScriptTarget(
        language = CustomButtonScriptLanguage.JS,
        fileName = "custombuttons.js",
        loadedFlagProperty = "user-data/Mpv∞/custombuttons_js_loaded",
        versionProperty = "user-data/Mpv∞/custombuttons_js_version",
      ),
    )
  private val customButtonScriptTargetsByLanguage =
    customButtonScriptTargets.associateBy { it.language }

  private data class CustomButtonScriptTarget(
    val language: CustomButtonScriptLanguage,
    val fileName: String,
    val loadedFlagProperty: String,
    val versionProperty: String,
  )

  init {
    viewModelScope.launch {
      decoderPreferences.gpuNext.changes().collect { enabled ->
        _isGpuNextEnabled.value = enabled
        reconcileHdrModeWithRenderer()
      }
    }
    viewModelScope.launch {
      decoderPreferences.useVulkan.changes().collect { enabled ->
        _isVulkanEnabled.value = enabled
        reconcileHdrModeWithRenderer()
      }
    }

    // Single adaptive polling loop for playback position.
    //  1. An event-driven collect on PlaybackSession.propInt["time-pos"]
    //  2. This polling loop via PlaybackSession.getPropertyDouble("time-pos")
    // Having both caused redundant StateFlow emissions and double recompositions of the
    // seek bar on every MPV property event.  The polling loop alone is sufficient:
    //  - It provides Double precision (vs integer from the observer)
    //  - It drives maybeAutoSkipIntro() which needs sub-second accuracy
    //  - The adaptive interval keeps CPU cost proportional to actual UI demand
    //
    // Intervals:
    //   50 ms  – seek bar / controls visible (smooth scrubbing)
    //   500 ms – uninterrupted playback (halved from original 250 ms to cut idle overhead)
    //   500 ms – paused
    viewModelScope.launch(playbackStateDispatcher) {
      while (isActive) {
        val currentHost = hostReference.get()
        val isNative = currentHost?.isNativeEngineActive() == true || nativeEngineActive.value
        val playbackPhase = PlaybackSession.state.value.phase
        val hasActiveTimeline = isNative || playbackPhase == PlaybackPhase.READY || playbackPhase == PlaybackPhase.BACKGROUND
        if (currentHost == null || (!_isMpvCoreReady.value && !isNative) || !hasActiveTimeline) {
          delay(250L)
          continue
        }
        runCatching {
          // Poll only the active renderer. Reading MPV time-pos while Native Media3 is active
          // produced redundant bridge traffic, stale positions, and unnecessary work on every
          // playback tick; it also prevented auto-skip from following Native playback.
          val time =
            if (isNative) {
              currentHost.nativePlaybackPositionSeconds()
            } else {
              PlaybackSession.getPropertyDouble("time-pos") ?: Double.NaN
            }
          if (time.isFinite()) {
            val posFloat = time.toFloat()
            if (_precisePosition.value != posFloat) {
              _precisePosition.value = posFloat
            }
            maybeAutoSkipIntro(time)
          }
        }.onFailure { error ->
          if (isActive) {
            Log.w(TAG, "Playback position polling failed", error)
          }
        }
        val isPaused = if (isNative) !currentHost.isNativePlaying() else paused == true
        val intervalMs =
          when {
            isPaused -> 1000L // Reduce polling frequency when paused to conserve CPU/battery
            // 100 ms is below the threshold where seek-bar motion reads as stepped, while halving
            // the JNI reads and state emissions a 50 ms loop caused while controls are visible.
            seekBarVisibleForPolling || controlsVisibleForPolling -> 100L
            else -> 500L
          }
        delay(intervalMs)
      }
    }

    // ── Thermal monitor ────────────────────────────────────────────────────────
    // Sample Android's PowerManager.getThermalHeadroom() every 10 s during active
    // playback.  When thermal margin shrinks the ambient shader sample budget is
    // capped automatically, preventing the device from entering hard CPU/GPU throttling
    // which would otherwise manifest as dropped frames and accelerated battery drain.
    viewModelScope.launch(playbackStateDispatcher) {
      while (isActive) {
        if (_isMpvCoreReady.value && paused == false) {
          val newHeadroom = ThermalMonitor.getHeadroom(appContext)
          if (kotlin.math.abs(newHeadroom - thermalHeadroom) > 0.08f) {
            thermalHeadroom = newHeadroom
            if (_isAmbientEnabled.value) {
              // Invalidate the spec cache so the new budget cap is applied on the
              // next scheduled ambient update.
              lastCompiledSpec = null
              scheduleAmbientUpdate()
            }
            Log.d(TAG, "Thermal headroom updated: %.2f".format(newHeadroom))
          }
        }
        delay(10_000L)
      }
    }

    // Update precise duration when the integer duration changes (avoid polling)
    viewModelScope.launch(playbackStateDispatcher) {
      _duration.collect { observedDuration ->
        if (!_isMpvCoreReady.value) return@collect
        if (observedDuration == null || observedDuration <= 0) {
          _preciseDuration.value = 0f
          return@collect
        }
        val dur = PlaybackSession.getPropertyDouble("duration")
        if (dur != null && dur > 0) {
          _preciseDuration.value = dur.toFloat()
          // chapter-list and duration are independent mpv properties. A chapter list can arrive
          // first (or be identical to the previous file), so always derive its markers again once
          // the new duration is known.
          refreshChapterDerivedSegments(chapters.value)
          checkPendingIntroLookup()

          // --- AMBIENT FIX: Adapt shader to new file dimensions by @Chinna95P ---
          if (_isAmbientEnabled.value) {
            lastAmbientScaleX = -1.0 // Force a complete shader rewrite
            // Slight delay ensures MPV's video-params (w/h/crop) are fully populated.
            scheduleAmbientUpdate(250)
          }
          // --------------------------------------------------------
        }
      }
    }

    viewModelScope.launch {
      val activeGeneration =
        PlaybackSession.state
          .map { state ->
            state.generation.takeIf {
              state.phase == PlaybackPhase.READY || state.phase == PlaybackPhase.BACKGROUND
            }
          }.distinctUntilChanged()

      combine(
        PlaybackSession.propString["video-crop"],
        activeGeneration,
        _isMpvCoreReady,
      ) { crop, generation, coreReady -> Triple(crop, generation, coreReady) }
        .distinctUntilChanged()
        .collect { (_, generation, coreReady) ->
          if (!coreReady || generation == null) return@collect
          refreshStretchAspectAfterCropChange()
          restartAmbientIfActive()
        }
    }

    viewModelScope.launch(playbackStateDispatcher) {
      chapters
        .collect { chapterList ->
          refreshChapterDerivedSegments(chapterList)
        }
    }

    viewModelScope.launch(playbackStateDispatcher) {
      combine(
        playerPreferences.customIntroKeywordsEnabled.changes(),
        playerPreferences.customIntroKeywords.changes(),
        playerPreferences.customOutroKeywordsEnabled.changes(),
        playerPreferences.customOutroKeywords.changes(),
      ) { _, _, _, _ -> }.collect {
        refreshChapterDerivedSegments(chapters.value)
      }
    }

    // Track selection is now handled by TrackSelector in PlayerActivity

    // Restore repeat mode and shuffle state from preferences
    _repeatMode.value = playerPreferences.repeatMode.get()
    _shuffleEnabled.value = playerPreferences.shuffleEnabled.get()
    PlaybackSession.setRepeatMode(_repeatMode.value)
    PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)

    // Observe volume boost cap changes to enforce limits dynamically (in PiP)
    viewModelScope.launch(playbackStateDispatcher) {
      audioPreferences.volumeBoostCap.changes().collect { cap ->
        val maxVol = 100 + cap
        runCatching {
          PlaybackSession.setPropertyString("volume-max", maxVol.toString())

          // Clamp current volume if it exceeds the new limit
          val currentMpvVol = PlaybackSession.getPropertyInt("volume") ?: 100
          if (currentMpvVol > maxVol) {
            PlaybackSession.setPropertyInt("volume", maxVol)
          }
        }.onFailure { e ->
          Log.e(TAG, "Error setting volume-max: $maxVol", e)
        }
      }
    }
    // Observe audio effect changes to update mpv af filters and audio-channels
    viewModelScope.launch(playbackStateDispatcher) {
      combine(
        audioPreferences.volumeNormalization.changes(),
        audioPreferences.drcEnabled.changes(),
        audioPreferences.audioChannels.changes(),
      ) { _, _, channels -> channels }.collect { channels ->
        if (!_isMpvCoreReady.value) return@collect
        if (channels != AudioChannels.ReverseStereo) {
          PlaybackSession.setPropertyString("audio-channels", channels.value)
        }
        applyEqualizerMpvFilters(immediate = true)
      }
    }

    // Monitor duration and AB loop changes to automatically enable precise seeking
    viewModelScope.launch(playbackStateDispatcher) {
      combine(_duration, abLoopState) { duration, abLoop ->
        Pair(duration, abLoop)
      }.collect { (duration, abLoop) ->
        if (!_isMpvCoreReady.value) return@collect
        val videoDuration = duration ?: 0
        val isLoopActive = abLoop.a != null || abLoop.b != null
        val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || videoDuration < 120 || isLoopActive
        PlaybackSession.setPropertyString("hr-seek", if (shouldUsePreciseSeeking) "yes" else "no")
        PlaybackSession.setPropertyString("hr-seek-framedrop", if (shouldUsePreciseSeeking) "no" else "yes")
      }
    }

    // Refresh custom buttons whenever their configuration changes.
    viewModelScope.launch {
      playerPreferences.customButtons.changes().drop(1).collect {
        setupCustomButtons()
      }
    }

    // Observe ambient battery saver preference
    viewModelScope.launch {
      playerPreferences.ambientBatterySaver.changes().collect { enabled ->
        _isAmbientBatterySaver.value = enabled
        if (enabled && _isAmbientEnabled.value) {
          applyBatterySaverPolicy()
        } else if (!enabled && ambientWasOnBattery && _isAmbientEnabled.value) {
          restoreFromBatterySaver()
        }
      }
    }

    viewModelScope.launch {
      sheetShown.collect { shownSheet ->
        if (shownSheet == Sheets.Playlist) {
          refreshPlaylistItems(forceMetadata = true)
        } else {
          playlistMetadataJob?.cancel()
        }
      }
    }

    setupCustomButtons()
  }

  fun onMpvCoreInitialized() {
    _isMpvCoreReady.value = true
    scheduleAmbientUpdate(0)
    startMpvStateCollectors()
    isMpvReadyForCustomButtons = true
    reloadCustomButtonsScript("mpv_core_initialized")
    startAndroidSystemInfoBridge()
  }

  /** Stops every ViewModel path that can read or write libmpv during native teardown. */
  fun onMpvCoreStopping() {
    disableAmbientShader()
    _isMpvCoreReady.value = false
    isMpvReadyForCustomButtons = false
    mpvStateCollectorsJob?.cancel()
    mpvStateCollectorsJob = null
    androidSystemInfoBridgeJob?.cancel()
    androidSystemInfoBridgeJob = null
    customButtonsSetupJob?.cancel()
    _paused.value = null
    _pos.value = null
    _duration.value = null
    _volumeBoostCap.value = null
    _precisePosition.value = 0f
    _preciseDuration.value = 0f
  }

  private fun startMpvStateCollectors() {
    if (mpvStateCollectorsJob?.isActive == true) return
    mpvStateCollectorsJob =
      viewModelScope.launch(playbackStateDispatcher) {
        launch { PlaybackSession.propBoolean["pause"].collect { _paused.value = it } }
        launch { PlaybackSession.propInt["time-pos"].collect { _pos.value = it } }
        launch { PlaybackSession.propInt["duration"].collect { _duration.value = it } }
        launch { PlaybackSession.propInt["volume-max"].collect { _volumeBoostCap.value = it } }
        launch {
          PlaybackSession.queue
            .map { it.repeatMode }
            .distinctUntilChanged()
            .collect { _repeatMode.value = it }
        }
      }
  }

  private fun startAndroidSystemInfoBridge() {
    if (androidSystemInfoBridgeJob?.isActive == true) return

    val appContext = appContext.applicationContext
    androidSystemInfoBridgeJob =
      viewModelScope.launch(playbackStateDispatcher) {
        while (isActive) {
          publishAndroidBatteryState(appContext)
          delay(30_000L)
        }
      }
  }

  private fun publishAndroidBatteryState(context: Context) {
    runCatching {
      val state = readAndroidBatteryState(context)
      PlaybackSession.setPropertyInt("user-data/android/battery-level", state.level)
      PlaybackSession.setPropertyBoolean("user-data/android/battery-charging", state.charging)
      PlaybackSession.setPropertyBoolean("user-data/android/battery-plugged", state.plugged)
      onBatteryStateChanged(state.charging)
    }.onFailure { error ->
      Log.w(TAG, "Failed to publish Android battery properties", error)
    }
  }

  private data class AndroidBatteryState(
    val level: Int,
    val charging: Boolean,
    val plugged: Boolean,
  )

  private fun readAndroidBatteryState(context: Context): AndroidBatteryState {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val intentLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val propertyLevel =
      batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
    val fallbackLevel =
      if (intentLevel >= 0 && scale > 0) {
        ((intentLevel * 100f) / scale).roundToInt().coerceIn(0, 100)
      } else {
        -1
      }
    val status =
      batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        ?: BatteryManager.BATTERY_STATUS_UNKNOWN
    val pluggedExtra = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return AndroidBatteryState(
      level = propertyLevel ?: fallbackLevel,
      charging = charging,
      plugged = pluggedExtra != 0,
    )
  }

  fun onVideoLoadStarted() {
    introLookupJob?.cancel()
    autoCropJob?.cancel()
    autoCropJob = null
    autoCropReadinessJob?.cancel()
    autoCropReadinessJob = null
    autoCropAnalyzedGeneration = -1L
    if (autoCropApplied || playerPreferences.autoCropBlackBars.get()) {
      clearAutoCropProperty()
    }
    _autoCropState.value = AutoCropState.IDLE
    // PlaybackSession is process-wide, while this ViewModel can be recreated. Clear both halves of
    // the timeline so a stopped file's last position cannot be rendered beside the incoming file's
    // reset 00:00 duration while mpv is still opening it.
    _pos.value = null
    _duration.value = null
    _precisePosition.value = 0f
    _preciseDuration.value = 0f
    chapterDerivedSegments = emptyList()
    introDbSegments = emptyList()
    skipSegmentsSnapshot = emptyList()
    _skipSegments.value = emptyList()
    _currentSkippableSegment.value = null
    _showSkipChipAuto.value = false
    skippedSegments.clear()
    pendingIntroLookupTitle = currentMediaTitle.takeIf { it.isNotBlank() }
    _videoOpenAnimationState.update {
      it.copy(
        loadToken = it.loadToken + 1,
        isWaitingForVideo = true,
      )
    }
  }

  fun onVideoLoadCompleted() {
    _videoOpenAnimationState.update { current ->
      if (current.isWaitingForVideo) {
        current.copy(isWaitingForVideo = false)
      } else {
        current
      }
    }
    applyEqualizerMpvFilters()
    scheduleAutoCropAnalysis()
  }

  private fun setupCustomButtons() {
    customButtonsSetupJob?.cancel()
    customButtonsSetupJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val buttons = mutableListOf<CustomButtonState>()
          val scriptBodies = linkedMapOf<CustomButtonScriptLanguage, StringBuilder>()
          val jsonString = playerPreferences.customButtons.get()
          if (jsonString.isNotBlank()) {
            try {
              // Try new slot-based format first
              val slotsData = json.decodeFromString<app.infinity.mpvz.ui.preferences.CustomButtonSlots>(jsonString)
              slotsData.slots.forEachIndexed { index, btn ->
                if (btn != null && btn.enabled) {
                  val safeId = btn.id.replace("-", "_")
                  val isLeft = index < 4 // Slots 0-3 are left, 4-7 are right
                  processButton(
                    originalId = btn.id,
                    safeId = safeId,
                    label = btn.title,
                    command = btn.content,
                    longPressCommand = btn.longPressContent,
                    onStartup = btn.onStartup,
                    language = btn.scriptLanguage,
                    scriptBuilder = scriptBodies.getOrPut(btn.scriptLanguage) { StringBuilder() },
                    isLeft = isLeft,
                    uiList = buttons,
                  )
                }
              }
            } catch (e: Exception) {
              // Fallback to old format for backward compatibility
              try {
                val customButtonsList =
                  json.decodeFromString<List<app.infinity.mpvz.ui.preferences.CustomButton>>(
                    jsonString,
                  )
                customButtonsList.forEachIndexed { index, btn ->
                  val safeId = btn.id.replace("-", "_")
                  val isLeft = index < 4 // First 4 are left buttons, rest are right
                  processButton(
                    originalId = btn.id,
                    safeId = safeId,
                    label = btn.title,
                    command = btn.content,
                    longPressCommand = btn.longPressContent,
                    onStartup = btn.onStartup,
                    language = btn.scriptLanguage,
                    scriptBuilder = scriptBodies.getOrPut(btn.scriptLanguage) { StringBuilder() },
                    isLeft = isLeft,
                    uiList = buttons,
                  )
                }
              } catch (e2: Exception) {
                e2.printStackTrace()
              }
            }
          }

          _customButtons.value = buttons

          val generatedPaths = mutableMapOf<CustomButtonScriptLanguage, String>()
          val scriptsDir = File(appContext.filesDir, "scripts")
          if (!scriptsDir.exists()) scriptsDir.mkdirs()

          scriptBodies.forEach { (language, bodyBuilder) ->
            val rawScriptContent = bodyBuilder.toString()
            if (rawScriptContent.isBlank()) return@forEach

            val target = customButtonScriptTargetsByLanguage[language] ?: return@forEach
            val scriptVersion = rawScriptContent.md5()
            val scriptContent = buildCustomButtonsScript(rawScriptContent, scriptVersion, target)

            val file = File(scriptsDir, target.fileName)
            file.writeText(scriptContent)
            generatedPaths[language] = file.absolutePath
          }

          customButtonsScriptPaths = generatedPaths.toMap()
          deleteCustomButtonsScriptFiles(activePaths = generatedPaths.values.toSet())
          withContext(Dispatchers.Main) {
            customButtonScriptTargets
              .filter { it.language !in generatedPaths.keys }
              .forEach(::deactivateCustomButtonsScript)
          }

          if (generatedPaths.isNotEmpty()) {
            if (isMpvReadyForCustomButtons) {
              customButtonsLoadMutex.withLock {
                withContext(Dispatchers.Main) {
                  deactivateLegacyCustomButtonsScript()
                }
                generatedPaths.forEach { (language, path) ->
                  val target = customButtonScriptTargetsByLanguage[language] ?: return@forEach
                  val loaded =
                    withContext(Dispatchers.Main) {
                      loadCustomButtonsScript(File(path), target)
                    }
                  if (!loaded) {
                    android.util.Log.w("PlayerViewModel", "Failed to load ${target.fileName}")
                  }
                }
              }
            } else {
              android.util.Log.d("PlayerViewModel", "Deferring custom button scripts until MPV is ready")
            }
          } else {
            customButtonsScriptPaths = emptyMap()
            deleteCustomButtonsScriptFiles()
            withContext(Dispatchers.Main) {
              customButtonScriptTargets.forEach(::deactivateCustomButtonsScript)
              deactivateLegacyCustomButtonsScript()
            }
          }
        } catch (e: Exception) {
          android.util.Log.e("PlayerViewModel", "Error setting up custom buttons", e)
        }
      }
  }

  private fun reloadCustomButtonsScript(reason: String) {
    if (!isMpvReadyForCustomButtons) return

    viewModelScope.launch(Dispatchers.IO) {
      var rebuildNeeded = false
      customButtonsLoadMutex.withLock {
        val scriptPaths = customButtonsScriptPaths
        if (scriptPaths.isEmpty()) return@withLock

        withContext(Dispatchers.Main) {
          deactivateLegacyCustomButtonsScript()
        }

        for ((language, scriptPath) in scriptPaths) {
          val target = customButtonScriptTargetsByLanguage[language] ?: continue
          val isLoaded =
            withContext(Dispatchers.Main) {
              isCustomButtonsScriptLoaded(target)
            }
          if (isLoaded) continue

          val file = File(scriptPath)
          if (!file.exists()) {
            android.util.Log.w("PlayerViewModel", "${target.fileName} missing during $reason, rebuilding")
            rebuildNeeded = true
            break
          }

          val loaded =
            withContext(Dispatchers.Main) {
              loadCustomButtonsScript(file, target)
            }
          if (!loaded) {
            android.util.Log.w("PlayerViewModel", "${target.fileName} load failed during $reason")
          }
        }
      }
      if (rebuildNeeded) {
        setupCustomButtons()
      }
    }
  }

  private fun isCustomButtonsScriptLoaded(target: CustomButtonScriptTarget): Boolean =
    runCatching { PlaybackSession.getPropertyString(target.loadedFlagProperty) == "1" }
      .getOrDefault(false)

  private fun loadCustomButtonsScript(
    file: File,
    target: CustomButtonScriptTarget,
  ): Boolean {
    runCatching { PlaybackSession.setPropertyString(target.loadedFlagProperty, "0") }

    return runCatching {
      PlaybackSession.command("load-script", file.absolutePath)
      true
    }.getOrElse {
      android.util.Log.w("PlayerViewModel", "load-script failed for ${target.fileName}: ${it.message}")
      false
    }
  }

  private fun deactivateCustomButtonsScript(target: CustomButtonScriptTarget) {
    runCatching {
      PlaybackSession.setPropertyString(target.loadedFlagProperty, "0")
      PlaybackSession.setPropertyString(target.versionProperty, "")
    }
  }

  private fun deactivateLegacyCustomButtonsScript() {
    runCatching {
      PlaybackSession.setPropertyString(legacyCustomButtonsLoadedFlagProperty, "0")
      PlaybackSession.setPropertyString(legacyCustomButtonsVersionProperty, "")
    }
  }

  private fun deleteCustomButtonsScriptFiles(activePaths: Set<String> = emptySet()) {
    runCatching {
      val activeNames = activePaths.map { File(it).name }.toSet()
      val scriptsDir = File(appContext.filesDir, "scripts")
      customButtonScriptTargets.forEach { target ->
        val file = File(scriptsDir, target.fileName)
        if (file.exists() && file.name !in activeNames) {
          file.delete()
        }
      }
    }
  }

  private fun buildCustomButtonsScript(
    body: String,
    version: String,
    target: CustomButtonScriptTarget,
  ): String =
    when (target.language) {
      CustomButtonScriptLanguage.LUA ->
        buildString {
          appendLine("local loaded_flag_property = '${target.loadedFlagProperty.toScriptLiteral()}'")
          appendLine("local version_property = '${target.versionProperty.toScriptLiteral()}'")
          appendLine("local instance_version = '${version.toScriptLiteral()}'")
          appendLine("if mp.get_property_native(version_property) == instance_version then")
          appendLine("    mp.set_property_native(loaded_flag_property, '1')")
          appendLine("    return")
          appendLine("end")
          appendLine("mp.set_property_native(version_property, instance_version)")
          appendLine("mp.set_property_native(loaded_flag_property, '1')")
          appendLine("local function is_active_instance()")
          appendLine("    return mp.get_property_native(version_property) == instance_version")
          appendLine("end")
          appendLine()
          append(body)
        }

      CustomButtonScriptLanguage.JS ->
        buildString {
          appendLine("var loadedFlagProperty = '${target.loadedFlagProperty.toScriptLiteral()}';")
          appendLine("var versionProperty = '${target.versionProperty.toScriptLiteral()}';")
          appendLine("var instanceVersion = '${version.toScriptLiteral()}';")
          appendLine("if (mp.get_property_native(versionProperty) === instanceVersion) {")
          appendLine("    mp.set_property_native(loadedFlagProperty, '1');")
          appendLine("} else {")
          appendLine("    mp.set_property_native(versionProperty, instanceVersion);")
          appendLine("    mp.set_property_native(loadedFlagProperty, '1');")
          appendLine("    var isActiveInstance = function() {")
          appendLine("        return mp.get_property_native(versionProperty) === instanceVersion;")
          appendLine("    };")
          appendLine()
          append(body.prependIndent("    "))
          appendLine()
          appendLine("}")
        }
    }

  fun callCustomButton(id: String) {
    val safeId = id.replace("-", "_")
    PlaybackSession.command("script-message", "call_button_$safeId")
  }

  fun callCustomButtonLongPress(id: String) {
    val safeId = id.replace("-", "_")
    PlaybackSession.command("script-message", "call_button_long_$safeId")
  }

  private fun processButton(
    originalId: String,
    safeId: String,
    label: String,
    command: String,
    longPressCommand: String,
    onStartup: String,
    language: CustomButtonScriptLanguage,
    scriptBuilder: StringBuilder,
    isLeft: Boolean,
    uiList: MutableList<CustomButtonState>,
  ) {
    if (label.isNotBlank()) {
      uiList.add(CustomButtonState(originalId, label, isLeft))

      // On Startup Code
      if (onStartup.isNotBlank()) {
        scriptBuilder.append(onStartup)
        scriptBuilder.append("\n")
      }

      // Click Handler
      if (command.isNotBlank()) {
        scriptBuilder.appendButtonHandler(
          functionName = "button_$safeId",
          messageName = "call_button_$safeId",
          command = command,
          language = language,
        )
      }

      // Long Press Handler
      if (longPressCommand.isNotBlank()) {
        scriptBuilder.appendButtonHandler(
          functionName = "button_long_$safeId",
          messageName = "call_button_long_$safeId",
          command = longPressCommand,
          language = language,
        )
      }
    }
  }

  private fun StringBuilder.appendButtonHandler(
    functionName: String,
    messageName: String,
    command: String,
    language: CustomButtonScriptLanguage,
  ) {
    when (language) {
      CustomButtonScriptLanguage.LUA -> {
        append(
          """
          function $functionName()
              if not is_active_instance() then return end
              $command
          end
          mp.register_script_message('$messageName', $functionName)
          """.trimIndent(),
        )
      }
      CustomButtonScriptLanguage.JS -> {
        append(
          """
          var $functionName = function() {
              if (!isActiveInstance()) return;
              $command
          };
          mp.register_script_message('$messageName', $functionName);
          """.trimIndent(),
        )
      }
    }
    append("\n")
  }

  private fun String.toScriptLiteral(): String = replace("\\", "\\\\").replace("'", "\\'")

  // Cached values
  private val doubleTapToSeekDuration by lazy { gesturePreferences.doubleTapToSeekDuration.get() }
  private val inputMethodManager by lazy {
    appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
  }

  // Seek coalescing for smooth performance
  private var pendingSeekOffset: Int = 0
  private var seekCoalesceJob: Job? = null
  private val seekPreviewLock = Any()
  private var pendingSeekPreviewPosition: Float? = null
  private var seekPreviewJob: Job? = null
  private var frameSeekJob: Job? = null

  private companion object {
    const val TAG = "PlayerViewModel"
    const val AUTO_CROP_SAMPLE_COUNT = 7
    const val AUTO_CROP_MIN_VALID_SAMPLES = 3
    const val AUTO_CROP_THUMBNAIL_SIZE = 640
    const val AUTO_CROP_READY_TIMEOUT_MS = 15_000L
    const val AUTO_CROP_CACHE_CAPACITY = 20
    const val AUTO_SHOW_SKIP_CHIP_DURATION = 10.0
    const val SEEK_COALESCE_DELAY_MS = 60L
    const val RELATIVE_SEEK_EOF_GUARD_SECONDS = 0.25
    const val SEEK_TARGET_TOLERANCE_SECONDS = 0.05
    const val PREVIEW_SEEK_INTERVAL_MS = 100L
    const val FRAME_SEEK_POLL_INTERVAL_MS = 10L
    const val FRAME_SEEK_MIN_SETTLE_POLLS = 8
    const val FRAME_SEEK_MAX_SETTLE_POLLS = 75
    const val FRAME_SEEK_EXACT_PASSES = 3
    const val FRAME_SEEK_MAX_CORRECTION_STEPS = 12
    const val FRAME_SEEK_CORRECTION_INTERVAL_MS = 24L
    const val FRAME_SEEK_CORRECTION_SETTLE_MS = 40L
    val QUALITY_HEIGHT_REGEX = Regex("""(?i)(\d{3,4})p""")
    const val NATIVE_LINEAR_HDR_YOUTUBE_BLUR_RADIUS = 100.0
    val MPV_ONLY_PSEUDO_PROTOCOLS =
      setOf("fd", "fdclose", "edl", "memory", "null", "av", "lavf", "archive", "slice", "mf", "hex", "bd", "dvd", "dvb")
    const val PLAYLIST_METADATA_PREFETCH_RADIUS = 40
    const val PLAYLIST_METADATA_PREFETCH_LIMIT = 120
    const val INTRO_MARKER_CACHE_PREFS = "intro_marker_cache"
    const val INTRO_MARKER_CACHE_PREFIX = "intro_marker:v2:"
    const val INTRO_MARKER_CACHE_MAX_ENTRIES = 200
    const val INTRO_MARKER_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    const val INTRO_MARKER_CACHE_LOADED = "loaded"
    const val INTRO_MARKER_CACHE_NO_SEGMENTS = "no_segments"
    const val INTRO_MARKER_CACHE_UNRESOLVED = "unresolved"
    val VALID_SUBTITLE_EXTENSIONS =
      setOf(
        // Common & modern
        "srt",
        "vtt",
        "ass",
        "ssa",
        // DVD / Blu-ray
        "sub",
        "idx",
        "sup",
        // Streaming / XML / Professional
        "xml",
        "ttml",
        "dfxp",
        "itt",
        "ebu",
        "imsc",
        "usf",
        // Online platforms
        "sbv",
        "srv1",
        "srv2",
        "srv3",
        "json",
        // Legacy & niche
        "sami",
        "smi",
        "mpl",
        "pjs",
        "stl",
        "rt",
        "psb",
        "cap",
        // Broadcast captions
        "scc",
        "vttx",
        // Karaoke / lyrics
        "lrc",
        "krc",
        // Fallback / raw text
        "txt",
        "pgs",
      )
  }

  // ==================== Timer ====================

  fun startTimer(seconds: Int) {
    timerJob?.cancel()
    _remainingTime.value = seconds
    if (seconds < 1) return

    timerJob =
      viewModelScope.launch {
        for (time in seconds downTo 0) {
          _remainingTime.value = time
          delay(1000)
        }
        if (host.isNativeEngineActive()) host.nativePause()
        else PlaybackSession.setPropertyBoolean("pause", true)
        showToast(appContext.getString(R.string.toast_sleep_timer_ended))
      }
  }

  // ==================== Decoder ====================

  // ==================== Audio/Subtitle Management ====================

  fun addAudio(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        if (uri.scheme == "content") {
          try {
            appContext.contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          } catch (e: SecurityException) {
            android.util.Log.i(
              "PlayerViewModel",
              "Persistent permission not taken for audio $uri",
            )
          }
        }

        val path =
          uri.resolveUri(appContext)
            ?: return@launch withContext(Dispatchers.Main) {
              showToast("Failed to load audio file: Invalid URI")
            }

        val title = getFileNameFromUri(uri)?.substringBeforeLast(".")?.ifBlank { null }

        withContext(Dispatchers.Main) {
          if (title != null) {
            PlaybackSession.command("audio-add", path, "cached", title)
          } else {
            PlaybackSession.command("audio-add", path, "cached")
          }
          showToast("Audio track added")
        }
      }.onFailure { e ->
        withContext(Dispatchers.Main) {
          showToast("Failed to load audio: ${e.message}")
        }
        android.util.Log.e("PlayerViewModel", "Error adding audio", e)
      }
    }
  }

  fun addSubtitle(
    uri: Uri,
    select: Boolean = true,
    silent: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      addSubtitleSuspend(uri, select, silent)
    }
  }

  suspend fun addSubtitleSuspend(
    uri: Uri,
    select: Boolean = true,
    silent: Boolean = false,
  ) {
    subtitleAddMutex.withLock {
      // This method runs on Dispatchers.IO. Use the ViewModel state flow instead of asking the
      // Media3 host, whose ExoPlayer instance is main-thread confined.
      val nativeActive = nativeEngineActive.value
      val uriString = uri.toString()
      val nativeAlreadyRegistered =
        nativeExternalSubtitleTracks.value.any { it.externalFilename == uriString }
      if (_externalSubtitles.contains(uriString) && (!nativeActive || nativeAlreadyRegistered)) {
        android.util.Log.d("PlayerViewModel", "Subtitle already tracked, skipping: $uriString")
        return@withLock
      }

      runCatching {
        val fileName = getFileNameFromUri(uri) ?: "subtitle.srt"

        if (!isValidSubtitleFile(fileName)) {
          return@withLock withContext(Dispatchers.Main) {
            showToast("Invalid subtitle file format")
          }
        }

        // Take persistent URI permission for content:// URIs
        if (uri.scheme == "content") {
          try {
            appContext.contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          } catch (e: SecurityException) {
            // Permission already granted, not available, or not needed (e.g. from tree).
            android.util.Log.i(
              "PlayerViewModel",
              "Persistent permission not taken for $uri (may already have it via tree)",
            )
          }
        }

        val mpvPath = uri.resolveUri(appContext) ?: uri.toString()
        val mode = if (select) "select" else "auto"

        if (nativeActive) {
          val attached = withContext(Dispatchers.Main) { host.nativeAddSubtitle(uri, select) }
          if (!attached) throw Exception("Native subtitle renderer is not ready")
          if (!_externalSubtitles.contains(uriString)) _externalSubtitles.add(uriString)
          registerNativeExternalSubtitle(uri, fileName, select)
          if (!silent) {
            withContext(Dispatchers.Main) { showToast("$fileName added") }
          }
          return@withLock
        }

        // Check if MPV already auto-loaded this subtitle (prevents duplication)
        val existingTrack = subtitleTracks.value.find { it.externalFilename == mpvPath }
        if (existingTrack != null) {
          android.util.Log.d("PlayerViewModel", "Subtitle already loaded by MPV, skipping sub-add: $mpvPath")
          if (select) {
            withContext(Dispatchers.Main) {
              runCatching { PlaybackSession.setPropertyInt("sid", existingTrack.id) }
            }
          }
          // Still track it in _externalSubtitles if it's not there
          if (!_externalSubtitles.contains(uriString)) {
            _externalSubtitles.add(uriString)
          }
          return@withLock
        }

        // Store mapping for reliable physical deletion later
        mpvPathToUriMap[mpvPath] = uri.toString()

        withContext(Dispatchers.Main) {
          PlaybackSession.command("sub-add", mpvPath, mode)
        }

        // Track external subtitle URI for persistence
        if (!_externalSubtitles.contains(uriString)) {
          _externalSubtitles.add(uriString)
        }

        val displayName = fileName.take(30).let { if (fileName.length > 30) "$it..." else it }
        if (!silent) {
          withContext(Dispatchers.Main) {
            showToast("$displayName added")
          }
        }
      }.onFailure { error ->
        android.util.Log.e(
          "PlayerViewModel",
          "Failed to load subtitle uri=$uri native=$nativeActive",
          error,
        )
        if (!silent) {
          withContext(Dispatchers.Main) {
            showToast("Failed to load subtitle: ${error.message ?: "unknown error"}")
          }
        }
      }
    }
  }



  private fun scanLocalSubtitles(mediaTitle: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val saveFolderUri = subtitlesPreferences.subtitleSaveFolder.get()
      if (saveFolderUri.isBlank()) return@launch

      var addedCount = 0
      try {
        val sanitizedTitle = MediaInfoParser.parse(mediaTitle).title
        val fullTitle = mediaTitle.substringBeforeLast(".")
        val checksumTitle = ChecksumUtils.getCRC32(mediaTitle)
        val parentDirs = resolveSubtitleLookupDirectories(appContext, saveFolderUri)
        if (parentDirs.isEmpty()) return@launch

        // Scan potential folder names for compatibility: checksum, full, and sanitized
        // Use seenUris so the same file found via multiple folder name variants isn't double-added
        val seenUris = mutableSetOf<String>()
        parentDirs.forEach { parentDir ->
          listOf(checksumTitle, fullTitle, sanitizedTitle).distinct().forEach { folderName ->
            val movieDir = parentDir.findFile(folderName) ?: return@forEach
            if (movieDir.isDirectory) {
              movieDir.listFiles().forEach { file ->
                val uriStr = file.uri.toString()
                if (file.isFile && isValidSubtitleFile(file.name ?: "") && seenUris.add(uriStr)) {
                  // Don't auto-select during scan, just make available.
                  addSubtitle(file.uri, select = false, silent = true)
                  addedCount++
                }
              }
            }
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("PlayerViewModel", "Error scanning local subtitles: ${e.message}", e)
      }

      // Auto-select the first external subtitle if the video has no embedded subtitle track active
      if (addedCount > 0) {
        // Give MPV time to register the sub-add commands
        kotlinx.coroutines.delay(300)
        withContext(Dispatchers.Main) {
          val activeSid = getTrackSelectionId("sid")
          if (activeSid == 0) {
            val firstExternal = subtitleTracks.value.firstOrNull { it.external == true }
            if (firstExternal != null) {
              runCatching { setTrackSelectionId("sid", firstExternal.id) }
            }
          }
        }
      }
    }
  }

  fun setMediaTitle(mediaTitle: String) {
    if (currentMediaTitle != mediaTitle) {
      currentMediaTitle = mediaTitle
      lastAutoSelectedMediaTitle = null
      introLookupJob?.cancel()
      pendingIntroLookupTitle = null
      videoHashJob?.cancel()
      // Clear external subtitles when media changes
      _externalSubtitles.clear()
      nativeExternalSubtitleTracks.value = emptyList()
      // Reset subtitle hash when media changes.
      _videoHash.value = null
      scanLocalSubtitles(mediaTitle)

      skippedSegments.clear()
      chapterDerivedSegments = emptyList()
      introDbSegments = emptyList()
      _skipSegments.value = emptyList()
      skipSegmentsSnapshot = emptyList()
      _currentSkippableSegment.value = null
      _showSkipChipAuto.value = false
      _introDbStatus.value =
        if (playerPreferences.enableIntroDb.get()) {
          IntroDbStatus()
        } else {
          IntroDbStatus(
            state = IntroDbStatusState.DISABLED,
            message = "Online skip markers are disabled",
          )
        }
      lookupIntroSegments(mediaTitle)
      refreshChapterDerivedSegments(chapters.value)

      // Reset Video Pan
      if (_videoPanX.value != 0f || _videoPanY.value != 0f) {
        _videoPanX.value = 0f
        _videoPanY.value = 0f
        runCatching {
          PlaybackSession.setPropertyDouble("video-pan-x", 0.0)
          PlaybackSession.setPropertyDouble("video-pan-y", 0.0)
        }
      }
      // ---------------------------------------------------
    }
  }

  private fun maybeAutoSkipIntro(positionSeconds: Double) {
    val activeSegment =
      skipSegmentsSnapshot.firstOrNull { segment ->
        positionSeconds >= segment.startSeconds &&
          positionSeconds < segment.endSeconds &&
          segment !in skippedSegments
      }

    val showChip =
      activeSegment != null &&
        (positionSeconds - activeSegment.startSeconds) < AUTO_SHOW_SKIP_CHIP_DURATION
    if (_currentSkippableSegment.value != activeSegment) {
      _currentSkippableSegment.value = activeSegment
    }
    if (_showSkipChipAuto.value != showChip) {
      _showSkipChipAuto.value = showChip
    }

    if (paused == true || activeSegment == null) return
    val autoSkipEnabled =
      when (activeSegment.type) {
        SkipSegmentType.INTRO -> playerPreferences.autoSkipIntro.get()
        SkipSegmentType.RECAP -> playerPreferences.autoSkipIntro.get()
        SkipSegmentType.OUTRO -> playerPreferences.autoSkipOutro.get()
        SkipSegmentType.CREDITS -> playerPreferences.autoSkipOutro.get()
        SkipSegmentType.PREVIEW -> playerPreferences.autoSkipOutro.get()
      }
    if (!autoSkipEnabled) return

    skippedSegments += activeSegment
    _showSkipChipAuto.value = false
    seekPastSkipSegment(activeSegment, auto = true)
  }

  fun skipActiveSegment() {
    val segment = _currentSkippableSegment.value ?: return
    skippedSegments += segment
    _showSkipChipAuto.value = false
    seekPastSkipSegment(segment, auto = false)
  }

  private fun seekPastSkipSegment(
    segment: SkipSegment,
    auto: Boolean,
  ) {
    val seekTarget = SkipMarkerResolver.seekTarget(segment, currentDurationSeconds())
    if (host.isNativeEngineActive()) {
      // Skip markers were previously always written to MPV's time-pos. When Native Media3 was
      // active that changed an inactive renderer, so the chip appeared but playback did not move.
      host.nativeSeekTo((seekTarget * 1000.0).toLong().coerceAtLeast(0L))
    } else {
      PlaybackSession.setPropertyDouble("time-pos", seekTarget)
    }
    showToast(if (auto) "${segment.label} (auto)" else segment.label)
  }

  private fun mergeSkipSegments() {
    val merged = SkipMarkerResolver.merge(resolveIntroDbSegments() + chapterDerivedSegments)
    skipSegmentsSnapshot = merged
    _skipSegments.value = merged
  }

  private fun currentDurationSeconds(): Double =
    (_preciseDuration.value.takeIf { it > 0f } ?: (duration ?: 0).toFloat()).toDouble()

  private fun resolveIntroDbSegments(): List<SkipSegment> {
    val durationSec = currentDurationSeconds()
    return SkipMarkerResolver.resolveProviderSegments(
      markers =
        introDbSegments.map { segment ->
          ProviderSkipMarker(
            type = segment.segmentType,
            startSeconds = segment.startSecondsOrNull,
            endSeconds = segment.endSecondsOrNull,
          )
        },
      durationSeconds = durationSec,
      source = introDbSourceKey,
    )
  }

  private fun lookupIntroSegments(mediaTitle: String) {
    if (!playerPreferences.enableIntroDb.get()) {
      pendingIntroLookupTitle = null
      introDbSegments = emptyList()
      mergeSkipSegments()
      return
    }

    val durationSec = currentDurationSeconds()
    if (durationSec <= 0) {
      pendingIntroLookupTitle = mediaTitle
      return
    }

    pendingIntroLookupTitle = null

    val lookupKey = mediaTitle
    val provider = playerPreferences.introSegmentProvider.get()
    val lookupHints = host.currentPlayerLookupHints()
    val lookupRequest =
      IntroDbLookupRequest(
        mediaTitle = mediaTitle,
        canonicalTitle = lookupHints.canonicalTitle,
        lookupHint = host.currentMediaLookupHint(),
        imdbId = lookupHints.imdbId,
        tmdbId = lookupHints.tmdbId,
        mediaType = lookupHints.mediaType,
        season = lookupHints.season,
        episode = lookupHints.episode,
        provider = provider,
      )
    val cacheKey = buildIntroMarkerCacheKey(lookupRequest)

    introDbSourceKey = provider.sourceKey
    readIntroMarkerCacheEntry(cacheKey)?.let { cachedEntry ->
      applyIntroMarkerCacheEntry(provider, cachedEntry)
      mergeSkipSegments()
      showProviderStatusFeedback(_introDbStatus.value.message)
      return
    }
    _introDbStatus.value =
      IntroDbStatus(
        state = IntroDbStatusState.LOOKING_UP,
        message = "${provider.displayName}: matching title",
      )

    introLookupJob?.cancel()
    introLookupJob =
      viewModelScope.launch {
        val outcome =
          if (provider == IntroSegmentProvider.HYBRID) {
            val providers =
              listOf(
                IntroSegmentProvider.INTRO_DB,
                IntroSegmentProvider.THE_INTRO_DB,
                IntroSegmentProvider.ANI_SKIP,
                IntroSegmentProvider.ANIME_SKIP,
              )
            val receivedOutcomes =
              providers
                .map { lookupProvider ->
                  async(Dispatchers.IO) {
                    try {
                      introDbRepository.lookupSegments(lookupRequest.copy(provider = lookupProvider))
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                      throw cancellation
                    } catch (error: Exception) {
                      IntroDbLookupOutcome.Error(error.message ?: "unknown", lookupProvider)
                    }
                  }
                }.awaitAll()
            val loadedOutcomes = receivedOutcomes.filterIsInstance<IntroDbLookupOutcome.Loaded>()

            if (loadedOutcomes.isNotEmpty()) {
              val primary = loadedOutcomes.first()
              IntroDbLookupOutcome.Loaded(
                imdbId = primary.imdbId,
                segments = loadedOutcomes.flatMap(IntroDbLookupOutcome.Loaded::segments).distinct(),
                source = primary.source,
                provider = IntroSegmentProvider.HYBRID,
              )
            } else {
              val fallbackOutcome =
                receivedOutcomes.firstOrNull { it is IntroDbLookupOutcome.NoSegments }
                  ?: receivedOutcomes.firstOrNull { it is IntroDbLookupOutcome.Unresolved }
                  ?: receivedOutcomes.firstOrNull()

              if (fallbackOutcome != null) {
                when (fallbackOutcome) {
                  is IntroDbLookupOutcome.NoSegments ->
                    IntroDbLookupOutcome.NoSegments(
                      fallbackOutcome.imdbId,
                      fallbackOutcome.source,
                      IntroSegmentProvider.HYBRID,
                    )
                  is IntroDbLookupOutcome.Unresolved ->
                    IntroDbLookupOutcome.Unresolved(
                      fallbackOutcome.title,
                      IntroSegmentProvider.HYBRID,
                    )
                  is IntroDbLookupOutcome.Error ->
                    IntroDbLookupOutcome.Error(
                      fallbackOutcome.reason,
                      IntroSegmentProvider.HYBRID,
                    )
                  else -> fallbackOutcome
                }
              } else {
                IntroDbLookupOutcome.Error("No outcomes", IntroSegmentProvider.HYBRID)
              }
            }
          } else {
            introDbRepository.lookupSegments(lookupRequest)
          }

        if (currentMediaTitle != lookupKey) return@launch

        applyIntroDbOutcome(outcome)
        cacheIntroDbOutcome(cacheKey, outcome)
        mergeSkipSegments()
        showProviderStatusFeedback(_introDbStatus.value.message)
      }
  }

  private fun showProviderStatusFeedback(message: String) {
    if (message.isBlank() || !playerPreferences.showProviderStatusOverlay.get()) return
    playerUpdate.value = PlayerUpdates.ProviderStatusText(message)
  }

  private fun showProviderStatusToast(message: String) {
    if (message.isBlank() || !playerPreferences.showProviderStatusOverlay.get()) return
    showToast(message)
  }

  private fun checkPendingIntroLookup() {
    val pendingTitle = pendingIntroLookupTitle ?: return
    val durationSec = currentDurationSeconds()
    if (durationSec <= 0) return
    pendingIntroLookupTitle = null
    lookupIntroSegments(pendingTitle)
  }

  private fun buildIntroMarkerCacheKey(request: IntroDbLookupRequest): String =
    buildString {
      append(request.provider.sourceKey)
      append('|')
      append(request.lookupHint.orEmpty())
      append('|')
      append(request.mediaTitle)
      append('|')
      append(request.canonicalTitle.orEmpty())
      append('|')
      append(request.imdbId.orEmpty())
      append('|')
      append(request.tmdbId?.toString().orEmpty())
      append('|')
      append(request.mediaType.orEmpty())
      append('|')
      append(request.season?.toString().orEmpty())
      append('|')
      append(request.episode?.toString().orEmpty())
    }.md5()

  private fun readIntroMarkerCacheEntry(cacheKey: String): IntroMarkerCacheEntry? {
    val prefKey = INTRO_MARKER_CACHE_PREFIX + cacheKey
    val rawValue = introMarkerCachePrefs.getString(prefKey, null) ?: return null
    val entry =
      runCatching { json.decodeFromString<IntroMarkerCacheEntry>(rawValue) }
        .getOrElse {
          introMarkerCachePrefs.edit().remove(prefKey).apply()
          return null
        }

    if ((System.currentTimeMillis() - entry.cachedAtMs) > INTRO_MARKER_CACHE_TTL_MS) {
      introMarkerCachePrefs.edit().remove(prefKey).apply()
      return null
    }

    return entry
  }

  private fun cacheIntroDbOutcome(
    cacheKey: String,
    outcome: IntroDbLookupOutcome,
  ) {
    val cacheEntry =
      when (outcome) {
        is IntroDbLookupOutcome.Loaded ->
          IntroMarkerCacheEntry(
            providerSourceKey = outcome.provider.sourceKey,
            outcomeType = INTRO_MARKER_CACHE_LOADED,
            imdbId = outcome.imdbId,
            message = outcome.message,
            segments = outcome.segments,
          )

        is IntroDbLookupOutcome.NoSegments ->
          IntroMarkerCacheEntry(
            providerSourceKey = outcome.provider.sourceKey,
            outcomeType = INTRO_MARKER_CACHE_NO_SEGMENTS,
            imdbId = outcome.imdbId,
            message = outcome.message,
          )

        is IntroDbLookupOutcome.Unresolved ->
          IntroMarkerCacheEntry(
            providerSourceKey = outcome.provider.sourceKey,
            outcomeType = INTRO_MARKER_CACHE_UNRESOLVED,
            message = outcome.message,
          )

        is IntroDbLookupOutcome.Error -> null
      } ?: return

    introMarkerCachePrefs
      .edit()
      .putString(
        INTRO_MARKER_CACHE_PREFIX + cacheKey,
        json.encodeToString(IntroMarkerCacheEntry.serializer(), cacheEntry),
      ).apply()
    trimIntroMarkerCache()
  }

  private fun trimIntroMarkerCache() {
    val cacheEntries =
      introMarkerCachePrefs.all
        .mapNotNull { (key, value) ->
          if (!key.startsWith(INTRO_MARKER_CACHE_PREFIX) || value !is String) {
            return@mapNotNull null
          }
          val entry =
            runCatching { json.decodeFromString<IntroMarkerCacheEntry>(value) }.getOrNull()
              ?: return@mapNotNull key to null
          key to entry
        }

    if (cacheEntries.size <= INTRO_MARKER_CACHE_MAX_ENTRIES) return

    val keysToRemove =
      cacheEntries
        .sortedBy { (_, entry) -> entry?.cachedAtMs ?: Long.MIN_VALUE }
        .take(cacheEntries.size - INTRO_MARKER_CACHE_MAX_ENTRIES)
        .map { it.first }

    introMarkerCachePrefs
      .edit()
      .apply {
        keysToRemove.forEach(::remove)
      }.apply()
  }

  private fun applyIntroMarkerCacheEntry(
    provider: IntroSegmentProvider,
    entry: IntroMarkerCacheEntry,
  ) {
    introDbSourceKey = entry.providerSourceKey
    when (entry.outcomeType) {
      INTRO_MARKER_CACHE_LOADED -> {
        introDbSegments = entry.segments
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.LOADED,
            message =
              cacheStatusMessage(
                provider,
                entry.message,
                "loaded ${entry.segments.size} marker${if (entry.segments.size == 1) "" else "s"}",
              ),
            imdbId = entry.imdbId,
            segmentCount = entry.segments.size,
          )
      }

      INTRO_MARKER_CACHE_NO_SEGMENTS -> {
        introDbSegments = emptyList()
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.NO_SEGMENTS,
            message = cacheStatusMessage(provider, entry.message, "no markers cached"),
            imdbId = entry.imdbId,
          )
      }

      else -> {
        introDbSegments = emptyList()
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.UNRESOLVED,
            message = cacheStatusMessage(provider, entry.message, "cached title match failed"),
          )
      }
    }
  }

  private fun applyIntroDbOutcome(outcome: IntroDbLookupOutcome) {
    when (outcome) {
      is IntroDbLookupOutcome.Loaded -> {
        introDbSegments = outcome.segments
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.LOADED,
            message = outcome.message,
            imdbId = outcome.imdbId,
            segmentCount = outcome.segments.size,
          )
      }

      is IntroDbLookupOutcome.NoSegments -> {
        introDbSegments = emptyList()
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.NO_SEGMENTS,
            message = outcome.message,
            imdbId = outcome.imdbId,
          )
      }

      is IntroDbLookupOutcome.Unresolved -> {
        introDbSegments = emptyList()
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.UNRESOLVED,
            message = outcome.message,
          )
      }

      is IntroDbLookupOutcome.Error -> {
        introDbSegments = emptyList()
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.ERROR,
            message = outcome.message,
          )
      }
    }
  }

  private fun cacheStatusMessage(
    provider: IntroSegmentProvider,
    message: String,
    fallback: String,
  ): String {
    val sourceMessage = message.ifBlank { "${provider.displayName}: $fallback" }
    return if (sourceMessage.contains("(cached)")) sourceMessage else "$sourceMessage (cached)"
  }

  private fun refreshChapterDerivedSegments(chapters: List<dev.vivvvek.seeker.Segment>) {
    if (!playerPreferences.detectIntroOutroFromChapters.get()) {
      chapterDerivedSegments = emptyList()
      mergeSkipSegments()
      return
    }
    val durationSec = currentDurationSeconds()
    if (durationSec <= 0.0 || chapters.isEmpty()) {
      chapterDerivedSegments = emptyList()
      mergeSkipSegments()
      return
    }

    val derived =
      SkipMarkerResolver.resolveChapters(
        chapters = chapters.map { ChapterSkipMarker(it.name, it.start.toDouble()) },
        durationSeconds = durationSec,
        classify = ::chapterTitleToType,
      )

    chapterDerivedSegments = derived
    mergeSkipSegments()
  }

  private fun chapterTitleToType(title: String?): SkipSegmentType? {
    val introKeywords =
      if (playerPreferences.customIntroKeywordsEnabled.get()) {
        SkipMarkerResolver.parseCustomKeywords(playerPreferences.customIntroKeywords.get())
      } else {
        introKeywordPatterns
      }

    val outroKeywords =
      if (playerPreferences.customOutroKeywordsEnabled.get()) {
        SkipMarkerResolver.parseCustomKeywords(playerPreferences.customOutroKeywords.get())
      } else {
        outroKeywordPatterns
      }

    return SkipMarkerResolver.classifyTitle(
      title = title,
      introKeywords = introKeywords,
      outroKeywords = outroKeywords,
      recapKeywords = recapKeywordPatterns,
      creditsKeywords = creditsKeywordPatterns,
      previewKeywords = previewKeywordPatterns,
    )
  }

  fun removeSubtitle(id: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      // Find the subtitle track info before removing
      val tracks = subtitleTracks.value
      val trackToRemove = tracks.firstOrNull { it.id == id }

      // If it's external, physically delete the file if we can find its URI
      if (trackToRemove?.external == true && trackToRemove.externalFilename != null) {
        val mpvPath = trackToRemove.externalFilename
        val originalUriString = mpvPathToUriMap[mpvPath] ?: mpvPath
        val uri = Uri.parse(originalUriString)

        val deleted = wyzieRepository.deleteSubtitleFile(uri)

        if (deleted) {
          _externalSubtitles.remove(originalUriString)
          mpvPathToUriMap.remove(mpvPath)
          withContext(Dispatchers.Main) {
            showToast("Subtitle deleted")
          }
        }
      }

      PlaybackSession.command("sub-remove", id.toString())
    }
  }

  // --- Media Search and Series Management ---

  private var mediaSearchJob: Job? = null

  private data class WyzieSearchPlan(
    val request: OnlineSubtitleSearchRequest?,
    val missingSelectionMessage: String? = null,
  )

  fun searchMedia(query: String) {
    mediaSearchJob?.cancel()
    if (query.isBlank()) {
      _mediaSearchResults.value = emptyList()
      return
    }

    mediaSearchJob =
      viewModelScope.launch {
        delay(300) // Debounce
        _isSearchingMedia.value = true
        wyzieRepository
          .searchMedia(query)
          .onSuccess { results ->
            _mediaSearchResults.value = results
          }.onFailure {
            // Silent failure for autocomplete, or optionally show toast(if someone is reading this if u need u can impelmen this in future )
          }
        _isSearchingMedia.value = false
      }
  }

  fun selectMedia(result: app.infinity.mpvz.repository.wyzie.WyzieTmdbResult) {
    _mediaSearchResults.value = emptyList() // Clear results after selection
    _onlineSubtitleSearchResults.value = emptyList() // Clear old subtitle results
    val fileInfo = MediaInfoParser.parse(currentMediaTitle)

    if (result.mediaType == "tv") {
      fetchTvShowDetails(
        id = result.id,
        preferredSeason = fileInfo.season,
        preferredEpisode = fileInfo.episode,
      )
    } else {
      searchSubtitles(result.title, year = result.releaseYear ?: fileInfo.year, tmdbId = result.id)
    }
  }

  private fun fetchTvShowDetails(
    id: Int,
    preferredSeason: Int? = null,
    preferredEpisode: Int? = null,
  ) {
    viewModelScope.launch {
      _isFetchingTvDetails.value = true
      wyzieRepository
        .getTvShowDetails(id)
        .onSuccess { details ->
          val validSeasons = details.seasons.filter { it.season_number > 0 }.sortedBy { it.season_number }
          _selectedTvShow.value = details.copy(seasons = validSeasons)
          _selectedSeason.value = null
          _seasonEpisodes.value = emptyList()
          val matchingSeason =
            preferredSeason?.let { wanted ->
              validSeasons.firstOrNull { it.season_number == wanted }
            }
          if (matchingSeason != null) {
            selectSeason(matchingSeason, preferredEpisode)
          }
        }.onFailure {
          showProviderStatusToast("Failed to load series details: ${it.message}")
        }
      _isFetchingTvDetails.value = false
    }
  }

  fun selectSeason(
    season: app.infinity.mpvz.repository.wyzie.WyzieSeason,
    preferredEpisode: Int? = null,
  ) {
    val tvShowId = _selectedTvShow.value?.id ?: return
    _selectedSeason.value = season

    viewModelScope.launch {
      _isFetchingEpisodes.value = true
      wyzieRepository
        .getSeasonEpisodes(tvShowId, season.season_number)
        .onSuccess { episodes ->
          val validEpisodes = episodes.filter { it.episode_number > 0 }.sortedBy { it.episode_number }
          _seasonEpisodes.value = validEpisodes
          val matchingEpisode =
            preferredEpisode?.let { wanted ->
              validEpisodes.firstOrNull { it.episode_number == wanted }
            }
          _selectedEpisode.value = matchingEpisode
          matchingEpisode?.let { episode ->
            val tvShowName = _selectedTvShow.value?.name ?: currentMediaTitle
            searchSubtitles(
              query = tvShowName,
              season = season.season_number,
              episode = episode.episode_number,
              tmdbId = tvShowId,
            )
          }
        }.onFailure {
          showProviderStatusToast("Failed to load series details: ${it.message}")
        }
      _isFetchingEpisodes.value = false
    }
  }

  fun selectEpisode(episode: app.infinity.mpvz.repository.wyzie.WyzieEpisode) {
    _selectedEpisode.value = episode
    val tvShowName = _selectedTvShow.value?.name ?: currentMediaTitle
    searchSubtitles(tvShowName, episode.season_number, episode.episode_number, tmdbId = _selectedTvShow.value?.id)
  }

  fun clearMediaSelection() {
    _selectedTvShow.value = null
    _selectedSeason.value = null
    _seasonEpisodes.value = emptyList()
    _selectedEpisode.value = null
    _mediaSearchResults.value = emptyList()
  }

  // --- Subtitle Search ---
  private var subtitleSearchJob: Job? = null

  private fun subtitleSearchMediaTitle(): String {
    currentMediaTitle.takeIf { it.isNotBlank() }?.let { return it }
    val source = host.currentMediaLookupHint().orEmpty()
    val uriName = runCatching { Uri.decode(Uri.parse(source).lastPathSegment.orEmpty()) }.getOrDefault("")
    return uriName.substringAfterLast('/').takeIf { it.isNotBlank() } ?: source
  }

  fun searchOnlineSubtitles(query: String) {
    val queryInfo = MediaInfoParser.parse(query)
    val fileInfo = MediaInfoParser.parse(subtitleSearchMediaTitle())
    val searchTitle = queryInfo.title.ifBlank { query.trim() }.ifBlank { fileInfo.title }
    if (searchTitle.isBlank()) return

    val mode = subtitlesPreferences.onlineSubtitleSearchMode.get()
    if (mode != OnlineSubtitleSearchMode.SUBHUB) {
      searchMedia(searchTitle)
    } else {
      _mediaSearchResults.value = emptyList()
    }

    val year = queryInfo.year ?: fileInfo.year
    val wyziePlan = buildWyzieSearchPlan(searchTitle, year, queryInfo, fileInfo)
    val includeWyzie = mode != OnlineSubtitleSearchMode.SUBHUB && wyziePlan.request != null
    val includeSubtitleHub = mode != OnlineSubtitleSearchMode.WYZIE
    val detectedSeason = queryInfo.season ?: fileInfo.season
    val detectedEpisode = queryInfo.episode ?: fileInfo.episode

    if (mode == OnlineSubtitleSearchMode.WYZIE && wyziePlan.request == null) {
      wyziePlan.missingSelectionMessage?.let(::showToast)
      _onlineSubtitleSearchResults.value = emptyList()
      return
    }

    val wyzieRequest =
      wyziePlan.request
        ?: OnlineSubtitleSearchRequest(
          query = searchTitle,
          year = year,
          // Keep the parsed episode for SubtitleHub in HYBRID/SUBHUB mode even when Wyzie
          // requires an explicit show selection and therefore is disabled for this search.
          season = detectedSeason,
          episode = detectedEpisode,
        )
    searchSubtitles(
      query = wyzieRequest.query,
      season = wyzieRequest.season,
      episode = wyzieRequest.episode,
      year = wyzieRequest.year,
      tmdbId = wyzieRequest.tmdbId,
      includeWyzie = includeWyzie,
      includeSubtitleHub = includeSubtitleHub,
    )
  }

  fun searchSubtitles(
    query: String,
    season: Int? = null,
    episode: Int? = null,
    year: String? = null,
    tmdbId: Int? = null,
    includeWyzie: Boolean = true,
    includeSubtitleHub: Boolean = true,
  ) {
    subtitleSearchJob?.cancel()
    _onlineSubtitleSearchResults.value = emptyList()
    val effectiveQuery = query.ifBlank { subtitleSearchMediaTitle() }
    subtitleSearchJob =
      viewModelScope.launch {
        _isSearchingSub.value = true
        val cleanSubHubTitle = MediaInfoParser.parse(effectiveQuery).title.ifBlank { effectiveQuery.trim() }
        val lookupHints = host.currentPlayerLookupHints()
        val lookupTitle = lookupHints.canonicalTitle ?: subtitleSearchMediaTitle()
        val cleanLookupTitle = MediaInfoParser.parse(lookupTitle).title.ifBlank { lookupTitle.trim() }
        val matchesCurrentLookup =
          cleanLookupTitle.equals(cleanSubHubTitle, ignoreCase = true) ||
            (tmdbId != null && tmdbId == lookupHints.tmdbId)
        val wyzieRequest =
          OnlineSubtitleSearchRequest(
            query = effectiveQuery,
            tmdbId = tmdbId,
            season = season,
            episode = episode,
            year = year,
            movieHash = _videoHash.value,
          )
        val subtitleHubRequest =
          OnlineSubtitleSearchRequest(
            query = cleanSubHubTitle,
            tmdbId = tmdbId ?: lookupHints.tmdbId.takeIf { matchesCurrentLookup },
            imdbId = lookupHints.imdbId.takeIf { matchesCurrentLookup },
            season = season,
            episode = episode,
            year = year,
          )
        onlineSubtitleOrchestrator
          .search(
            wyzieRequest,
            subtitlesPreferences.onlineSubtitleSearchMode.get(),
            subtitleHubRequest = subtitleHubRequest,
            includeWyzie = includeWyzie,
            includeSubtitleHub = includeSubtitleHub,
            onResults = { results ->
              if (subtitleSearchJob?.isActive == true) {
                _onlineSubtitleSearchResults.value = results
              }
            },
          ).onSuccess { results ->
            _onlineSubtitleSearchResults.value = results
          }.onFailure {
            showProviderStatusToast("Search failed: ${it.message}")
          }
        _isSearchingSub.value = false
      }
  }

  private fun buildWyzieSearchPlan(
    searchTitle: String,
    year: String?,
    queryInfo: ParsedMediaInfo,
    fileInfo: ParsedMediaInfo,
  ): WyzieSearchPlan {
    val selectedShow = _selectedTvShow.value
    val selectedSeason = _selectedSeason.value?.season_number
    val selectedEpisode = _selectedEpisode.value?.episode_number

    if (selectedShow != null) {
      if (selectedSeason == null || selectedEpisode == null) {
        return WyzieSearchPlan(
          request = null,
          missingSelectionMessage = "Select season and episode for Wyzie.",
        )
      }
      return WyzieSearchPlan(
        request =
          OnlineSubtitleSearchRequest(
            query = selectedShow.name,
            tmdbId = selectedShow.id,
            season = selectedSeason,
            episode = selectedEpisode,
            year = year,
            movieHash = _videoHash.value,
          ),
      )
    }

    val detectedSeason = queryInfo.season ?: fileInfo.season
    val detectedEpisode = queryInfo.episode ?: fileInfo.episode
    if (detectedSeason != null || detectedEpisode != null) {
      return WyzieSearchPlan(
        request = null,
        missingSelectionMessage = "Select the show, season, and episode for Wyzie.",
      )
    }

    return WyzieSearchPlan(
      request =
        OnlineSubtitleSearchRequest(
          query = searchTitle,
          year = year,
          movieHash = _videoHash.value,
        ),
    )
  }

  fun downloadSubtitle(subtitle: OnlineSubtitle) {
    viewModelScope.launch {
      _isDownloadingSub.value = true
      onlineSubtitleOrchestrator
        .download(subtitle, currentMediaTitle)
        .onSuccess { uri ->
          if (subtitle.isHashMatch) {
            PlaybackSession.setPropertyDouble("sub-delay", 0.0)
            Log.d(TAG, "Applied perfect-sync subtitle match for ${subtitle.displayName}")
          }
          addSubtitle(uri)
        }.onFailure {
          showToast("Download failed: ${it.message}")
        }
      _isDownloadingSub.value = false
    }
  }

  fun toggleSubtitle(id: Int) {
    // During a native handoff the active engine can be Native before the persisted preference
    // is updated. Route the click by the actual active engine, otherwise this would write MPV's
    // sid/sub-visibility properties while Media3 is the visible player.
    if (nativeEngineActive.value) {
      if (id <= -10_000) {
        nativeExternalSubtitleToggleListener?.invoke(id)
        return
      }
      nativeSubtitleToggleListener?.invoke(id)
      return
    }
    val primarySid = getTrackSelectionId("sid")
    val secondarySid = getTrackSelectionId("secondary-sid")

    val wasOff = primarySid <= 0 && secondarySid <= 0

    when {
      id == primarySid -> setTrackSelectionId("sid", null)
      id == secondarySid -> setTrackSelectionId("secondary-sid", null)
      primarySid <= 0 -> setTrackSelectionId("sid", id)
      secondarySid <= 0 -> setTrackSelectionId("secondary-sid", id)
      else -> setTrackSelectionId("sid", id)
    }

    if (wasOff && !subtitlesPreferences.autoEnableSubtitles.get()) {
      subtitlesPreferences.autoEnableSubtitles.set(true)
    }

    syncSubtitleLayout()
  }

  fun isSubtitleSelected(id: Int): Boolean {
    if (nativeEngineActive.value) {
      return subtitleTracks.value.firstOrNull { it.id == id }?.selected == true
    }
    val primarySid = getTrackSelectionId("sid")
    val secondarySid = getTrackSelectionId("secondary-sid")
    return (id == primarySid && primarySid > 0) || (id == secondarySid && secondarySid > 0)
  }

  fun subtitleSelectionIndicator(id: Int): String? {
    if (nativeEngineActive.value) {
      return if (subtitleTracks.value.firstOrNull { it.id == id }?.selected == true) "P" else null
    }
    val primarySid = getTrackSelectionId("sid")
    val secondarySid = getTrackSelectionId("secondary-sid")
    return when {
      primarySid > 0 && id == primarySid -> "P"
      secondarySid > 0 && id == secondarySid -> "S"
      else -> null
    }
  }

  private fun getFileNameFromUri(uri: Uri): String? =
    when (uri.scheme) {
      "content" ->
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

      "file" -> uri.lastPathSegment
      else -> uri.lastPathSegment
    }

  private fun isValidSubtitleFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in VALID_SUBTITLE_EXTENSIONS

  // ==================== Playback Control ====================

  fun pauseUnpause() {
    if (host.isNativeEngineActive()) {
      host.nativePauseUnpause()
      return
    }
    viewModelScope.launch(playbackStateDispatcher) {
      val wasPaused = PlaybackSession.getPropertyBoolean("pause") ?: PlaybackSession.state.value.paused
      if (wasPaused) {
        val focusGranted = withContext(Dispatchers.Main) { host.requestAudioFocus() }
        if (!focusGranted) return@launch
        PlaybackSession.setPropertyBoolean("pause", false)
      } else {
        PlaybackSession.setPropertyBoolean("pause", true)
        withContext(Dispatchers.Main) { host.abandonAudioFocus() }
      }
    }
  }

  fun pause() {
    if (host.isNativeEngineActive()) {
      host.nativePause()
      return
    }
    viewModelScope.launch(playbackStateDispatcher) {
      PlaybackSession.setPropertyBoolean("pause", true)
      withContext(Dispatchers.Main) { host.abandonAudioFocus() }
    }
  }

  fun unpause() {
    if (host.isNativeEngineActive()) {
      host.nativeUnpause()
      return
    }
    viewModelScope.launch(playbackStateDispatcher) {
      val focusGranted = withContext(Dispatchers.Main) { host.requestAudioFocus() }
      if (!focusGranted) return@launch
      PlaybackSession.setPropertyBoolean("pause", false)
    }
  }

  // ==================== UI Control ====================

  fun showControls() {
    if (sheetShown.value != Sheets.None || panelShown.value != Panels.None) return
    if (!isAudioOnly.value) {
      try {
        if (playerPreferences.showSystemStatusBar.get()) {
          host.windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
          host.windowInsetsController.isAppearanceLightStatusBars = false
        }
        if (playerPreferences.showSystemNavigationBar.get()) {
          host.windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
        }
      } catch (e: Exception) {
        // Defensive: InsetsController animation can crash under FD pressure
        // (e.g. during high-res HEVC playback on certain devices)
        Log.e(TAG, "Failed to show system bars", e)
      }
    }
    _controlsShown.value = true
    controlsVisibleForPolling = true
  }

  fun hideControls() {
    if (!isAudioOnly.value) {
      try {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      } catch (e: Exception) {
        Log.e(TAG, "Failed to hide system bars", e)
      }
    }
    _controlsShown.value = false
    _seekBarShown.value = false
    controlsVisibleForPolling = false
    seekBarVisibleForPolling = false
  }

  fun autoHideControls() {
    if (!isAudioOnly.value) {
      try {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      } catch (e: Exception) {
        Log.e(TAG, "Failed to hide system bars", e)
      }
    }
    _controlsShown.value = false
    _seekBarShown.value = true
    controlsVisibleForPolling = false
    seekBarVisibleForPolling = true
  }

  fun showSeekBar() {
    if (sheetShown.value == Sheets.None) {
      _seekBarShown.value = true
      seekBarVisibleForPolling = true
    }
  }

  fun hideSeekBar() {
    _seekBarShown.value = false
    seekBarVisibleForPolling = false
  }


  fun lockControls() {
    _areControlsLocked.value = true
  }

  fun unlockControls() {
    _areControlsLocked.value = false
  }

  // ==================== Seeking ====================

  fun seekBy(offset: Int) {
    cancelFrameSeek()
    if (host.isNativeEngineActive()) {
      host.nativeSeekBy(offset.toLong() * 1000L)
      return
    }
    coalesceSeek(offset)
  }

  fun seekAudioTo(positionSeconds: Float) {
    if (!positionSeconds.isFinite()) return
    seekTo(positionSeconds.toInt(), fast = false)
  }

  fun seekToPlaybackChapter(chapter: Segment) {
    PlaybackSession.setPropertyInt("chapter", chapters.value.indexOf(chapter).coerceAtLeast(0))
    unpause()
  }

  fun stepPlaybackChapter(offset: Int) {
    if (offset !in setOf(-1, 1)) return
    if (offset < 0) playPrevious() else playNext()
  }

  fun nativePlaybackPositionSeconds(): Double = hostReference.get()?.nativePlaybackPositionSeconds() ?: 0.0

  fun nativePlaybackDurationSeconds(): Double = hostReference.get()?.nativePlaybackDurationSeconds() ?: 0.0

  /**
   * Conflated live preview used by the legacy/full-screen seek mode and the MPV audio seekbar.
   * Native preview is intentionally visual-only: every Media3 seek flushes the hardware decoder,
   * so native playback commits one seek when the user releases the seekbar instead.
   */
  fun seekPreviewTo(position: Float) {
    cancelFrameSeek()
    if (host.isNativeEngineActive()) return
    synchronized(seekPreviewLock) {
      pendingSeekPreviewPosition = position.coerceAtLeast(0f)
      if (seekPreviewJob?.isActive == true) return
      seekPreviewJob = viewModelScope.launch(Dispatchers.IO) { runSeekPreviewLoop() }
    }
  }

  private suspend fun runSeekPreviewLoop() {
    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
      val target =
        synchronized(seekPreviewLock) {
          pendingSeekPreviewPosition?.also { pendingSeekPreviewPosition = null }
            ?: run {
              seekPreviewJob = null
              return
            }
        }
      PlaybackSession.command("seek", target.toString(), "absolute+keyframes")
      delay(PREVIEW_SEEK_INTERVAL_MS)
    }
  }

  private fun cancelSeekPreview() {
    synchronized(seekPreviewLock) {
      seekPreviewJob?.cancel()
      seekPreviewJob = null
      pendingSeekPreviewPosition = null
    }
  }

  fun seekTo(
    position: Int,
    fast: Boolean = false,
  ) {
    cancelFrameSeek()
    cancelSeekPreview()
    if (host.isNativeEngineActive()) {
      host.nativeSeekTo(position.toLong() * 1000L)
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      val maxDuration =
        (PlaybackSession.getPropertyInt("duration") ?: duration ?: _preciseDuration.value.toInt())
          .coerceAtLeast(0)
      var clampedPosition =
        if (maxDuration > 0) position.coerceIn(0, maxDuration) else position.coerceAtLeast(0)

      // Clamp within AB loop if active
      val loopA = _abLoopState.value.a
      val loopB = _abLoopState.value.b
      if (loopA != null && loopB != null) {
        val min = minOf(loopA.toInt(), loopB.toInt())
        val max = maxOf(loopA.toInt(), loopB.toInt())
        clampedPosition = clampedPosition.coerceIn(min, max)
      }

      // Cancel pending relative seek before absolute seek
      seekCoalesceJob?.cancel()
      pendingSeekOffset = 0

      // Exact seeking is intentionally opt-in. Forcing it on every short clip is expensive and can
      // leave sparse-keyframe MP4/MKV files on a black frame. Drag previews always use keyframes.
      val seekMode =
        if (!fast && playerPreferences.usePreciseSeeking.get()) "absolute+exact" else "absolute+keyframes"
      PlaybackSession.command("seek", clampedPosition.toString(), seekMode)
    }
  }

  private fun coalesceSeek(offset: Int) {
    pendingSeekOffset += offset
    seekCoalesceJob?.cancel()
    seekCoalesceJob =
      viewModelScope.launch(Dispatchers.IO) {
        delay(SEEK_COALESCE_DELAY_MS)
        val toApply = pendingSeekOffset
        pendingSeekOffset = 0

        if (toApply != 0) {
          val durationSeconds =
            PlaybackSession
              .getPropertyDouble("duration")
              ?.takeIf { it.isFinite() && it > 0.0 }
              ?: currentDurationSeconds().takeIf { it.isFinite() && it > 0.0 }
          val currentPosition =
            PlaybackSession
              .getPropertyDouble("time-pos")
              ?.takeIf { it.isFinite() && it >= 0.0 }
              ?: (pos ?: 0).toDouble().coerceAtLeast(0.0)
          val preciseSeeking = playerPreferences.usePreciseSeeking.get()
          val requestedTarget = (currentPosition + toApply).coerceAtLeast(0.0)
          val targetPosition =
            durationSeconds?.let { duration ->
              val guardedEndPosition = (duration - RELATIVE_SEEK_EOF_GUARD_SECONDS).coerceAtLeast(0.0)
              val nonPreciseForwardOvershoot =
                !preciseSeeking &&
                  toApply > 0 &&
                  requestedTarget >= duration - SEEK_TARGET_TOLERANCE_SECONDS
              val nonPreciseEndPosition =
                if (nonPreciseForwardOvershoot) {
                  val seekInterval =
                    minOf(kotlin.math.abs(toApply), doubleTapToSeekDuration)
                      .toDouble()
                      .coerceAtLeast(RELATIVE_SEEK_EOF_GUARD_SECONDS)
                  (duration - seekInterval).coerceAtLeast(0.0)
                } else {
                  guardedEndPosition
                }
              // Precise seeking retains its explicit EOF behavior. Non-precise seeking stops at
              // the last complete seek interval instead of issuing a command into keep-open EOF.
              val allowExplicitEof =
                preciseSeeking &&
                toApply > 0 &&
                  currentPosition >= guardedEndPosition - SEEK_TARGET_TOLERANCE_SECONDS
              requestedTarget.coerceAtMost(if (allowExplicitEof) duration else nonPreciseEndPosition)
            }

          if (toApply > 0 && targetPosition != null && targetPosition <= currentPosition) {
            return@launch
          }

          // A backward keyframe seek can expose pre-target video while audio still starts at the
          // requested timestamp. Exact seeking decodes through that gap and keeps A/V aligned.
          // A clamped forward seek uses an absolute target, but non-precise mode must still let
          // MPV choose a safe keyframe instead of HR-seeking into the EOF boundary.
          val targetWasClamped = targetPosition != null && targetPosition < requestedTarget
          val useRelativeKeyframeSeek = !preciseSeeking && toApply > 0 && !targetWasClamped
          val useExactSeeking = toApply < 0 || preciseSeeking
          val seekMode =
            if (useRelativeKeyframeSeek) {
              "relative+keyframes"
            } else if (targetPosition != null) {
              if (useExactSeeking) "absolute+exact" else "absolute+keyframes"
            } else {
              if (useExactSeeking) "relative+exact" else "relative+keyframes"
            }
          val seekValue =
            if (useRelativeKeyframeSeek) {
              toApply.toString()
            } else {
              targetPosition?.toString() ?: toApply.toString()
            }
          val synchronizedPosition = targetPosition ?: requestedTarget

          PlaybackSession.command("seek", seekValue, seekMode)
        }
      }
  }

  fun leftSeek() {
    val currentPosition = if (host.isNativeEngineActive()) nativePlaybackPositionSeconds().toInt() else (pos ?: 0)
    _seekState.update { s ->
      s.copy(amount = if (currentPosition > 0) s.amount - doubleTapToSeekDuration else s.amount, isForwards = false)
    }
    seekBy(-doubleTapToSeekDuration)
  }

  fun rightSeek() {
    val currentPosition = if (host.isNativeEngineActive()) nativePlaybackPositionSeconds().toInt() else (pos ?: 0)
    val currentDuration = if (host.isNativeEngineActive()) nativePlaybackDurationSeconds().toInt() else (duration ?: 0)
    _seekState.update { s ->
      s.copy(
        amount =
          if (currentPosition <
            currentDuration
          ) {
            s.amount + doubleTapToSeekDuration
          } else {
            s.amount
          },
        isForwards = true,
      )
    }
    seekBy(doubleTapToSeekDuration)
  }

  fun updateSeekAmount(amount: Int) {
    _seekState.update { it.copy(amount = amount) }
  }

  fun updateSeekText(text: String?) {
    _seekState.update { it.copy(text = text) }
  }

  fun updateIsSeekingForwards(isForwards: Boolean) {
    _seekState.update { it.copy(isForwards = isForwards) }
  }

  private fun seekToWithText(
    seekValue: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    _seekState.value = SeekState(text = text, amount = seekValue - currentPos, isForwards = seekValue > currentPos)
    seekTo(seekValue)
  }

  private fun seekByWithText(
    value: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    val maxDuration = duration ?: return

    _seekState.update { s ->
      val newAmount = if ((value < 0 && s.amount < 0) || currentPos + value > maxDuration) 0 else s.amount + value
      SeekState(text = text, amount = newAmount, isForwards = value > 0)
    }
    seekBy(value)
  }

  // ==================== Brightness & Volume ====================

  fun changeBrightnessTo(brightness: Float) {
    val isAudio = host.isCurrentMediaKnownAudio() || isAudioOnly.value
    val minBrightness = if (isAudio) 0f else -0.75f
    val coercedBrightness = brightness.coerceIn(minBrightness, 1f)
    host.hostWindow.attributes =
      host.hostWindow.attributes.apply {
        screenBrightness = coercedBrightness.coerceIn(0f, 1f)
      }
    currentBrightness.value = coercedBrightness

    // Save brightness to preferences if enabled
    if (playerPreferences.rememberBrightness.get()) {
      playerPreferences.defaultBrightness.set(coercedBrightness)
    }
  }

  fun displayBrightnessSlider() {
    isBrightnessSliderShown.value = true
    brightnessSliderTimestamp.value = System.currentTimeMillis()
  }

  /**
   * Resets the window brightness to follow the system. Used when "remember brightness"
   * is disabled so playback adheres to the device's current brightness (including
   * auto-brightness) instead of being forced to the manual SCREEN_BRIGHTNESS value,
   * which is wrong/dimmer when auto-brightness is active.
   */
  fun resetBrightnessToSystem() {
    val systemBrightness =
      runCatching {
        Settings.System
          .getFloat(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .coerceIn(0f, 255f) / 255f
      }.getOrNull() ?: 0f
    currentBrightness.value = systemBrightness
    runCatching {
      host.hostWindow.attributes =
        host.hostWindow.attributes.apply {
          screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
  }

  fun changeVolumeBy(
    change: Int,
    showUi: Boolean = false,
  ) {
    val isAudio = host.isCurrentMediaKnownAudio() || isAudioOnly.value
    val currentSystemVolume = syncCurrentSystemVolume()
    val mpvVolume = PlaybackSession.getPropertyInt("volume") ?: 100
    // Audio playback must not apply gain boost (>100%). Boost is a video-only feature,
    // so cap the maximum at 100 for audio.
    val absoluteMaxVolume =
      if (isAudio) {
        100
      } else {
        volumeBoostCap ?: (audioPreferences.volumeBoostCap.get() + 100)
      }

    if (currentSystemVolume < maxVolume && mpvVolume > 100) {
      changeMPVVolumeTo(100)
    }

    if (absoluteMaxVolume > 100 && currentSystemVolume == maxVolume) {
      if (mpvVolume == 100 && change < 0) {
        changeVolumeTo(currentSystemVolume + change, showUi)
      }
      val finalMPVVolume = (mpvVolume + change).coerceAtLeast(100)
      if (finalMPVVolume in 100..absoluteMaxVolume) {
        return changeMPVVolumeTo(finalMPVVolume)
      }
    }

    changeVolumeTo(currentSystemVolume + change, showUi)
  }

  fun changeVolumePercentTo(
    volumePercent: Int,
    showUi: Boolean = false,
  ) {
    val newPercent = volumePercent.coerceIn(0, 100)
    val newVolume = percentToSystemVolume(newPercent)
    val flags = if (showUi || isAudioOnly.value) AudioManager.FLAG_SHOW_UI else 0
    (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, flags)
    currentVolume.value = syncCurrentSystemVolume()
    currentVolumePercent.value = newPercent

    if (currentVolume.value < maxVolume) {
      val currentMpvVolume = PlaybackSession.getPropertyInt("volume") ?: 100
      if (currentMpvVolume > 100) {
        changeMPVVolumeTo(100)
      }
    }
  }

  fun changeVolumeTo(
    volume: Int,
    showUi: Boolean = false,
  ) {
    val newVolume = volume.coerceIn(0..maxVolume)
    val flags = if (showUi || isAudioOnly.value) AudioManager.FLAG_SHOW_UI else 0
    (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, flags)
    currentVolume.value = syncCurrentSystemVolume()

    if (currentVolume.value < maxVolume) {
      val currentMpvVolume = PlaybackSession.getPropertyInt("volume") ?: 100
      if (currentMpvVolume > 100) {
        changeMPVVolumeTo(100)
      }
    }
  }

  fun changeMPVVolumeTo(volume: Int) {
    PlaybackSession.setPropertyInt("volume", volume)
  }

  fun displayVolumeSlider() {
    isVolumeSliderShown.value = true
    volumeSliderTimestamp.value = System.currentTimeMillis()
  }

  fun changeSubtitlePositionTo(position: Int) {
    val newPosition = clampSubtitlePosition(position)
    subtitlesPreferences.subPos.set(newPosition)
    if (host.isNativeEngineActive()) {
      host.nativeSetSubtitlePosition(newPosition)
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.subtitle_position_update, newPosition))
      return
    }
    syncSubtitleLayout(newPosition)
    playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.subtitle_position_update, newPosition))
  }

  fun setSubtitleScale(scale: Float) {
    val clamped = scale.coerceIn(0.1f, 5f)
    if (host.isNativeEngineActive()) host.nativeSetSubtitleScale(clamped)
    else PlaybackSession.setPropertyFloat("sub-scale", clamped)
    playerUpdate.value = PlayerUpdates.SubtitleZoom(clamped)
  }

  fun setPlaybackSpeed(speed: Float) {
    val clamped = speed.coerceIn(0.25f, 8f)
    if (host.isNativeEngineActive()) host.nativeSetSpeed(clamped)
    else PlaybackSession.setPropertyFloat("speed", clamped)
  }

  fun isNativePlaying(): Boolean = hostReference.get()?.isNativePlaying() == true

  fun isNativeEngineActive(): Boolean = hostReference.get()?.isNativeEngineActive() == true || nativeEngineActive.value

  fun activePlaybackSpeed(): Float =
    hostReference.get()?.takeIf { it.isNativeEngineActive() }?.nativePlaybackSpeed()
      ?: (PlaybackSession.getPropertyFloat("speed") ?: 1f)

  private fun syncSubtitleLayout(primaryPosition: Int = subtitlesPreferences.subPos.get()) {
    applySubtitleLayout(primaryPosition, subtitlesPreferences.overrideAssSubs.get())
  }

  private fun syncCurrentSystemVolume(): Int {
    val systemVolume = (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
    currentVolume.value = systemVolume
    currentVolumePercent.value = systemVolumeToPercent(systemVolume)
    return systemVolume
  }

  fun syncCurrentVolumeState() {
    syncCurrentSystemVolume()
  }

  private fun systemVolumeToPercent(systemVolume: Int): Int {
    if (maxVolume <= 0) return 0
    return ((systemVolume.toFloat() / maxVolume.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
  }

  private fun percentToSystemVolume(volumePercent: Int): Int {
    if (maxVolume <= 0) return 0
    return ((volumePercent / 100f) * maxVolume.toFloat()).roundToInt().coerceIn(0, maxVolume)
  }

  // ==================== Video Aspect ====================

  fun changeVideoAspect(
    aspect: VideoAspect,
    showUpdate: Boolean = true,
    persistGlobal: Boolean = true,
  ) {
    if (host.isNativeEngineActive()) {
      host.nativeSetVideoAspect(aspect)
      if (persistGlobal) {
        playerPreferences.lastVideoAspect.set(aspect)
        playerPreferences.lastCustomAspectRatio.set(-1f)
      }
      _videoAspect.value = aspect
      _currentAspectRatio.value = -1.0
      if (showUpdate) {
        playerUpdate.value = PlayerUpdates.AspectRatio
        host.onVideoAspectChanged()
      }
      return
    }
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.VIDEO_ASPECT)) return
    when (aspect) {
      VideoAspect.Fit -> {
        // To FIT: Reset both properties to their defaults.
        PlaybackSession.setPropertyDouble("panscan", 0.0)
        PlaybackSession.setPropertyString("video-aspect-override", "no")
      }
      VideoAspect.Crop -> {
        // To CROP: Reset aspect override first, then set panscan
        PlaybackSession.setPropertyString("video-aspect-override", "no")
        PlaybackSession.setPropertyDouble("panscan", 1.0)
      }
      VideoAspect.Stretch -> {
        // To STRETCH: Calculate screen ratio accounting for video rotation
        @Suppress("DEPRECATION")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        host.hostWindowManager.defaultDisplay.getRealMetrics(dm)

        // Get video rotation from metadata
        val rotate = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0
        val isVideoRotated = (rotate % 180 == 90) // 90° or 270° rotation

        // Calculate screen ratio, inverting if video is rotated
        val screenRatio =
          if (isVideoRotated) {
            // Video is rotated, so invert the screen ratio
            dm.heightPixels.toDouble() / dm.widthPixels.toDouble()
          } else {
            // Video is not rotated, use normal screen ratio
            dm.widthPixels.toDouble() / dm.heightPixels.toDouble()
          }

        // Set aspect override first, then reset panscan
        // This prevents the brief flash of Fit mode
        PlaybackSession.setPropertyDouble(
          "video-aspect-override",
          VideoAspectGeometry.stretchAspectOverride(screenRatio),
        )
        PlaybackSession.setPropertyDouble("panscan", 0.0)
      }
    }

    // Update the state
    if (persistGlobal) {
      playerPreferences.lastVideoAspect.set(aspect)
      playerPreferences.lastCustomAspectRatio.set(-1f)
    }
    _videoAspect.value = aspect
    _currentAspectRatio.value = -1.0 // Reset custom ratio when using standard modes

    // Notify the UI
    if (showUpdate) {
      playerUpdate.value = PlayerUpdates.AspectRatio
      host.onVideoAspectChanged()
    }
  }

  fun setCustomAspectRatio(
    ratio: Double,
    showUpdate: Boolean = true,
    persistGlobal: Boolean = true,
  ) {
    if (host.isNativeEngineActive()) {
      host.nativeSetVideoAspect(VideoAspect.Stretch)
      if (persistGlobal) playerPreferences.lastCustomAspectRatio.set(ratio.toFloat())
      _currentAspectRatio.value = ratio
      if (showUpdate) playerUpdate.value = PlayerUpdates.AspectRatio
      return
    }
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.VIDEO_ASPECT)) return
    PlaybackSession.setPropertyDouble("panscan", 0.0)
    PlaybackSession.setPropertyDouble("video-aspect-override", ratio)
    if (persistGlobal) playerPreferences.lastCustomAspectRatio.set(ratio.toFloat())
    _currentAspectRatio.value = ratio
    if (showUpdate) {
      playerUpdate.value = PlayerUpdates.AspectRatio
      host.onVideoAspectChanged()
    }
  }

  fun restoreSavedVideoAspect(showUpdate: Boolean = false) {
    if (playerPreferences.rememberVideoAspectPerVideo.get()) {
      // Per-video mode must never fall back to the global aspect value. A missed lifecycle
      // callback must not reapply the previous video's Crop/Stretch setting.
      changeVideoAspect(VideoAspect.Fit, showUpdate, persistGlobal = false)
      return
    }
    val customAspectRatio = playerPreferences.lastCustomAspectRatio.get()
    if (customAspectRatio > 0f) {
      setCustomAspectRatio(customAspectRatio.toDouble(), showUpdate)
      return
    }

    changeVideoAspect(playerPreferences.lastVideoAspect.get(), showUpdate)
  }

  fun restoreVideoAspect(
    aspectName: String,
    customAspectRatio: Float,
    showUpdate: Boolean = false,
  ) {
    if (customAspectRatio > 0f) {
      setCustomAspectRatio(customAspectRatio.toDouble(), showUpdate, persistGlobal = false)
      return
    }
    val aspect = VideoAspect.entries.firstOrNull { it.name.equals(aspectName, ignoreCase = true) } ?: VideoAspect.Fit
    changeVideoAspect(aspect, showUpdate, persistGlobal = false)
  }

  fun setAutoCropBlackBars(enabled: Boolean) {
    playerPreferences.autoCropBlackBars.set(enabled)
    autoCropJob?.cancel()
    autoCropJob = null
    autoCropReadinessJob?.cancel()
    autoCropReadinessJob = null
    autoCropAnalyzedGeneration = -1L
    if (!enabled) {
      clearAutoCropProperty()
      _autoCropState.value = AutoCropState.IDLE
      return
    }
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUTO_CROP)) {
      _autoCropState.value = AutoCropState.UNSUPPORTED
      return
    }
    clearAutoCropProperty()
    scheduleAutoCropAnalysis(force = true)
  }

  fun scheduleAutoCropAnalysis(force: Boolean = false) {
    if (!playerPreferences.autoCropBlackBars.get()) return
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUTO_CROP)) {
      _autoCropState.value = AutoCropState.UNSUPPORTED
      return
    }

    val isNative = host.isNativeEngineActive()
    val isReady = if (isNative) {
      host.isNativePlaying() || host.nativePlaybackPositionSeconds() > 0.0
    } else {
      val phase = PlaybackSession.state.value.phase
      phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    }

    if (!isReady) {
      if (autoCropReadinessJob?.isActive != true) {
        _autoCropState.value = AutoCropState.ANALYZING
        autoCropReadinessJob =
          viewModelScope.launch {
            delay(1200L)
            autoCropReadinessJob = null
            if (playerPreferences.autoCropBlackBars.get()) {
              scheduleAutoCropAnalysis(force)
            }
          }
      }
      return
    }

    val generation = if (isNative) 1000L else PlaybackSession.state.value.generation
    if (!force && autoCropAnalyzedGeneration == generation) return

    val (sourceWidth, sourceHeight) = if (isNative) {
      val size = host.nativeVideoSize()
      if (size.first > 0 && size.second > 0) size else 1920 to 1080
    } else {
      val w = PlaybackSession.getPropertyInt("video-params/w") ?: 0
      val h = PlaybackSession.getPropertyInt("video-params/h") ?: 0
      if (w > 0 && h > 0) w to h else 1920 to 1080
    }

    autoCropAnalyzedGeneration = generation
    autoCropJob?.cancel()
    _autoCropState.value = AutoCropState.ANALYZING

    val source = runCatching { host.currentThumbnailSource() }.getOrNull()?.takeIf(String::isNotBlank)
    val durationSeconds = if (isNative) {
      host.nativePlaybackDurationSeconds().takeIf { it > 0.0 } ?: 0.0
    } else {
      PlaybackSession.getPropertyDouble("duration") ?: preciseDuration.value.toDouble()
    }
    val cacheKey = if (source != null) "$source|$sourceWidth|$sourceHeight|${durationSeconds.toLong()}" else null

    autoCropJob =
      viewModelScope.launch(Dispatchers.IO) {
        var detected: AutoCropEdges? = cacheKey?.let { autoCropResultCache.get(it) }

        if (detected == null && source != null && durationSeconds > 10.0) {
          // Attempt background frame extraction with MediaMetadataRetriever across multiple timestamps.
          // Zero impact on active playback/GPU (0% framedrop in MPV/Native), and solves dark scenes (e.g. The Batman hallway)
          // by sampling bright scenes elsewhere in the movie.
          val positions = autoCropSamplePositions(source, durationSeconds)
          val retrieverSamples = extractAutoCropWithRetriever(source, positions)
          if (!retrieverSamples.isNullOrEmpty()) {
            detected = AutoCropAnalyzer.combine(retrieverSamples)
          }
        }

        if (detected == null) {
          // Fallback: Sample current frames
          withContext(Dispatchers.Main) {
            if (isNative) {
              host.nativeSetAutoCropActive(false)
            } else {
              PlaybackSession.setPropertyString("video-crop", "")
            }
          }
          delay(150L)

          val samples = mutableListOf<AutoCropEdges>()
          var hadVisibleFrame = false
          for (i in 0 until 3) {
            if (!isActive) break
            val bitmap: Bitmap? =
              suspendCancellableCoroutine { cont ->
                host.captureCurrentVideoFrame { bmp ->
                  if (cont.isActive) cont.resume(bmp) else bmp?.recycle()
                }
              }
            if (bitmap != null) {
              val edges =
                try {
                  AutoCropAnalyzer.analyzeFrame(bitmap)
                } finally {
                  bitmap.recycle()
                }
              if (edges != null) {
                hadVisibleFrame = true
                if (edges.top > 0f || edges.bottom > 0f || edges.left > 0f || edges.right > 0f) {
                  samples.add(edges)
                }
              }
            }
            if (i < 2) delay(300L)
          }

          if (samples.isNotEmpty()) {
            detected = AutoCropAnalyzer.combine(samples)
          } else if (!hadVisibleFrame) {
            // The scene was too dark to detect content (e.g. paused in pitch black hallway in The Batman).
            // Do NOT falsely report NO_BARS. Reset generation so future scene changes can retry.
            autoCropAnalyzedGeneration = -1L
            withContext(Dispatchers.Main) {
              clearAutoCropProperty()
              _autoCropState.value = AutoCropState.ERROR
            }
            return@launch
          }
        }

        withContext(Dispatchers.Main) {
          if (!playerPreferences.autoCropBlackBars.get()) {
            return@withContext
          }

          if (detected == null || (detected.left <= 0f && detected.top <= 0f && detected.right <= 0f && detected.bottom <= 0f)) {
            clearAutoCropProperty()
            _autoCropState.value = AutoCropState.NO_BARS
            return@withContext
          }

          val cropValue = buildAutoCropValue(detected, sourceWidth, sourceHeight)
          if (cropValue == null) {
            clearAutoCropProperty()
            _autoCropState.value = AutoCropState.NO_BARS
            return@withContext
          }

          cacheKey?.let { autoCropResultCache.put(it, detected) }

          if (isNative) {
            host.nativeSetAutoCropActive(true)
          } else {
            PlaybackSession.setPropertyString("video-crop", cropValue)
          }

          autoCropApplied = true
          _autoCropState.value = AutoCropState.APPLIED
          Log.i(TAG, "Auto-crop applied successfully: crop=$cropValue edges=$detected isNative=$isNative")
        }
      }
  }

  private fun autoCropSamplePositions(
    source: String,
    durationSeconds: Double,
  ): List<Double> {
    val random = Random(source.hashCode())
    val start = durationSeconds * 0.05
    val span = durationSeconds * 0.90
    val segment = span / AUTO_CROP_SAMPLE_COUNT
    return List(AUTO_CROP_SAMPLE_COUNT) { index ->
      start + segment * (index + random.nextDouble(0.2, 0.8))
    }
  }

  private suspend fun extractAutoCropSamples(
    source: String,
    positionsSeconds: List<Double>,
  ): AutoCropSamples? {
    if (isAndroidReadableMediaSource(source)) {
      extractAutoCropWithRetriever(source, positionsSeconds)?.let { samples ->
        if (samples.size >= AUTO_CROP_MIN_VALID_SAMPLES) {
          return AutoCropSamples(samples, framesWereRotated = false)
        }
      }
    }

    val samples =
      positionsSeconds.mapNotNull { position ->
        currentCoroutineContext().ensureActive()
        val bitmap = PlaybackSession.grabThumbnailFast(source, position, AUTO_CROP_THUMBNAIL_SIZE, true)
        bitmap?.analyzeAndRecycle()
      }
    return samples.takeIf { it.size >= AUTO_CROP_MIN_VALID_SAMPLES }?.let {
      AutoCropSamples(it, framesWereRotated = true)
    }
  }

  private suspend fun extractAutoCropWithRetriever(
    source: String,
    positionsSeconds: List<Double>,
  ): List<AutoCropEdges>? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        val uri = Uri.parse(source)
        when (uri.scheme?.lowercase()) {
          null, "" -> retriever.setDataSource(source)
          "file" -> retriever.setDataSource(uri.path ?: source)
          "content", "android.resource" -> retriever.setDataSource(appContext, uri)
          "http", "https" -> {
            val headers = (PlaybackSession.state.value.currentItem?.headers ?: emptyMap())
            retriever.setDataSource(source, headers)
          }
          else -> retriever.setDataSource(source)
        }
        val sourceWidth =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            ?: AUTO_CROP_THUMBNAIL_SIZE
        val sourceHeight =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            ?: AUTO_CROP_THUMBNAIL_SIZE
        val scale = AUTO_CROP_THUMBNAIL_SIZE.toDouble() / maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
        val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(2)
        val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(2)
        positionsSeconds.mapNotNull { position ->
          currentCoroutineContext().ensureActive()
          val timeUs = (position * 1_000_000.0).toLong()
          val bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
              retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetHeight,
              )
            } else {
              retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
          bitmap?.analyzeAndRecycle()
        }
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private fun Bitmap.analyzeAndRecycle(): AutoCropEdges? =
    try {
      AutoCropAnalyzer.analyzeFrame(this)
    } finally {
      recycle()
    }

  private fun isAndroidReadableMediaSource(source: String): Boolean {
    val scheme = Uri.parse(source).scheme?.lowercase()
    return scheme.isNullOrBlank() || scheme in setOf("file", "content", "android.resource")
  }

  private fun mapRotatedEdgesToSource(
    edges: AutoCropEdges,
    rotation: Int,
  ): AutoCropEdges =
    when (rotation) {
      90 -> AutoCropEdges(left = edges.top, top = edges.right, right = edges.bottom, bottom = edges.left)
      180 -> AutoCropEdges(left = edges.right, top = edges.bottom, right = edges.left, bottom = edges.top)
      270 -> AutoCropEdges(left = edges.bottom, top = edges.left, right = edges.top, bottom = edges.right)
      else -> edges
    }

  private fun buildAutoCropValue(
    edges: AutoCropEdges,
    sourceWidth: Int,
    sourceHeight: Int,
  ): String? {
    fun evenFloor(value: Float): Int = (value.toInt().coerceAtLeast(0) / 2) * 2

    // If letterbox (top/bottom) is detected: lock horizontal to 0, 100% width preserved! Zero side cut!
    if (edges.top + edges.bottom >= 0.02f) {
      val top = evenFloor(edges.top * sourceHeight)
      val bottom = evenFloor(edges.bottom * sourceHeight)
      val height = sourceHeight - top - bottom
      if (height < sourceHeight / 2) return null
      if (sourceHeight - height < 4) return null
      return "${sourceWidth}x$height+0+$top"
    }

    // If pillarbox (left/right) is detected (e.g. 4:3 inside 16:9): lock vertical to 0! Zero top/bottom cut!
    if (edges.left + edges.right >= 0.02f) {
      val left = evenFloor(edges.left * sourceWidth)
      val right = evenFloor(edges.right * sourceWidth)
      val width = sourceWidth - left - right
      if (width < sourceWidth / 2) return null
      if (sourceWidth - width < 4) return null
      return "${width}x$sourceHeight+$left+0"
    }

    return null
  }

  private fun clearAutoCropProperty() {
    PlaybackSession.setPropertyString("video-crop", "")
    runCatching { host.nativeSetAutoCropActive(false) }
    runCatching { host.nativeSetAutoCropScale(1f) }
    autoCropApplied = false
  }

  private fun refreshStretchAspectAfterCropChange() {
    if (playerPreferences.rememberVideoAspectPerVideo.get()) return
    if (playerPreferences.lastCustomAspectRatio.get() > 0f) return
    if (playerPreferences.lastVideoAspect.get() != VideoAspect.Stretch) return
    changeVideoAspect(VideoAspect.Stretch, showUpdate = false)
  }

  // ==================== Screen Rotation ====================

  fun cycleScreenRotations() {
    if (isAudioOnly.value) {
      host.onManualOrientationOverride()
      host.hostRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      return
    }
    // Temporarily cycle orientation WITHOUT modifying preferences
    // Preferences remain the single source of truth and will be reapplied on next video
    val nextOrientation = when (host.hostRequestedOrientation) {
      ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
      ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
      -> {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      }
      else -> {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
    }
    // Mark the tap before assigning requestedOrientation. On some devices the configuration
    // callback is delivered synchronously and would otherwise reapply the video aspect first.
    host.onManualOrientationOverride()
    host.hostRequestedOrientation = nextOrientation
  }

  // ==================== Lua Invocation Handling ====================

  fun handleLuaInvocation(
    property: String,
    value: String,
  ) {
    val data = value.removeSurrounding("\"").ifEmpty { return }

    when (property.substringAfterLast("/")) {
      "show_text" -> playerUpdate.value = PlayerUpdates.ShowText(data)
      "toggle_ui" -> handleToggleUI(data)
      "show_panel" -> handleShowPanel(data)
      "seek_to_with_text" -> {
        val (seekValue, text) = data.split("|", limit = 2)
        seekToWithText(seekValue.toInt(), text)
      }
      "seek_by_with_text" -> {
        val (seekValue, text) = data.split("|", limit = 2)
        seekByWithText(seekValue.toInt(), text)
      }
      "seek_by" -> seekByWithText(data.toInt(), null)
      "seek_to" -> seekToWithText(data.toInt(), null)
      "software_keyboard" -> handleSoftwareKeyboard(data)
      // Curl bridge: dispatch the HTTP request asynchronously; response is written
      // back to user-data/Mpv∞/curl_response for the script to observe.
      "curl_request" -> scriptCurlBridge.handleRequest(data)
    }

    // Do not clear curl_request or curl_response here:
    //  - curl_request is consumed asynchronously by the bridge.
    //  - curl_response is written by the bridge and must remain readable
    //    until the script's observe_property callback fires.
    if (property.substringAfterLast("/") !in listOf("curl_request", "curl_response")) {
      PlaybackSession.setPropertyString(property, "")
    }
  }

  private fun handleToggleUI(data: String) {
    when (data) {
      "show" -> showControls()
      "toggle" -> if (controlsShown.value) hideControls() else showControls()
      "hide" -> {
        sheetShown.value = Sheets.None
        panelShown.value = Panels.None
        hideControls()
      }
    }
  }

  private fun handleShowPanel(data: String) {
    when (data) {
      "frame_navigation" -> {
        sheetShown.value = Sheets.FrameNavigation
      }
      else -> {
        panelShown.value =
          when (data) {
            "subtitle_settings" -> Panels.SubtitleSettings
            "subtitle_delay" -> Panels.SubtitleDelay
            "audio_delay" -> Panels.AudioDelay
            "video_filters" -> Panels.VideoFilters
            "lua_scripts" -> Panels.LuaScripts
            "hdr_screen_output" -> Panels.HdrScreenOutput
            else -> Panels.None
          }
      }
    }
  }

  private fun handleSoftwareKeyboard(data: String) {
    when (data) {
      "show" -> forceShowSoftwareKeyboard()
      "hide" -> forceHideSoftwareKeyboard()
      "toggle" ->
        if (!inputMethodManager.isActive) {
          forceShowSoftwareKeyboard()
        } else {
          forceHideSoftwareKeyboard()
        }
    }
  }

  @Suppress("DEPRECATION")
  private fun forceShowSoftwareKeyboard() {
    inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
  }

  @Suppress("DEPRECATION")
  private fun forceHideSoftwareKeyboard() {
    inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
  }

  // ==================== Gesture Handling ====================

  fun handleLeftDoubleTap() {
    when (gesturePreferences.leftSingleActionGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapLeft.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  fun handleCenterDoubleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
        }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleCenterSingleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
        }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleRightDoubleTap() {
    when (gesturePreferences.rightSingleActionGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapRight.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  fun handleMediaPrevious() {
    when (gesturePreferences.mediaPreviousGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  fun handleMediaPlayPause() {
    when (gesturePreferences.mediaPlayGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
        }
      SingleActionGesture.Seek,
      SingleActionGesture.None,
      -> {}
    }
  }

  fun handleMediaNext() {
    when (gesturePreferences.mediaNextGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.MediaNext.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  // ==================== Video Zoom ====================

  fun setVideoZoom(zoom: Float) {
    if (host.isNativeEngineActive()) {
      _videoZoom.value = zoom
      host.nativeSetZoom(zoom)
      return
    }
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.VIDEO_ZOOM)) {
      _videoZoom.value = 0f
      return
    }
    _videoZoom.value = zoom
    runCatching { PlaybackSession.setPropertyDouble("video-zoom", zoom.toDouble()) }
  }

  // Video pan (for pan & zoom feature)
  private val _videoPanX = MutableStateFlow(0f)
  val videoPanX: StateFlow<Float> = _videoPanX.asStateFlow()

  private val _videoPanY = MutableStateFlow(0f)
  val videoPanY: StateFlow<Float> = _videoPanY.asStateFlow()

  fun setVideoPan(
    x: Float,
    y: Float,
  ) {
    if (host.isNativeEngineActive()) {
      _videoPanX.value = x
      _videoPanY.value = y
      host.nativeSetPan(x, y)
      return
    }
    val width = PlaybackSession.getPropertyInt("osd-width")?.takeIf { it > 0 }?.toFloat()
      ?: PlaybackSession.getPropertyInt("width")?.takeIf { it > 0 }?.toFloat()
      ?: 1f
    val height = PlaybackSession.getPropertyInt("osd-height")?.takeIf { it > 0 }?.toFloat()
      ?: PlaybackSession.getPropertyInt("height")?.takeIf { it > 0 }?.toFloat()
      ?: 1f
    val panX = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-x")) 0f else x / width
    val panY = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-y")) 0f else y / height
    _videoPanX.value = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-x")) 0f else x
    _videoPanY.value = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-y")) 0f else y
    PlaybackSession.setPropertyDouble("video-pan-x", panX.toDouble())
    PlaybackSession.setPropertyDouble("video-pan-y", panY.toDouble())
  }

  fun resetVideoPan() {
    setVideoPan(0f, 0f)
  }

  fun resetVideoZoom() {
    setVideoZoom(0f)
    resetVideoPan()
  }

  // ==================== Frame Navigation ====================

  fun updateFrameInfo() {
    _currentFrame.value = PlaybackSession.getPropertyInt("estimated-frame-number") ?: 0

    val estimatedFrameCount =
      PlaybackSession
        .getPropertyInt("estimated-frame-count")
        ?.takeIf { it > 0 }
    val durationValue = PlaybackSession.getPropertyDouble("duration") ?: 0.0
    val fps =
      PlaybackSession.getPropertyDouble("container-fps")
        ?: PlaybackSession.getPropertyDouble("estimated-vf-fps")
        ?: 0.0

    _totalFrames.value =
      estimatedFrameCount
        ?: if (durationValue > 0 && fps > 0) {
          (durationValue * fps).toInt()
        } else {
          0
        }
  }

  fun seekToFrame(
    targetFrame: Int,
    totalFrames: Int,
    finished: Boolean,
  ) {
    val durationSeconds =
      PlaybackSession
        .getPropertyDouble("duration")
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: currentDurationSeconds().takeIf { it.isFinite() && it > 0.0 }
        ?: return
    val frameCount =
      PlaybackSession
        .getPropertyInt("estimated-frame-count")
        ?.takeIf { it > 0 }
        ?: totalFrames.takeIf { it > 0 }
        ?: return
    val lastFrame = (frameCount - 1).coerceAtLeast(0)
    val clampedFrame = targetFrame.coerceIn(0, lastFrame)
    val requestedPosition = clampedFrame.toDouble() * durationSeconds / frameCount
    val loopA = _abLoopState.value.a
    val loopB = _abLoopState.value.b
    val targetPosition =
      if (loopA != null && loopB != null) {
        requestedPosition.coerceIn(minOf(loopA, loopB), maxOf(loopA, loopB))
      } else {
        requestedPosition
      }
    val effectiveTargetFrame =
      if (targetPosition == requestedPosition) {
        clampedFrame
      } else {
        kotlin.math
          .round(targetPosition * frameCount / durationSeconds)
          .toInt()
          .coerceIn(0, lastFrame)
      }

    if (!finished) {
      seekPreviewTo(targetPosition.toFloat())
      return
    }

    cancelSeekPreview()
    cancelFrameSeek()
    frameSeekJob =
      viewModelScope.launch(Dispatchers.IO) {
        seekCoalesceJob?.cancel()
        pendingSeekOffset = 0
        if (PlaybackSession.getPropertyBoolean("pause") != true) {
          PlaybackSession.setPropertyBoolean("pause", true)
          withContext(Dispatchers.Main) { host.abandonAudioFocus() }
        }

        var refinedPosition = targetPosition
        var remainingFrameDelta: Int? = null
        var exactPassCount = 0
        while (exactPassCount < FRAME_SEEK_EXACT_PASSES) {
          exactPassCount += 1
          PlaybackSession.command("seek", refinedPosition.toString(), "absolute+exact")
          awaitFrameSeekSettled()
          val observedFrame = PlaybackSession.getPropertyInt("estimated-frame-number") ?: break
          remainingFrameDelta = effectiveTargetFrame - observedFrame
          if (kotlin.math.abs(remainingFrameDelta) <= FRAME_SEEK_MAX_CORRECTION_STEPS) break
          refinedPosition =
            (refinedPosition + remainingFrameDelta.toDouble() * durationSeconds / frameCount)
              .coerceIn(0.0, durationSeconds)
        }

        val correctedFrames =
          remainingFrameDelta
            ?.takeIf { kotlin.math.abs(it) <= FRAME_SEEK_MAX_CORRECTION_STEPS }
            ?.let { frameDelta ->
              repeat(kotlin.math.abs(frameDelta)) {
                PlaybackSession.command(
                  "no-osd",
                  if (frameDelta > 0) "frame-step" else "frame-back-step",
                )
                delay(FRAME_SEEK_CORRECTION_INTERVAL_MS)
              }
              kotlin.math.abs(frameDelta)
            }
            ?: 0
        if (correctedFrames > 0) delay(FRAME_SEEK_CORRECTION_SETTLE_MS)

        updateFrameInfo()
      }
  }

  fun cancelFrameSeek() {
    frameSeekJob?.cancel()
    frameSeekJob = null
  }

  private suspend fun awaitFrameSeekSettled() {
    var observedSeeking = false
    for (poll in 0 until FRAME_SEEK_MAX_SETTLE_POLLS) {
      val seeking = PlaybackSession.getPropertyBoolean("seeking") == true
      observedSeeking = observedSeeking || seeking
      if (!seeking && (observedSeeking || poll >= FRAME_SEEK_MIN_SETTLE_POLLS)) return
      delay(FRAME_SEEK_POLL_INTERVAL_MS)
    }
  }

  fun toggleFrameNavigationExpanded() {
    val wasExpanded = _isFrameNavigationExpanded.value
    _isFrameNavigationExpanded.update { !it }
    // Update frame info and pause when expanding (going from false to true)
    if (!wasExpanded) {
      // Pause the video if it's playing
      if (paused != true) {
        pauseUnpause()
      }
      updateFrameInfo()
      showFrameInfoOverlay()
      resetFrameNavigationTimer()
    } else {
      // Cancel timer when manually collapsing
      frameNavigationCollapseJob?.cancel()
    }
  }

  private fun showFrameInfoOverlay() {
    playerUpdate.value = PlayerUpdates.FrameInfo(_currentFrame.value, _totalFrames.value)
  }

  fun frameStepForward() {
    viewModelScope.launch(Dispatchers.IO) {
      if (paused != true) {
        pauseUnpause()
        delay(50)
      }
      PlaybackSession.command("no-osd", "frame-step")
      delay(100)
      updateFrameInfo()
      withContext(Dispatchers.Main) {
        showFrameInfoOverlay()
        // Reset the inactivity timer
        resetFrameNavigationTimer()
      }
    }
  }

  fun frameStepBackward() {
    viewModelScope.launch(Dispatchers.IO) {
      if (paused != true) {
        pauseUnpause()
        delay(50)
      }
      PlaybackSession.command("no-osd", "frame-back-step")
      delay(100)
      updateFrameInfo()
      withContext(Dispatchers.Main) {
        showFrameInfoOverlay()
        // Reset the inactivity timer
        resetFrameNavigationTimer()
      }
    }
  }

  private var frameNavigationCollapseJob: Job? = null

  fun resetFrameNavigationTimer() {
    frameNavigationCollapseJob?.cancel()
    frameNavigationCollapseJob =
      viewModelScope.launch {
        delay(10000) // 10 seconds
        if (_isFrameNavigationExpanded.value) {
          _isFrameNavigationExpanded.value = false
        }
      }
  }

  fun takeSnapshot(context: Context) {
    viewModelScope.launch(Dispatchers.IO) {
      _isSnapshotLoading.value = true
      try {
        val includeSubtitles = playerPreferences.includeSubtitlesInSnapshot.get()
        ScreenshotSaver
          .save(
            context = context,
            settings = ScreenshotSettings.fromPreferences(playerPreferences),
            includeSubtitles = includeSubtitles,
          ).getOrThrow()
        withContext(Dispatchers.Main) {
          Toast
            .makeText(
              context,
              context.getString(R.string.player_sheets_frame_navigation_snapshot_saved),
              Toast.LENGTH_SHORT,
            ).show()
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Toast
            .makeText(
              context,
              context.getString(
                R.string.toast_failed_to_save_snapshot,
                e.message ?: context.getString(R.string.generic_unknown_error),
              ),
              Toast.LENGTH_LONG,
            ).show()
        }
      } finally {
        _isSnapshotLoading.value = false
      }
    }
  }

  // ==================== Playlist Management ====================

  fun hasPlaylistSupport(): Boolean = PlaybackSession.queue.value.isExplicitQueue

  fun getPlaylistInfo(): String? {
    val queue = PlaybackSession.queue.value
    if (!queue.isExplicitQueue || queue.currentIndex !in queue.items.indices) return null
    return "${queue.currentIndex + 1}/${queue.items.size}"
  }

  fun isPlaylistM3U(): Boolean = PlaybackSession.queue.value.isM3u

  fun getPlaylistTotalCount(): Int = PlaybackSession.queue.value.items.size

  fun getPlaylistData(): List<app.infinity.mpvz.ui.player.controls.components.sheets.PlaylistItem>? {
    val queue = PlaybackSession.queue.value
    if (!queue.isExplicitQueue || queue.items.isEmpty()) return null

    // Get current video progress
    val currentPos = pos ?: 0
    val currentDuration = duration ?: 0
    val currentProgress =
      if (currentDuration > 0) {
        ((currentPos.toFloat() / currentDuration.toFloat()) * 100f).coerceIn(0f, 100f)
      } else {
        0f
      }

    return queue.items.mapIndexed { index, item ->
      val uri = Uri.parse(NetworkPlaybackUri.normalize(item.originalUri))
      val title = item.title?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
      val resolvedUri =
        if (uri.scheme == "content") {
          uri.extractLocalPath()?.let { Uri.fromFile(File(it)) } ?: uri
        } else {
          uri
        }
      val localPath = uri.extractLocalPath() ?: if (uri.scheme == "file") uri.path else null
      val path = localPath ?: resolvedUri.path?.takeIf { File(it).exists() } ?: resolvedUri.toString()
      val isAudio =
        path
          .substringBefore('?')
          .substringBefore('#')
          .substringAfterLast('.', "")
          .lowercase() in FileTypeUtils.AUDIO_EXTENSIONS ||
          resolvedUri.toString().lowercase().contains("audio") ||
          uri.toString().lowercase().contains("audio")
      val isCurrentlyPlaying = index == queue.currentIndex

      // Try to get from cache first (synchronized access)
      val cacheKey = resolvedUri.toString()
      val extractedDuration =
        item.durationSeconds
          ?.takeIf { seconds -> seconds > 0 }
          ?.let { seconds -> formatDuration(seconds * 1000L) }
          .orEmpty()
      val (durationStr, resolutionStr) =
        synchronized(metadataCache) { metadataCache[cacheKey] }
          ?: (extractedDuration to "")

      app.infinity.mpvz.ui.player.controls.components.sheets.PlaylistItem(
        uri = resolvedUri,
        title = title,
        artist = item.artist.orEmpty(),
        index = index,
        isPlaying = isCurrentlyPlaying,
        path = path,
        progressPercent = if (isCurrentlyPlaying) currentProgress else 0f,
        isWatched = isCurrentlyPlaying && currentProgress >= 95f,
        duration = durationStr,
        resolution = resolutionStr,
        isAudio = isAudio,
        tvgLogo = item.artworkUri.orEmpty(),
        networkConnectionId = item.networkSource?.connectionId,
        networkPath = item.networkSource?.relativePath.orEmpty(),
      )
    }
  }

  private fun getVideoMetadata(uri: Uri): Pair<String, String> {
    val resolvedUri =
      if (uri.scheme == "content") {
        uri.extractLocalPath()?.let { Uri.fromFile(File(it)) } ?: uri
      } else {
        uri
      }

    // Remote queue items use their authenticated thumbnail path instead of local metadata APIs.
    val scheme = resolvedUri.scheme?.lowercase()
    if (scheme == "http" ||
      scheme == "https" ||
      scheme == "rtmp" ||
      scheme == "rtsp" ||
      scheme == "ftp" ||
      scheme == "sftp" ||
      scheme == "smb" ||
      scheme == "davs" ||
      scheme == "mms" ||
      scheme == NetworkPlaybackUri.SCHEME
    ) {
      return "" to ""
    }

    // Skip M3U/M3U8 files
    val uriString = resolvedUri.toString().lowercase()
    if (uriString.contains(".m3u8") || uriString.contains(".m3u")) {
      return "" to ""
    }

    // Try MediaStore first (much faster - uses cached values)
    val mediaStoreMetadata = getVideoMetadataFromMediaStore(resolvedUri)
    if (mediaStoreMetadata != null) {
      return mediaStoreMetadata
    }

    // Fallback to MediaMetadataRetriever only if MediaStore fails
    val retriever = android.media.MediaMetadataRetriever()
    return try {
      // For file:// URIs, use the path directly (faster)
      if (resolvedUri.scheme == "file") {
        retriever.setDataSource(resolvedUri.path)
      } else {
        // For content:// URIs, use context
        retriever.setDataSource(appContext, resolvedUri)
      }

      // Get duration
      val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
      val durationStr =
        if (durationMs != null) {
          formatDuration(durationMs.toLong())
        } else {
          ""
        }

      // Get resolution
      val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
      val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
      val resolutionStr =
        if (width != null && height != null) {
          "${width}x$height"
        } else {
          ""
        }

      durationStr to resolutionStr
    } catch (e: Exception) {
      android.util.Log.e("PlayerViewModel", "Failed to get video metadata for $resolvedUri", e)
      "" to ""
    } finally {
      try {
        retriever.release()
      } catch (e: Exception) {
        // Ignore release errors
      }
    }
  }

  /**
   * Get video metadata from MediaStore (fast - uses cached system values).
   * Returns null if the video is not found in MediaStore.
   */
  private fun getVideoMetadataFromMediaStore(uri: Uri): Pair<String, String>? {
    return try {
      val projection =
        arrayOf(
          android.provider.MediaStore.Video.Media.DURATION,
          android.provider.MediaStore.Video.Media.WIDTH,
          android.provider.MediaStore.Video.Media.HEIGHT,
          android.provider.MediaStore.Video.Media.DATA,
        )

      // Determine the query URI based on the input URI scheme
      val queryUri =
        when (uri.scheme) {
          "content" -> {
            // If it's already a content URI, use it directly
            if (uri.toString().startsWith(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                  .toString(),
              )
            ) {
              uri
            } else {
              // Try to find by path if available
              null
            }
          }
          "file" -> {
            // For file:// URIs, query by path
            null
          }
          else -> null
        }

      // Query by URI if we have a content URI
      if (queryUri != null) {
        appContext.contentResolver
          .query(
            queryUri,
            projection,
            null,
            null,
            null,
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val durationColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DURATION)
              val widthColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.WIDTH)
              val heightColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.HEIGHT)

              val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
              val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
              val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0

              val durationStr = formatDuration(durationMs)

              val resolutionStr =
                if (width > 0 && height > 0) {
                  "${width}x$height"
                } else {
                  ""
                }

              return durationStr to resolutionStr
            }
          }
      }

      // Query by file path if we have a file:// URI or content URI without direct match
      val filePath =
        when (uri.scheme) {
          "file" -> uri.path
          "content" -> {
            // Try to get the file path from content URI
            appContext.contentResolver
              .query(
                uri,
                arrayOf(android.provider.MediaStore.Video.Media.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val dataColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                  if (dataColumn >= 0) cursor.getString(dataColumn) else null
                } else {
                  null
                }
              }
          }
          else -> null
        }

      if (filePath != null) {
        val selection = "${android.provider.MediaStore.Video.Media.DATA} = ?"
        val selectionArgs = arrayOf(filePath)

        appContext.contentResolver
          .query(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val durationColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DURATION)
              val widthColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.WIDTH)
              val heightColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.HEIGHT)

              val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
              val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
              val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0

              val durationStr = formatDuration(durationMs)

              val resolutionStr =
                if (width > 0 && height > 0) {
                  "${width}x$height"
                } else {
                  ""
                }

              return durationStr to resolutionStr
            }
          }
      }

      null
    } catch (e: Exception) {
      android.util.Log.w(
        "PlayerViewModel",
        "Failed to get metadata from MediaStore for $uri, will try MediaMetadataRetriever",
        e,
      )
      null
    }
  }

  /**
   * Format duration in milliseconds to hh:mm:ss or mm:ss format
   */
  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return ""

    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
      String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format("%d:%02d", minutes, seconds)
    }
  }

  fun playPlaylistItem(index: Int) {
    host.playQueueItem(index)
  }

  fun reorderPlaylistItem(
    from: Int,
    to: Int,
  ) {
    host.reorderQueueItem(from, to)
  }

  /**
   * Refreshes the playlist items to update the currently playing indicator.
   * Called when a new video starts playing to update the playlist UI.
   */
  fun refreshPlaylistItems(forceMetadata: Boolean = sheetShown.value == Sheets.Playlist) {
    viewModelScope.launch(Dispatchers.IO) {
      val updatedItems = getPlaylistData()
      if (updatedItems != null) {
        // Clear cache if playlist size changed
        if (_playlistItems.value.size != updatedItems.size) {
          metadataCache.evictAll()
        }

        _playlistItems.value = updatedItems

        if (forceMetadata) {
          // Load metadata only when the playlist sheet is actually in use.
          loadPlaylistMetadataAsync(updatedItems)
        }
      }
    }
  }

  /**
   * Loads metadata for all playlist items asynchronously in the background.
   * Updates the playlist items as metadata becomes available.
   * Uses batched updates to avoid O(n²) complexity with large playlists.
   * Skips metadata extraction for M3U playlists (network streams).
   */
  private fun loadPlaylistMetadataAsync(
    items: List<app.infinity.mpvz.ui.player.controls.components.sheets.PlaylistItem>,
  ) {
    playlistMetadataJob?.cancel()
    playlistMetadataJob =
      viewModelScope.launch(Dispatchers.IO) {
        // Skip metadata extraction for M3U playlists only if they contain network streams
        val queue = PlaybackSession.queue.value
        if (queue.isM3u) {
          val hasNetworkStreams =
            queue.items.any { item ->
              val uri = Uri.parse(NetworkPlaybackUri.normalize(item.originalUri))
              val scheme = uri.scheme?.lowercase()
              scheme == "http" ||
                scheme == "https" ||
                scheme == "davs" ||
                scheme == "smb" ||
                scheme == "ftp" ||
                scheme == "sftp"
            }
          if (hasNetworkStreams) {
            Log.d(TAG, "Skipping metadata extraction for M3U playlist with network streams")
            return@launch
          }
        }

        val metadataItems =
          if (items.size <= PLAYLIST_METADATA_PREFETCH_LIMIT) {
            items
          } else {
            val currentIndex = queue.currentIndex.coerceIn(0, items.lastIndex)
            val startIndex = maxOf(0, currentIndex - PLAYLIST_METADATA_PREFETCH_RADIUS)
            val endIndex = minOf(items.lastIndex, currentIndex + PLAYLIST_METADATA_PREFETCH_RADIUS)
            items.subList(startIndex, endIndex + 1)
          }.filter { item -> queue.items.getOrNull(item.index)?.durationSeconds == null }

        // Limit concurrent metadata extraction to avoid overwhelming resources
        val batchSize = 5
        metadataItems.chunked(batchSize).forEach { batch ->
          val updates = mutableMapOf<String, Pair<String, String>>()

          // Extract metadata for the batch
          batch.forEach { item ->
            val cacheKey = item.uri.toString()

            // Skip if already in cache (LruCache is thread-safe)
            if (metadataCache.get(cacheKey) == null) {
              // Extract metadata
              val (durationStr, resolutionStr) = getVideoMetadata(item.uri)

              // Update cache and track update
              updateMetadataCache(cacheKey, durationStr to resolutionStr)
              updates[cacheKey] = durationStr to resolutionStr
            }
          }

          // Apply all batched updates at once (single playlist update)
          if (updates.isNotEmpty()) {
            _playlistItems.value =
              _playlistItems.value.map { currentItem ->
                val cacheKey = currentItem.uri.toString()
                val (durationStr, resolutionStr) = updates[cacheKey] ?: return@map currentItem
                currentItem.copy(duration = durationStr, resolution = resolutionStr)
              }
          }
        }
      }
  }

  fun hasNext(): Boolean = PlaybackSession.hasNext()

  fun hasPrevious(): Boolean = PlaybackSession.hasPrevious()

  fun playNext() {
    host.playNextQueueItem()
  }



  fun playPrevious() {
    host.playPreviousQueueItem()
  }

  // ==================== Repeat and Shuffle ====================

  fun applyPersistedShuffleState() {
    PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)
    if (_shuffleEnabled.value) {
      host.onQueueShuffleChanged(true)
    }
  }

  fun cycleRepeatMode() {
    val hasPlaylist = PlaybackSession.queue.value.isExplicitQueue

    _repeatMode.value =
      when (_repeatMode.value) {
        RepeatMode.OFF -> RepeatMode.ONE
        RepeatMode.ONE -> if (hasPlaylist) RepeatMode.ALL else RepeatMode.OFF
        RepeatMode.ALL -> RepeatMode.OFF
      }

    // Persist the repeat mode
    playerPreferences.repeatMode.set(_repeatMode.value)
    PlaybackSession.setRepeatMode(_repeatMode.value)
    if (host.isNativeEngineActive()) host.nativeSetRepeatMode(_repeatMode.value)

    // Show overlay update instead of toast
    playerUpdate.value = PlayerUpdates.RepeatMode(_repeatMode.value)
  }

  fun toggleShuffle() {
    _shuffleEnabled.value = !_shuffleEnabled.value

    // Persist the shuffle state
    playerPreferences.shuffleEnabled.set(_shuffleEnabled.value)
    PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)

    // Notify activity to handle shuffle state change
    host.onQueueShuffleChanged(_shuffleEnabled.value)

    // Show overlay update instead of toast
    playerUpdate.value = PlayerUpdates.Shuffle(_shuffleEnabled.value)
  }

  fun shouldRepeatCurrentFile(): Boolean =
    _repeatMode.value == RepeatMode.ONE ||
      (_repeatMode.value == RepeatMode.ALL && !PlaybackSession.queue.value.isExplicitQueue)

  fun shouldRepeatPlaylist(): Boolean =
    _repeatMode.value == RepeatMode.ALL && PlaybackSession.queue.value.isExplicitQueue

  // ==================== A-B Loop ====================

  fun toggleABLoopExpanded() {
    _abLoopState.update { it.copy(isExpanded = !it.isExpanded) }
  }

  fun setLoopA() {
    if (_abLoopState.value.a != null) {
      _abLoopState.update { it.copy(a = null) }
      if (host.isNativeEngineActive()) host.nativeSetLoopA(null) else PlaybackSession.setPropertyString("ab-loop-a", "no")
      return
    }
    val currentPos = if (host.isNativeEngineActive()) {
      host.nativePlaybackPositionSeconds()
    } else {
      PlaybackSession.getPropertyDouble("time-pos") ?: return
    }
    _abLoopState.update { it.copy(a = currentPos) }
    if (host.isNativeEngineActive()) host.nativeSetLoopA(currentPos) else PlaybackSession.setPropertyDouble("ab-loop-a", currentPos)
  }

  fun setLoopB() {
    if (_abLoopState.value.b != null) {
      _abLoopState.update { it.copy(b = null) }
      if (host.isNativeEngineActive()) host.nativeSetLoopB(null) else PlaybackSession.setPropertyString("ab-loop-b", "no")
      return
    }
    val currentPos = if (host.isNativeEngineActive()) {
      host.nativePlaybackPositionSeconds()
    } else {
      PlaybackSession.getPropertyDouble("time-pos") ?: return
    }
    _abLoopState.update { it.copy(b = currentPos) }
    if (host.isNativeEngineActive()) host.nativeSetLoopB(currentPos) else PlaybackSession.setPropertyDouble("ab-loop-b", currentPos)
  }

  fun clearABLoop() {
    _abLoopState.update { it.copy(a = null, b = null) }
    if (host.isNativeEngineActive()) {
      host.nativeClearLoop()
    } else {
      PlaybackSession.setPropertyString("ab-loop-a", "no")
      PlaybackSession.setPropertyString("ab-loop-b", "no")
    }
  }

  fun formatTimestamp(seconds: Double): String {
    val totalSec = seconds.toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
  }

  // ==================== Mirroring ====================

  fun toggleMirroring() {
    val newMirrorState = !_transformState.value.isMirrored
    _transformState.update { it.copy(isMirrored = newMirrorState) }

    // Use labeled video filter for mirroring to avoid state desync
    if (newMirrorState) {
      PlaybackSession.command("vf", "add", "@Mpv∞_hflip:hflip")
    } else {
      PlaybackSession.command("vf", "remove", "@Mpv∞_hflip")
    }
    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(
          if (newMirrorState) R.string.player_horizontal_flip_on else R.string.player_horizontal_flip_off,
        ),
      )
  }

  fun toggleVerticalFlip() {
    val newState = !_transformState.value.isVerticalFlipped
    _transformState.update { it.copy(isVerticalFlipped = newState) }

    // Use labeled video filter for vflip to avoid state desync
    if (newState) {
      PlaybackSession.command("vf", "add", "@Mpv∞_vflip:vflip")
    } else {
      PlaybackSession.command("vf", "remove", "@Mpv∞_vflip")
    }

    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(if (newState) R.string.player_vertical_flip_on else R.string.player_vertical_flip_off),
      )
  }

  fun toggleHdrScreenOutput() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) return
    val nextMode =
      if (_hdrScreenMode.value == HdrScreenMode.OFF) {
        val lastMode = decoderPreferences.lastHdrMode.get()
        val targetMode = if (lastMode == HdrScreenMode.OFF) HdrScreenMode.defaultEnabledMode else lastMode
        if (targetMode == HdrScreenMode.LINEAR && !isLinearHdrAvailable.value) {
          HdrScreenMode.defaultEnabledMode
        } else {
          targetMode
        }
      } else {
        HdrScreenMode.OFF
      }
    setHdrScreenMode(nextMode)
  }

  fun setHdrScreenMode(mode: HdrScreenMode) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) return
    val resolvedMode =
      if (mode == HdrScreenMode.LINEAR && !isLinearHdrAvailable.value) {
        HdrScreenMode.defaultEnabledMode
      } else {
        mode
      }
    val pipelineReady = isHdrScreenOutputAvailable(resolvedMode)

    _hdrScreenMode.value = resolvedMode
    _isHdrScreenOutputEnabled.value = pipelineReady && resolvedMode != HdrScreenMode.OFF
    decoderPreferences.hdrScreenMode.set(resolvedMode)
    decoderPreferences.hdrScreenOutput.set(resolvedMode != HdrScreenMode.OFF)
    if (resolvedMode != HdrScreenMode.OFF) {
      decoderPreferences.lastHdrMode.set(resolvedMode)
    }
    applyHdrScreenOutput(resolvedMode)
    restartAmbientIfActive()
    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(R.string.hdr_screen_output_update, appContext.getString(resolvedMode.shortTitleRes)),
      )
  }

  private fun isHdrScreenOutputAvailable(mode: HdrScreenMode = _hdrScreenMode.value): Boolean =
    mode != HdrScreenMode.LINEAR || isLinearHdrAvailable.value

  private fun initialHdrScreenMode(): HdrScreenMode {
    if (!decoderPreferences.hdrScreenOutput.get()) {
      return HdrScreenMode.OFF
    }
    val savedMode = decoderPreferences.hdrScreenMode.get()
    if (savedMode == HdrScreenMode.OFF) {
      return HdrScreenMode.OFF
    }
    return if (savedMode == HdrScreenMode.LINEAR &&
      !(decoderPreferences.gpuNext.get() && decoderPreferences.useVulkan.get())
    ) HdrScreenMode.defaultEnabledMode else savedMode
  }

  private fun reconcileHdrModeWithRenderer() {
    if (_hdrScreenMode.value == HdrScreenMode.LINEAR && !isLinearHdrAvailable.value) {
      setHdrScreenMode(HdrScreenMode.defaultEnabledMode)
    } else {
      refreshHdrScreenOutputPipelineState()
    }
  }

  private fun refreshHdrScreenOutputPipelineState(): Boolean {
    val pipelineReady = isHdrScreenOutputAvailable()
    _isHdrScreenOutputPipelineReady.value = pipelineReady
    _isHdrScreenOutputEnabled.value = pipelineReady && _hdrScreenMode.value != HdrScreenMode.OFF
    return pipelineReady
  }

  private fun applyHdrScreenOutput(mode: HdrScreenMode) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
      _isHdrScreenOutputPipelineReady.value = false
      _isHdrScreenOutputEnabled.value = false
      return
    }
    val pipelineReady = refreshHdrScreenOutputPipelineState()
    runCatching {
      val boostSdr = decoderPreferences.boostSdrToHdr.get()
      applyHdrScreenOutputOptions(mode, pipelineReady, boostSdr)
      applyHdrScreenOutputProperties(mode, pipelineReady, boostSdr)
      applyHdrToysMode(mode, pipelineReady)
    }.onFailure { e ->
      Log.e(TAG, "Error applying HDR screen output: mode=$mode, pipelineReady=$pipelineReady", e)
    }
  }

  /** Re-applies the current HDR mode to a newly loaded video. */
  fun refreshHdrScreenOutputForCurrentVideo() {
    applyHdrScreenOutput(_hdrScreenMode.value)
  }

  fun selectAnime4KMode(mode: Anime4KManager.Mode) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) return
    decoderPreferences.anime4kMode.set(mode.name)
    viewModelScope.launch(Dispatchers.Default) {
      runCatching {
        val shouldRefreshShaderStack =
          if (mode == Anime4KManager.Mode.OFF) {
            clearAnime4KShaders()
            true
          } else {
            val selection =
              selectRuntimeStableAnime4K(
                mode = mode,
                quality = decoderPreferences.anime4kQuality.get(),
                context = appContext,
                enableIn4k = decoderPreferences.anime4kIn4k.get(),
              )
            if (selection.mode == Anime4KManager.Mode.OFF) {
              clearAnime4KShaders()
              true
            } else {
              anime4kManager.setPostFilters(
                darken = decoderPreferences.anime4kDarken.get(),
                thin = decoderPreferences.anime4kThin.get(),
                deblur = decoderPreferences.anime4kDeblur.get(),
              )
              applyAnime4KShaderChain(anime4kManager, selection.mode, selection.quality).also { applied ->
                if (applied) {
                  applyAnime4KStabilityOptions(
                    useVulkan = PlaybackSession.getPropertyString("gpu-api") == "vulkan",
                  )
                }
              }
            }
          }

        if (shouldRefreshShaderStack) {
          restartHdrScreenOutputAndAmbientIfActive()
        }
      }.onFailure { error ->
        Log.e(TAG, "Failed to apply Anime4K mode ${mode.name}", error)
      }
    }
  }

  /**
   * Called after Anime4K or file changes so HDR remains layered with the rest of
   * the shader stack, then the ambient shader is moved back to the final pass.
   */
  fun restartHdrScreenOutputAndAmbientIfActive() {
    refreshHdrScreenOutputForCurrentVideo()
    restartAmbientIfActive()
  }

  private fun applyHdrToysMode(
    mode: HdrScreenMode,
    pipelineReady: Boolean,
  ) {
    val profile = mode.hdrToysProfile
    if (!pipelineReady || profile == null) {
      hdrToysManager.clear()
      return
    }
    if (!hdrToysManager.apply(profile)) {
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_hdr_shaders_unavailable))
    }
  }

  // ==================== Ambient Mode Integration ====================

  fun setAmbientLifecycleActive(active: Boolean) {
    if (_isAmbientLifecycleActive.value == active) return
    _isAmbientLifecycleActive.value = active
    if (active) {
      scheduleAmbientUpdate(0)
    } else {
      disableAmbientShader()
    }
  }

  private fun isAmbientRuntimeActive(): Boolean =
    _isAmbientLifecycleActive.value &&
      _isMpvCoreReady.value &&
      _isAmbientEnabled.value &&
      !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)

  private fun isAmbientGlowRuntimeActive(): Boolean =
    isAmbientRuntimeActive() && _ambientStyle.value == AmbientStyle.Glow

  fun toggleAmbientMode() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    _isAmbientEnabled.value = !_isAmbientEnabled.value
    playerPreferences.isAmbientEnabled.set(_isAmbientEnabled.value)
    if (_isAmbientEnabled.value) {
      if (_ambientStyle.value == AmbientStyle.Glow) {
        lastAmbientScaleX = -1.0
        scheduleAmbientUpdate(0)
      }
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambience_on))
    } else {
      disableAmbientShader()
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambience_off))
    }
  }

  /** Switches between the shader-backed Glow and frame-captured YouTube styles. */
  fun setAmbientStyle(style: AmbientStyle) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    if (_ambientStyle.value == style) return
    _ambientStyle.value = style
    playerPreferences.ambientStyle.set(style)
    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(R.string.ambient_style_update, appContext.getString(style.titleRes)),
      )
    if (style == AmbientStyle.Glow) {
      lastAmbientScaleX = -1.0
      scheduleAmbientUpdate(0)
    } else {
      disableAmbientShader()
    }
  }

  /** Disables the ambient shader and resets video scale. Safe to call from any state. */
  private fun disableAmbientShader() {
    synchronized(ambientScheduleLock) {
      ambientUpdateGeneration.incrementAndGet()
      ambientDebounceJob?.cancel()
      ambientDebounceJob = null
    }
    synchronized(ambientRenderLock) {
      ambientShaderFile?.let { file ->
        runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", file.absolutePath) }
        file.delete()
      }
      ambientShaderFile = null
      // Reset the shader cache and scale tracking so a subsequent enable always
      // compiles a fresh shader and recalculates the correct video-scale offsets.
      lastCompiledSpec = null
      lastAmbientScaleX = -1.0
      lastAmbientScaleY = -1.0
      runCatching {
        PlaybackSession.setPropertyDouble("video-scale-x", 1.0)
        PlaybackSession.setPropertyDouble("video-scale-y", 1.0)
        PlaybackSession.setPropertyString("blend-subtitles", "no")
      }
    }
  }

  /** Called when the device orientation changes. Refreshes Glow for the new output dimensions. */
  fun onOrientationChanged() {
    if (!isAmbientGlowRuntimeActive()) return

    // The compiled spec is the cache key; scale sentinels alone do not force a rebuild.
    lastCompiledSpec = null
    lastAmbientScaleX = -1.0
    lastAmbientScaleY = -1.0
    scheduleAmbientUpdate(200)
  }

  /** Removes the old file-specific ambient shader while preserving the user's selected ambient mode. */
  fun prepareAmbientForNewVideo() {
    if (!_isAmbientEnabled.value || MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    disableAmbientShader()
    lastAmbientScaleX = -1.0
    lastAmbientScaleY = -1.0
  }

  /**
   * Re-injects the ambient shader if ambient mode is currently ON.
   * Called after shader-stack changes so ambient stays as the last OUTPUT pass.
   */
  fun restartAmbientIfActive() {
    if (!isAmbientGlowRuntimeActive()) return
    disableAmbientShader()
    // Small delay to let Anime4K shaders settle.
    scheduleAmbientUpdate(200)
  }

  fun updateAmbientParams(
    blurSamples: Int = _ambientBlurSamples.value,
    maxRadius: Float = _ambientMaxRadius.value,
    glowIntensity: Float = _ambientGlowIntensity.value,
    satBoost: Float = _ambientSatBoost.value,
    vignetteStrength: Float = _ambientVignetteStrength.value,
    warmth: Float = _ambientWarmth.value,
    fadeCurve: Float = _ambientFadeCurve.value,
    opacity: Float = _ambientOpacity.value,
  ) {
    _ambientBlurSamples.value = blurSamples
    _ambientMaxRadius.value = maxRadius
    _ambientGlowIntensity.value = glowIntensity
    _ambientSatBoost.value = satBoost
    _ambientVignetteStrength.value = vignetteStrength
    _ambientWarmth.value = warmth
    _ambientFadeCurve.value = fadeCurve
    _ambientOpacity.value = opacity

    // Persist to preferences
    playerPreferences.ambientBlurSamples.set(blurSamples)
    playerPreferences.ambientMaxRadius.set(maxRadius)
    playerPreferences.ambientGlowIntensity.set(glowIntensity)
    playerPreferences.ambientSatBoost.set(satBoost)
    playerPreferences.ambientVignetteStrength.set(vignetteStrength)
    playerPreferences.ambientWarmth.set(warmth)
    playerPreferences.ambientFadeCurve.set(fadeCurve)
    playerPreferences.ambientOpacity.set(opacity)

    scheduleAmbientUpdate()
  }

  private fun scheduleAmbientUpdate(delayMs: Long = 150L) {
    synchronized(ambientScheduleLock) {
      if (!isAmbientGlowRuntimeActive()) return

      val generation = ambientUpdateGeneration.incrementAndGet()
      ambientDebounceJob?.cancel()
      ambientDebounceJob =
        viewModelScope.launch(renderPrepDispatcher) {
          delay(delayMs)
          updateAmbientStretch(generation)
        }
    }
  }

  fun applyAmbientProfileFast() {
    val preset = AmbientShaderPresets.glowFast
    updateAmbientParams(
      blurSamples = preset.blurSamples,
      maxRadius = preset.maxRadius,
      glowIntensity = preset.glowIntensity,
      satBoost = preset.satBoost,
      vignetteStrength = preset.vignetteStrength,
      warmth = preset.warmth,
      fadeCurve = preset.fadeCurve,
      opacity = preset.opacity,
    )
  }

  /** Balanced profile — good quality/performance trade-off for most devices. */
  fun applyAmbientProfileBalanced() {
    val preset = AmbientShaderPresets.glowBalanced
    updateAmbientParams(
      blurSamples = preset.blurSamples,
      maxRadius = preset.maxRadius,
      glowIntensity = preset.glowIntensity,
      satBoost = preset.satBoost,
      vignetteStrength = preset.vignetteStrength,
      warmth = preset.warmth,
      fadeCurve = preset.fadeCurve,
      opacity = preset.opacity,
    )
  }

  /** High Quality profile — maximum visual fidelity for high-end devices. */
  fun applyAmbientProfileHighQuality() {
    val preset = AmbientShaderPresets.glowHighQuality
    updateAmbientParams(
      blurSamples = preset.blurSamples,
      maxRadius = preset.maxRadius,
      glowIntensity = preset.glowIntensity,
      satBoost = preset.satBoost,
      vignetteStrength = preset.vignetteStrength,
      warmth = preset.warmth,
      fadeCurve = preset.fadeCurve,
      opacity = preset.opacity,
    )
  }

  fun updateAmbientBatterySaver(enabled: Boolean) {
    _isAmbientBatterySaver.value = enabled
    playerPreferences.ambientBatterySaver.set(enabled)
    if (enabled && _isAmbientEnabled.value) {
      applyBatterySaverPolicy()
    } else if (!enabled && ambientWasOnBattery && _isAmbientEnabled.value) {
      restoreFromBatterySaver()
    }
  }

  private fun applyBatterySaverPolicy() {
    if (_ambientBlurSamples.value <= 4) return
    ambientPreBatterySaverSamples = _ambientBlurSamples.value
    ambientPreBatterySaverRadius = _ambientMaxRadius.value
    ambientPreBatterySaverIntensity = _ambientGlowIntensity.value
    ambientPreBatterySaverSatBoost = _ambientSatBoost.value
    ambientPreBatterySaverVignette = _ambientVignetteStrength.value
    ambientPreBatterySaverWarmth = _ambientWarmth.value
    ambientPreBatterySaverFadeCurve = _ambientFadeCurve.value
    ambientPreBatterySaverOpacity = _ambientOpacity.value
    ambientWasOnBattery = true
    applyAmbientProfileFast()
    playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambient_battery_saver_on))
  }

  private fun restoreFromBatterySaver() {
    if (!ambientWasOnBattery) return
    ambientWasOnBattery = false
    updateAmbientParams(
      blurSamples = ambientPreBatterySaverSamples,
      maxRadius = ambientPreBatterySaverRadius,
      glowIntensity = ambientPreBatterySaverIntensity,
      satBoost = ambientPreBatterySaverSatBoost,
      vignetteStrength = ambientPreBatterySaverVignette,
      warmth = ambientPreBatterySaverWarmth,
      fadeCurve = ambientPreBatterySaverFadeCurve,
      opacity = ambientPreBatterySaverOpacity,
    )
    playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambient_battery_saver_off))
  }

  fun onBatteryStateChanged(isCharging: Boolean) {
    if (!_isAmbientBatterySaver.value || !_isAmbientEnabled.value) return
    if (isCharging) {
      restoreFromBatterySaver()
    } else {
      applyBatterySaverPolicy()
    }
  }

  private suspend fun updateAmbientStretch(generation: Long) {
    if (!isAmbientGlowRuntimeActive() || generation != ambientUpdateGeneration.get()) return

    runCatching {
      val osdW = PlaybackSession.getPropertyInt("osd-width") ?: 1920
      val osdH = PlaybackSession.getPropertyInt("osd-height") ?: 1080

      // Portrait mode: ambient glow goes on top/bottom (letterbox)
      // Landscape mode: ambient glow goes on left/right (pillarbox)
      // Both are handled by the same scaleX/scaleY math below

      val vidAr = VideoAspectGeometry.currentEffectiveDisplayAspect() ?: return

      if (osdW <= 0 || osdH <= 0) return

      val screenAr = osdW.toDouble() / osdH.toDouble()

      // Scale the video to fill the screen — the shader remaps it back to the
      // correct aspect ratio, so only the "overflow" area receives ambient glow.
      val scaleX = if (screenAr > vidAr) screenAr / vidAr else 1.0
      val scaleY = if (vidAr > screenAr) vidAr / screenAr else 1.0

      // ── Snapshot current parameter values ─────────────────────────────────
      val sx = scaleX
      val sy = scaleY
      // Thermal-aware sample budget: cap shader complexity before the device enters
      // hard CPU/GPU throttling.  On a cool device this is a no-op.
      val rawSamples = _ambientBlurSamples.value
      val samples = ThermalMonitor.clampAmbientSampleBudget(rawSamples, thermalHeadroom)
      val radius = _ambientMaxRadius.value
      val glow = _ambientGlowIntensity.value
      val sat = _ambientSatBoost.value
      val vignette = _ambientVignetteStrength.value
      val warmth = _ambientWarmth.value
      val curve = _ambientFadeCurve.value
      val opacity = _ambientOpacity.value

      // ── Generate GLSL shader ───────────────────────────────────────────────
      val spec =
        buildAmbientSpec(
          sx = sx,
          sy = sy,
          blurSamples = samples,
          maxRadius = radius,
          glowIntensity = glow,
          satBoost = sat,
          vignetteStrength = vignette,
          warmth = warmth,
          fadeCurve = curve,
          opacity = opacity,
        )

      // ── Shader parameter cache ──────────────────────────────────────────────────────
      // Compare the AmbientGlowShaderSpec data class (cheap equality) before building
      // the GLSL string. This avoids allocating the multi-KB shader string and
      // running buildSpiralTapTable trig math on no-op refreshes (e.g. thermal
      // monitor ticks that don't change the effective sample budget, orientation
      // callbacks that fire with unchanged video dimensions).
      val shaderIsCurrent =
        synchronized(ambientRenderLock) {
          spec == lastCompiledSpec && ambientShaderFile?.exists() == true
        }
      if (shaderIsCurrent) {
        return
      }

      // Each reload gets a unique filename so MPV never reuses a cached
      // compiled shader — incrementing seq guarantees a fresh compile every time.
      val shaderCode = AmbientShaderBuilder.build(appContext, spec)
      val newFile = File(appContext.cacheDir, "ambient_${ambientShaderSeq.incrementAndGet()}.glsl")
      // Blocking file write — dispatched to IO pool to avoid stalling renderPrepDispatcher.
      // Catch CancellationException here: IO is not preemptible, so the write always
      // completes fully even when the job is cancelled mid-flight. Without this guard,
      // the file would be written but never tracked or deleted (disk leak on every
      // superseded debounced update — fast slider drags, orientation flips, etc.).
      try {
        withContext(kotlinx.coroutines.Dispatchers.IO) { newFile.writeText(shaderCode) }
      } catch (error: Throwable) {
        newFile.delete()
        throw error
      }
      currentCoroutineContext().ensureActive()

      synchronized(ambientRenderLock) {
        if (!isAmbientGlowRuntimeActive() || generation != ambientUpdateGeneration.get()) {
          newFile.delete()
          return@synchronized
        }
        ambientShaderFile?.let { oldFile ->
          runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", oldFile.absolutePath) }
          oldFile.delete()
        }
        PlaybackSession.setPropertyDouble("video-scale-x", scaleX)
        PlaybackSession.setPropertyDouble("video-scale-y", scaleY)
        val blendMode = if (subtitlesPreferences.blendSubtitlesWithVideo.get()) "video" else "no"
        PlaybackSession.setPropertyString("blend-subtitles", blendMode)
        PlaybackSession.command("change-list", "glsl-shaders", "append", newFile.absolutePath)
        lastAmbientScaleX = scaleX
        lastAmbientScaleY = scaleY
        ambientShaderFile = newFile
        lastCompiledSpec = spec
      }
    }.onFailure { e ->
      // runCatching catches Throwable including CancellationException — rethrow it so
      // structured concurrency is not broken and debounce cancellation does not log
      // spurious "Failed to update ambient stretch" stack traces that would mask real errors.
      if (e is kotlinx.coroutines.CancellationException) throw e
      Log.e(TAG, "Failed to update ambient stretch", e)
    }
  }

  /**
  * Builds an [AmbientGlowShaderSpec] from the current ambient parameter values.
   * The spec is a lightweight data class that captures all shader inputs;
   * [AmbientShaderBuilder.build] converts it to a GLSL string only when the
   * spec has actually changed from the last compiled version.
   */
  private fun buildAmbientSpec(
    sx: Double,
    sy: Double,
    blurSamples: Int,
    maxRadius: Float,
    glowIntensity: Float,
    satBoost: Float,
    vignetteStrength: Float,
    warmth: Float,
    fadeCurve: Float,
    opacity: Float,
  ): AmbientGlowShaderSpec {
    val context = AmbientRenderContext(scaleX = sx, scaleY = sy)
    val shared =
      AmbientSharedShaderConfig(
        bezelDepth = 0f,
        vignetteStrength = vignetteStrength,
        opacity = opacity,
      )

    return AmbientGlowShaderSpec(
      context = context,
      shared = shared,
      blurSamples = blurSamples,
      maxRadius = maxRadius,
      glowIntensity = glowIntensity,
      satBoost = satBoost,
      warmth = warmth,
      fadeCurve = fadeCurve,
    )
  }

  // ==================== Utility ====================

  fun showToast(message: String) {
    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
  }

  override fun onCleared() {
    // Cancel manually retained jobs before releasing native/player resources. ViewModel's
    // automatic scope cancellation happens after onCleared(), and a dispatcher worker that is
    // still unwinding can otherwise retain this ViewModel (observed in LeakCanary).
    hostReference.clear()
    subtitleSearchJob?.cancel()
    subtitleSearchJob = null
    mediaSearchJob?.cancel()
    mediaSearchJob = null
    playlistMetadataJob?.cancel()
    introLookupJob?.cancel()
    autoCropJob?.cancel()
    autoCropReadinessJob?.cancel()
    mpvStateCollectorsJob?.cancel()
    androidSystemInfoBridgeJob?.cancel()
    customButtonsSetupJob?.cancel()
    equalizerMpvDebounceJob?.cancel()
    timerJob?.cancel()
    videoHashJob?.cancel()
    ambientDebounceJob?.cancel()
    seekCoalesceJob?.cancel()
    seekPreviewJob?.cancel()
    frameSeekJob?.cancel()
    frameNavigationCollapseJob?.cancel()
    viewModelScope.cancel()

    // Deterministic cleanup of resources that previously relied on GC.
    // viewModelScope is auto-cancelled by ViewModel, but the following
    // resources are not coroutine-scoped and need explicit release.
    // See issue 2.1 in the leak audit.

    // The metadataCache (Pair<String, String> entries) is small and
    // bounded at 100 entries, so it is not urgent to clear, but clearing
    // here keeps the working set fresh for the next session.
    runCatching { metadataCache.evictAll() }

    runCatching { audioEqualizerManager.release() }
    _isAmbientLifecycleActive.value = false
    runCatching { disableAmbientShader() }

    super.onCleared()
  }
}

// Extension functions
fun Float.normalize(
  inMin: Float,
  inMax: Float,
  outMin: Float,
  outMax: Float,
): Float = (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin

fun <T> Flow<T>.collectAsState(
  scope: CoroutineScope,
  initialValue: T? = null,
) = object : ReadOnlyProperty<Any?, T?> {
  private var value: T? = initialValue

  init {
    scope.launch { collect { value = it } }
  }

  override fun getValue(
    thisRef: Any?,
    property: KProperty<*>,
  ) = value
}

private fun String.md5(): String {
  val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
  return digest.joinToString("") { byte -> "%02x".format(byte) }
}
