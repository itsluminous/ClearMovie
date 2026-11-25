#!/usr/bin/env bash
# Runs one shard of the instrumented tests on the booted emulator.
#
#   scripts/ci-instrumented-shard.sh <shard> <shard-count>    (shard is 1-based)
#
# CI's instrumented-shard job runs it, once per API level and shard. The
# test runner splits the tests itself (AndroidJUnitRunner's numShards and
# shardIndex: a test runs in shard abs(hash) % count), so every test lands in
# exactly one shard, a new test needs no list, and nothing is filtered out.
#
# Shard 1 first lists every test without running any (the runner's log-only
# mode) into app/build/outputs/androidTest-list/. The instrumented job of each
# API level checks that the shards together ran exactly that list
# (scripts/check-instrumented-shards.py), so a test no shard picked up fails CI.
set -euo pipefail

shard="${1:-}"
count="${2:-}"
for value in "$shard" "$count"; do
  case "$value" in
    '' | *[!0-9]*)
      echo "::error::Usage: $0 <shard> <shard-count>, both whole numbers, the count at least 2."
      exit 2
      ;;
  esac
done
if [ "$count" -lt 2 ] || [ "$shard" -lt 1 ] || [ "$shard" -gt "$count" ]; then
  # At least 2 shards: scripts/check-instrumented-shards.py says why.
  echo "::error::Shard $shard of $count: the count must be at least 2 and the shard 1..count."
  exit 2
fi

# The app heap the booted emulator gives (scripts/ci-emulator-heap.sh sets it
# for API 29), so a log shows whether that took.
echo "App heap: dalvik.vm.heapsize=$(adb shell getprop dalvik.vm.heapsize | tr -d '\r')" \
  "dalvik.vm.heapgrowthlimit=$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')"

results=app/build/outputs/androidTest-results
args=-Pandroid.testInstrumentationRunnerArguments

if [ "$shard" -eq 1 ]; then
  echo "Listing every instrumented test (log-only, nothing runs)."
  ./gradlew connectedDebugAndroidTest "$args.log=true"
  rm -rf app/build/outputs/androidTest-list
  mkdir -p app/build/outputs/androidTest-list
  cp -R "$results/connected" app/build/outputs/androidTest-list/
  # The real run below writes its own results; none of the listing may remain.
  rm -rf "$results" app/build/reports/androidTests
fi

echo "Running shard $shard of $count."
./gradlew connectedDebugAndroidTest "$args.numShards=$count" "$args.shardIndex=$((shard - 1))"
