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
-keepattributes InnerClasses,EnclosingMethod

# Suppress harmless third-party warnings
-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# =====================================================================
# LOGGING STRIPPING (assumenosideeffects)
# =====================================================================
# Strip verbose, debug, and info logs in minified builds (230+ call-sites)
# Preserves Log.w, Log.e, Log.wtf for crash diagnostics and CrashActivity
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# =====================================================================
# 1. LIBMPV JNI BRIDGE & CALLBACKS (SACRED)
# =====================================================================
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# MPVLib, MPVNode, BaseMPVView, and all JNI interfaces (EventObserver, LogObserver, MpvFormat, MpvEvent)
-keep class is.xyz.mpv.** { *; }

# =====================================================================
# 2. MEDIA3 / EXOPLAYER RENDERERS & DECODERS
# =====================================================================
# Jellyfin FFmpeg Audio Decoder is instantiated reflectively by DefaultRenderersFactory
-dontwarn androidx.media3.decoder.ffmpeg.**
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    public <init>(...);
}

# Native libass / ASS subtitle renderer bindings
-keep class io.github.peerless2012.** { *; }

# =====================================================================
# 3. ROOM DATABASE & DAOS
# =====================================================================
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# =====================================================================
# 4. KOTLINX SERIALIZATION
# =====================================================================
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
