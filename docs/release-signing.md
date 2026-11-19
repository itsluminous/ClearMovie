# Release signing and APKs

CI builds release APKs on every push to `main`, every `v*` tag and every pull
request to `main`. Only a `v*` tag is signed: pushes to `main`, pull requests
and forks build unsigned APKs, so the checks below always run and no signed
build of an unreleased commit leaves CI. A `v*` tag refuses to publish unless the APKs are
signed and the tag matches `versionName`.

## The five APKs

ClearMovie bundles small native libraries from AndroidX (Compose's
`libandroidx.graphics.path.so` and DataStore's `libdatastore_shared_counter.so`),
so each release has one APK per processor type plus a universal one:

| File | abiCode | versionCode |
| --- | --- | --- |
| `ClearMovie-<version>-universal.apk` | 0 | base × 10 |
| `ClearMovie-<version>-armeabi-v7a.apk` | 1 | base × 10 + 1 |
| `ClearMovie-<version>-arm64-v8a.apk` | 2 | base × 10 + 2 |
| `ClearMovie-<version>-x86.apk` | 3 | base × 10 + 3 |
| `ClearMovie-<version>-x86_64.apk` | 4 | base × 10 + 4 |

`base` is `baseVersionCode` in `app/build.gradle.kts`. Every per-ABI APK has a
higher code than the universal one, so moving from the universal APK to the
matching per-ABI APK is always an update. Raise `baseVersionCode` by one for each
release.

`scripts/verify-release-apks.sh` (run by the release job) checks the names, that
each per-ABI APK contains only its own `lib/<abi>/` folder, the versionCodes,
and, when signed, a v2 and a v3 signature on every APK. It also guards the
splits: if the universal APK ships any `.so` but fewer than four per-ABI APKs
exist, it fails with "Native code found but ABI splits are off". If a change ever
removes every native library, it fails until `splits.abi` is removed on purpose
and this page is updated.

## One-time keystore setup

Keep the keystore and its passwords private, and keep an offline backup: a lost
key means existing installs can no longer be updated. It is never committed
(`*.jks` and `*.keystore` are gitignored).

```bash
keytool -genkeypair -v -keystore clearmovie-release.jks -alias clearmovie \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets under
*Settings → Secrets and variables → Actions*:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `base64 -i clearmovie-release.jks` on macOS, `base64 -w0 clearmovie-release.jks` on Linux |
| `SIGNING_KEYSTORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | the key alias (e.g. `clearmovie`) |
| `SIGNING_KEY_PASSWORD` | the key password |

Or with the GitHub CLI (macOS `base64` shown; use `base64 -w0` on Linux):

```bash
gh secret set SIGNING_KEYSTORE_BASE64 --body "$(base64 -i clearmovie-release.jks)"
gh secret set SIGNING_KEYSTORE_PASSWORD
gh secret set SIGNING_KEY_ALIAS --body "clearmovie"
gh secret set SIGNING_KEY_PASSWORD
```

Locally, the same four values go in environment variables
(`SIGNING_KEYSTORE_FILE` is the path to the `.jks`); with none set,
`./gradlew assembleRelease` builds unsigned APKs.

## Releasing

1. Raise `baseVersionCode` and `versionName` in `app/build.gradle.kts`.
2. Add the user-facing notes: a short summary (under 500 characters, the
   fastlane convention) at
   `fastlane/metadata/android/en-US/changelogs/<baseVersionCode>.txt`, and,
   for a longer GitHub Release text, `docs/release-notes/v<versionName>.md`.
   (F-Droid looks changelogs up by an APK's own versionCode,
   `base × 10 + abiCode`, so an F-Droid listing would need its own files.)
3. Push a tag matching `versionName`, such as `v0.0.1`. The release job builds
   and signs the five APKs and verifies them; once the instrumented tests have
   also passed, the publish job attaches all five to a GitHub Release. Its text
   is `docs/release-notes/<tag>.md` if it exists, otherwise the changelog (or a
   one-line stand-in if that is missing too), plus GitHub's generated commit
   list.
