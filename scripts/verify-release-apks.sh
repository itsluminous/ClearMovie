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
#   4. The internet is used only to download the voice pack
#      (cm-voice-pack-download):
#      a. The APK asks for exactly the permissions listed below; a new one,
#         from the app or a library, fails until it is added here on purpose.
#      b. Its network security config allows no plain HTTP, and trusts no
#         host but raw.githubusercontent.com (no trust anchors elsewhere, no
#         debug overrides).
#      c. Every call into the network in its code is in the voice pack
#         downloader (data/voice/PackDownloader.kt), or one of a known list
#         of library methods the app never reaches (Media3's HTTP and UDP
#         sources, JNA reading its own resources). A new one fails.
#      d. No native library in the APK, nor any voice pack engine (loaded
#         into the app's process, where the network security config does not
#         reach), imports socket, name lookup or TLS functions
#         (scripts/check-native-network.py).
#   5. With VERIFY_SIGNATURES=true, the APK passes `apksigner verify` with both
#      a v2 and a v3 signature.
# It needs the Android SDK build-tools (aapt2, dexdump, apksigner) under $ANDROID_HOME, and python3.
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
dexdump="$build_tools/dexdump"
[ -x "$aapt2" ] || fail "aapt2 not found in $build_tools"
[ -x "$dexdump" ] || fail "dexdump not found in $build_tools"
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

# 4. The internet, for the voice pack download only.
# 4a. Exactly these permissions (aapt2's lines, sorted).
expected_permissions="$(sort <<'EOF'
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE' maxSdkVersion='32'
uses-permission: name='android.permission.READ_MEDIA_IMAGES'
uses-permission: name='android.permission.READ_MEDIA_VIDEO'
uses-permission: name='android.permission.READ_MEDIA_VISUAL_USER_SELECTED'
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_DATA_SYNC'
uses-permission: name='android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
permission: com.itsluminous.ClearMovie.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.itsluminous.ClearMovie.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
EOF
)"
perms="$("$aapt2" dump permissions "$apk")" || fail "aapt2 could not read the permissions of $name"
perms="$(printf '%s\n' "$perms" | grep -v '^package: ' | sort)"
echo
echo "Permissions in $name:"
printf '%s\n' "$perms" | sed 's/^/  /'
if [ "$perms" != "$expected_permissions" ]; then
  echo "Differences (< expected, > in the APK):" >&2
  diff <(printf '%s\n' "$expected_permissions") <(printf '%s\n' "$perms") >&2 || true
  fail "$name asks for other permissions than expected; a new one must be added to this check on purpose"
fi

# 4b. The network security config the manifest points at, read from the APK.
manifest_tree="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")" || fail "aapt2 could not read the manifest of $name"
printf '%s\n' "$manifest_tree" | grep -q 'usesCleartextTraffic(0x010104ec)=false' || fail "$name does not set usesCleartextTraffic=false"
nsc_id="$(printf '%s\n' "$manifest_tree" | sed -nE 's/.*:networkSecurityConfig\(0x01010527\)=@(0x[0-9a-f]+).*/\1/p' | head -n 1)"
[ -n "$nsc_id" ] || fail "$name has INTERNET but no network security config"
resources="$("$aapt2" dump resources "$apk")" || fail "aapt2 could not read the resources of $name"
nsc_file="$(printf '%s\n' "$resources" | awk -v id="$nsc_id" '$1 == "resource" && $2 == id {found = 1; next} found && /\(file\)/ {print $3; exit}')"
[ -n "$nsc_file" ] || fail "could not find the file of resource $nsc_id in $name"
nsc="$("$aapt2" dump xmltree --file "$nsc_file" "$apk")" || fail "aapt2 could not read $nsc_file in $name"
echo
echo "Network security config ($nsc_file):"
printf '%s\n' "$nsc" | sed 's/^/  /'
# Its shape, without indentation and line numbers: exactly this, nothing more.
expected_nsc="$(cat <<'EOF'
E: network-security-config
E: base-config
A: cleartextTrafficPermitted=false
E: trust-anchors
E: domain-config
A: cleartextTrafficPermitted=false
E: domain
A: includeSubdomains=false
T: 'raw.githubusercontent.com'
E: trust-anchors
E: certificates
A: src="system" (Raw: "system")
EOF
)"
shape="$(printf '%s\n' "$nsc" | sed -E 's/^ +//; s/ \(line=[0-9]+\)$//')"
[ "$shape" = "$expected_nsc" ] || fail "the network security config in $name is not the expected one (no plain HTTP, only raw.githubusercontent.com trusted)"

