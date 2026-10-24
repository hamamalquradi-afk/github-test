#!/usr/bin/env python3
"""Apply an exact, fail-closed HeliBoard voice overlay to the pinned upstream source.

No modifications are made to keyboard layouts or user's imported custom layouts.
"""
from pathlib import Path
import shutil
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 apply_overlay.py <upstream-heliboard-dir>")

overlay = Path(__file__).resolve().parent
root = Path(sys.argv[1]).resolve()
src = root / "app/src/main/java/helium314/keyboard/latin"
manifest = root / "app/src/main/AndroidManifest.xml"
latin = src / "LatinIME.java"

def replace_once(original, old, new, label):
    count = original.count(old)
    if count != 1:
        raise RuntimeError(f"Unsafe source drift: {label}: expected 1 match, got {count}")
    return original.replace(old, new, 1)

s = latin.read_text(encoding="utf-8")
s = replace_once(s,
    "    private final StatsUtilsManager mStatsUtilsManager;",
    "    private final StatsUtilsManager mStatsUtilsManager;\n"
    "    private VoiceInputController mInlineVoiceInput;",
    "voice field")
s = replace_once(s,
    "    public void onDestroy() {\n        mClipboardHistoryManager.onDestroy();",
    "    public void onDestroy() {\n"
    "        if (mInlineVoiceInput != null) mInlineVoiceInput.destroy();\n"
    "        mClipboardHistoryManager.onDestroy();",
    "destroy lifecycle")
s = replace_once(s,
    "    void onFinishInputViewInternal(final boolean finishingInput) {\n"
    "        super.onFinishInputView(finishingInput);",
    "    void onFinishInputViewInternal(final boolean finishingInput) {\n"
    "        if (mInlineVoiceInput != null) mInlineVoiceInput.stop();\n"
    "        super.onFinishInputView(finishingInput);",
    "input view lifecycle")
s = replace_once(s,
    "        if (KeyCode.VOICE_INPUT == event.getKeyCode()) {\n"
    "            mRichImm.switchToShortcutIme(this);\n"
    "        }",
    "        if (KeyCode.VOICE_INPUT == event.getKeyCode()) {\n"
    "            if (mInlineVoiceInput == null) mInlineVoiceInput = new VoiceInputController(this);\n"
    "            mInlineVoiceInput.toggle(mRichImm == null ? null : mRichImm.getCurrentSubtypeLocale());\n"
    "            return; // never send VOICE_INPUT to the normal key processing path\n"
    "        }",
    "voice key handler")
latin.write_text(s, encoding="utf-8")

m = manifest.read_text(encoding="utf-8")
m = replace_once(m,
    '    <uses-permission android:name="android.permission.READ_CONTACTS" />',
    '    <uses-permission android:name="android.permission.READ_CONTACTS" />\n'
    '    <uses-permission android:name="android.permission.RECORD_AUDIO" />',
    "microphone permission")
m = replace_once(m,
    "        <!-- Activities -->",
    '        <!-- Activities -->\n'
    '        <activity android:name="helium314.keyboard.latin.VoicePermissionActivity"\n'
    '            android:theme="@android:style/Theme.Translucent.NoTitleBar"\n'
    '            android:excludeFromRecents="true"\n'
    '            android:exported="false" />',
    "permission activity")
m = replace_once(m,
    "    <queries>\n",
    '    <queries>\n'
    '        <intent><action android:name="android.speech.RecognitionService" /></intent>\n',
    "speech recognizer visibility")
manifest.write_text(m, encoding="utf-8")

for name in ("VoiceInputController.java", "VoicePermissionActivity.java"):
    shutil.copy2(overlay / name, src / name)

print("Patched pinned HeliBoard source in", root)
print("Input layout resources unchanged; voice mic now remains in LatinIME.")
