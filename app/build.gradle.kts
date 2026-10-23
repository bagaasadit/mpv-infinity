import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProperties =
  Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
      localFile.inputStream().use { load(it) }
    }
  }

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
  Properties().apply {
    if (keystorePropertiesFile.exists()) {
      keystorePropertiesFile.inputStream().use { load(it) }
    }
  }

val targetAbiProp = project.findProperty("targetAbi")?.toString() ?: localProperties.getProperty("targetAbi")
val activeAbis =
  if (!targetAbiProp.isNullOrBlank()) {
    targetAbiProp.split(",").map { it.trim() }
  } else {
    listOf("arm64-v8a")
  }
val universalOnlyDistributions = setOf("noVulkan", "fongmi")
val releaseVersionCode = 259
val versionCodeBandSize = 10_000
val stableVersionCode = releaseVersionCode * versionCodeBandSize + (versionCodeBandSize - 1)
val previewVersionCode =
  (releaseVersionCode + 1) * versionCodeBandSize +
    (getCommitCount().toIntOrNull() ?: 1).coerceIn(1, versionCodeBandSize - 2)

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
  alias(libs.plugins.ktlint)
}

android {
  namespace = "app.infinity.mpvz"
  compileSdk = 37
  ndkVersion = "27.3.13750724"

  defaultConfig {
    applicationId = "app.mpvzero"
    minSdk = 26
    targetSdk = 36
    // Stable occupies the top of its version band. Preview uses the next band's commit-count
    // offset, so Stable -> Preview -> newer Preview -> next Stable is always an Android upgrade.
    versionCode = 1
    versionName = "1.0.0"

    resourceConfigurations += listOf("en", "ar", "de", "es", "fr", "hi", "ja", "pt-rBR", "ru", "zh-rCN")

    vectorDrawables {
      useSupportLibrary = true
    }

    buildConfigField("String", "GIT_SHA", "\"${getCommitSha()}\"")
    buildConfigField("int", "GIT_COUNT", getCommitCount())
  }

  flavorDimensions += "distribution"

  productFlavors {
    create("standard") {
      dimension = "distribution"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("String", "UPDATE_APK_VARIANT", "\"standard\"")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_VULKAN", "true")
      buildConfigField("boolean", "MPV_SUPPORTS_MEDIACODEC_VULKAN", "false")
    }

    create("noVulkan") {
      dimension = "distribution"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("String", "UPDATE_APK_VARIANT", "\"no-vulkan\"")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_VULKAN", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_MEDIACODEC_VULKAN", "false")
    }

    create("fongmi") {
      dimension = "distribution"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("String", "UPDATE_APK_VARIANT", "\"fongmi\"")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_VULKAN", "true")
      buildConfigField("boolean", "MPV_SUPPORTS_MEDIACODEC_VULKAN", "true")
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  splits {
    abi {
      isEnable = true
      reset()
      include(*activeAbis.toTypedArray())
      isUniversalApk = activeAbis.size > 1
    }
  }

  signingConfigs {
    create("release") {
      val envKeystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
      val propKeystorePath = keystoreProperties.getProperty("storeFile")

      val resolvedFile =
        when {
          !envKeystorePath.isNullOrBlank() -> file(envKeystorePath)
          !propKeystorePath.isNullOrBlank() -> file(propKeystorePath)
          else -> null
        }

      if (resolvedFile != null && resolvedFile.exists()) {
        storeFile = resolvedFile
        storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
        keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
        keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    named("release") {
      buildConfigField("boolean", "IS_PREVIEW_BUILD", "false")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      val releaseSigning = signingConfigs.findByName("release")
      signingConfig =
        if (releaseSigning?.storeFile?.exists() == true) {
          releaseSigning
        } else {
          logger.lifecycle("⚠️ Release keystore not found. Falling back to debug signing config.")
          signingConfigs.getByName("debug")
        }
      ndk {
        debugSymbolLevel = "none"
      }
    }

    create("preview") {
      initWith(getByName("release"))
      signingConfig = null
      buildConfigField("boolean", "IS_PREVIEW_BUILD", "true")
      versionNameSuffix = "-beta.r${getCommitCount()}"
    }

    named("debug") {
      buildConfigField("boolean", "IS_PREVIEW_BUILD", "false")
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"
      resValue("string", "app_name", "Mpv∞-Debug")
    }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    viewBinding = true
    buildConfig = true
    resValues = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE*"
      excludes += "META-INF/NOTICE*"
      excludes += "META-INF/*.kotlin_module"
      excludes += "META-INF/*.version"
      excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
      excludes += "kotlin/**"
      excludes += "**/*.proto"
    }
    jniLibs {
      useLegacyPackaging = true
      pickFirsts += "**/libc++_shared.so"
    }
  }

  @Suppress("UnstableApiUsage")
  androidResources {
    generateLocaleConfig = true
  }

  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

androidComponents {
  val abiCodes =
    mapOf(
      "armeabi-v7a" to 1,
      "arm64-v8a" to 2,
      "x86" to 3,
      "x86_64" to 4,
    )

  onVariants { variant ->
    val isUniversalOnly =
      variant.productFlavors.any { (dimension, flavor) ->
        dimension == "distribution" && flavor in universalOnlyDistributions
      }

    variant.outputs.forEach { output ->
      val abi =
        output.filters
          .find { it.filterType == FilterConfiguration.FilterType.ABI }
          ?.identifier

      // Keep universal packages for these flavors, but also publish the arm64-v8a split for
      // modern devices that need the architecture-specific native playback profile.
      if (isUniversalOnly && abi != null && abi != "arm64-v8a") {
        output.enabled.set(false)
      }

      val channelVersionCode =
        if (variant.buildType == "preview") previewVersionCode else (output.versionCode.orNull ?: stableVersionCode)
      output.versionCode.set(channelVersionCode * 10 + (abiCodes[abi] ?: 0))
    }
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
    )
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

composeCompiler {
  includeSourceInformation = false
}

room {
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3.android)
  implementation(libs.google.material)
  implementation(libs.androidx.compose.material)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  testImplementation("junit:junit:4.13.2")
  implementation(libs.bundles.compose.navigation3)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.compose.constraintlayout)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.composables.material.symbols.rounded.filled.android)
  implementation(libs.mediasession)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.palette)

  implementation(platform(libs.koin.bom))
  implementation(libs.bundles.koin)

  implementation(libs.seeker)
  implementation(libs.compose.prefs)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.jsoup)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.exoplayer.dash)
  implementation(libs.androidx.media3.ui)
  implementation("io.github.peerless2012:ass-kt:0.5.1")
  implementation("io.github.peerless2012:ass-media:0.5.1")
  implementation(libs.jellyfin.media3.ffmpeg.decoder)

  coreLibraryDesugaring(libs.desugar.jdk.libs)

  implementation(libs.truetype.parser)
  implementation(libs.fsaf)
  implementation(libs.androidx.profileinstaller)

  "standardImplementation"(files("libs/mpvlib.aar"))
  "noVulkanImplementation"(files("libs/mpvlib-no-vulkun.aar"))
  "fongmiImplementation"(files("libs/mpvlib-fongmi.aar"))

  implementation(libs.reorderable)
}

// ---------------- Git helpers ----------------

fun getCommitCount(): String = runCommand("git rev-list --count HEAD") ?: "0"

fun getCommitSha(): String = runCommand("git rev-parse --short HEAD") ?: "unknown"

fun runCommand(command: String): String? =
  try {
    val parts = command.split(' ')
    val process =
      ProcessBuilder(parts)
        .redirectErrorStream(true)
        .start()

    val output =
      process.inputStream
        .bufferedReader()
        .readText()
        .trim()

    process.waitFor()
    output.ifEmpty { null }
  } catch (e: Exception) {
    null
  }