# 4c. The code that calls the network, as "<class> <method> -> <call>", from every dex file.
# Run at the top level (not in a pipeline or $(...)), so a dexdump that fails fails the check.
dex_dir="$(mktemp -d)"
unzip -q -o "$apk" 'classes*.dex' -d "$dex_dir" || fail "could not unpack the dex files of $name"
shopt -s nullglob
dex_files=("$dex_dir"/classes*.dex)
shopt -u nullglob
[ "${#dex_files[@]}" -gt 0 ] || fail "$name has no dex files"
for dex in "${dex_files[@]}"; do
  "$dexdump" -d "$dex" > "$dex.txt" 2> /dev/null || fail "dexdump could not read $(basename "$dex") of $name"
  [ -s "$dex.txt" ] || fail "dexdump wrote nothing for $(basename "$dex") of $name"
done
network_re='(Ljava/net/URL;\.(openConnection|openStream|getContent)|Ljava/net/(Socket|DatagramSocket|MulticastSocket|ServerSocket);\.<init>|Ljava/net/InetAddress;\.(getByName|getAllByName|getLocalHost)|Ljavax/net/(ssl/SSL)?SocketFactory;\.createSocket|Ljava/nio/channels/(Asynchronous)?(Socket|Datagram|ServerSocket)Channel;\.open|Landroid/net/Network;\.(openConnection|getSocketFactory|bindSocket|getByName|getAllByName)|Landroid/media/MediaPlayer;\.setDataSource|Landroid/webkit/WebView;\.load|Landroid/app/DownloadManager;\.enqueue|Landroid/net/http/)'
awk -v re="$network_re" '
  /Class descriptor/ { cls = $NF; gsub(/\047/, "", cls) }
  /^      name          :/ { m = $NF; gsub(/\047/, "", m) }
  /invoke-/ && $0 ~ re { match($0, re); print cls " " m " -> " substr($0, RSTART, RLENGTH) }' "$dex_dir"/classes*.dex.txt | sort -u > "$dex_dir/calls.txt" ||
  fail "could not list the network calls of $name"
calls="$(cat "$dex_dir/calls.txt")"
rm -rf "$dex_dir"
expected_calls="$(sort <<'EOF'
Lcom/itsluminous/clearmovie/data/voice/PackDownloader; connect -> Ljava/net/URL;.openConnection
Landroidx/media3/datasource/DefaultHttpDataSource; makeConnection -> Ljava/net/URL;.openConnection
Landroidx/media3/datasource/UdpDataSource; open -> Ljava/net/DatagramSocket;.<init>
Landroidx/media3/datasource/UdpDataSource; open -> Ljava/net/InetAddress;.getByName
Landroidx/media3/datasource/UdpDataSource; open -> Ljava/net/MulticastSocket;.<init>
Lcom/sun/jna/Native; extractFromResourcePath -> Ljava/net/URL;.openStream
EOF
)"
echo
echo "Network calls in the code of $name:"
printf '%s\n' "$calls" | sed 's/^/  /'
if [ "$calls" != "$expected_calls" ]; then
  echo "Differences (< expected, > in the APK):" >&2
  diff <(printf '%s\n' "$expected_calls") <(printf '%s\n' "$calls") >&2 || true
  fail "$name has network calls other than the voice pack downloader's and the known library ones"
fi

# 4d. Native code: neither the APK's libraries nor the voice pack's engines (loaded into the app's
# process, where the network security config does not reach) may import socket, lookup or TLS functions.
command -v python3 > /dev/null || fail "python3 is required"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
engines=("$script_dir"/../voice-packs/v*/engine-*.zip)
[ -f "${engines[0]}" ] || fail "no voice pack engines under voice-packs/"
echo
python3 "$script_dir/check-native-network.py" "$apk" "${engines[@]}" || fail "native code that ClearMovie runs imports network functions"
echo "INTERNET only with the network security config, and only the voice pack downloader calls the network."

# 5. Signatures (CI sets VERIFY_SIGNATURES=true when the signing secrets exist).
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
