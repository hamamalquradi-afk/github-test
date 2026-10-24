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


# Screenshot-matched 16-symbol row, bilingual layouts, and compact bottom controls.
# Imported user layouts remain untouched: these only replace packaged built-ins.
assets = root / "app/src/main/assets/layouts"
for source, dest in (
    ("arabic.json", assets / "main/arabic.txt"),
    ("qwerty.json", assets / "main/qwerty.txt"),
    ("functional_keys.json", assets / "functional/functional_keys.json")
):
    shutil.copy2(overlay / "layouts" / source, dest)

# With a fixed top symbols row, numeric hints belong on the FIRST LETTER row.
# A narrow exact-match guard avoids accidentally changing other layouts.
parser = root / "app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt"
k = parser.read_text(encoding="utf-8")
k = replace_once(k,
    "        } else if (!params.mId.numberRowEnabled && params.mId.element.isAlphabet && !hasBuiltInNumbers()) {\n",
    "        } else if (!params.mId.numberRowEnabled && params.mId.element.isAlphabet && !hasBuiltInNumbers()) {\n"
    "            val popupHintRow = if (baseKeys.size >= 4 && baseKeys[0].size == 16 && baseKeys[0][0].label == \"(\") 1 else 0\n",
    "number-hint row picker")
k = replace_once(k, "            if (baseKeys[0].any { it.popup.main != null || !it.popup.relevant.isNullOrEmpty() }",
    "            if (baseKeys[popupHintRow].any { it.popup.main != null || !it.popup.relevant.isNullOrEmpty() }",
    "number-hint popup conflict")
k = replace_once(k,
    "            baseKeys.first().forEachIndexed { index, keyData -> keyData.popup.numberLabel = numberRow.getOrNull(index)?.label }",
    "            baseKeys[popupHintRow].forEachIndexed { index, keyData -> keyData.popup.numberLabel = numberRow.getOrNull(index)?.label }",
    "number-hint target")
parser.write_text(k, encoding="utf-8")

# Compact defaults affect only installations with no explicit size preference.
# Existing users can set keyboard height / bottom row height in Appearance.
defaults = root / "app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt"
d = defaults.read_text(encoding="utf-8")
d = replace_once(d,
    "    val PREF_KEYBOARD_HEIGHT_SCALE = Array(4) { DEFAULT_SIZE_SCALE }",
    "    val PREF_KEYBOARD_HEIGHT_SCALE = arrayOf(0.84f, 0.90f, 0.84f, 0.90f)",
    "default keyboard height")
d = replace_once(d,
    "    val PREF_BOTTOM_ROW_SCALE = Array(4) { DEFAULT_SIZE_SCALE }",
    "    val PREF_BOTTOM_ROW_SCALE = Array(4) { 0.72f }",
    "default bottom row height")
defaults.write_text(d, encoding="utf-8")

print("Patched pinned HeliBoard source in", root)
print("Updated built-in Arabic/English main layouts, coding row, compact functional row, and height defaults.")
print("Imported custom layout files and Android speech-recognition service remain unchanged.")
