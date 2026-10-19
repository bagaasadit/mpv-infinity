# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontobfuscate
-keep,allowoptimization class is.xyz.mpv.** { public protected *; }
-keep,allowoptimization class net.mediaarea.mediainfo.lib.** { public protected *; }
-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**
-dontwarn org.slf4j.impl.StaticLoggerBinder


# Keep all classes that use reflection or are loaded dynamically
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# =====================================================================
# 1. LIBMPV JNI BRIDGE & CALLBACKS (SACRED)
# =====================================================================
-keep class is.xyz.mpv.** { *; }
-keepclassmembers class is.xyz.mpv.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# =====================================================================
# 2. LIBASS & MATROSKA SUBTITLE SUBSYSTEM (SACRED)
# =====================================================================
-keep class * extends androidx.media3.exoplayer.BaseRenderer { *; }
-keep class **.Media3LibassRenderer { *; }
-keep class **.LibassSubtitleSurfaceView { *; }
-keep class **.AssMatroskaExtractor* { *; }
-keep class **.MatroskaFontScanner* { *; }

# =====================================================================
# 3. MEDIA3 / EXOPLAYER RENDERERS & DECODERS
# =====================================================================
-dontwarn androidx.media3.**
-keepclassmembers class androidx.media3.** { *; }

# libass JNI bridge and native callbacks
-keep class androidx.media3.subtitle.libass.** { *; }

# Jellyfin FFmpeg Audio Decoder (instantiated via reflection by DefaultRenderersFactory)
-dontwarn org.jellyfin.media3.ffmpeg.**
-keep class org.jellyfin.media3.ffmpeg.** { *; }

# =====================================================================
# 4. SEEKER & UI CONTROLS
# =====================================================================
-keep class com.github.abdallahmehiz.seeker.** { *; }

# =====================================================================
# 5. ROOM DATABASE ENTITIES
# =====================================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep class * implements androidx.room.migration.Migration { *; }

