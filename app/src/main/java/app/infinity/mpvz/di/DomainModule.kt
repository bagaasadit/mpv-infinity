/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.di

import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.domain.hdr.HdrToysManager
import app.infinity.mpvz.domain.hdr.MpvShaderRuntime
import app.infinity.mpvz.network.AndroidCookieJar
import app.infinity.mpvz.network.SharedHttpClient
import app.infinity.mpvz.repository.IntroDbRepository
import app.infinity.mpvz.repository.subtitle.OnlineSubtitleFileStore
import app.infinity.mpvz.repository.subtitle.OnlineSubtitleOrchestrator
import app.infinity.mpvz.repository.subtitlehub.MpvInfinitySubtitleHubRepository
import app.infinity.mpvz.repository.wyzie.WyzieSearchRepository
import app.infinity.mpvz.ui.player.PlaybackSessionShaderRuntime
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val domainModule =
  module {
    single { AndroidCookieJar() }
    single<OkHttpClient> {
      SharedHttpClient.derive {
        connectTimeout(30, TimeUnit.SECONDS)
        cookieJar(get<AndroidCookieJar>())
      }
    }
    single { Anime4KManager(androidContext()) }
    single<MpvShaderRuntime> { PlaybackSessionShaderRuntime }
    single { HdrToysManager(androidContext(), get()) }
    single { OnlineSubtitleFileStore(androidContext(), get()) }
    single { WyzieSearchRepository(androidContext(), get(), get(), get(), get()) }
    single { MpvInfinitySubtitleHubRepository(get(), get(), get(), get()) }
    single { OnlineSubtitleOrchestrator(get<WyzieSearchRepository>(), get<MpvInfinitySubtitleHubRepository>()) }
    single { IntroDbRepository(get(), get()) }
  }
