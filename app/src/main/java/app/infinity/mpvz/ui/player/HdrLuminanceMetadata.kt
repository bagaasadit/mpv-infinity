/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format

/**
 * Parsed HDR & Dolby Vision Luminance Metadata (CTA-861.3 / SMPTE ST 2086 / ST 2094).
 */
data class HdrLuminanceMetadata(
  val formatName: String,
  val isHdr: Boolean = false,
  val isDolbyVision: Boolean = false,
  val dvProfile: String? = null,
  val maxMasteringLuminance: Int? = null,
  val minMasteringLuminance: Double? = null,
  val maxCll: Int? = null,
  val maxFall: Int? = null,
  val targetDisplayNits: Int = 1000,
)

data class Cta861Metadata(
  val maxMastering: Int?,
  val minMastering: Double?,
  val maxCll: Int?,
  val maxFall: Int?,
)

object HdrMetadataParser {

  /**
   * Parse CTA-861.3 Type 1 HDR Static Metadata (25 bytes payload).
   * Byte 17-18: max_display_mastering_luminance (unsigned 16-bit, Little Endian, in 1 cd/m²)
   * Byte 19-20: min_display_mastering_luminance (unsigned 16-bit, Little Endian, in 0.0001 cd/m²)
   * Byte 21-22: max_content_light_level (MaxCLL, unsigned 16-bit, Little Endian, in 1 cd/m²)
   * Byte 23-24: max_frame_average_light_level (MaxFALL, unsigned 16-bit, Little Endian, in 1 cd/m²)
   */
  fun parseCta861StaticInfo(bytes: ByteArray?): Cta861Metadata? {
    if (bytes == null || bytes.size < 25) return null
    return try {
      val maxMastering = (bytes[17].toInt() and 0xFF) or ((bytes[18].toInt() and 0xFF) shl 8)
      val minMasteringRaw = (bytes[19].toInt() and 0xFF) or ((bytes[20].toInt() and 0xFF) shl 8)
      val minMastering = minMasteringRaw / 10000.0
      val maxCll = (bytes[21].toInt() and 0xFF) or ((bytes[22].toInt() and 0xFF) shl 8)
      val maxFall = (bytes[23].toInt() and 0xFF) or ((bytes[24].toInt() and 0xFF) shl 8)
      Cta861Metadata(
        maxMastering = maxMastering.takeIf { it > 0 },
        minMastering = minMastering.takeIf { minMasteringRaw > 0 },
        maxCll = maxCll.takeIf { it > 0 },
        maxFall = maxFall.takeIf { it > 0 },
      )
    } catch (_: Exception) {
      null
    }
  }

  fun parseFromMedia3Format(format: Format?, titleOrUri: String? = null): HdrLuminanceMetadata {
    val metaLower = titleOrUri.orEmpty().lowercase()
    val mime = format?.sampleMimeType.orEmpty().lowercase()
    val codecs = format?.codecs.orEmpty().lowercase()
    val colorInfo = format?.colorInfo

    val filenameHasDv = metaLower.contains("dolby vision") ||
      metaLower.contains("dolby.vision") ||
      metaLower.contains("dolbyvision") ||
      metaLower.contains("dovi") ||
      metaLower.contains(".dv.") ||
      metaLower.contains("-dv.") ||
      metaLower.contains("_dv_") ||
      metaLower.contains(" dv ") ||
      metaLower.contains(".dv ")
    val filenameHasHdr = filenameHasDv ||
      metaLower.contains("hdr") ||
      metaLower.contains("hdr10") ||
      metaLower.contains("hlg")

    val isDv = mime.contains("dolby-vision") ||
      codecs.startsWith("dvh1") ||
      codecs.startsWith("dvhe") ||
      codecs.startsWith("dav1") ||
      codecs.startsWith("dva1") ||
      filenameHasDv

    val dvProfile = when {
      codecs.contains(".05") || codecs.startsWith("dvh1.05") || codecs.startsWith("dvhe.05") || metaLower.contains("profile 5") || metaLower.contains("profile.5") || metaLower.contains(".p5.") || metaLower.contains("appletv") || metaLower.contains("atvp") -> "Profile 5 (Apple TV / Web-DL)"
      codecs.contains(".08") || codecs.startsWith("dvh1.08") || codecs.startsWith("dvhe.08") || metaLower.contains("profile 8") || metaLower.contains("profile.8") || metaLower.contains(".p8.") -> "Profile 8.1"
      codecs.contains(".07") || codecs.startsWith("dvh1.07") || codecs.startsWith("dvhe.07") || metaLower.contains("profile 7") || metaLower.contains("profile.7") || metaLower.contains(".p7.") || metaLower.contains("remux") -> "Profile 7 (UHD Blu-ray)"
      isDv -> "Dolby Vision"
      else -> null
    }

    val isHdr = isDv ||
      colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
      colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG ||
      colorInfo?.colorSpace == C.COLOR_SPACE_BT2020 ||
      filenameHasHdr

    val staticInfo = parseCta861StaticInfo(colorInfo?.hdrStaticInfo)

    val formatName = when {
      dvProfile != null -> "Dolby Vision $dvProfile"
      isDv -> "Dolby Vision"
      colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG || metaLower.contains("hlg") -> "HLG (Hybrid Log-Gamma)"
      colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 || metaLower.contains("hdr10") -> "HDR10 (PQ)"
      isHdr -> "HDR10 (PQ)"
      else -> "SDR (Rec.709)"
    }

    return HdrLuminanceMetadata(
      formatName = formatName,
      isHdr = isHdr,
      isDolbyVision = isDv,
      dvProfile = dvProfile,
      maxMasteringLuminance = staticInfo?.maxMastering ?: (if (isHdr) 1000 else null),
      minMasteringLuminance = staticInfo?.minMastering ?: (if (isHdr) 0.0001 else null),
      maxCll = staticInfo?.maxCll ?: (if (isHdr) 1000 else null),
      maxFall = staticInfo?.maxFall ?: (if (isHdr) 280 else null),
      targetDisplayNits = 1000,
    )
  }
}
