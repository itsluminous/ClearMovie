#!/usr/bin/env bash
# Verifies the release APKs that `./gradlew assembleRelease` produced.
#
#   scripts/verify-release-apks.sh [apk-dir]     (default app/build/outputs/apk/release)
#
# CI's release job runs it on every push and pull request, so the native-code
# guard always runs. It checks:
#   1. Native-code guard: if the universal APK ships any lib/<abi>/*.so, all
#      four per-ABI APKs must exist ("Native code found but ABI splits are
#      off"). If no .so ships at all, per-ABI APKs must NOT exist either, so
#      removing the splits stays a deliberate change (docs/release-signing.md).
#   2. Each per-ABI APK contains only its own lib/<abi>/ folder.
#   3. versionCodes are distinct and follow base*10 + abiCode
#      (0 universal, 1 armeabi-v7a, 2 arm64-v8a, 3 x86, 4 x86_64).
#   4. With VERIFY_SIGNATURES=true, every APK passes `apksigner verify` with
#      both a v2 and a v3 signature.
# It needs the Android SDK build-tools (aapt2, apksigner) under $ANDROID_HOME.
set -euo pipefail

dir="${1:-app/build/outputs/apk/release}"
abis=(armeabi-v7a arm64-v8a x86 x86_64) # in abiCode order 1..4

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
  printf '%s\n' "$entries" | grep '^lib/.*\.so$' || [ $? -eq 1 ]
}

shopt -s nullglob
universal=("$dir"/ClearMovie-*-universal.apk)
all=("$dir"/*.apk)
shopt -u nullglob

echo "APKs in $dir:"
for apk in ${all[@]+"${all[@]}"}; do
  printf '  %-45s %10s bytes\n' "$(basename "$apk")" "$(wc -c < "$apk" | tr -d ' ')"
done

[ "${#universal[@]}" -eq 1 ] || fail "expected exactly one ClearMovie-<version>-universal.apk, found ${#universal[@]}"
universal="${universal[0]}"
version="$(basename "$universal" | sed -E 's/^ClearMovie-(.+)-universal\.apk$/\1/')"

# Only the APKs of the LAST build count: an APK left over from an earlier build
# (say, per-ABI APKs from before splits were switched off) must not satisfy the
# guard. AGP lists the outputs of the build in output-metadata.json.
metadata="$dir/output-metadata.json"
if [ -f "$metadata" ]; then
  built="$(sed -nE 's/.*"outputFile": *"([^"]+)".*/\1/p' "$metadata" | sort)"
  present="$(for apk in ${all[@]+"${all[@]}"}; do basename "$apk"; done | sort)"
  [ "$built" = "$present" ] || fail "APKs in $dir do not match output-metadata.json (stale files from an earlier build?). Built: [$(echo $built)] present: [$(echo $present)]"
fi

# Every APK must be one of the five expected names; anything else means the
# naming in app/build.gradle.kts changed.
for apk in ${all[@]+"${all[@]}"}; do
  name="$(basename "$apk")"
  case "$name" in
    "ClearMovie-$version-universal.apk") ;;
    "ClearMovie-$version-armeabi-v7a.apk" | "ClearMovie-$version-arm64-v8a.apk" | \
      "ClearMovie-$version-x86.apk" | "ClearMovie-$version-x86_64.apk") ;;
    *) fail "unexpected APK name $name (expected ClearMovie-$version-<abi>.apk)" ;;
  esac
done

per_abi=()
for abi in "${abis[@]}"; do
  [ -f "$dir/ClearMovie-$version-$abi.apk" ] && per_abi+=("$abi")
done

libs="$(native_libs "$universal")"
echo
echo "Native libraries in the universal APK:"
if [ -n "$libs" ]; then echo "$libs" | sed 's/^/  /'; else echo "  (none)"; fi
echo "Per-ABI APKs: ${#per_abi[@]} (${per_abi[*]+${per_abi[*]}})"

# 1. Native-code guard.
if [ -n "$libs" ] && [ "${#per_abi[@]}" -lt "${#abis[@]}" ]; then
  fail "Native code found but ABI splits are off: the universal APK ships .so files, but only ${#per_abi[@]} of ${#abis[@]} per-ABI APKs exist. Re-enable splits.abi in app/build.gradle.kts."
fi
if [ -z "$libs" ] && [ "${#per_abi[@]}" -gt 0 ]; then
  fail "No native code ships any more, but ABI splits are still on. Remove splits.abi on purpose and update docs/release-signing.md."
fi

# 2. Each per-ABI APK holds only its own ABI's libraries.
for abi in ${per_abi[@]+"${per_abi[@]}"}; do
  apk="$dir/ClearMovie-$version-$abi.apk"
  folders="$(native_libs "$apk" | cut -d/ -f2 | sort -u)"
  [ "$folders" = "$abi" ] || fail "$(basename "$apk") contains lib/ folders [$(echo "$folders" | tr '\n' ' ')], expected only $abi"
done

# 3. versionCodes: base*10 + abiCode, all distinct.
version_code() { "$aapt2" dump badging "$1" | sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p" | head -n 1; }
universal_code="$(version_code "$universal")"
[ -n "$universal_code" ] || fail "could not read the versionCode of $(basename "$universal")"
[ $((universal_code % 10)) -eq 0 ] || fail "universal versionCode $universal_code is not base*10"
base=$((universal_code / 10))
echo
echo "versionCodes (base $base):"
echo "  universal    $universal_code"
code=1
for abi in "${abis[@]}"; do
  apk="$dir/ClearMovie-$version-$abi.apk"
  if [ -f "$apk" ]; then
    actual="$(version_code "$apk")"
    printf '  %-12s %s\n' "$abi" "$actual"
    [ "$actual" = "$((base * 10 + code))" ] || fail "$abi versionCode is $actual, expected $((base * 10 + code))"
  fi
  code=$((code + 1))
done

# 4. Signatures (CI sets VERIFY_SIGNATURES=true when the signing secrets exist).
if [ "${VERIFY_SIGNATURES:-false}" = "true" ]; then
  [ -x "$apksigner" ] || fail "apksigner not found in $build_tools"
  for apk in ${all[@]+"${all[@]}"}; do
    echo
    echo "==> apksigner verify $(basename "$apk")"
    # --min-sdk-version 24: for minSdk 29 apksigner checks only the v3 block;
    # asking for an older floor makes it verify the v2 block as well.
    out="$("$apksigner" verify --verbose --print-certs --min-sdk-version 24 "$apk")" || fail "apksigner rejected $(basename "$apk")"
    echo "$out" | grep -E 'Verified using v[23] scheme|Signer: certificate (DN|SHA-256)' || true
    echo "$out" | grep -qE 'Verified using v2 scheme .*: true' || fail "$(basename "$apk") has no v2 signature"
    echo "$out" | grep -qE 'Verified using v3 scheme .*: true' || fail "$(basename "$apk") has no v3 signature"
  done
fi

echo
echo "OK: release APKs verified."
