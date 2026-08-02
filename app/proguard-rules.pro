# StreamPack native library
-keep class io.github.thibaultbee.** { *; }
-keep class io.github.thibaultbee.srtdroid.** { *; }

# SRT native
-keepclassmembers class * {
    native <methods>;
}
