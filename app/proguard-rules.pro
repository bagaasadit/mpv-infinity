# =====================================================================
# R8 / ProGuard Optimization & Minification Configuration - mpvZero
# =====================================================================

# Aggressive optimization settings
-repackageclasses ''
-allowaccessmodification

# Compact stack trace preservation (preserves line numbers, hides raw file paths)
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile

# Attributes required by Kotlinx Serialization, Koin, Compose, and Room
-keepattributes Signature
-keepattributes *Annotation*

# Suppress harmless third-party warnings
-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# =====================================================================
# 1. LIBMPV JNI BRIDGE & CALLBACKS (SACRED)
# =====================================================================
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# MPVLib and MPVNode are invoked via JNI reflection from libplayer.so
-keep class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.MPVNode* { *; }
-keep class is.xyz.mpv.BaseMPVView { public protected *; }

# =====================================================================
# 2. MEDIA3 / EXOPLAYER RENDERERS & DECODERS
# =====================================================================
# Media3 AARs supply their own consumer rules. Only reflective extensions need keep:
# Jellyfin FFmpeg Audio Decoder is instantiated reflectively by DefaultRenderersFactory
-dontwarn androidx.media3.decoder.ffmpeg.**
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    public <init>(...);
}

# =====================================================================
# 4. ROOM DATABASE ENTITIES
# =====================================================================
-keep @androidx.room.Entity class * { *; }
