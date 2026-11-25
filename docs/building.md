# Building ClearMovie

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
./gradlew ktlintCheck lintDebug testDebugUnitTest koverVerifyDebug
```

- **ktlint** uses the code style in `.editorconfig`. Run `./gradlew ktlintFormat` to fix formatting.
- **Android Lint** treats hard-coded UI text (`HardcodedText`), missing translations
  (`MissingTranslation`) and strings that exist only in a translation (`ExtraTranslation`)
  as errors. See [translating.md](translating.md).
- **Unit tests** include convention tests that scan the sources: every user-visible
  label must come from a string resource (Lint cannot see literals in Compose code, so
  `UserVisibleLabelConventionTest` does), the English, Hindi and Hinglish files must
  have the same keys and placeholders (`StringKeyParityTest`), and the colours used for
  the timeline and preview must keep enough contrast in light and dark themes. The
  Robolectric tests emulate Android 14 (SDK 34), because newer SDKs need Java 21 and the
  build uses JDK 17; real Android 10 and 16 are covered by the instrumented tests.
- **Model coverage:** `koverVerifyDebug` fails if the unit tests cover less than 90% of
  the lines in `domain.model` (the project and preset model). `./gradlew
  koverHtmlReportDebug` writes a report under `app/build/reports/kover/` showing
  which lines are missed.

`./gradlew assembleRelease` produces one release APK,
`app/build/outputs/apk/release/ClearMovie-<version>.apk`, for every processor
type: it carries the small AndroidX native (`.so`) libraries for `arm64-v8a`,
`armeabi-v7a`, `x86_64` and `x86`. Its `versionCode` is `base × 10` (see
[release-signing.md](release-signing.md)). Without the signing environment
variables (`SIGNING_KEYSTORE_FILE`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) the APK is unsigned. The debug
build is `app/build/outputs/apk/debug/app-debug.apk`.
Check a release build the way CI does:

```bash
./gradlew assembleRelease
scripts/verify-release-apks.sh     # one APK, its name, native libraries, versionCode
```

GitHub Actions builds a signed APK on `v*` tags, using a key held in repository
secrets, and attaches it to the GitHub Release (see
[release-signing.md](release-signing.md)). CI fails if there is not exactly one
APK, if its name does not match its versionName, if it has no native libraries
or an ABI lacks ones the others have, or if its versionCode is not `base × 10`
(above 14 after 0.0.1), if it asks for any permission not on the script's
list, if its network security config is not the expected one (no plain
HTTP, only `raw.githubusercontent.com` trusted), or if any code but the
voice pack downloader (and a known list of unused library methods) calls
the network. Keystores are never committed
(`*.jks` and `*.keystore` are gitignored).

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
Hardware encoding is checked on a real phone, where a dedicated test
(`ExportDeviceTest.hardwareEncoderIsUsedOnAPhone`) runs and the emulator skips
it with the reason. Every export logs its encoders and the decoders it keeps
open at once (`adb logcat -s ExportPipeline RenderExporter VideoExport`).

The render tests (`app/src/androidTest/.../render/`) export small projects through
the real Transformer and check pixels and sound levels in the output: keying, layer
order, transitions, timing and ducking. Each export is also written to the app's
external files folder, `render-tests/`, with the frames it checked, so you can look
at them (`adb pull /sdcard/Android/data/com.itsluminous.ClearMovie/files/render-tests`)
before the test run uninstalls the app. Two kinds of render test only run by hand:

- `SpikeLongTest` (a 2-minute export watching memory, and frame-video seek times)
  takes several minutes: add `-Pandroid.testInstrumentationRunnerArguments.cm.long=true`.
- `RealMediaExportTest` exports a whole project made from real preset files
  at every size through the real export, for a by-eye check. Put
  `Intro.mp4`, `Frame-White.mp4`, `speech_last.mp3` and some `photo*.jpg` and
  `video*.mp4` files in the app's external files folder under `realmedia/`,
  then add `-Pandroid.testInstrumentationRunnerArguments.cm.realmedia=true`.
  The outputs and `realmedia-timeline.txt` (when each part starts) land in
  `render-tests/`.
- `RealFrameKeyingTest` checks keying against a real preset frame. Put `Intro.mp4`,
  `Frame-White-20s.mp4` (16 s or more) and `speech_last.mp3` in the app's
  external files folder under `realmedia/` first; without them it is skipped.
  Never commit such media.

## Checking light and dark themes

ClearMovie follows the system's light or dark setting (Settings → Theme can override
it), and on Android 12+ takes its colours from the wallpaper (Settings → Colours from
wallpaper turns this off). When you change UI, check it in all four combinations:

```bash
adb -s emulator-5584 shell cmd uimode night yes   # dark; "no" for light
```

## Test media

Tests that need media use small synthetic clips generated with `ffmpeg` and committed
under `app/src/androidTest/assets/fixtures`, each under 300 KB:

```bash
scripts/make-fixtures.sh    # regenerates every fixture; the script says what each one is
```

Personal media is never committed.

## Adding a language

Translations need no code changes: add a `values-<code>/` folder next to the English
strings and the build picks it up, including Android's per-app language list. The
full steps are in [translating.md](translating.md).
