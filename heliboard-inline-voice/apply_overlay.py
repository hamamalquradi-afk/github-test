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

# Finishing/reloading the IME must clear both the effective mask and latched
# modifier presses. Otherwise the first Ctrl/Alt tap toggles a stale latch off.
listener = root / "app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt"
l = listener.read_text(encoding="utf-8")
l = replace_once(l,
    "    override fun resetMetaState() {\n        metaState = 0\n    }",
    "    override fun resetMetaState() {\n"
    "        metaState = 0\n"
    "        metaPressStates.forEach { key, _ ->\n"
    "            keyboardSwitcher.mainKeyboardView?.updateLockState(key, false)\n"
    "        }\n"
    "        metaPressStates.clear()\n"
    "    }",
    "clear modifier latches and graphics when input finishes")
listener.write_text(l, encoding="utf-8")

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


# The upstream broadcast receiver kills its entire process whenever the IME is
# not the *currently selected* keyboard.  A BOOT_COMPLETED broadcast may arrive
# while the Compact welcome screen is open, terminating that foreground Activity.
# Compact is intended to be tested side-by-side BEFORE selecting it as the IME.
# Never kill an entire foreground process from a broadcast; Android owns normal
# idle process reclamation. This change is scoped to the experimental overlay.
receiver = src / "SystemBroadcastReceiver.java"
receiver_source = receiver.read_text(encoding="utf-8")
receiver_source = replace_once(
    receiver_source,
    "        if (!isCurrentImeOfCurrentUser) {\n"
    "            final int myPid = Process.myPid();\n"
    '            Log.i(TAG, "Killing my process: pid=" + myPid);\n'
    "            Process.killProcess(myPid);\n"
    "        }",
    "        // Do not kill the process: this receiver can run while the setup\n"
    "        // Activity is foreground and before Compact has been selected as IME.\n"
    "        // The OS will reclaim the process when genuinely idle.",
    "broadcast receiver foreground process kill"
)
receiver.write_text(receiver_source, encoding="utf-8")

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


# The previous v1 APK used a per-run debug signing key.  Give the compact
# screenshot edition a separate application ID so it can be installed
# alongside the user's working v1 without uninstalling or losing preferences.
build = root / "app/build.gradle.kts"
g = build.read_text(encoding="utf-8")
# CI caches this explicit file, rather than assuming AGP's default key location.
# Local builds retain upstream defaults unless the environment selects a key.
g = replace_once(g, "    buildTypes {",
    '    signingConfigs.getByName("debug") {\n'
    '        System.getenv("KEYNOVA_DEBUG_KEYSTORE")?.let { storeFile = file(it); storeType = "pkcs12" }\n'
    '        System.getenv("KEYNOVA_SIGNING_PASSWORD")?.trim()?.let { storePassword = it; keyPassword = it }\n'
    '        System.getenv("KEYNOVA_SIGNING_KEY_ALIAS")?.trim()?.let { keyAlias = it }\n'
    '    }\n\n    buildTypes {', "explicit optional CI signing file")
g = replace_once(g,
    "            isMinifyEnabled = true\n"
    "            isJniDebuggable = false\n"
    "            applicationIdSuffix = \".debug\"\n",
    "            isMinifyEnabled = true\n"
    "            isJniDebuggable = false\n"
    "            applicationIdSuffix = \".compactdebug\"\n",
    "unique experimental application id")
build.write_text(g, encoding="utf-8")

# A new applicationIdSuffix alone is NOT sufficient for side-by-side installation.
# Upstream debug resources still name the .debug clipboard and gesture providers.
# Android globally reserves provider authorities, so they must be made unique too.
# Keep the overrides scoped to the debug variant: do not modify stable/release IDs.
for resource_name, old_authority, new_authority in (
    ("clip_provider.xml", "helium314.keyboard.debug.clipprovider",
     "helium314.keyboard.compactdebug.clipprovider"),
    ("gesture_data.xml", "helium314.keyboard.debug.provider",
     "helium314.keyboard.compactdebug.provider"),
):
    resource = root / "app/src/debug/res/values" / resource_name
    value = resource.read_text(encoding="utf-8")
    value = replace_once(value, old_authority, new_authority,
                         "unique " + resource_name + " provider authority")
    resource.write_text(value, encoding="utf-8")

# Clearly distinguish the compact test IME from an existing stable HeliBoard.
m = manifest.read_text(encoding="utf-8")
if m.count('android:label="@string/english_ime_name"') != 2:
    raise RuntimeError("Unexpected application/service label changes")
# Keep a STRING RESOURCE reference in the application manifest. The upstream
# WelcomeWizard reads applicationInfo.labelRes and passes it to stringResource().
# A literal label clears labelRes to zero and crashes on first launch with
# Resources$NotFoundException: String resource ID #0x0.
m = m.replace('android:label="@string/english_ime_name"',
              'android:label="@string/compact_ime_name"')
manifest.write_text(m, encoding="utf-8")
name_resource = root / "app/src/debug/res/values/compact_ime_name.xml"
name_resource.write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<resources><string name="compact_ime_name" translatable="false">HeliBoard Compact</string></resources>\n',
    encoding="utf-8"
)

# Compact uses narrower Shift/Delete keys. The parser inserts two non-clickable
# spacers in the German letter row; its test must count keys, not those spacers.
parser_test = root / "app/src/test/java/helium314/keyboard/KeyboardParserTest.kt"
test_source = parser_test.read_text(encoding="utf-8")
for row in ("keys", "keys2"):
    test_source = replace_once(test_source,
        f"        assertEquals(10, {row}[2].size)",
        f"        assertEquals(10, {row}[2].count {{ !it.isSpacer }})",
        "German key count excluding alignment spacers " + row)
parser_test.write_text(test_source, encoding="utf-8")
shutil.copy2(overlay / "VoiceInputControllerTest.java",
             root / "app/src/test/java/helium314/keyboard/latin/VoiceInputControllerTest.java")

print("Patched pinned HeliBoard source in", root)
print("Updated built-in Arabic/English main layouts, coding row, compact functional row, and height defaults.")
print("Imported custom layout files and Android speech-recognition service remain unchanged.")

# Gallery changes remain an exact, independently reviewable extension of this overlay.
import subprocess
subprocess.run([sys.executable, str(overlay / "themes/apply_themes.py"), str(root)], check=True)

# Phase 01 identity is applied last so both existing build variants retain branding.
subprocess.run([sys.executable, str(overlay / "branding/apply_branding.py"), str(root)], check=True)
