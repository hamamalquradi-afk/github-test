#!/usr/bin/env bash
# Verification-only host script; never copied into the Android application.
set -euo pipefail
mkdir -p heliboard-inline-voice/test-results
launch_log=heliboard-inline-voice/test-results/launch-commands.txt
if ! bash heliboard-inline-voice/smoke-test.sh verified/debug/HeliBoard_4.3-beta1-debug.apk > "$launch_log" 2>&1; then
  cat "$launch_log"
  python3 - "$launch_log" <<'PY'
from pathlib import Path
import sys
lines = Path(sys.argv[1]).read_text(errors='replace').splitlines()
for line in lines[-18:]:
    print('::error title=Launcher command failure::' + line.replace('%', '%25').replace('\r', '%0D'))
log = Path('heliboard-inline-voice/test-results/emulator-logcat.txt')
if log.exists():
    lines = log.read_text(errors='replace').splitlines()
    indices = [i for i, line in enumerate(lines) if 'FATAL EXCEPTION' in line or 'Process: helium314.keyboard.compactdebug' in line]
    start = max(0, indices[0]-2) if indices else max(0, len(lines)-18)
    for line in lines[start:start+24]:
        print('::error title=Launcher smoke failure::' + line.replace('%', '%25').replace('\r', '%0D'))
PY
  exit 1
fi
cat "$launch_log"
bash heliboard-inline-voice/themes/emulator-test.sh helium314.keyboard.compactdebug
adb install -r verified/themes/HeliBoard_4.3-beta1-themes.apk
adb shell pm path helium314.keyboard.compactdebug
adb shell pm path helium314.keyboard.compactthemesdebug
bash heliboard-inline-voice/themes/emulator-test.sh helium314.keyboard.compactthemesdebug
adb shell pm path helium314.keyboard.compactdebug
echo '::notice title=Android emulator verification::Both variants passed launch, all 80 themes, English/Arabic typing, persistence, RTL and install-alongside checks.'
