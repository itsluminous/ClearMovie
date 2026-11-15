# Building ClearMovie

> The Gradle project arrives with the first code milestone. The commands below are the
> ones the project will support. Until then, this repository holds only docs and the website.

Requirements: JDK 17+ and the Android SDK (compileSdk 36). The Android Gradle
plugin installs any missing SDK build-tools on first build if the SDK manager's
licences are accepted. No system Gradle is needed: use the wrapper (`./gradlew`),
which downloads the pinned Gradle version on first run. Devices: Android 10
(API 29) or newer.

```bash
git clone https://github.com/itsluminous/ClearMovie.git
cd ClearMovie
# point to your SDK if ANDROID_HOME is not set:
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew installDebug         # install on the connected device or emulator
```

Run checks the same way CI does:

```bash
./gradlew ktlintCheck lintDebug testDebugUnitTest
```

- **ktlint** uses the code style in `.editorconfig`. Run `./gradlew ktlintFormat` to fix formatting.
- **Android Lint** treats hard-coded UI text (`HardcodedText`), missing translations
  (`MissingTranslation`) and strings that exist only in a translation (`ExtraTranslation`)
  as errors. See [translating.md](translating.md).
- **Unit tests** include convention tests that scan the sources: every user-visible
  label must come from a string resource, the English, Hindi and Hinglish files must
  have the same keys and placeholders, and the colours used for the timeline and
  preview must keep enough contrast in light and dark themes.

`./gradlew assembleRelease` produces release APKs under
`app/build/outputs/apk/release/`: one per processor type (`arm64-v8a`,
`armeabi-v7a`, `x86_64`, `x86`) and a universal one. The split exists because some
AndroidX libraries ship small native (`.so`) files. Each APK has its own
`versionCode`, `base × 10 + abiCode` (0 universal, 1 armeabi-v7a, 2 arm64-v8a,
3 x86, 4 x86_64), so an update always installs the right APK. Without the signing
environment variables (`SIGNING_KEYSTORE_FILE`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) the APKs are unsigned.

GitHub Actions builds signed APKs on `v*` tags, using a key held in repository
secrets. It renames them to `ClearMovie-<version>-<abi>.apk` and attaches all of
them to the GitHub Release. CI fails if the universal APK contains native
libraries but the per-ABI APKs are missing. Keystores are never committed (`*.jks`
and `*.keystore` are gitignored).

## Instrumented tests

These need a running emulator or a connected phone.

```bash
ANDROID_SERIAL=<device-serial> ./gradlew connectedDebugAndroidTest
```

To run them on an emulator you already have, without changing it, start it in
read-only mode on its own port:

```bash
$ANDROID_HOME/emulator/emulator -list-avds
$ANDROID_HOME/emulator/emulator -avd <YourAvd> -read-only -no-snapshot-save -port 5584 &
adb -s emulator-5584 wait-for-device
ANDROID_SERIAL=emulator-5584 ./gradlew connectedDebugAndroidTest
adb -s emulator-5584 emu kill
```

Emulators encode video in software, so export tests prove correctness, not speed.
Hardware encoding is checked on a real phone, where a dedicated test runs and the
emulator skips it.

## Checking light and dark themes

ClearMovie follows the system's light or dark setting (Settings → Theme can override
it), and on Android 12+ takes its colours from the wallpaper (Settings → Colours from
wallpaper turns this off). When you change UI, check it in all four combinations:

```bash
adb -s emulator-5584 shell cmd uimode night yes   # dark; "no" for light
```

## Test media

Tests use small synthetic clips generated with `ffmpeg` and committed under
`app/src/test*/resources/fixtures`. Personal media is never committed.

## Adding a language

Translations need no code changes: add a `values-<code>/` folder next to the English
strings and the build picks it up, including Android's per-app language list. The
full steps are in [translating.md](translating.md).
