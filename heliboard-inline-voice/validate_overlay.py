#!/usr/bin/env python3
"""Fail CI early when compact debug resources/layouts silently regress."""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("Usage: validate_overlay.py <patched-source>")
root = Path(sys.argv[1])
overlay = Path(__file__).resolve().parent
app = root / "app"

for name, authority in (
    ("clip_provider.xml", "helium314.keyboard.compactdebug.clipprovider"),
    ("gesture_data.xml", "helium314.keyboard.compactdebug.provider"),
):
    value = ET.parse(app / "src/debug/res/values" / name).getroot()
    entries = [node.text for node in value.findall("string")
               if node.get("name") == ("clipboard_provider_authority" if name.startswith("clip") else "gesture_data_provider_authority")]
    assert entries == [authority], (name, entries)
    assert ".debug." not in authority

gradle = (app / "build.gradle.kts").read_text()
assert gradle.count('applicationIdSuffix = ".compactdebug"') == 1

manifest = ET.parse(app / "src/main/AndroidManifest.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
# Upstream WelcomeWizard uses applicationInfo.labelRes as a Compose stringResource ID.
# Literal android:label resets labelRes to 0 and causes a deterministic startup crash.
compact_name = ET.parse(app / "src/debug/res/values/compact_ime_name.xml").getroot()
assert any(n.get("name") == "compact_ime_name" and n.text == "HeliBoard Compact"
           for n in compact_name.findall("string"))
app_label = manifest.find("application").get(android + "label")
assert app_label == "@string/compact_ime_name", app_label
assert any(node.get(android + "name") == "android.permission.RECORD_AUDIO"
           for node in manifest.findall("uses-permission"))
assert any(node.get(android + "name") == "helium314.keyboard.latin.VoicePermissionActivity"
           for node in manifest.iter("activity"))
assert len([node for node in manifest.iter("service")
            if node.get(android + "name") == "LatinIME"]) == 1

layouts = {}
for name, path in (
    ("arabic", app / "src/main/assets/layouts/main/arabic.txt"),
    ("qwerty", app / "src/main/assets/layouts/main/qwerty.txt"),
    ("functional", app / "src/main/assets/layouts/functional/functional_keys.json"),
):
    layouts[name] = json.loads(path.read_text(encoding="utf-8"))
assert [len(x) for x in layouts["qwerty"]] == [16, 10, 9, 7]
assert [len(x) for x in layouts["arabic"]] == [16, 11, 11, 10]
for language in ("arabic", "qwerty"):
    symbols = [key["label"] for key in layouts[language][0]]
    assert symbols == list('()[]{}<>/=;" .-*\\'.replace(" ", "")), symbols
functional = layouts["functional"]
labels = [key.get("label") for row in functional for key in row]
for required in ("shift", "delete", "symbol_alpha", "Ctrl", "Alt", "Tab", "language_switch", "space", "action"):
    assert required in labels, required
assert not any(label in labels for label in ("arrow_left", "arrow_right"))

receiver = (app / "src/main/java/helium314/keyboard/latin/SystemBroadcastReceiver.java").read_text()
assert "Process.killProcess(myPid)" not in receiver
assert "this receiver can run while the setup" in receiver
latin = (app / "src/main/java/helium314/keyboard/latin/LatinIME.java").read_text()
assert "mInlineVoiceInput.toggle(" in latin
assert "mRichImm.switchToShortcutIme(this)" not in latin
voice = (overlay / "VoiceInputController.java").read_text()
# The original composing-preview implementation appended partials in some editors.
# Never rely on editor-owned composing spans to replace speech previews.
assert "RecognizerIntent.EXTRA_PARTIAL_RESULTS, true" in voice
assert "onPartialResults(Bundle partialResults)" in voice
assert ".setComposingText(" not in voice
assert "getTextBeforeCursor(previewText.length(), 0)" in voice
assert "previewText.contentEquals(before)" in voice
assert "deleteSurroundingText(previewText.length(), 0)" in voice
assert "connection.beginBatchEdit()" in voice and "connection.endBatchEdit()" in voice
assert "if (finalText.equals(previewText))" in voice
assert 'connection.commitText(" ", 1)' in voice
assert "clearPreviewTracking()" in voice
# Speech service can never hold a perpetually green state when it stops
# delivering callbacks: watchdog, bounded retries, and explicit state colors.
assert "READY_TIMEOUT_MS" in voice and "FINAL_RESULT_TIMEOUT_MS" in voice
assert "MAX_STALLED_SESSIONS" in voice and "MAX_ENGINE_ERRORS" in voice
assert "pendingWatchdog" in voice and "cancelWatchdog()" in voice
assert "ICON_RESTARTING" in voice and "ICON_LISTENING" in voice
assert "armWatchdog(session, READY_TIMEOUT_MS)" in voice
assert "scheduleRestart(NEXT_UTTERANCE_DELAY_MS)" in voice
assert "mainHandler.removeCallbacks(pendingRestart)" in voice
assert "ERROR_TOO_MANY_REQUESTS" in voice
print("PASS: layout/install identifiers; deduplicated speech previews; verified cursor replacement; voice watchdog/retry state guards")
