#!/usr/bin/env bash
# Run on a booted Android emulator by GitHub Actions. Fail on launcher/IME crash.
set -euo pipefail
apk="${1:?APK path is required}"
package="helium314.keyboard.compactdebug"
ime="${package}/helium314.keyboard.latin.LatinIME"
adb wait-for-device
adb shell input keyevent 82 >/dev/null || true
adb logcat -c
legacy_apk="${2:-}"
if [ -n "$legacy_apk" ] && [ -f "$legacy_apk" ]; then
  adb install -r "$legacy_apk"
  adb shell pm path helium314.keyboard.debug | grep -Fq helium314.keyboard.debug
  echo "Installed previous debug APK first to verify coexistence"
fi
adb install -r "$apk"
if [ -n "$legacy_apk" ] && [ -f "$legacy_apk" ]; then
  adb shell pm path helium314.keyboard.debug | grep -Fq helium314.keyboard.debug
  echo "PASS: previous HeliBoard debug installation survived alongside Compact"
fi
adb shell pm path "$package" | grep -Fq "$package"
echo "Installed $package without uninstalling an existing HeliBoard variant"

# Start the actual settings/welcome screen, not merely the background process.
adb shell am start -W -n "$package/helium314.keyboard.settings.SettingsActivity"
sleep 7
if ! adb shell pidof "$package" | grep -Eq '[0-9]'; then
  adb logcat -d -v time | tail -n 180
  echo "FAIL: settings / welcome activity is not alive"
  exit 1
fi
echo "PASS: app settings/welcome process survived startup"

# Register and activate the real IME service, then check for delayed failure.
adb shell ime enable "$ime"
adb shell ime set "$ime"
sleep 5
adb shell ime list -s | grep -Fq "$ime"
if ! adb shell pidof "$package" | grep -Eq '[0-9]'; then
  adb logcat -d -v time | tail -n 180
  echo "FAIL: IME process exited"
  exit 1
fi
log=$(mktemp)
adb logcat -d -v time > "$log"
# Android crash logs include this exact process line next to a fatal exception.
if grep -E "Process: ${package}, PID: " "$log"; then
  grep -B 15 -A 45 -E "Process: ${package}, PID: " "$log" || true
  echo "FAIL: compact APK crashed"
  exit 1
fi
echo "PASS: IME registered and selected; no observed Android fatal exception"
echo "NOTICE: automated test does not verify microphone transcription or human typing"
