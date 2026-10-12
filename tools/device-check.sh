#!/usr/bin/env bash
# Runs the real-device check on a connected Android TV emulator/device and writes everything to
# $OUT (default device-results/): screenshots, raw instrumentation output, logcat, summary.
# Called from .github/workflows/device.yml inside reactivecircus/android-emulator-runner.
# Always exits 0; the verdict is in $OUT/status.txt (written by tools/device-report.cjs).
set -u
OUT="${OUT:-device-results}"
PKG=tv.ezano.app
RUNNER=tv.ezano.app.test/androidx.test.runner.AndroidJUnitRunner
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
SHOTS=/data/local/tmp/device-check
mkdir -p "$OUT/screens" "$OUT/raw" "$OUT/logcat" "$OUT/files"
: > "$OUT/steps.tsv"
log() { echo "[device-check $(date +%H:%M:%S)] $*" | tee -a "$OUT/host.log"; }

adb wait-for-device
adb logcat -G 16M >/dev/null 2>&1 || true
{
  echo "model: $(adb shell getprop ro.product.model)"
  echo "android: $(adb shell getprop ro.build.version.release) (API $(adb shell getprop ro.build.version.sdk))"
  echo "build: $(adb shell getprop ro.build.fingerprint)"
  echo "characteristics: $(adb shell getprop ro.build.characteristics)"
  echo "abilist: $(adb shell getprop ro.product.cpu.abilist)"
  echo "leanback feature: $(adb shell pm list features | grep -c android.software.leanback)"
  echo "screen: $(adb shell wm size | tr -d '\r' | tail -1), density $(adb shell wm density | tr -d '\r' | tail -1)"
  echo "low-ram: $(adb shell getprop ro.config.low_ram)"
  echo "heap (dalvik.vm.heapsize / largeHeap): $(adb shell getprop dalvik.vm.heapsize)"
  echo "memory: $(adb shell cat /proc/meminfo | head -1)"
} | tr -d '\r' > "$OUT/device.txt"
cat "$OUT/device.txt"

# Animations off (the runner also does this) and keep the screen awake.
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
adb shell svc power stayon true || true

log "installing APKs"
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1 || { log "app install failed"; cat "$OUT/install.txt"; }
adb install -r -g "$TEST_APK" >> "$OUT/install.txt" 2>&1 || { log "test APK install failed"; cat "$OUT/install.txt"; }
cat "$OUT/install.txt"
adb shell rm -rf "$SHOTS"; adb shell mkdir -p "$SHOTS"; adb shell chmod 777 "$SHOTS"

pull_files() {
  adb pull "$SHOTS/." "$OUT/screens/" >/dev/null 2>&1 || true
  mkdir -p "$OUT/files/$1"
  adb pull "/sdcard/Android/data/$PKG/files/." "$OUT/files/$1/" >/dev/null 2>&1 || true
  # Older tests write PNGs to the app's internal files dir (debuggable build, so run-as works).
  for f in live-tested.png movies-tested.png player-030.png detail-030.png home-030.png favorites-030.png; do
    adb exec-out run-as "$PKG" cat "files/$f" > "$OUT/files/$1/$f" 2>/dev/null || true
    [ -s "$OUT/files/$1/$f" ] || rm -f "$OUT/files/$1/$f"
  done
}

# run_step NAME clear|keep DESCRIPTION -e class ...
run_step() {
  local name="$1" mode="$2" desc="$3"; shift 3
  log "step $name: $desc"
  if [ "$mode" = clear ]; then adb shell pm clear "$PKG" >/dev/null; fi
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb logcat -c || true
  local start end code
  start=$(date +%s)
  timeout 1500 adb shell am instrument -r -w "$@" "$RUNNER" > "$OUT/raw/$name.txt" 2>&1
  code=$?
  end=$(date +%s)
  adb logcat -d -v threadtime > "$OUT/logcat/$name.txt" 2>/dev/null || true
  pull_files "$name"
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$desc" "$*" "$code" "$((end-start))" >> "$OUT/steps.tsv"
  log "step $name finished in $((end-start)) s (exit $code): $(grep -E '^(OK|FAILURES|INSTRUMENTATION_RESULT: shortMsg)' "$OUT/raw/$name.txt" | head -2 | tr '\n' ' ')"
}

C=tv.ezano.app
run_step 01-fresh       clear "Fresh install, remote reaches Add a playlist"  -e class $C.FreshInstallTest
run_step 02-e2e         clear "Add playlist via UI, browse, play, captions"    -e class $C.EndToEndTest
run_step 03-seed        keep  "Ensure fixture library (no-op after e2e)"       -e class $C.ReopenTest#seedFixture
run_step 04-cold1       keep  "Cold start 1 (new process)"                     -e class $C.ReopenTest#coldStart -e label fixture -e run 1
run_step 05-cold2       keep  "Cold start 2 (new process)"                     -e class $C.ReopenTest#coldStart -e label fixture -e run 2
run_step 06-cold3       keep  "Cold start 3 (new process)"                     -e class $C.ReopenTest#coldStart -e label fixture -e run 3 -e shot 14-reopen
run_step 07-warm        keep  "Warm starts x3 (HOME, relaunch)"                -e class $C.ReopenTest#warmStarts

# Launcher-path cold starts measured purely from the host (am start -W TotalTime = first frame).
log "host cold starts"
for i in 1 2 3; do
  adb shell am force-stop "$PKG"; sleep 2
  out=$(adb shell am start -W -n "$PKG/.MainActivity" | tr -d '\r')
  echo "$out" > "$OUT/raw/host-cold-$i.txt"
  echo "METRIC host_cold${i}_first_frame_ms=$(echo "$out" | sed -n 's/^TotalTime: //p')" >> "$OUT/host-metrics.txt"
  sleep 8
  if [ "$i" = 3 ]; then adb shell screencap -p "$SHOTS/14c-host-cold-8s.png"; fi
done
adb shell am force-stop "$PKG"
pull_files host

run_step 08-large-seed  clear "Import large fixture (76,000 titles, 64,000 programs)" -e class $C.ReopenTest#seedLarge
run_step 09-large-cold  keep  "Cold start with large library"               -e class $C.ReopenTest#coldStart -e label large -e run 1 -e shot 15-large-reopen
run_step 10-device      clear "DeviceTest (import, playback, recording, D-pad)" -e class $C.DeviceTest
run_step 11-controls    clear "PlayerControlsTest"                           -e class $C.PlayerControlsTest
run_step 12-rebrand     clear "RebrandDeviceTest"                            -e class $C.RebrandDeviceTest

log "building report"
node tools/device-report.cjs "$OUT" || log "report generation failed"
log "done: $(cat "$OUT/status.txt" 2>/dev/null)"
exit 0
