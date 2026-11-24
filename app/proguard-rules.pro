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
