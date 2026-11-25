#!/usr/bin/env bash
# Sets the app heap of CI's Android 10 test emulator before it boots.
#
#   scripts/ci-emulator-heap.sh <api-level>
#
# CI's instrumented-shard job runs it as android-emulator-runner's
# pre-emulator-launch-script, after the AVD is made. The AVD the runner makes
# for API 29 (no device profile: a small 320x640 mdpi screen) lets an app's
# heap grow to only 16 MB. ExportFlowDeviceTest needs 18-20 MB while a Best
# export runs beside the editor (measured on API 36), so on API 29 it ran out
# of memory now and then and took the app down with it. For API 29 this sets
# the AVD's vm.heapSize to 64 MB. The runner's own heap-size input writes
# hw.heapSize, a key the emulator does not know (its hardware-properties.ini
# names it vm.heapSize), so it would change nothing. Other API levels keep
# the AVD's own heap. ci-instrumented-shard.sh logs the heap the booted
# emulator gives the app.
set -euo pipefail

api="${1:-}"
heap_mb=64
avd_dir="${ANDROID_AVD_HOME:-}/test.avd"
config="$avd_dir/config.ini"

if [ "$api" != 29 ]; then
  echo "API $api: AVD heap left as it is."
  exit 0
fi
[ -n "${ANDROID_AVD_HOME:-}" ] || { echo "::error::ANDROID_AVD_HOME is not set."; exit 1; }
[ -f "$config" ] || { echo "::error::No AVD config at $config."; exit 1; }

grep -v '^vm\.heapSize' "$config" > "$config.new" || true
echo "vm.heapSize=$heap_mb" >> "$config.new"
mv "$config.new" "$config"
echo "AVD heap setting for API $api: $(grep '^vm\.heapSize' "$config")"
