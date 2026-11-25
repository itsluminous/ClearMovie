# R8 rules for ClearMovie.
#
# Hilt, Room, DataStore, Compose and AppCompat ship consumer rules inside their
# artifacts, so nothing app-specific is needed yet.

# Keep source file + line numbers so stack traces from users stay readable.
# The app is open source: obfuscation is not a goal, size is.
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# cm-voice-choice: the voice pack's native libraries call back into these classes and read their
# fields by name (sherpa-onnx JNI), or reach them by reflection (Vosk through JNA).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# cm-voice-pack-download: the voice pack downloader is the only code that may use the network. It is
# kept whole (not inlined or merged into other classes), so scripts/verify-release-apks.sh can check
# that every network call in the release APK is in this class or a known, unused library one.
-keep class com.itsluminous.clearmovie.data.voice.PackDownloader { *; }
