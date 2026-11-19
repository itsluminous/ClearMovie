#!/usr/bin/env bash
# Verifies the release APK that `./gradlew assembleRelease` produced.
#
#   scripts/verify-release-apks.sh [apk-dir]     (default app/build/outputs/apk/release)
#
# CI's release job runs it on every push and pull request. ClearMovie ships one
# APK for every processor type (docs/release-signing.md). It checks:
#   1. Exactly one APK, named ClearMovie-<versionName>.apk, where versionName is
#      read from the APK itself and is digits and dots only (the README's
#      Obtainium filter expects that), and no stale APKs from an earlier build.
#   2. Native code: the APK ships lib/<abi>/*.so, and the same libraries for
#      all four ABIs (armeabi-v7a, arm64-v8a, x86, x86_64), so no processor
#      type is left without them. An APK with no native code at all fails too:
#      AndroidX's libraries are expected, so losing them must be noticed and
#      this check changed on purpose.
#   3. versionCode is base*10. Any versionName other than 0.0.1 needs a code
#      above 14, the highest code 0.0.1's per-ABI APKs used, so every 0.0.1
#      install accepts it as an update.
#   4. With VERIFY_SIGNATURES=true, the APK passes `apksigner verify` with both
#      a v2 and a v3 signature.
# It needs the Android SDK build-tools (aapt2, apksigner) under $ANDROID_HOME.
set -euo pipefail

dir="${1:-app/build/outputs/apk/release}"
abis=(armeabi-v7a arm64-v8a x86 x86_64)
# The last per-ABI release: 0.0.1, versionCodes 10 (universal) to 14 (x86_64).
last_split_version="0.0.1"
last_split_max_code=14

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[ -d "$dir" ] || fail "no APK directory at $dir"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || fail "ANDROID_HOME is not set"
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
aapt2="$build_tools/aapt2"
apksigner="$build_tools/apksigner"
[ -x "$aapt2" ] || fail "aapt2 not found in $build_tools"
command -v zipinfo > /dev/null || fail "zipinfo (from unzip) is required"

# The .so entries of an APK. zipinfo failing must fail the check, not read as
# "no native code"; only grep's "nothing matched" is tolerated.
native_libs() {
  local entries
  entries="$(zipinfo -1 "$1")" || fail "zipinfo could not read $(basename "$1")"
  printf '%s\n' "$entries" | grep '^lib/.*\.so$' || {
    status=$?
    [ "$status" -eq 1 ] || fail "grep failed ($status) on $(basename "$1")"
  }
}

shopt -s nullglob
all=("$dir"/*.apk)
shopt -u nullglob

echo "APKs in $dir:"
for apk in ${all[@]+"${all[@]}"}; do
  printf '  %-45s %10s bytes\n' "$(basename "$apk")" "$(wc -c < "$apk" | tr -d ' ')"
done

# 1. Exactly one APK, from the last build, with the right name.
[ "${#all[@]}" -gt 0 ] || fail "expected exactly one APK (ClearMovie-<version>.apk), found none"
[ "${#all[@]}" -eq 1 ] || fail "expected exactly one APK (ClearMovie-<version>.apk), found ${#all[@]} (stale files from an earlier build? ./gradlew clean assembleRelease)"
apk="${all[0]}"
name="$(basename "$apk")"

# An APK left over from an earlier build must not pass for this one. AGP lists
# the outputs of the build in output-metadata.json.
metadata="$dir/output-metadata.json"
if [ -f "$metadata" ]; then
  built="$(sed -nE 's/.*"outputFile": *"([^"]+)".*/\1/p' "$metadata" | sort)"
  [ "$built" = "$name" ] || fail "$name does not match output-metadata.json (stale file from an earlier build?). Built: [$(echo $built)]"
fi

badging="$("$aapt2" dump badging "$apk")" || fail "aapt2 could not read $name"
version_name="$(printf '%s\n' "$badging" | sed -nE "s/^package:.* versionName='([^']*)'.*/\1/p" | head -n 1)"
version_code="$(printf '%s\n' "$badging" | sed -nE "s/^package:.* versionCode='([0-9]+)'.*/\1/p" | head -n 1)"
[ -n "$version_name" ] || fail "could not read the versionName of $name"
[ -n "$version_code" ] || fail "could not read the versionCode of $name"
[ "$name" = "ClearMovie-$version_name.apk" ] || fail "unexpected APK name $name (expected ClearMovie-$version_name.apk)"
# Only digits and dots: the README's Obtainium filter, ClearMovie-[0-9.]+...,
# would not see any other release.
[[ "$version_name" =~ ^[0-9]+(\.[0-9]+)*$ ]] || fail "versionName $version_name is not digits and dots (such as 0.0.2); the README's Obtainium filter would not match it"

# 2. Native code for every ABI.
libs="$(native_libs "$apk")" || exit 1
echo
echo "Native libraries in $name:"
if [ -n "$libs" ]; then echo "$libs" | sed 's/^/  /'; else echo "  (none)"; fi
[ -n "$libs" ] || fail "$name ships no native libraries; expected AndroidX's .so files for ${abis[*]}. If they are gone on purpose, change this check and docs/release-signing.md."
# The union of library file names, which every ABI must carry.
names="$(printf '%s\n' "$libs" | awk -F/ '{print $NF}' | sort -u)"
for abi in "${abis[@]}"; do
  have="$(printf '%s\n' "$libs" | awk -F/ -v abi="$abi" '$2 == abi {print $NF}' | sort -u)"
  [ "$have" = "$names" ] || fail "lib/$abi/ in $name has [$(echo $have)], expected [$(echo $names)]"
done
extra="$(printf '%s\n' "$libs" | cut -d/ -f2 | sort -u | grep -vxF -e armeabi-v7a -e arm64-v8a -e x86 -e x86_64 || true)"
[ -z "$extra" ] || echo "Note: other ABI folders too: $(echo $extra)"

# 3. versionCode.
echo
echo "versionName $version_name, versionCode $version_code"
[ $((version_code % 10)) -eq 0 ] || fail "versionCode $version_code is not baseVersionCode*10"
if [ "$version_name" != "$last_split_version" ] && [ "$version_code" -le "$last_split_max_code" ]; then
  fail "versionCode $version_code must be above $last_split_max_code (0.0.1's per-ABI APKs), or phones with 0.0.1 cannot update. Raise baseVersionCode."
fi

# 4. Signatures (CI sets VERIFY_SIGNATURES=true when the signing secrets exist).
if [ "${VERIFY_SIGNATURES:-false}" = "true" ]; then
  [ -x "$apksigner" ] || fail "apksigner not found in $build_tools"
  echo
  echo "==> apksigner verify $name"
  # --min-sdk-version 24: for minSdk 29 apksigner checks only the v3 block;
  # asking for an older floor makes it verify the v2 block as well.
  out="$("$apksigner" verify --verbose --print-certs --min-sdk-version 24 "$apk")" || fail "apksigner rejected $name"
  echo "$out" | grep -E 'Verified using v[23] scheme|Signer: certificate (DN|SHA-256)' || true
  echo "$out" | grep -qE 'Verified using v2 scheme .*: true' || fail "$name has no v2 signature"
  echo "$out" | grep -qE 'Verified using v3 scheme .*: true' || fail "$name has no v3 signature"
fi

echo
echo "OK: release APK verified."
