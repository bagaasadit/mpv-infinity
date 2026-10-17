# Comprehensive Architecture Audit & Pruning Plan: Pure Powerful MPV Player

> **Target Device**: POCO F5 (`marble`)  
> **Platform**: Android 15 (HyperOS), Snapdragon 7+ Gen 2 (Adreno 725), 120Hz Tianma Flow AMOLED, Non-rooted.  
> **Source Project**: `mpv-infinity` (Fork of mpv-android / modern mpv player).  
> **Goal**: Transform into an ultra-lightweight, blazing-fast, rock-solid dedicated external video player (mirroring **mpvKt**'s minimalism and responsiveness) while **100% preserving** all high-end playback capabilities (1000-Nit Dolby Vision Profiles 5/7/8.1, TrueHD 7.1 downmixing, dual Media3+MPV engine, libass SSA/ASS subtitles, and aspect ratio adaptation).

---

## 1. Executive Summary & Objective

The application has become heavy and sluggish compared to minimalist players like **mpvKt**, despite having world-class playback capabilities. A deep audit of the codebase reveals that `mpv-infinity` accumulated numerous non-player features over time:
- An entire torrent client (`libtorrent4j` with ~25MB of native binaries).
- A background YouTube/yt-dlp and direct download engine.
- A QuickJS Javascript engine for catalog scraping (`quickjs-kt-android-nuvio.aar`).
- Streaming server client stacks (Jellyfin, Navidrome, Audiobookshelf, Overseerr).
- An embedded code editor with TextMate grammar parsing (`Sora Editor`).
- Multi-protocol network file clients (SMB, SFTP/SSH, FTP, WebDAV, embedded NanoHTTPD).
- Biometric authentication and encrypted vault (`Secure Folder`).
- AI integration clients (OpenAI, Anthropic, Groq, Together, Ollama).
- Active LeakCanary memory tracking in debug builds.

The objective is to **completely prune and delete** these unused features—not just disable them—reducing APK size by ~50MB, cutting cold start time to under 300ms, and freeing the UI to run at an uncompromised 120Hz.

---

## 2. Root Cause Analysis: Why the UI Feels Heavy ("UI Masih Berat")

1. **LeakCanary Active in Debug Builds**:
   - `debugImplementation(libs.leakcanary.android)` is bundled in current builds.
   - LeakCanary intercepts every Activity lifecycle, hooks into `Choreographer`, and performs heap inspections.
   - Log evidence: `I Choreographer: Skipped 76 frames! The application may be doing too much work on its main thread.` (Over 600ms of UI freeze!).
2. **Debug Build vs Release Build (Jetpack Compose Overhead)**:
   - All builds so far have been `assembleStandardDebug`.
   - In Debug mode, the Compose compiler disables smart recomposition skipping and adds tracing metadata to every composable node.
   - In contrast, mpvKt is shipped as an R8-minified, compiler-optimized Release build.
3. **Startup Background Workers in `App.kt`**:
   - `FastThumbnails.initialize(this@App)` launches background libmpv worker threads during app launch.
   - `scheduleMetadataMaintenanceOnce()` runs SQLite database vacuum and maintenance.
   - `UpdateViewModel` queries GitHub release APIs.
   - Koin registers and instantiates dozens of background singletons.

---

## 3. The Core Powerful Engine (SACRED: MUST NOT BE TOUCHED)

These modules represent the core playback power and must remain 100% intact:

| Component | Files | Capabilities & Technical Purpose |
|---|---|---|
| **Dolby Vision & HDR Engine** | `HdrScreenOutput.kt`<br>`MPVView.kt` | 1000-Nit peak luminance control, Dolby Vision Profiles 5, 7, 8.1 dynamic metadata passthrough, BT.2020 PQ tonemapping via libplacebo / `vo=gpu-next`. |
| **Audio Downmixing & Bitstream** | `TrackSelector.kt`<br>`PlayerViewModel.kt` | TrueHD 7.1 to 5.1/stereo downmixing, DTS-HD MA, E-AC3 JOC Atmos passthrough, track switching. |
| **Dual-Engine Architecture** | `NativeMedia3Engine.kt`<br>`PlaybackSession.kt`<br>`MPVView.kt` | Seamless switching between Google Media3/ExoPlayer (for low-latency hardware decode) and libmpv (for custom shaders, complex codecs, and advanced rendering). |
| **Subtitle Subsystem** | `Media3LibassRenderer.java`<br>`LibassSubtitleSurfaceView.java`<br>`AssMatroskaExtractor.kt`<br>`MatroskaFontScanner.kt` | Pixel-perfect SSA/ASS subtitle styling via native `libass`, PGS bitmap subtitle rendering, external SRT/VTT auto-loading. |
| **Aspect & Geometry Control** | `VideoAspectGeometry.kt`<br>`StretchAwarePlayerLayout.kt` | Fit, Crop, Stretch, 16:9, 21:9, Zoom, Pan-and-Scan, and Dynamic Black-Bar Adaptation. |
| **Player UI & Touch HUD** | `PlayerActivity.kt`<br>`controls/`<br>`MPVPipHelper.kt` | Ultra-responsive touch gestures (brightness, volume, seeking), picture-in-picture (PiP), lock controls. |

---

## 4. Pruning Audit: Features & Code to Permanently Remove

### A. Third-Party Dependencies (`gradle/libs.versions.toml` & `build.gradle.kts`)

| Dependency Group | Libraries | Reason for Removal | Size Saving |
|---|---|---|---|
| **Torrent Engine** | `libtorrent4j`<br>`libtorrent4j-android-arm64`<br>`libtorrent4j-android-arm`<br>`libtorrent4j-android-x86`<br>`libtorrent4j-android-x8664` | Pure video player does not need torrent downloading or DHT background services. | ~25 MB - 35 MB |
| **Script Code Editor** | `sora-editor-bom`<br>`sora-editor`<br>`sora-language-textmate` | Users do not write Lua scripts on phone; Lua configs can be placed via file manager. | ~3.5 MB |
| **Network Protocols** | `smbj`<br>`commons-net`<br>`jsch`<br>`sardine-android`<br>`nanohttpd`<br>`libarchive-android` | SMB, FTP, SFTP, WebDAV, local HTTP server, and archive decompression are irrelevant for pure player / SAF intent. | ~6.0 MB |
| **Google Cast** | `google-cast-framework` | Bloated Google Play Services dependency that hooks into media routers. | ~4.5 MB |
| **Biometrics & Crypto** | `androidx-biometric`<br>`androidx-security-crypto` | Vault / Secure Folder is not needed in a pure player. | ~1.0 MB |
| **Catalog & JS Engine** | `libs/quickjs-kt-android-1.0.5-nuvio.aar` | QuickJS engine used solely for Nuvio online scraping. | ~1.8 MB |
| **Memory Profiler** | `leakcanary-android` | Causes massive main-thread frame drops (76+ frames skipped). | ~1.5 MB |
| **Markdown Renderer** | `markdown-renderer-m3` | Only used for release notes / changelog rendering. | ~0.5 MB |

### B. Source Code Packages (`app/src/main/java/app/infinity/mpvz/`)

| Package Path | Description | Action |
|---|---|---|
| `domain/torrent/`, `ui/torrent/` | Torrent streaming & torrent file selector | **DELETE** |
| `domain/download/`, `ui/downloads/` | Yt-dlp download service, Direct downloader, QuickDownloadActivity | **DELETE** |
| `catalog/`, `catalog/nuvio/` | Nuvio online streaming catalog and scrapers | **DELETE** |
| `data/jellyfin/`, `domain/jellyfin/` | Jellyfin media server client | **DELETE** |
| `data/navidrome/`, `domain/navidrome/` | Navidrome audio server client | **DELETE** |
| `data/audiobookshelf/`, `domain/audiobookshelf/` | Audiobookshelf server client | **DELETE** |
| `domain/seerr/` | Overseerr / Jellyseerr integration | **DELETE** |
| `domain/syncplay/` | Multi-user video sync across internet | **DELETE** |
| `ui/editor/` | In-app TextMate Lua editor | **DELETE** |
| `ui/securefolder/` | PIN / Biometric secure media vault | **DELETE** |
| `ui/cast/` | Google Cast remote control activities | **DELETE** |
| `repository/ai/` | OpenAI, Groq, Anthropic, Ollama video summarizers | **DELETE** |
| `data/lyrics/`, `domain/lyrics/`, `repository/lyrics/` | LrcLib lyrics fetchers & sync | **DELETE** |
| `database/entities/` (Unused) | `AudiobookEntity`, `AudiobookshelfServerEntity`, `DownloadItemEntity`, `JellyfinServerEntity`, `NavidromeServerEntity`, `SecureMediaEntity`, `DirectoryScanEntity` | **DELETE** |
| `utils/media/MediaScanReceiver.kt` | Recursive MediaStore scanner | **DELETE** |

### C. AndroidManifest.xml Pruning

- **Remove Permissions**:
  - `RECORD_AUDIO` (for audio visualizer).
  - `USE_BIOMETRIC`.
  - `REQUEST_INSTALL_PACKAGES` (in-app updater).
  - `FOREGROUND_SERVICE_DATA_SYNC` (torrent / ytdlp sync).
- **Remove Services & Receivers**:
  - `YtdlpDownloadService`
  - `DirectDownloadService`
  - `MediaTransferReceiver`
  - `MediaScanReceiver`
  - `MpvInfinityCastOptionsProvider`
- **Remove Activities**:
  - `TorrentSelectionActivity`
  - `CastRemoteControllerActivity`
  - `CastExpandedControlsActivity`
  - `QuickDownloadActivity` & `QuickDownloadActivityAlias`

---

## 5. Architectural Decoupling & Migration Plan

### Step 1: Clean Up Koin Dependency Injection (`di/`)
1. In `DomainModule.kt`:
   - Remove bindings for all AI clients (`OpenAiClient`, `GroqClient`, etc.).
   - Remove `TorrentStreamingEngine`, `SeerrRepository`, `SyncplayManager`, `LyricsRepository`.
   - Keep only: `Anime4KManager`, `HdrToysManager`, `PlaybackSessionShaderRuntime`, subtitle store.
2. In `DatabaseModule.kt`:
   - Prune unneeded DAOs and repositories.
   - Retain only `PlaybackStateEntity` (saves position, resume state, track preferences) and `RecentlyPlayedEntity` (recent history).
3. In `App.kt`:
   - Remove `DownloadModule` and `FileManagerModule` from `startKoin`.
   - Remove `scheduleFastThumbnailWarmupOnce()` and `scheduleMetadataMaintenanceOnce()` calls.

### Step 2: Clean Up `MainActivity.kt` & `PurePlayerHomeScreen.kt`
- Keep `PurePlayerHomeScreen` as the primary launcher UI.
- Remove `MiniPlayer` from `MainActivity.kt` (pure players launch `PlayerActivity` directly; no persistent bottom mini-player is needed).
- Remove `UpdateSheet` and update checker flows from `MainActivity.kt`.

### Step 3: Configure Optimized Release Build with Debug Keystore
To get full R8 optimizations without signing issues on the device:
```kotlin
buildTypes {
    named("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        // Sign release builds with debug keystore for seamless installation
        signingConfig = signingConfigs.getByName("debug")
    }
}
```

---

## 6. Verification & Performance Benchmarks to Track

| Metric | Current State (Build #15) | Target State (Post-Pruning) |
|---|---|---|
| **APK File Size** | ~95 MB (arm64-v8a) | **~35 - 42 MB** |
| **Cold Startup Time** | ~1800 ms | **< 300 ms** |
| **Main Thread Frame Drops** | 76+ frames skipped | **0 frames skipped (120 FPS rock-solid)** |
| **RAM Footprint (Idle)** | ~180 MB | **< 60 MB** |
| **Dolby Vision Profile 5/7/8** | Fully Functional | **100% Intact & Uncompromised** |
| **TrueHD 7.1 Downmixing** | Fully Functional | **100% Intact & Uncompromised** |
| **Dual Media3/MPV Engine** | Fully Functional | **100% Intact & Uncompromised** |

---

## 7. Discussion Prompts for the Reviewing AI

When submitting this audit to another AI for review, consider asking:

1. **Architecture & Decoupling**:
   - *"Are there any hidden dependencies between `PlayerViewModel` / `PlaybackSession` and the pruned network/database repositories that could cause runtime crashes?"*
2. **Room Database Strategy**:
   - *"Should we perform a destructive Room schema migration (fallbackToDestructiveMigration) since the app is being transitioned to a pure external player, or write a dedicated migration dropping the removed tables?"*
3. **Jetpack Compose Performance on 120Hz Displays**:
   - *"What specific `@Stable` / `@Immutable` annotations or custom layouts should be applied to `PlayerControls` and `PurePlayerHomeScreen` to guarantee zero recomposition overhead during 4K 60fps HDR playback?"*
4. **Libmpv Lifecycle & Memory Safety**:
   - *"How can we optimize `startIdleMpvCoreReaper` and surface binding to ensure that libmpv resources are released immediately upon exiting playback without affecting fast re-entry?"*
5. **Additional Bloat Identification**:
   - *"Looking at the file structure, are there any other dormant libraries or services that can be eliminated to achieve absolute parity with mpvKt?"*
