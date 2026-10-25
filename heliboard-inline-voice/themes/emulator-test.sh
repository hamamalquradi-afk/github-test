#!/usr/bin/env bash
set -euo pipefail
package="${1:-helium314.keyboard.compactdebug}"
runner="$package/helium314.keyboard.latin.themes.ThemeSmokeInstrumentation"
out="heliboard-inline-voice/test-results/gallery/$package"
mkdir -p "$out"
adb shell settings put secure show_ime_with_hard_keyboard 1
run_mode() {
  local mode="$1"
  python3 heliboard-inline-voice/themes/prepare-emulator-fixture.py "$package"
  # Force-stop changes Android's selected IME. Re-select it before opening settings,
  # otherwise the welcome wizard correctly covers the gallery during this test.
  adb shell ime enable "$package/helium314.keyboard.latin.LatinIME"
  adb shell ime set "$package/helium314.keyboard.latin.LatinIME"
  adb shell am instrument -w -r -e mode "$mode" "$runner" | tee "$out/instrumentation-$mode.txt"
  adb pull "/sdcard/Android/data/$package/files/gallery-smoke/." "$out/" || true
  if ! grep -Fq 'THEME_SMOKE_PASS' "$out/instrumentation-$mode.txt"; then
    python3 - "$out/instrumentation-$mode.txt" <<'PYCODE'
import pathlib, sys
lines = pathlib.Path(sys.argv[1]).read_text(errors='replace').splitlines()
start = next((i for i, line in enumerate(lines) if 'stream=' in line or 'Exception' in line or 'AssertionError' in line), 0)
for line in lines[start:start + 18]:
    line = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error title=Keyboard emulator failure::' + line[:1800])
PYCODE
    adb logcat -d -t 1800 > "$out/failure-logcat-$mode.txt" || true
    adb exec-out run-as "$package" cat "/data/user_de/0/$package/shared_prefs/${package}_preferences.xml" > "$out/failure-preferences-$mode.xml" || true
    exit 1
  fi
}
if [ "${2:-full}" = "remaining" ]; then
  # The immutable source APK already passed the full catalog/image/default matrix.
  # This mode finishes live typing and seeds the next restart check without repeating it.
  run_mode typing
else
  run_mode full
fi
adb shell am force-stop "$package"
run_mode persist
adb shell am force-stop "$package"
adb shell cmd locale set-app-locales "$package" --user 0 --locales ar
run_mode rtl
adb shell cmd locale set-app-locales "$package" --user 0 --locales en
echo 'PASS: full gallery, process persistence, real typing/rotation and Arabic RTL smoke tests'
