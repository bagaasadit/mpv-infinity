<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="200" height="200" />
</p>

<h1 align="center">mpvZero</h1>

<p align="center">
  <b>Ultralight, high-performance Android media player optimized for dedicated 4K Remux direct-stream playback, zero-bloat architecture, and audiophile IEM headphone downmixing.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg" />
  <img src="https://img.shields.io/badge/License-AGPL_v3-blue.svg" />
  <img src="https://img.shields.io/github/v/release/bagaasadit/mpv-infinity.svg?logo=github&label=Release&cacheSeconds=3600" />
</p>

---

## Overview

**mpvZero** is a dedicated, zero-bloat fork built specifically for uncompromising media playback:
- **Dual Playback Engine Architecture**: Full MPV (libmpv `gpu-next` / Vulkan) alongside AndroidX Native Media3 (160 MB buffer, `CLOSEST_SYNC`, direct surface punch-through).
- **Audiophile IEM Downmix Engine**: High-fidelity multichannel-to-stereo downmixing powered by 32-bit float arithmetic, ITU-R BS.775 attenuation, BS2B crossfeed filter, a true lookahead peak limiter ($-1.5\text{ dBFS}$), and TPDF dithering.
- **Precision Subtitles**: Dual subtitle rendering with native `libass` C++ JNI integration and Android system font fallback.
- **Zero-Bloat Guarantee**: No telemetry, no background scrapers, no bloatware dependencies.

---

## Showcase

<div align="center">
  <img src="docs/showcase/player-glass.jpg" width="90%" alt="mpvZero player surface">
</div>

<br>

<div align="center">
  <img src="docs/showcase/video-library.jpg" width="31%" alt="mpvZero video library">
  <img src="docs/showcase/about-mpv-infinity.jpg" width="31%" alt="mpvZero About page">
  <img src="docs/showcase/decoder-native.jpg" width="31%" alt="Playback engine selector">
</div>

---

## Build

### Requirements
- JDK 17
- Android SDK with platform tools & NDK installed

### Build Command
```bash
./gradlew :app:assembleStandardRelease
```

---

## Lineage & Acknowledgments

mpvZero is built upon the open-source lineage of:
- **[Mpv-infinity](https://github.com/ZHINFINITY/Mpv-infinity)** (Upstream Project by ZHINFINITY)
- **[mpvRx](https://github.com/Riteshp2001/mpvRx)**
- **[mpvKt](https://github.com/husams/mpvKt)**
- **[mpvEx](https://github.com/marlboro-advance/mpvEx)**
- **[mpv-android](https://github.com/mpv-android/mpv-android)**

And relies on foundational open-source components:
- [mpv](https://mpv.io) & [libmpv](https://github.com/mpv-player/mpv)
- [AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3)
- [FFmpeg](https://ffmpeg.org)
- [libass](https://github.com/libass/libass)
- [QuickJS](https://bellard.org/quickjs/)

---

## License

Distributed under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) for details.
