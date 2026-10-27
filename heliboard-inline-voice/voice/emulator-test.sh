#!/usr/bin/env bash
set -euo pipefail
out="heliboard-inline-voice/test-results/voice"
mkdir -p "$out"
for package in helium314.keyboard.compactdebug helium314.keyboard.compactthemesdebug; do
  adb shell am instrument -w -r "$package/helium314.keyboard.latin.VoiceSmokeInstrumentation" | tee "$out/$package.txt"
  if ! grep -Fq 'VOICE_SMOKE_PASS:' "$out/$package.txt"; then
    adb logcat -d -t 1200 > "$out/$package-failure-logcat.txt"
    echo "::error::Voice real-editor regression failed for $package; inspect uploaded evidence"
    exit 1
  fi
done
echo 'PASS: both minified APKs with real Android editor; physical microphone acceptance remains required'
