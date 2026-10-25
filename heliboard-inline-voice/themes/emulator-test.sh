#!/usr/bin/env bash
set -euo pipefail
package=helium314.keyboard.compactdebug
runner="$package/helium314.keyboard.latin.themes.ThemeSmokeInstrumentation"
out=heliboard-inline-voice/test-results/gallery
mkdir -p "$out"
run_mode() {
  local mode="$1"
  adb shell am instrument -w -r -e mode "$mode" "$runner" | tee "$out/instrumentation-$mode.txt"
  adb pull "/sdcard/Android/data/$package/files/gallery-smoke/." "$out/" || true
  if ! grep -Fq 'THEME_SMOKE_PASS' "$out/instrumentation-$mode.txt"; then
    adb logcat -d -t 1800 > "$out/failure-logcat-$mode.txt" || true
    exit 1
  fi
}
run_mode full
adb shell am force-stop "$package"
run_mode persist
adb shell am force-stop "$package"
adb shell cmd locale set-app-locales "$package" --user 0 --locales ar
run_mode rtl
adb shell cmd locale set-app-locales "$package" --user 0 --locales en
echo 'PASS: full gallery, process persistence, real typing/rotation and Arabic RTL smoke tests'
