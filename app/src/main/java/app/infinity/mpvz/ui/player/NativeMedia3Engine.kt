package app.infinity.mpvz.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.SurfaceView
import java.io.File
import app.infinity.mpvz.R
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.audio.AudioProcessor
import app.infinity.mpvz.ui.player.audio.AudioOutputRouter
import app.infinity.mpvz.ui.player.audio.DownmixLimiterAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import app.infinity.mpvz.ui.player.LibassSubtitleSurfaceView
import androidx.media3.subtitle.libass.LibassSubtitleRenderer
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

private object NativeMedia3Cache {
  @Volatile private var cache: SimpleCache? = null
  @Synchronized
  fun get(context: Context): SimpleCache {
    return cache ?: SimpleCache(
      java.io.File(context.applicationContext.cacheDir, "media3-native-cache"),
      LeastRecentlyUsedCacheEvictor(2048L * 1024L * 1024L),
      StandaloneDatabaseProvider(context.applicationContext),
    ).also { cache = it }
  }
}

data class NativePlaybackSnapshot(
  val isPlaying: Boolean = false,
  val isReady: Boolean = false,
  val isBuffering: Boolean = false,
  val isLoading: Boolean = false,
  val isEnded: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val totalBufferedDurationMs: Long = 0L,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val videoMimeType: String? = null,
  val videoCodec: String? = null,
  val videoDecoder: String? = null,
  val audioDecoder: String? = null,
  val videoDynamicRange: String? = null,
  val videoColorSpace: String? = null,
  val videoBitrate: Int = 0,
  val audioCodec: String? = null,
  val audioBitrate: Int = 0,
  val audioChannels: Int = 0,
  val audioSampleRate: Int = 0,
  val speed: Float = 1f,
  val subtitleTracks: List<NativeTrack> = emptyList(),
  val audioTracks: List<NativeTrack> = emptyList(),
  val chapters: List<NativeChapter> = emptyList(),
)

data class NativeTrack(
  val groupIndex: Int,
  val trackIndex: Int,
  val type: Int,
  val label: String,
  val language: String?,
  val selected: Boolean,
  val formatId: String? = null,
  val external: Boolean = false,
)

private data class NativeSubtitleSelection(
  val groupId: String?,
  val formatId: String?,
  val label: String?,
  val language: String?,
  val mimeType: String?,
  val codecs: String?,
)

data class AspectTimeSegment(
  val startMs: Long,
  val endMs: Long,
  val ratio: Float,
)

data class NativeChapter(
  val title: String,
  val startSeconds: Float,
)

