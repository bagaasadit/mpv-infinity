# libass JNI classes are loaded through the native bridge and must remain available.
-keep class androidx.media3.subtitle.libass.** { *; }
