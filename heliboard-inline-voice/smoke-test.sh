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
mkdir -p heliboard-inline-voice/test-results
log="heliboard-inline-voice/test-results/emulator-logcat.txt"
# A fresh emulator emits thousands of unrelated boot log lines. On overloaded
# runners a full adb logcat dump can disconnect with exit 255 and falsely fail
# the smoke test. Use a bounded snapshot; process and IME checks still gate CI.
if ! adb logcat -d -t 1200 -v time > "$log"; then
  echo "WARN: bounded logcat snapshot failed; retrying with a smaller window"
  adb logcat -d -t 300 -v time > "$log" || true
fi
if grep -Fq "Process: ${package}, PID:" "$log"; then
  grep -B 20 -A 55 -F "Process: ${package}, PID:" "$log" || true
  echo "FAIL: Android logged a fatal crash for Compact during launcher startup"
  exit 1
fi
if ! adb shell pidof "$package" | grep -Eq '[0-9]'; then
  adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|helium314.keyboard" | tail -n 25 || true
  grep -E "AndroidRuntime|FATAL EXCEPTION|am_crash|Process: helium314|Fatal signal" "$log" | tail -n 100 || true
  echo "FAIL: settings / welcome activity is not alive (see uploaded logcat artifact)"
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
# A fresh emulator emits thousands of unrelated boot log lines. On overloaded
# runners a full adb logcat dump can disconnect with exit 255 and falsely fail
# the smoke test. Use a bounded snapshot; process and IME checks still gate CI.
if ! adb logcat -d -t 1200 -v time > "$log"; then
  echo "WARN: bounded logcat snapshot failed; retrying with a smaller window"
  adb logcat -d -t 300 -v time > "$log" || true
fi
# Android crash logs include this exact process line next to a fatal exception.
if grep -E "Process: ${package}, PID: " "$log"; then
  grep -B 15 -A 45 -E "Process: ${package}, PID: " "$log" || true
  echo "FAIL: compact APK crashed"
  exit 1
fi
echo "PASS: IME registered and selected; no observed Android fatal exception"
echo "NOTICE: automated test does not verify microphone transcription or human typing"
