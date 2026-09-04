# The original IJK playback runtime resolves Java classes and native methods by name.
-keep class tv.danmaku.ijk.media.player.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.local.ktv.KtvApplication { *; }
-keep class com.local.ktv.MainActivity { *; }
-keep class com.local.ktv.BootReceiver { *; }
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