/** A source-local Android Media3 playback engine. */
class NativeMedia3Engine(context: Context) {
  private val appContext = context.applicationContext
  private val logTag = "Mpv∞-Media3"
  private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(15_000)
    .setReadTimeoutMs(30_000)
  // Direct streaming into RAM without intermediate flash storage I/O thrashing on high-bitrate 4K streams
  private val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)
  private val assHandler = AssHandler(renderType = AssRenderType.OVERLAY_CANVAS)
  private val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)

  /**
   * Tracks whether the sound is leaving through the phone speaker, wired headphones, or Bluetooth.
   * The downmix/crossfeed stage is tuned per route; without this the binaural filter would also
   * run on the tiny built-in speakers and audibly narrow the stereo image there.
   */
  private val audioOutputRouter = AudioOutputRouter(context.applicationContext)

  /** Most recent audio processor handed to the sink, kept so route changes can retune it. */
  private val registeredAudioProcessor = java.util.concurrent.atomic.AtomicReference<DownmixLimiterAudioProcessor?>(null)

  private val extractorsFactory: ExtractorsFactory =
    androidx.media3.extractor.DefaultExtractorsFactory()
      .withAssMkvSupport(assSubtitleParserFactory, assHandler)
  private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
    .setSubtitleParserFactory(assSubtitleParserFactory)
  private val player = ExoPlayer.Builder(context.applicationContext)
    .setLoadControl(
      run {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val totalMemMb = (memInfo.totalMem / (1024 * 1024)).coerceAtLeast(1024L)
        val isLowRam = activityManager?.isLowRamDevice == true || totalMemMb < 3500L
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).coerceAtLeast(64L)

        val targetBufferBytes = when {
          isLowRam || maxHeapMb < 256 -> 32 * 1024 * 1024       // 32MB: Safe for 32-bit / Low-RAM
          totalMemMb < 6000L || maxHeapMb < 512 -> 64 * 1024 * 1024 // 64MB: Mid-range devices
          else -> 128 * 1024 * 1024                             // 128MB: High-end 64-bit devices
        }
        val maxBufferMs = if (isLowRam) 45_000 else 120_000
        val minBufferMs = if (isLowRam) 15_000 else 30_000
        val startBufferMs = if (isLowRam) 1_500 else 2_000

        DefaultLoadControl.Builder()
          .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            startBufferMs,
            4_000,
          )
          .setBackBuffer(if (isLowRam) 10_000 else 30_000, false)
          .setTargetBufferBytes(targetBufferBytes)
          .setPrioritizeTimeOverSizeThresholds(true)
          .build()
      },
    )
    // Xiaomi's 4K HDR decoder can report no loading progress while the SurfaceView and codec are
    // being handed over from MPV. Disable this watchdog for Native; a real player/codec error is
    // still delivered through Player.Listener.onPlayerError.
    .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
    .setMediaSourceFactory(mediaSourceFactory)
    .setRenderersFactory(
      object : DefaultRenderersFactory(context.applicationContext) {
        override fun buildAudioSink(
          context: Context,
          enableFloatOutput: Boolean,
          enableAudioTrackPlaybackParams: Boolean
        ): AudioSink? {
          val activeOutput = audioOutputRouter.currentOutput
          val downmixLimiterProcessor = DownmixLimiterAudioProcessor().apply {
            enableLfeMixing = activeOutput.supportsLfeFoldDown
            enableBinauralCrossfeed = activeOutput.supportsCrossfeed
            // Stereo is the dominant source on phones; run the same crossfeed on it instead of
            // silently skipping every 2.0 file, but only when the active route can benefit.
            enableStereoCrossfeed = activeOutput.supportsCrossfeed
            outputFloatingPoint = false // PCM 16-bit guarantees 100% stability on Qualcomm Audio HAL
          }
          registeredAudioProcessor.set(downmixLimiterProcessor)
          return DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(downmixLimiterProcessor))
            // Force standard stereo capabilities: prevents Media3 from opening direct 8-channel passthrough to analog jack
            .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(false) // Prevent Snapdragon ADSP crash on analog stereo jack
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        }
      }
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        .setEnableDecoderFallback(true)
        .withAssSupport(assHandler)
    )
    .build()
  private var attachedView: PlayerView? = null
  private var subtitleOverlay: LibassSubtitleSurfaceView? = null
  private var libassRenderer: LibassSubtitleRenderer? = null
  private val externalAssEnabled = mutableMapOf<String, Boolean>()
  private var subtitleScale = 1f
  // Aspect Map (JSON) Controller for Shifting IMAX Films
  private var activeAspectSegments: List<AspectTimeSegment> = emptyList()
  private var lastAppliedResizeMode: Int? = null
  private var lastAppliedSegmentIndex: Int = -1
  private var isManualAspectOverride: Boolean = false
  private var subtitlePosition = 100
  private var subtitleFontSize = 55
  private var subtitleFontFamily = "sans-serif"
  private var subtitleBold = false
  private var subtitleItalic = false
  private var subtitleTextColor = android.graphics.Color.WHITE
  private var subtitleBorderColor = android.graphics.Color.BLACK
  private var subtitleBackgroundColor = android.graphics.Color.TRANSPARENT
  private var subtitleBorderSize = 3
  private var subtitlePresentationVisible = true
  private val subtitleStyleRunnable = Runnable { applyAssStyle() }
  private val subtitleViewRunnable = Runnable { configureSubtitleView() }
  private var loopASeconds: Double? = null
  private var loopBSeconds: Double? = null
  private val loopHandler = Handler(Looper.getMainLooper())
  private var pendingSeekPositionMs: Long? = null
  private var pendingSeekDisplayPositionMs: Long? = null
  private var pendingInitialSeekPositionMs: Long? = null
  var lastKnownPositionMs: Long = 0L
    private set
  val lastKnownPosition: Long
    get() = maxOf(lastKnownPositionMs, runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L))
  var lastKnownDurationMs: Long = 0L
    private set
  val lastKnownDuration: Long
    get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: lastKnownDurationMs
  private var pendingExternalSelectionId: String? = null
  private val seekRunnable = Runnable {
    val positionMs = pendingSeekPositionMs ?: return@Runnable
    pendingSeekPositionMs = null
    val targetMs = positionMs.coerceAtLeast(0L)
    pendingSeekDisplayPositionMs = targetMs
    // The source is prepared once with a seek-capable extractor. Seeking only moves the existing
    // extractor/decoder forward; it never rebuilds the MediaSource or flushes the surface.
    player.seekTo(targetMs)
    publishPlaybackSnapshot()
  }
  private val timelineRunnable = object : Runnable {
    override fun run() {
      // Keep the subtitle clock alive while paused, buffering, and immediately
      // after a seek. MPV renders from its current clock in all of these states.
      if (player.currentMediaItem == null) return
      publishPlaybackSnapshot()
      val currentPosMs = player.currentPosition.coerceAtLeast(0L)
      if (currentPosMs > 0L) lastKnownPositionMs = currentPosMs
      subtitleOverlay?.setPositionUs(currentPosMs * 1000L)
      if (activeAspectSegments.isNotEmpty()) {
        val segmentIndex = activeAspectSegments.indexOfFirst { currentPosMs in it.startMs..it.endMs }
        if (segmentIndex != -1 && segmentIndex != lastAppliedSegmentIndex) {
          lastAppliedSegmentIndex = segmentIndex
          isManualAspectOverride = false
          val segment = activeAspectSegments[segmentIndex]
          val targetMode = if (segment.ratio < 2.0f) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT // IMAX: 100% full height, 0% frame cut
          } else {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM // Scope: fills 20:9 width
          }
          lastAppliedResizeMode = targetMode
          attachedView?.post {
            attachedView?.resizeMode = targetMode
            attachedView?.requestLayout()
          }
        }
      }
      // Keep the seekbar responsive without forcing a 10 Hz Compose/native snapshot loop on a
      // 4K HDR decoder. Direct commands remain immediate; the UI only needs a quarter-second tick.
      loopHandler.postDelayed(this, 250L)
    }
  }
  private val loopRunnable = object : Runnable {
    override fun run() {
      val a = loopASeconds
      val b = loopBSeconds
      if (a != null && b != null && b > a && player.currentPosition >= (b * 1000.0).toLong()) {
        player.seekTo((a * 1000.0).toLong())
        if (!player.isPlaying) player.play()
      }
      if (a != null && b != null) loopHandler.postDelayed(this, 150L)
    }
  }
  private var subtitleStyle = CaptionStyleCompat(
    android.graphics.Color.WHITE,
    android.graphics.Color.TRANSPARENT,
    android.graphics.Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    android.graphics.Color.BLACK,
    null,
  )
  private val _snapshot = MutableStateFlow(NativePlaybackSnapshot())
  val snapshot: StateFlow<NativePlaybackSnapshot> = _snapshot.asStateFlow()
  private val _subtitleCueText = MutableStateFlow("")
  val subtitleCueText: StateFlow<String> = _subtitleCueText.asStateFlow()
  private var selectedNativeSubtitleKey: Pair<Int, Int>? = null
  private val _hasRenderedFirstFrame = MutableStateFlow(false)
  val hasRenderedFirstFrame: StateFlow<Boolean> = _hasRenderedFirstFrame.asStateFlow()
  val currentPlayer: Player get() = player
  var onEnded: (() -> Unit)? = null
  private var endedUri: Uri? = null
  private var metadataChapters: List<NativeChapter> = emptyList()
  private var preparationStartedAtMs: Long = 0L
  private var preparationUri: Uri? = null
  private var sourceSizeBytes: Long = 0L
  private var videoDecoderName: String? = null
  private var audioDecoderName: String? = null
  private var selectedNativeSubtitleSelection: NativeSubtitleSelection? = null
  private var restoringNativeSubtitleSelection = false

  private val analyticsListener = object : AnalyticsListener {
    override fun onVideoDecoderInitialized(
      eventTime: AnalyticsListener.EventTime,
      decoderName: String,
      initializedTimestampMs: Long,
      initializationDurationMs: Long,
    ) {
      videoDecoderName = decoderName
      publishSnapshot()
    }

    override fun onAudioDecoderInitialized(
      eventTime: AnalyticsListener.EventTime,
      decoderName: String,
      initializedTimestampMs: Long,
      initializationDurationMs: Long,
    ) {
      audioDecoderName = decoderName
      publishSnapshot()
    }
  }

  private val listener = object : Player.Listener {
    override fun onRenderedFirstFrame() {
      val uri = player.currentMediaItem?.localConfiguration?.uri
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "first frame rendered uri=$uri prepareElapsedMs=$elapsed")
      _hasRenderedFirstFrame.value = true
      attachedView?.post {
        ensureLibassRenderer()
        configureSubtitleView()
      }
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == Player.STATE_READY) {
        pendingSeekDisplayPositionMs = null
        val initialSeek = pendingInitialSeekPositionMs
        if (initialSeek != null && initialSeek > 0L) {
          pendingInitialSeekPositionMs = null
          if (kotlin.math.abs(player.currentPosition - initialSeek) > 1500L) {
            Log.i(logTag, "Enforcing initial resume seek to $initialSeek ms (current=${player.currentPosition} ms)")
            player.seekTo(initialSeek)
          }
        }
      }
      Log.d(logTag, "playback state=$playbackState uri=${player.currentMediaItem?.localConfiguration?.uri}")
      if (playbackState == Player.STATE_ENDED) {
        val uri = player.currentMediaItem?.localConfiguration?.uri
        if (uri != null && uri != endedUri) {
          endedUri = uri
          onEnded?.invoke()
        }
      }
    }
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      pendingSeekDisplayPositionMs = null
      pendingInitialSeekPositionMs = null
    }
    override fun onPlayerError(error: PlaybackException) {
      Log.e(logTag, "player error uri=${player.currentMediaItem?.localConfiguration?.uri}", error)
    }

    override fun onCues(cueGroup: CueGroup) {
      // This is only a text bridge for embedded translation. Media3/ass-media still renders the
      // original cue through PlayerView/libass; no cue is converted or re-rendered here.
      _subtitleCueText.value = cueGroup.cues
        .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .joinToString("\n")
    }
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "timeline changed reason=$reason windowCount=${timeline.windowCount} prepareElapsedMs=$elapsed uri=$preparationUri")
    }

    override fun onTracksChanged(tracks: Tracks) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      val types = tracks.groups.joinToString(",") { it.type.toString() }
      val selectedText = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        .sumOf { group -> (0 until group.length).count { group.isTrackSelected(it) } }
      Log.d(logTag, "tracks changed groups=${tracks.groups.size} types=$types selectedText=$selectedText prepareElapsedMs=$elapsed uri=$preparationUri")
      tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
        (0 until group.length).forEach { index ->
          val format = group.getTrackFormat(index)
          Log.d(logTag, "text track group=${group.mediaTrackGroup.id} index=$index selected=${group.isTrackSelected(index)} mime=${format.sampleMimeType} codecs=${format.codecs} label=${format.label} language=${format.language}")
        }
      }
      restoreSelectedNativeSubtitle(tracks)
      applyPendingExternalSelection()
      autoSelectBestAudioTrack(tracks)
      autoSelectBestSubtitleTrack(tracks)
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "loading=$isLoading prepareElapsedMs=$elapsed uri=$preparationUri")
    }

    override fun onMetadata(metadata: Metadata) {
      // Media3 emits transient metadata callbacks while the indexed source is replacing the fast
      // source. Do not erase the already-published chapter list when that callback has no chapters.
      metadataEntriesToChapters(metadata).takeIf { it.isNotEmpty() }?.let { metadataChapters = it }
      publishSnapshot()
    }

    override fun onEvents(player: Player, events: Player.Events) {
      if (events.contains(Player.EVENT_TRACKS_CHANGED)) configureSubtitleView()
      if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
        events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
        events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
        events.contains(Player.EVENT_PLAYER_ERROR)
      ) {
        publishSnapshot()
      }
    }
  }
  init {
    // Keep Native playback in Android's media-audio focus lifecycle. A reused Media3 player can
    // otherwise continue buffering after a video item while the next music item has no focus.
    player.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build(),
      false, // Delegated to PlayerActivity as single source of truth
    )
    player.setHandleAudioBecomingNoisy(false) // PlayerActivity manages noisyReceiver
    assHandler.init(player)
    startAudioOutputTracking()
    Log.i(logTag, "Native Media3 configured: stuckBufferingDetectionTimeoutMs=${Int.MAX_VALUE}")
    // Large UHD/Dolby Vision files can take a long time to decode an exact frame after a seek.
    // Start at the nearest keyframe so the decoder can resume immediately and refill forward.
    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    player.addListener(listener)
    player.addAnalyticsListener(analyticsListener)
  }

  fun attach(view: PlayerView) {
    attachedView?.player = null
    attachedView = view
    subtitleOverlay = view.rootView.findViewById(R.id.media3_subtitle_overlay)
    view.subtitleView?.withAssSupport(assHandler)
    ensureLibassRenderer()
    // SurfaceView is composed in a separate layer and can cover normal sibling Views. Mark it as
    // a media layer so the standalone libass bitmap remains visible above the video surface.
    (view.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
    view.useController = false
    // Do not let a stale portrait measurement stretch native HDR video after rotation.
    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    view.player = player
    configureSubtitleView()
    view.post { if (attachedView === view) configureSubtitleView() }
    startTimelineUpdates()
  }

  /** Re-measure the Media3 surface after the host activity changes orientation. */
  fun refreshAfterConfigurationChange() {
    val view = attachedView ?: return
    view.post {
      if (attachedView !== view) return@post
      view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
      view.requestLayout()
      view.videoSurfaceView?.requestLayout()
      view.invalidate()
      configureSubtitleView()
    }
  }

  private var autoCropScale: Float = 1f
  private var autoCropActive: Boolean = false

  fun setAutoCropActive(active: Boolean) {
    autoCropActive = active
    val view = attachedView ?: return
    view.post {
      if (attachedView !== view) return@post
      (view.videoSurfaceView as? android.view.SurfaceView)?.let { surface ->
        surface.scaleX = 1f
        surface.scaleY = 1f
      }
      val targetMode = if (active) {
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      } else {
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
      }
      lastAppliedResizeMode = targetMode
      view.resizeMode = targetMode
      view.requestLayout()
      view.videoSurfaceView?.requestLayout()
    }
  }

  fun setAutoCropScale(scale: Float) {
    autoCropScale = scale
    setAutoCropActive(scale > 1.02f)
  }

  private fun applySurfaceScale() {
    (attachedView?.videoSurfaceView as? android.view.SurfaceView)?.let { surface ->
      surface.scaleX = 1f
      surface.scaleY = 1f
    }
  }

  fun setVideoAspect(aspect: VideoAspect) {
    isManualAspectOverride = true
    val mode = when (aspect) {
      VideoAspect.Fit -> {
        applySurfaceScale()
        if (autoCropActive) {
          androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
          androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
      }
      VideoAspect.Crop -> {
        applySurfaceScale()
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      }
      VideoAspect.Stretch -> {
        applySurfaceScale()
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
      }
    }
    lastAppliedResizeMode = mode
    attachedView?.post {
      attachedView?.resizeMode = mode
      attachedView?.requestLayout()
    }
  }

  // ==============================================================================
  // INTELLIGENT AUDIO & SUBTITLE SELECTION (PORT OF LUA HEURISTICS)
  // ==============================================================================
  private var hasUserManuallySelectedAudio: Boolean = false
  private var hasUserManuallySelectedSubtitle: Boolean = false
  private var hasAutoSelectedAudioForCurrentItem: Boolean = false
  private var hasAutoSelectedSubtitleForCurrentItem: Boolean = false

  private fun autoSelectBestAudioTrack(tracks: Tracks) {
    if (hasUserManuallySelectedAudio || hasAutoSelectedAudioForCurrentItem) return
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audioGroups.isEmpty()) return
    hasAutoSelectedAudioForCurrentItem = true

    var bestGroup: Tracks.Group? = null
    var bestTrackIndex = -1
    var highestScore = Int.MIN_VALUE

    for (group in audioGroups) {
      for (i in 0 until group.length) {
        val format = group.getTrackFormat(i)
        val score = scoreAudioTrack(format)
        if (score > highestScore) {
          highestScore = score
          bestGroup = group
          bestTrackIndex = i
        }
      }
    }

    if (bestGroup != null && bestTrackIndex >= 0 && highestScore > -10000) {
      if (!bestGroup.isTrackSelected(bestTrackIndex)) {
        Log.i(logTag, "Auto-selected optimal Lossless/Original audio track: ${bestGroup.getTrackFormat(bestTrackIndex).label} (score=$highestScore)")
        player.trackSelectionParameters = player.trackSelectionParameters
          .buildUpon()
          .setOverrideForType(TrackSelectionOverride(bestGroup.mediaTrackGroup, bestTrackIndex))
          .build()
      }
    }
  }

  private fun scoreAudioTrack(format: androidx.media3.common.Format): Int {
    var score = 0
    val label = (format.label ?: "").lowercase()
    val mime = (format.sampleMimeType ?: "").lowercase()
    val codecs = (format.codecs ?: "").lowercase()
    val lang = (format.language ?: "").lowercase()

    // Anti-Commentary
    val isCommentary = listOf("commentary", "director", "cast", "track 2", "audio commentary").any { label.contains(it) }
    val isAudioDescription = listOf("descriptive", "adh", "audio description", "visually impaired", "description").any { label.contains(it) }
    if (isCommentary || isAudioDescription) return -50000

    // Anti-Dubbing
    val isDub = listOf("dub", "dubbed", "mvo", "dvo", "avo", "закадровый", "дубляж", "синхронный", "voiceover", "voice-over").any { label.contains(it) }
    if (isDub) return -30000

    // Codec hierarchy (with FfmpegAudioRenderer PREFERRED, TrueHD and DTS are 100% decoded in software by libffmpegJNI)
    when {
      // Lossless Master Audio (FraMeSToR TrueHD Atmos & DTS-HD MA)
      mime.contains("true-hd") || codecs.contains("truehd") || label.contains("truehd") -> score += 10000
      mime.contains("dts.hd") || codecs.contains("dts-hd") || label.contains("dts-hd") || label.contains("dts:x") -> score += 9000
      mime.contains("flac") || mime.contains("raw") || label.contains("lpcm") || label.contains("pcm") -> score += 8000
      mime.contains("eac3") || codecs.contains("ec-3") || label.contains("ddp") || label.contains("e-ac3") || label.contains("atmos") -> score += 7000
      mime.contains("dts") -> score += 6000
      mime.contains("ac3") || codecs.contains("ac-3") || label.contains("ac3") || label.contains("dolby digital") -> score += 5000
      mime.contains("opus") -> score += 4000
      mime.contains("mp4a") || mime.contains("aac") -> score += 3000
    }

    // Channel count bonus (multichannel audio downmixed cleanly by AudioFlinger)
    score += (format.channelCount.coerceIn(1, 8)) * 100

    // Original/Theatrical mix bonus
    if (label.contains("original") || label.contains("theatrical") || label.contains("cinema")) {
      score += 1500
    }

    // English / Original language preference
    if (lang in setOf("eng", "en") || label.contains("english") || label.contains("eng")) {
      score += 1000
    }

    return score
  }

  private fun autoSelectBestSubtitleTrack(tracks: Tracks) {
    if (hasUserManuallySelectedSubtitle || hasAutoSelectedSubtitleForCurrentItem) return
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    if (textGroups.isEmpty()) return
    hasAutoSelectedSubtitleForCurrentItem = true

    var bestGroup: Tracks.Group? = null
    var bestTrackIndex = -1
    var highestScore = Int.MIN_VALUE

    for (group in textGroups) {
      for (i in 0 until group.length) {
        val format = group.getTrackFormat(i)
        val score = scoreSubtitleTrack(format)
        if (score > highestScore) {
          highestScore = score
          bestGroup = group
          bestTrackIndex = i
        }
      }
    }

    if (bestGroup != null && bestTrackIndex >= 0 && highestScore > -10000) {
      if (!bestGroup.isTrackSelected(bestTrackIndex)) {
        Log.i(logTag, "Auto-selected optimal English subtitle track: ${bestGroup.getTrackFormat(bestTrackIndex).label} (score=$highestScore)")
        player.trackSelectionParameters = player.trackSelectionParameters
          .buildUpon()
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
          .setOverrideForType(TrackSelectionOverride(bestGroup.mediaTrackGroup, bestTrackIndex))
          .build()
      }
    }
  }

  private fun scoreSubtitleTrack(format: androidx.media3.common.Format): Int {
    var score = 0
    val label = (format.label ?: "").lowercase()
    val mime = (format.sampleMimeType ?: "").lowercase()
    val lang = (format.language ?: "").lowercase()

    // English check
    val isEnglish = lang in setOf("eng", "en", "enm") ||
                    listOf("english", "eng", "inglés", "ingles", "anglais").any { label.contains(it) }
    if (!isEnglish) return -50000

    // Anti-Commentary
    if (listOf("commentary", "director", "cast").any { label.contains(it) }) return -40000

    // SDH penalty
    val isSdh = listOf("sdh", "hearing impaired", "hoh", "deaf").any { label.contains(it) }
    if (isSdh) score -= 3000

    // Forced subtitle handling
    val isForced = listOf("forced", "forzato", "forzati").any { label.contains(it) }
    if (isForced) score -= 10000

    // Format hierarchy (Text > Bitmap)
    when {
      mime.contains("x-subrip") || mime.contains("subrip") || label.contains("srt") -> score += 5000
      mime.contains("x-ssa") || mime.contains("ass") || label.contains("ass") -> score += 4500
      mime.contains("vtt") || label.contains("vtt") -> score += 4000
      mime.contains("pgs") || label.contains("pgs") || mime.contains("hdmv") -> score += 1500
    }

    if (listOf("full", "dialog", "dialogue").any { label.contains(it) }) score += 800
    if ((format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0) score += 200

    return score
  }


  fun setZoom(zoom: Float) {
    val scale = 2f.pow(zoom.coerceIn(-1f, 3f))
    attachedView?.scaleX = scale
    attachedView?.scaleY = scale
  }

  fun setPan(x: Float, y: Float) {
    attachedView?.translationX = x
    attachedView?.translationY = y
  }

  fun setSubtitleScale(scale: Float) {
    subtitleScale = scale.coerceIn(0.1f, 5f)
    loopHandler.removeCallbacks(subtitleViewRunnable)
    configureSubtitleView()
  }

  fun setSubtitlePosition(position: Int) {
    subtitlePosition = position.coerceIn(0, 150)
    loopHandler.removeCallbacks(subtitleViewRunnable)
    configureSubtitleView()
  }

  /** Hide/show subtitle presentation only; keep the selected Media3 text track active. */
  fun setSubtitlePresentationVisible(visible: Boolean) {
    subtitlePresentationVisible = visible
    attachedView?.subtitleView?.visibility = if (visible) View.VISIBLE else View.GONE
    subtitleOverlay?.visibility = if (visible) View.VISIBLE else View.GONE
  }

  fun addExternalSubtitle(uri: Uri, select: Boolean): Boolean {
    val current = player.currentMediaItem ?: return false
    val localConfiguration = current.localConfiguration ?: return false
    val mimeType = when (subtitleExtension(uri)) {
      "srt" -> "application/x-subrip"
      "vtt" -> "text/vtt"
      "ass", "ssa" -> "text/x-ssa"
      else -> "text/plain"
    }
    val configuration = MediaItem.SubtitleConfiguration.Builder(uri)
      .setId("external:$uri")
      // Keep the identity available even when an ASS/SSA parser replaces the Format id.
      // The snapshot code uses this marker to keep the track in the external section only.
      .setLabel("external:$uri")
      .setMimeType(mimeType)
      .setSelectionFlags(if (select) C.SELECTION_FLAG_DEFAULT else 0)
      .build()
    if (localConfiguration.subtitleConfigurations.any { it.id == configuration.id }) {
      if (select) {
        pendingExternalSelectionId = configuration.id
        loopHandler.post { applyPendingExternalSelection() }
      }
      return true
    }
    val wasPlaying = player.isPlaying
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    // Keep the original MediaItem (headers, DRM, metadata and stream identity) intact. Rebuilding
    // from only current.uri makes downloaded online subtitles fail on authenticated/network media.
    val updated = current.buildUpon()
      .setSubtitleConfigurations(localConfiguration.subtitleConfigurations + configuration)
      .build()
    player.setMediaItem(updated, positionMs)
    player.prepare()
    player.playWhenReady = wasPlaying
    if (select) {
      pendingExternalSelectionId = configuration.id
      loopHandler.post { applyPendingExternalSelection() }
    }
    return true
  }

  fun toggleExternalSubtitle(uri: Uri): Boolean? {
    val id = "external:$uri"
    val match = player.currentTracks.groups
      .asSequence()
      .filter { it.type == C.TRACK_TYPE_TEXT }
      .flatMap { group ->
        (0 until group.length).asSequence().map { index -> group to index }
      }
      .firstOrNull { (group, index) ->
        val format = group.getTrackFormat(index)
        format.id == id || format.label == id
      }
      ?: return null
    val group = match.first
    val trackIndex = match.second
    val enabled = !group.isTrackSelected(trackIndex)
    val builder = player.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
    if (enabled) {
      builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
    } else {
      val fallback = player.currentTracks.groups
        .asSequence()
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { candidate -> (0 until candidate.length).asSequence().map { candidate to it } }
        .firstOrNull { (candidate, index) ->
          candidate !== group && !candidate.getTrackFormat(index).id.orEmpty().startsWith("external:")
        }
      if (fallback != null) {
        builder.addOverride(TrackSelectionOverride(fallback.first.mediaTrackGroup, fallback.second))
      } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      }
    }
    player.trackSelectionParameters = builder.build()
    return enabled
  }

  private fun applyPendingExternalSelection() {
    val id = pendingExternalSelectionId ?: return
    val match = player.currentTracks.groups
      .asSequence()
      .filter { it.type == C.TRACK_TYPE_TEXT }
      .flatMap { group -> (0 until group.length).asSequence().map { group to it } }
      .firstOrNull { (group, index) ->
        val format = group.getTrackFormat(index)
        format.id == id || format.label == id
      }
      ?: return
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
      .build()
    selectedNativeSubtitleKey = player.currentTracks.groups.indexOf(match.first) to match.second
    pendingExternalSelectionId = null
    publishSnapshot()
  }

  private fun subtitleSelectionFor(group: Tracks.Group, trackIndex: Int): NativeSubtitleSelection {
    val format = group.getTrackFormat(trackIndex)
    return NativeSubtitleSelection(
      groupId = group.mediaTrackGroup.id,
      formatId = format.id,
      label = format.label,
      language = format.language,
      mimeType = format.sampleMimeType,
      codecs = format.codecs,
    )
  }

  /** Rebind an explicit user choice if Media3 replaces its TrackGroup after preparation. */
  private fun restoreSelectedNativeSubtitle(tracks: Tracks) {
    val wanted = selectedNativeSubtitleSelection ?: return
    if (restoringNativeSubtitleSelection) return
    val match = tracks.groups
      .asSequence()
      .filter { it.type == C.TRACK_TYPE_TEXT }
      .flatMap { group -> (0 until group.length).asSequence().map { group to it } }
      .firstOrNull { (group, index) ->
        val candidate = subtitleSelectionFor(group, index)
        val stableIdMatches = wanted.formatId != null && wanted.formatId == candidate.formatId
        val descriptiveFieldsMatch =
          wanted.label == candidate.label &&
            wanted.language == candidate.language &&
            wanted.mimeType == candidate.mimeType &&
            wanted.codecs == candidate.codecs
        (wanted.groupId == candidate.groupId && descriptiveFieldsMatch) ||
          stableIdMatches || descriptiveFieldsMatch
      }
      ?: return
    if (match.first.isTrackSelected(match.second)) {
      selectedNativeSubtitleKey = tracks.groups.indexOf(match.first) to match.second
      return
    }
    restoringNativeSubtitleSelection = true
    try {
      player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
        .build()
      selectedNativeSubtitleKey = tracks.groups.indexOf(match.first) to match.second
      Log.d(logTag, "Restored selected native subtitle after track-group refresh index=${match.second}")
    } finally {
      restoringNativeSubtitleSelection = false
    }
  }

  /** Content-provider URIs can hide the actual downloaded filename in their last path segment. */
  private fun disableNativeTextTracks() {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .build()
    publishSnapshot()
  }

  private fun subtitleExtension(uri: Uri): String {
    val uriName = Uri.decode(uri.lastPathSegment.orEmpty())
    val providerName =
      if (uri.scheme == "content") {
        runCatching {
          appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
              val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull().orEmpty()
      } else {
        ""
      }
    return listOf(providerName, uriName)
      .firstOrNull { it.substringAfterLast('.', "").lowercase() in setOf("srt", "vtt", "ass", "ssa", "sub") }
      ?.substringAfterLast('.', "")
      ?.lowercase()
      .orEmpty()
  }

  fun setSubtitleStyle(
    textColor: Int,
    backgroundColor: Int,
    borderColor: Int,
    borderSize: Int,
    fontSize: Int,
    fontFamily: String? = null,
    bold: Boolean = false,
    italic: Boolean = false,
  ) {
    subtitleFontSize = fontSize.coerceIn(8, 160)
    subtitleFontFamily = fontFamily?.takeIf { it.isNotBlank() } ?: "sans-serif"
    subtitleBold = bold
    subtitleItalic = italic
    subtitleTextColor = textColor
    subtitleBorderColor = borderColor
    subtitleBackgroundColor = backgroundColor
    subtitleBorderSize = borderSize.coerceAtLeast(0)
    val typefaceStyle = when {
      bold && italic -> android.graphics.Typeface.BOLD_ITALIC
      bold -> android.graphics.Typeface.BOLD
      italic -> android.graphics.Typeface.ITALIC
      else -> android.graphics.Typeface.NORMAL
    }
    subtitleStyle = CaptionStyleCompat(
      textColor,
      backgroundColor,
      android.graphics.Color.TRANSPARENT,
      CaptionStyleCompat.EDGE_TYPE_OUTLINE,
      borderColor,
      android.graphics.Typeface.create(fontFamily?.takeIf { it.isNotBlank() }, typefaceStyle),
    )
    loopHandler.removeCallbacks(subtitleStyleRunnable)
    applyAssStyle()
    loopHandler.removeCallbacks(subtitleViewRunnable)
    configureSubtitleView()
  }

  private fun applyAssStyle() {
    libassRenderer?.setStyle(
      subtitleFontFamily,
      subtitleFontSize,
      subtitleTextColor,
      subtitleBorderColor,
      subtitleBackgroundColor,
      subtitleBorderSize,
      subtitleBold,
      subtitleItalic,
    )
  }

  private fun scheduleSubtitleViewConfiguration() {
    loopHandler.removeCallbacks(subtitleViewRunnable)
    loopHandler.postDelayed(subtitleViewRunnable, 40L)
  }


  private fun ensureLibassRenderer(): LibassSubtitleRenderer? {
    val view = subtitleOverlay ?: return null
    val sourceWidth = snapshot.value.videoWidth.takeIf { it > 0 } ?: view.width.takeIf { it > 0 } ?: 1280
    val sourceHeight = snapshot.value.videoHeight.takeIf { it > 0 } ?: view.height.takeIf { it > 0 } ?: 720
    // libass uses the video's storage dimensions; the in-layout TextureView is transparent and
    // follows the same FIT container as Media3 so ASS coordinates remain in video space.
    val width = sourceWidth.coerceAtLeast(1)
    val height = sourceHeight.coerceAtLeast(1)
    if (width <= 0 || height <= 0) return null
    libassRenderer?.let {
      if (it.getWidth() != width || it.getHeight() != height) it.setSize(width, height)
      return it
    }
    return runCatching {
      LibassSubtitleRenderer(width, height, null).also {
        libassRenderer = it
        view.setRenderer(it)
        applyAssStyle()
        Log.i(logTag, "libass initialized size=${width}x${height} tracks=0")
      }
    }.onFailure { Log.e(logTag, "libass initialization failed", it) }.getOrNull()
  }

  private fun configureSubtitleView() {
    val view = attachedView ?: return
    view.subtitleView?.apply {
      // ass-media installs its ASS overlay inside this subtitle view. Keep the parent visible;
      // normal SRT/WebVTT cues continue to use the same Media3 view.
      visibility = if (subtitlePresentationVisible) View.VISIBLE else View.GONE
      setStyle(subtitleStyle)
      // Set base text fractional size from font size preference
      setFractionalTextSize((subtitleFontSize / 1000f).coerceIn(0.01f, 0.16f))
      pivotX = width / 2f
      pivotY = height.toFloat()
      scaleX = subtitleScale
      scaleY = subtitleScale
      translationY = ((subtitlePosition - 100) / 100f * height * 0.5f)
        .coerceIn(-height * 0.5f, height * 0.5f)
      setBottomPaddingFraction(0f)
    }
    subtitleOverlay?.apply {
      // Hide the overlay TextureView when not in use to eliminate GPU compositing overhead
      val isOverlayNeeded = subtitlePresentationVisible && libassRenderer != null && (attachedView?.subtitleView == null)
      visibility = if (isOverlayNeeded) View.VISIBLE else View.GONE
      pivotX = width / 2f
      pivotY = height.toFloat()
      scaleX = subtitleScale
      scaleY = subtitleScale
      translationY = ((subtitlePosition - 100) / 100f * height * 0.5f)
        .coerceIn(-height * 0.5f, height * 0.5f)
    }
  }

  private fun isAssFormat(format: androidx.media3.common.Format): Boolean {
    val mime = format.sampleMimeType?.lowercase() ?: return false
    val codecs = format.codecs?.lowercase() ?: ""
    return mime.contains("ssa") || mime.contains("ass") || codecs.contains("ssa") || codecs.contains("ass")
  }

  fun play(
    uri: Uri,
    startPositionMs: Long = 0L,
    autoplay: Boolean = true,
    headers: Map<String, String> = emptyMap(),
    mimeType: String? = null,
    sourceUri: Uri? = null,
    sourceHint: String = "",
  ) {
    _hasRenderedFirstFrame.value = false
    endedUri = null
    hasAutoSelectedAudioForCurrentItem = false
    hasAutoSelectedSubtitleForCurrentItem = false
    hasUserManuallySelectedAudio = false
    hasUserManuallySelectedSubtitle = false
    lastKnownDurationMs = 0L
    if (startPositionMs > 0L) {
      lastKnownPositionMs = startPositionMs
      pendingInitialSeekPositionMs = startPositionMs
      pendingSeekPositionMs = startPositionMs
      pendingSeekDisplayPositionMs = startPositionMs
    } else {
      lastKnownPositionMs = 0L
      pendingInitialSeekPositionMs = null
    }
    videoDecoderName = null
    audioDecoderName = null
    // The player instance survives item changes; reset any ducked/zero output level before the
    // first audio-only item after a video transition.
    player.volume = 1f
    setSubtitlePresentationVisible(true)
    pendingExternalSelectionId = null
    selectedNativeSubtitleSelection = null
    externalAssEnabled.clear()
    libassRenderer?.getTrackIds()?.keys?.toList()?.forEach { id ->
      libassRenderer?.removeTrack(id)
    }
    // Uri.parse("/storage/...") has no scheme. Make local paths explicit so Media3 selects
    // FileDataSource instead of treating the original MediaStore URI as the playable source.
    val mediaUri =
      if (uri.scheme.isNullOrBlank() && uri.path?.startsWith("/") == true) {
        Uri.fromFile(File(uri.path!!))
      } else {
        uri
      }
    Log.d(logTag, "play uri=$mediaUri source=$sourceUri hint=$sourceHint positionMs=$startPositionMs autoplay=$autoplay")
    lastAppliedResizeMode = null
    lastAppliedSegmentIndex = -1
    isManualAspectOverride = false
    loadAspectMapForUri(mediaUri, sourceHint, sourceUri)
    if (activeAspectSegments.isNotEmpty()) {
      val initialIndex = activeAspectSegments.indexOfFirst { startPositionMs in it.startMs..it.endMs }
      if (initialIndex != -1) {
        lastAppliedSegmentIndex = initialIndex
        val initialActive = activeAspectSegments[initialIndex]
        val initialMode = if (initialActive.ratio < 2.0f) {
          androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
          androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        lastAppliedResizeMode = initialMode
        attachedView?.post {
          attachedView?.resizeMode = initialMode
          attachedView?.requestLayout()
        }
      }
    }
    (attachedView?.videoSurfaceView as? android.view.SurfaceView)?.let { surface ->
      surface.scaleX = 1f
      surface.scaleY = 1f
    }
    httpDataSourceFactory.setDefaultRequestProperties(headers)
    val mediaItem =
      MediaItem.Builder()
        .setUri(mediaUri)
        .apply {
          // Database/network metadata may contain broad values such as video/*; passing those
          // to Media3 prevents extractor sniffing and makes otherwise valid MP4/WebM sources fail.
          val declaredMime = mimeType
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("application/octet-stream", true) && !it.endsWith("/*") }
          (declaredMime ?: nativeContainerMimeType(mediaUri) ?: sourceUri?.let(::nativeContainerMimeType))
            ?.let(::setMimeType)
        }
        .build()
    Log.i(logTag, "ass-media extractor enabled uri=$mediaUri")
    Log.d(logTag, "Media3 MediaItem uri=${mediaItem.localConfiguration?.uri} scheme=${mediaUri.scheme}")
    preparationStartedAtMs = SystemClock.elapsedRealtime()
    preparationUri = mediaItem.localConfiguration?.uri
    sourceSizeBytes = resolveLocalSize(mediaUri)
    if (sourceSizeBytes <= 0L && (mediaUri.scheme.equals("http", true) || mediaUri.scheme.equals("https", true))) {
      Thread {
        val resolved = resolveHttpSize(mediaUri, headers)
        if (resolved > 0L && preparationUri == mediaUri) {
          sourceSizeBytes = resolved
          loopHandler.post { publishSnapshot() }
        }
      }.apply { name = "native-media-size"; isDaemon = true }.start()
    }
    metadataChapters = emptyList()
    Log.d(logTag, "prepare begin uri=$preparationUri")
    player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
    // The PlayerView is attached once during Activity creation. Preparing immediately here is
    // required for local HDR files; deferring this through View.post can leave Media3 in BUFFERING
    // without ever starting the local data pipeline on Xiaomi devices.
    player.prepare()
    Log.d(logTag, "prepare returned elapsedMs=${SystemClock.elapsedRealtime() - preparationStartedAtMs} uri=$preparationUri")
    player.playWhenReady = autoplay
    publishSnapshot()
    startTimelineUpdates()
  }

  private fun nativeContainerMimeType(uri: Uri): String? =
    when (uri.getQueryParameter("format")?.lowercase() ?: uri.path?.substringAfterLast('.', "")?.lowercase()) {
      "mkv", "mka" -> "video/x-matroska"
      "ts", "m2ts", "mts" -> "video/mp2t"
      "mp4", "m4v" -> "video/mp4"
      "webm" -> "video/webm"
      "mov", "qt" -> "video/quicktime"
      "3gp", "3g2" -> "video/3gpp"
      "avi" -> "video/avi"
      "flv" -> "video/x-flv"
      "mpeg", "mpg" -> "video/mpeg"
      "ogv" -> "video/ogg"
      "m3u8" -> "application/x-mpegURL"
      "mpd" -> "application/dash+xml"
      else -> null
    }

  private fun resolveLocalSize(uri: Uri): Long {
    if (uri.scheme.equals("file", true)) return File(uri.path.orEmpty()).length()
    if (uri.scheme.equals("content", true)) {
      return runCatching {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
      }.getOrNull()?.takeIf { it > 0L } ?: 0L
    }
    return 0L
  }

  private fun resolveHttpSize(uri: Uri, requestHeaders: Map<String, String>): Long =
    runCatching {
      fun open(method: String, range: String? = null) =
        (java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection).apply {
          requestMethod = method
          connectTimeout = 4_000
          readTimeout = 4_000
          range?.let { setRequestProperty("Range", it) }
          requestHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
      open("HEAD").let { connection ->
        try {
          if (connection.responseCode in 200..399 && connection.contentLengthLong > 0L) connection.contentLengthLong else -1L
        } finally {
          connection.disconnect()
        }
      }.takeIf { it > 0L } ?: open("GET", "bytes=0-0").let { connection ->
        try {
          val range = connection.getHeaderField("Content-Range").orEmpty()
          Regex("/(\\d+)$").find(range)?.groupValues?.get(1)?.toLongOrNull()
            ?: connection.contentLengthLong.takeIf { it > 0L }
            ?: -1L
        } finally {
          connection.disconnect()
        }
      }
    }.getOrDefault(-1L).coerceAtLeast(0L)

  fun setPlaying(playing: Boolean) {
    if (playing) {
      if (player.playbackState == Player.STATE_ENDED) endedUri = null
      player.volume = 1f
      player.play()
    } else {
      player.pause()
    }
    publishSnapshot()
    startTimelineUpdates()
  }

  /** The app queue owns repeat semantics because Native loads one queue item at a time. */
  fun setRepeatMode(repeatMode: RepeatMode) {
    // Do not let Media3 repeat its single MediaItem. PlayerActivity advances the app queue for ALL
    // and restarts the current item for ONE, matching the libmpv queue behavior.
    player.repeatMode = Player.REPEAT_MODE_OFF
  }

  fun seekTo(positionMs: Long) {
    pendingInitialSeekPositionMs = null
    pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    loopHandler.removeCallbacks(seekRunnable)
    loopHandler.postDelayed(seekRunnable, 120L)
    // Seek controls must not enumerate every subtitle/audio metadata entry on the UI thread.
    publishPlaybackSnapshot()
    startTimelineUpdates()
  }

  fun seekBy(offsetMs: Long) {
    pendingInitialSeekPositionMs = null
    val basePositionMs =
      pendingSeekPositionMs
        ?: pendingSeekDisplayPositionMs
        ?: player.currentPosition.takeIf { it > 0L }
        ?: lastKnownPositionMs
    pendingSeekPositionMs = (basePositionMs + offsetMs).coerceAtLeast(0L)
    loopHandler.removeCallbacks(seekRunnable)
    loopHandler.postDelayed(seekRunnable, 120L)
    // Keep repeated seek-bar updates lightweight; the track/metadata snapshot is unchanged.
    publishPlaybackSnapshot()
    startTimelineUpdates()
  }

  fun setLoopA(positionSeconds: Double?) {
    loopASeconds = positionSeconds?.coerceAtLeast(0.0)
    restartLoopMonitor()
  }

  fun setLoopB(positionSeconds: Double?) {
    loopBSeconds = positionSeconds?.coerceAtLeast(0.0)
    restartLoopMonitor()
  }

  fun clearLoop() {
    loopASeconds = null
    loopBSeconds = null
    loopHandler.removeCallbacks(loopRunnable)
  }

  private fun restartLoopMonitor() {
    loopHandler.removeCallbacks(loopRunnable)
    if (loopASeconds != null && loopBSeconds != null) loopHandler.post(loopRunnable)
  }

  fun setSpeed(speed: Float, pitchCorrection: Boolean = true) {
    // Keep Native aligned with the shared speed sheet and ViewModel, both of which support up to
    // 8x. The old 4x clamp made the 4x-8x presets appear selectable but silently ineffective.
    val clampedSpeed = speed.coerceIn(0.25f, 8f)
    player.setPlaybackParameters(
      PlaybackParameters(clampedSpeed, if (pitchCorrection) 1f else clampedSpeed),
    )
    publishSnapshot()
  }

  fun selectTrack(track: NativeTrack) {
    if (track.type == C.TRACK_TYPE_AUDIO) {
      hasUserManuallySelectedAudio = true
      val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
      if (group.type != track.type || track.trackIndex !in 0 until group.length) return
      val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
      player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setOverrideForType(override)
        .build()
      publishSnapshot()
    } else if (track.type == C.TRACK_TYPE_TEXT) {
      selectSubtitleTrack(track.groupIndex, track.trackIndex)
    }
  }

  /**
   * Toggle an embedded subtitle using the current Media3 track state.
   *
   * The UI snapshot is asynchronous and can still contain the previous selected flag when a
   * track-change callback arrives. Making the enable/disable decision from currentTracks avoids
   * turning subtitles off immediately after selecting another track.
   */
  fun toggleSubtitle(track: NativeTrack) {
    val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
    if (group.type != C.TRACK_TYPE_TEXT || track.trackIndex !in 0 until group.length) return
    val isCurrentlySelected = if (selectedNativeSubtitleKey != null) {
      selectedNativeSubtitleKey == (track.groupIndex to track.trackIndex)
    } else {
      group.isTrackSelected(track.trackIndex)
    }
    if (isCurrentlySelected) {
      disableSubtitles()
    } else {
      selectSubtitleTrack(track.groupIndex, track.trackIndex)
    }
  }

  fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
    hasUserManuallySelectedAudio = true
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    publishSnapshot()
  }

  fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
    hasUserManuallySelectedSubtitle = true
    val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
    if (group.type != C.TRACK_TYPE_TEXT) return
    if (trackIndex !in 0 until group.length) return
    selectedNativeSubtitleSelection = subtitleSelectionFor(group, trackIndex)
    selectedNativeSubtitleKey = groupIndex to trackIndex
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    Log.i(logTag, "subtitle selection enabled group=${group.mediaTrackGroup.id} requested=$trackIndex")
    publishSnapshot()
  }


  // ==============================================================================
  // ASPECT MAP JSON LOADER (FOR SHIFTING RATIO FILMS LIKE NOLAN / IMAX)
  // ==============================================================================
  private fun loadAspectMapForUri(mediaUri: Uri, sourceHint: String = "", sourceUri: Uri? = null) {
    val candidateNames = mutableListOf<String>()

    fun extractDisplayName(u: Uri?): String? {
      if (u == null) return null
      if (u.scheme == "content") {
        return runCatching {
          appContext.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
          }
        }.getOrNull()
      }
      return u.lastPathSegment
    }

    if (sourceHint.isNotBlank()) candidateNames.add(sourceHint)
    extractDisplayName(mediaUri)?.let { candidateNames.add(it) }
    extractDisplayName(sourceUri)?.let { candidateNames.add(it) }
    mediaUri.path?.let { candidateNames.add(it) }
    mediaUri.lastPathSegment?.let { candidateNames.add(it) }
    sourceUri?.path?.let { candidateNames.add(it) }
    sourceUri?.lastPathSegment?.let { candidateNames.add(it) }

    val cleanCandidates = candidateNames.map {
      Uri.decode(it).lowercase().replace("[._\\-\\s]+".toRegex(), " ").trim()
    }.filter { it.isNotBlank() }

    Log.i(logTag, "Aspect map search candidates: $cleanCandidates")

    val candidateDirs = listOf(
      File("/storage/emulated/0/Research/aspect_maps"),
      File("/storage/emulated/0/aspect_maps"),
      File("/storage/emulated/0/Movies/aspect_maps"),
      File("/storage/emulated/0/Download/aspect_maps"),
      File("/storage/emulated/0/Documents/aspect_maps")
    )

    for (dir in candidateDirs) {
      if (dir.exists() && dir.isDirectory) {
        val jsonFiles = dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) } ?: continue
        for (file in jsonFiles) {
          val stem = file.nameWithoutExtension.lowercase().replace("[._\\-\\s]+".toRegex(), " ").trim()
          val isMatch = cleanCandidates.any { candidate ->
            candidate.contains(stem) || (stem.length >= 5 && stem.contains(candidate.take(12)))
          }
          if (isMatch) {
            Log.i(logTag, "Matched aspect map file: ${file.absolutePath} for candidates: $cleanCandidates")
            parseAspectMapJson(file)
            return
          }
        }
      }
    }
    activeAspectSegments = emptyList()
    Log.w(logTag, "No matching aspect map found for: $cleanCandidates")
  }

  private fun parseAspectMapJson(file: File) {
    runCatching {
      val text = file.readText()
      val array = JSONArray(text)
      val list = mutableListOf<AspectTimeSegment>()
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val start = if (obj.has("startMs")) obj.getLong("startMs") else (obj.optDouble("start", 0.0) * 1000.0).toLong()
        val end = if (obj.has("endMs")) obj.getLong("endMs") else (obj.optDouble("end", 0.0) * 1000.0).toLong()
        val ratio = obj.optDouble("ratio", 2.39).toFloat()
        if (end > start) {
          list.add(AspectTimeSegment(start, end, ratio))
        }
      }
      activeAspectSegments = list
      Log.i(logTag, "Loaded external aspect map from ${file.name} with ${list.size} cues")
    }.onFailure {
      Log.e(logTag, "Failed to parse aspect map JSON: ${it.message}")
    }
  }

  fun disableSubtitles() {
    hasUserManuallySelectedSubtitle = true
    disableAssTracks()
    selectedNativeSubtitleKey = null
    selectedNativeSubtitleSelection = null
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .build()
    publishSnapshot()
  }

  private fun isAssFormat(mime: String?, codecs: String?): Boolean {
    val value = "${mime.orEmpty()} ${codecs.orEmpty()}".lowercase()
    return value.contains("ass") || value.contains("ssa")
  }

  private fun disableAssTracks() {
    libassRenderer?.getTrackIds()?.keys?.forEach { id -> libassRenderer?.setTrackEnabled(id, false) }
  }

  fun addListener(listener: Player.Listener) = player.addListener(listener)
  fun removeListener(listener: Player.Listener) = player.removeListener(listener)

  fun stop() {
    clearLoop()
    loopHandler.removeCallbacks(timelineRunnable)
    loopHandler.removeCallbacks(seekRunnable)
    pendingSeekPositionMs = null
    pendingSeekDisplayPositionMs = null
    pendingInitialSeekPositionMs = null
    val currentPos = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
    if (currentPos > 0L) lastKnownPositionMs = currentPos
    val currentDur = runCatching { player.duration }.getOrDefault(0L).takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
    if (currentDur > 0L) lastKnownDurationMs = currentDur
    player.stop()
    player.clearMediaItems()
    _subtitleCueText.value = ""
    selectedNativeSubtitleKey = null
    selectedNativeSubtitleSelection = null
    externalAssEnabled.clear()
    libassRenderer?.let { renderer ->
      renderer.getTrackIds().keys.toList().forEach { renderer.removeTrack(it) }
    }
    _hasRenderedFirstFrame.value = false
    autoCropScale = 1f
    autoCropActive = false
    applySurfaceScale()
    publishSnapshot()
  }

  /**
   * Begins watching the audio route so the crossfeed stage follows the active transducer.
   *
   * Called when Native playback starts. A route change while audio is already running cannot
   * rebuild the audio sink in place, so the processor is retuned for the *next* sink build and
   * the current buffer simply plays out with the previous tuning. This avoids an audible glitch
   * every time a headset is plugged in.
   */
  fun startAudioOutputTracking() {
    audioOutputRouter.startObserving { newOutput ->
      registeredAudioProcessor.get()?.apply {
        enableBinauralCrossfeed = newOutput.supportsCrossfeed
        enableStereoCrossfeed = newOutput.supportsCrossfeed
        enableLfeMixing = newOutput.supportsLfeFoldDown
      }
    }
  }

  fun release() {
    clearLoop()
    audioOutputRouter.stopObserving()
    registeredAudioProcessor.set(null)
    loopHandler.removeCallbacks(timelineRunnable)
    loopHandler.removeCallbacks(seekRunnable)
    pendingSeekPositionMs = null
    player.removeListener(listener)
    attachedView?.player = null
    libassRenderer?.close()
    libassRenderer = null
    subtitleOverlay?.setRenderer(null)
    subtitleOverlay = null
    attachedView = null
    assHandler.release()
    player.release()
  }

  private fun publishSnapshot() {
    val groups = player.currentTracks.groups
    val trackChapters = groups.flatMap { group ->
      (0 until group.length).flatMap { index ->
        group.getTrackFormat(index).metadata?.let(::metadataEntriesToChapters).orEmpty()
      }
    }
    val chapters = (metadataChapters + trackChapters)
      .distinctBy { it.startSeconds to it.title }
      .sortedBy { it.startSeconds }
    fun tracksOfType(type: Int, fallback: String): List<NativeTrack> =
      groups.mapIndexedNotNull { groupIndex, group ->
        if (group.type != type) return@mapIndexedNotNull null
        (0 until group.length).mapNotNull { trackIndex ->
          val format = group.getTrackFormat(trackIndex)
          val externalUri = format.id
            ?.takeIf { it.startsWith("external:") }
            ?.removePrefix("external:")
            ?: format.label
              ?.takeIf { it.startsWith("external:") }
              ?.removePrefix("external:")
          val isExternal = externalUri != null
          NativeTrack(
            groupIndex = groupIndex,
            trackIndex = trackIndex,
            type = group.type,
            label = if (isExternal) {
              externalUri.orEmpty().substringAfterLast('/').ifBlank { externalUri.orEmpty() }
            } else {
              format.label ?: format.language ?: "$fallback ${trackIndex + 1}"
            },
            language = format.language,
            formatId = externalUri?.let { "external:$it" } ?: format.id,
            external = isExternal,
            selected = if (type == C.TRACK_TYPE_TEXT) {
              if (selectedNativeSubtitleKey != null) {
                selectedNativeSubtitleKey == (groupIndex to trackIndex)
              } else {
                group.isTrackSelected(trackIndex)
              }
            } else group.isTrackSelected(trackIndex),
          )
        }
      }.flatten()
    val subtitles = tracksOfType(C.TRACK_TYPE_TEXT, "Subtitle")
    val audioTracks = tracksOfType(C.TRACK_TYPE_AUDIO, "Audio")
    val video = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
      ?.getTrackFormat(0)
    val audio = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
      ?.getTrackFormat(0)
    val reportedDurationMs = player.duration
      .takeIf { it != C.TIME_UNSET && it > 0L }
      ?.also { lastKnownDurationMs = it }
      ?: lastKnownDurationMs
    val colorInfo = video?.colorInfo
    val dynamicRange = video?.let {
      val mime = it.sampleMimeType.orEmpty().lowercase()
      val codecs = it.codecs.orEmpty().lowercase()
      when {
        mime.contains("dolby-vision") || codecs.startsWith("dvhe") || codecs.startsWith("dvh1") -> "Dolby Vision"
        colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        else -> "SDR"
      }
    }
    val colorSpace =
      when (colorInfo?.colorSpace) {
        C.COLOR_SPACE_BT2020 -> "BT.2020"
        C.COLOR_SPACE_BT709 -> "BT.709"
        else -> null
      }
    val declaredVideoBitrate = video?.bitrate?.takeIf { it > 0 } ?: 0
    val estimatedVideoBitrate =
      if (declaredVideoBitrate == 0 && sourceSizeBytes > 0L && reportedDurationMs > 0L) {
        ((sourceSizeBytes * 8_000L) / reportedDurationMs)
          .coerceIn(1L, Int.MAX_VALUE.toLong())
          .toInt()
      } else {
        0
      }
    val currentPos = (pendingSeekDisplayPositionMs ?: player.currentPosition).coerceAtLeast(0L)
    if (currentPos > 0L) lastKnownPositionMs = currentPos
    _snapshot.value = NativePlaybackSnapshot(
      isPlaying = player.isPlaying,
      isReady = player.playbackState == Player.STATE_READY,
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
      isLoading = player.isLoading,
      isEnded = player.playbackState == Player.STATE_ENDED,
      positionMs = currentPos,
      durationMs = reportedDurationMs,
      totalBufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L),
      videoWidth = video?.width ?: 0,
      videoHeight = video?.height ?: 0,
      videoMimeType = video?.sampleMimeType,
      videoCodec = video?.codecs,
      videoDecoder = videoDecoderName,
      audioDecoder = audioDecoderName,
      videoDynamicRange = dynamicRange,
      videoColorSpace = colorSpace,
      videoBitrate = declaredVideoBitrate.takeIf { it > 0 } ?: estimatedVideoBitrate,
      audioCodec = audio?.codecs ?: audio?.sampleMimeType,
      audioBitrate = audio?.bitrate?.takeIf { it > 0 } ?: 0,
      audioChannels = audio?.channelCount ?: 0,
      audioSampleRate = audio?.sampleRate ?: 0,
      speed = player.playbackParameters.speed,
      subtitleTracks = subtitles,
      audioTracks = audioTracks,
      chapters = chapters,
    )
  }

  /** Publishes only rapidly changing playback values; track/metadata enumeration is expensive. */
  private fun publishPlaybackSnapshot() {
    val previous = _snapshot.value
    val reportedDurationMs = player.duration
      .takeIf { it != C.TIME_UNSET && it > 0L }
      ?.also { lastKnownDurationMs = it }
      ?: lastKnownDurationMs
    val currentPos = (pendingSeekDisplayPositionMs ?: player.currentPosition).coerceAtLeast(0L)
    if (currentPos > 0L) lastKnownPositionMs = currentPos
    val isPlaying = player.isPlaying
    val isReady = player.playbackState == Player.STATE_READY
    val isBuffering = player.playbackState == Player.STATE_BUFFERING
    val isLoading = player.isLoading
    val isEnded = player.playbackState == Player.STATE_ENDED
    val buffered = player.totalBufferedDuration.coerceAtLeast(0L)
    val speed = player.playbackParameters.speed

    if (previous.isPlaying == isPlaying &&
      previous.isReady == isReady &&
      previous.isBuffering == isBuffering &&
      previous.isLoading == isLoading &&
      previous.isEnded == isEnded &&
      previous.speed == speed &&
      previous.durationMs == reportedDurationMs &&
      kotlin.math.abs(previous.positionMs - currentPos) < 150L &&
      kotlin.math.abs(previous.totalBufferedDurationMs - buffered) < 500L
    ) {
      return
    }

    _snapshot.value = previous.copy(
      isPlaying = isPlaying,
      isReady = isReady,
      isBuffering = isBuffering,
      isLoading = isLoading,
      isEnded = isEnded,
      positionMs = currentPos,
      durationMs = reportedDurationMs,
      totalBufferedDurationMs = buffered,
      speed = speed,
    )
  }

  private fun metadataEntriesToChapters(metadata: Metadata): List<NativeChapter> =
    (0 until metadata.length()).mapNotNull { index ->
      val entry = metadata.get(index)
      if (entry is Chapter) {
        val startTimeMs = entry.getStartTimeMs()
        if (startTimeMs == C.TIME_UNSET || startTimeMs < 0L) return@mapNotNull null
        val title = entry.getTitle()?.value?.trim().orEmpty()
        return@mapNotNull NativeChapter(
          title.ifBlank { "Chapter ${index + 1}" },
          startTimeMs / 1000f,
        )
      }
      if (!entry.javaClass.simpleName.contains("chapter", ignoreCase = true)) return@mapNotNull null
      val startTimeMs = runCatching {
        entry.javaClass.methods.firstOrNull { it.name == "getStartTimeMs" }?.invoke(entry) as? Number
      }.getOrNull()
      val startUs = startTimeMs?.toLong()?.times(1000L) ?: sequenceOf("getStartTimeUs", "getChapterTimeStart")
        .mapNotNull { method ->
          runCatching {
            entry.javaClass.methods.firstOrNull { it.name == method }?.invoke(entry) as? Number
          }.getOrNull()
        }.firstOrNull()
        ?: sequenceOf("startTimeUs", "chapterTimeStart")
          .mapNotNull { field ->
            runCatching {
              entry.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(entry) as Number
            }.getOrNull()
          }.firstOrNull()
        ?: return@mapNotNull null
      val title = sequenceOf("getTitle", "getChapterString")
        .mapNotNull { method ->
          runCatching {
            val value = entry.javaClass.methods.firstOrNull { it.name == method }?.invoke(entry)
            when (value) {
              is String -> value
              null -> null
              else -> value.javaClass.methods.firstOrNull { it.name == "getValue" }?.invoke(value) as? String
            }
          }.getOrNull()
        }.firstOrNull()
        ?: sequenceOf("title", "chapterString")
          .mapNotNull { field ->
            runCatching {
              entry.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(entry) as? String
            }.getOrNull()
          }.firstOrNull()
        ?: "Chapter ${index + 1}"
      NativeChapter(title, (startUs.toLong() / 1_000_000f).coerceAtLeast(0f))
    }

  private fun startTimelineUpdates() {
    loopHandler.removeCallbacks(timelineRunnable)
    if (player.currentMediaItem != null) loopHandler.post(timelineRunnable)
  }
}
