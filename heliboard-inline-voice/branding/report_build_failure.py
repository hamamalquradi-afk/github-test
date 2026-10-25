#!/usr/bin/env python3
"""Expose bounded Gradle diagnostics when Actions log access is unavailable."""
from pathlib import Path
import re
import sys
lines = Path(sys.argv[1]).read_text(errors='replace').splitlines()
selected = []
for index, line in enumerate(lines):
    if any(token in line for token in ('* What went wrong:', ' error:', 'e: ', 'AAPT:', 'FAILURE:')):
        selected.extend(lines[index:index + (9 if '* What went wrong:' in line else 1)])
if not selected:
    selected = lines[-12:]
for line in list(dict.fromkeys(selected))[:18]:
    # Avoid exposing URL user-info if a dependency resolver prints an authenticated URL.
    line = re.sub(r'(https?://)[^/\s@]+@', r'\1[redacted]@', line)
    line = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error title=Gradle build failure::' + line[:1800])
