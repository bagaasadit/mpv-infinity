package app.infinity.mpvz.utils.media

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File

object SystemFontSymlinkManager {
  private const val TAG = "SystemFontSymlink"

  // Candidate paths for system CJK, Arabic, Thai, Devanagari, and Emoji fonts across Android 10-15 & OEMs
  private val CANDIDATE_FONT_PATHS = listOf(
    // CJK Pan-Asian TrueType Collection (Android 10 - 15)
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/NotoSerifCJK-Regular.ttc",
    // OEM CJK & Regional Variable Fonts (Xiaomi HyperOS / MIUI)
    "/product/fonts/MiSansVF.ttf",
    "/product/fonts/MiSansTCVF.ttf",
    "/product/fonts/MiSansArabicVF.ttf",
    "/product/fonts/MiSansThaiVF.ttf",
    "/product/fonts/MiSansDevanagariVF.ttf",
    // World Languages (Arabic, Thai, Devanagari, Emoji)
    "/system/fonts/NotoNaskhArabic-Regular.ttf",
    "/system/fonts/NotoSansThai-Regular.ttf",
    "/system/fonts/NotoSansDevanagari-VF.ttf",
    "/system/fonts/NotoColorEmoji.ttf",
    // Legacy Fallback
    "/system/fonts/DroidSansFallback.ttf",
  )

  /**
   * Ensures symlinks to system fonts exist inside context.filesDir/fonts/.
   * This enables libass to render CJK, Arabic, Thai, and Emoji glyphs
   * with zero additional APK size.
   */
  fun ensureSystemFontSymlinks(context: Context) {
    val fontsDir = File(context.filesDir, "fonts")
    if (!fontsDir.exists()) {
      fontsDir.mkdirs()
    }

    val detectedSystemFonts = mutableSetOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      runCatching {
        val availableFonts = android.graphics.fonts.SystemFonts.getAvailableFonts()
        for (font in availableFonts) {
          val path = font.file?.absolutePath ?: continue
          val name = font.file?.name?.lowercase() ?: continue
          if (name.contains("cjk") || name.contains("emoji") ||
            name.contains("arabic") || name.contains("thai") || name.contains("misans")
          ) {
            detectedSystemFonts.add(path)
          }
        }
      }.onFailure { Log.w(TAG, "SystemFonts API query failed, falling back to static scan", it) }
    }

    for (candidate in CANDIDATE_FONT_PATHS) {
      if (File(candidate).exists()) {
        detectedSystemFonts.add(candidate)
      }
    }

    var symlinkedCount = 0
    for (fontPath in detectedSystemFonts) {
      val targetFile = File(fontPath)
      if (!targetFile.exists() || !targetFile.canRead()) continue

      val linkFile = File(fontsDir, targetFile.name)
      if (linkFile.exists()) continue

      runCatching {
        Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
        symlinkedCount++
        Log.d(TAG, "Created font symlink: ${linkFile.name} -> ${targetFile.absolutePath}")
      }.onFailure { e ->
        Log.w(TAG, "Failed to symlink font ${targetFile.name}", e)
      }
    }

    Log.i(TAG, "System font symlinks verified. New symlinks created: $symlinkedCount")
  }
}
