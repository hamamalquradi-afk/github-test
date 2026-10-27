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
  local ime="$package/helium314.keyboard.latin.LatinIME"
  # The instrument command restarts the target APK. On a cold/slow emulator
  # Android may revert DEFAULT_INPUT_METHOD to Gboard while re-creating its
  # Settings task. Only retry that exact, independently reported setup race.
  # Never skip the instrumentation wizard assertion or change user settings.
  local attempt selected
  for attempt in 1 2; do
    adb shell ime enable "$ime"
    adb shell ime set "$ime"
    # A previous launcher smoke opens the welcome wizard before enabling IME.
    # Recreate only the UI task; retain app preferences and image files.
    adb shell am start -W -n "$package/helium314.keyboard.settings.SettingsActivity" -f 0x10008000
    # Cold startup can reset the selected IME AFTER the first 'ime set'.
    adb shell ime set "$ime"
    selected="$(adb shell settings get secure default_input_method | tr -d '\r')"
    if [ "$selected" != "$ime" ]; then
      echo "::error::Emulator selected IME mismatch before instrumentation: $selected (expected $ime)"
      exit 1
    fi
    adb shell am instrument -w -r -e mode "$mode" "$runner" | tee "$out/instrumentation-$mode.txt"
    if grep -Fq 'THEME_SMOKE_PASS' "$out/instrumentation-$mode.txt"; then
      break
    fi
    if [ "$attempt" -eq 1 ] && grep -Fq 'Welcome wizard requires setup; selectedIme=' "$out/instrumentation-$mode.txt"; then
      echo "WARN: emulator reverted to its default IME during instrumentation startup; reselecting once"
      continue
    fi
    break
  done
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
    # Print the Android exception as a workflow annotation, not only as a ZIP
    # artifact. Instrumentation startup crashes report no stream= exception.
    python3 - "$out/failure-logcat-$mode.txt" "$package" <<'PYCRASH'
import pathlib, sys
lines = pathlib.Path(sys.argv[1]).read_text(errors='replace').splitlines()
package = sys.argv[2]
starts = [i for i, line in enumerate(lines)
          if 'FATAL EXCEPTION:' in line and
          any(('Process: ' + package) in next_line for next_line in lines[i:i+5])]
if starts:
    for line in lines[starts[-1]:starts[-1]+24]:
        if 'AndroidRuntime:' not in line:
            continue
        message = line.split('AndroidRuntime:', 1)[-1].strip()
        if message:
            message = message.replace('%', '%25').replace('\\r', '%0D').replace('\\n', '%0A')
            print('::error title=Android instrumentation crash::' + message[:1700])
PYCRASH
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
