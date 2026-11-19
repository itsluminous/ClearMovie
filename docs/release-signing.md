# Release signing and the APK

CI builds the release APK on every push to `main`, every `v*` tag and every pull
request to `main`. Only a `v*` tag is signed: pushes to `main`, pull requests
and forks build an unsigned APK, so the checks below always run and no signed
build of an unreleased commit leaves CI. A `v*` tag refuses to publish unless the APK is
signed and the tag matches `versionName`.

## The APK

Each release is one APK, `ClearMovie-<version>.apk`, that works on every
processor type. ClearMovie bundles small native libraries from AndroidX
(Compose's `libandroidx.graphics.path.so` and DataStore's
`libdatastore_shared_counter.so`), but they are only a few KB each: 0.0.1's
per-ABI APKs and its universal one were all 8.65-8.75 MB. So 0.0.1 was the last
release split by ABI.

Its versionCode is `base × 10`, where `base` is `baseVersionCode` in
`app/build.gradle.kts`. Raise `baseVersionCode` by one for each release. 0.0.1's
per-ABI APKs used `base × 10 + abiCode`, codes 10 (universal) to 14 (x86_64);
from base 2 on, every code (20, 30, ...) is above all of them, so a phone with
any 0.0.1 APK installs the next release as an update.

`scripts/verify-release-apks.sh` (run by the release job) checks that there is
exactly one APK, named after the versionName inside it, which must be digits
and dots (the README's Obtainium filter expects that); that it ships `.so` files
and all four ABIs (armeabi-v7a, arm64-v8a, x86, x86_64) carry the same libraries
(an APK with no native code fails, so losing them is noticed); that the
versionCode is `base × 10` and, for any version after 0.0.1, above 14; and,
when signed, a v2 and a v3 signature.

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
`./gradlew assembleRelease` builds an unsigned APK.

## Releasing

1. Raise `baseVersionCode` and `versionName` in `app/build.gradle.kts`.
2. Add the user-facing notes: a short summary (under 500 characters, the
   fastlane convention) at
   `fastlane/metadata/android/en-US/changelogs/<baseVersionCode>.txt`, and,
   for a longer GitHub Release text, `docs/release-notes/v<versionName>.md`.
   (F-Droid looks changelogs up by the APK's own versionCode, `base × 10`,
   so an F-Droid listing would need its own files.)
3. Push a tag matching `versionName`, such as `v0.0.2`. The release job builds,
   signs and verifies the APK; once the instrumented tests have also passed,
   the publish job attaches it to a GitHub Release. Its text
   is `docs/release-notes/<tag>.md` if it exists, otherwise the changelog (or a
   one-line stand-in if that is missing too), plus GitHub's generated commit
   list.
