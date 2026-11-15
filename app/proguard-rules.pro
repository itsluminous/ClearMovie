# R8 rules for ClearMovie.
#
# Hilt, Room, DataStore, Compose and AppCompat ship consumer rules inside their
# artifacts, so nothing app-specific is needed yet.

# Keep source file + line numbers so stack traces from users stay readable.
# The app is open source: obfuscation is not a goal, size is.
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
