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
    "        if (mInlineVoiceInput != null) mInlineVoiceInput.onInputFinished();\n"
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
# Voice owns only buffered hypotheses and append-only phrase commits. Keep
# upstream recorrection from installing composing spans over finalized speech.
for old, new, label in (
    ("        updateSuggestionStripView(view);\n    }",
     "        updateSuggestionStripView(view);\n"
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.refreshView();\n    }", "reattach voice strip"),
    ("        SettingsValues settingsValues = mSettings.getCurrent();\n        Log.i(TAG, \"onConfigurationChanged\");",
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.suspendForRecreation();\n"
     "        SettingsValues settingsValues = mSettings.getCurrent();\n        Log.i(TAG, \"onConfigurationChanged\");", "voice rotation boundary"),
    ("        mHandler.onStartInput(editorInfo, restarting);",
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.onInputStarted(restarting);\n"
     "        mHandler.onStartInput(editorInfo, restarting);", "editor identity boundary"),
    ("        mStatsUtilsManager.onStartInputView();",
     "        mStatsUtilsManager.onStartInputView();\n"
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.onInputViewStarted();", "resume recreated input view"),
    ("        super.onFinishInput();",
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.onInputFinished();\n"
     "        super.onFinishInput();", "finish editor boundary"),
    ("        super.onWindowHidden();",
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.onInputFinished();\n"
     "        super.onWindowHidden();", "release hidden microphone"),
    ("                composingSpanStart, composingSpanEnd);\n        if (DebugFlags.DEBUG_ENABLED)",
     "                composingSpanStart, composingSpanEnd);\n"
     "        if (mInlineVoiceInput != null && mInlineVoiceInput.onUpdateSelection()) return;\n"
     "        if (DebugFlags.DEBUG_ENABLED)", "voice selection safety"),
    ("        InputTransaction completeInputTransaction =\n                mInputLogic.onCodeInput",
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.stop();\n"
     "        InputTransaction completeInputTransaction =\n                mInputLogic.onCodeInput", "manual key boundary"),
    ("        if (rawText == null) return;",
     "        if (rawText == null) return;\n"
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.stop();", "manual text boundary"),
    ("    public void onStartBatchInput() {",
     "    public void onStartBatchInput() {\n"
     "        if (mInlineVoiceInput != null) mInlineVoiceInput.stop();", "gesture boundary"),
    ("                case MSG_RESUME_SUGGESTIONS:\n",
     "                case MSG_RESUME_SUGGESTIONS:\n"
     "                    if (latinIme.mInlineVoiceInput != null && latinIme.mInlineVoiceInput.isEnabled()) break;\n", "suppress voice recorrection"),
):
    s = replace_once(s, old, new, label)
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

for name in ("VoiceInputController.java", "VoiceListeningIndicator.java", "VoicePermissionActivity.java",
             "VoiceDictationSession.java", "VoiceTextBuffer.java"):
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
for name in ("VoiceInputControllerTest.java", "VoiceDictationSessionTest.java", "VoiceTextBufferTest.java"):
    shutil.copy2(overlay / name, root / "app/src/test/java/helium314/keyboard/latin" / name)

print("Patched pinned HeliBoard source in", root)
print("Updated built-in Arabic/English main layouts, coding row, compact functional row, and height defaults.")
print("Imported custom layout files and Android speech-recognition service remain unchanged.")

# Keep additional candidate display strictly inside the suggestion strip.
# This is a version-pinned exact-match patch and does not affect keyboard keys.
import subprocess
subprocess.run([sys.executable, str(overlay / "keynova_suggestions.py"), str(root)], check=True)

# Gallery changes remain an exact, independently reviewable extension of this overlay.
subprocess.run([sys.executable, str(overlay / "themes/apply_themes.py"), str(root)], check=True)

# Phase 01 identity is applied last so both existing build variants retain branding.
subprocess.run([sys.executable, str(overlay / "branding/apply_branding.py"), str(root)], check=True)

# Voice regressions run inside each actual minified APK against Android's
# Editable/InputConnection, without replacing the device recognition service.
for variant in ("debug", "themes"):
    destination = root / f"app/src/{variant}/java/helium314/keyboard/latin/VoiceSmokeInstrumentation.java"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(overlay / "voice/debug/VoiceSmokeInstrumentation.java", destination)
    debug_manifest = root / f"app/src/{variant}/AndroidManifest.xml"
    value = debug_manifest.read_text()
    value = replace_once(value, "    <application>",
        '    <instrumentation android:name="helium314.keyboard.latin.VoiceSmokeInstrumentation"\n'
        '        android:targetPackage="${applicationId}" android:functionalTest="true" />\n'
        '    <application>', "voice instrumentation " + variant)
    debug_manifest.write_text(value)
with (root / "app/proguard-rules.pro").open("a") as rules:
    rules.write("\n-keep class helium314.keyboard.latin.VoiceSmokeInstrumentation { *; }\n")

# K02: preflight all settings files before replacing either preference store.
backup = root / "app/src/main/java/helium314/keyboard/settings/preferences/BackupRestorePreference.kt"
b = backup.read_text(encoding="utf-8")
b = replace_once(b, r'''private fun restoreLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val wait = CountDownLatch(1)''', r'''private fun restoreLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        var restoreSucceeded = false
        val wait = CountDownLatch(1)''', 'restore result scope')
b = replace_once(b, r'''            val oldPrefs = ctx.prefs().all.toMap()''', r'''            var preferencesChanged = false
            val settingsFiles = linkedMapOf<String, List<String>>()
            val oldPrefs = ctx.prefs().all.toMap()''', 'stage settings files')
b = replace_once(b, r'''                            } else if (entry.name == PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                val prefs = ctx.prefs()
                                prefs.edit { clear() }
                                anyMatch = true
                                readJsonLinesToSettings(prefLines, prefs)
                            } else if (entry.name == PROTECTED_PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                val protectedPrefs = ctx.protectedPrefs()
                                protectedPrefs.edit { clear() }
                                readJsonLinesToSettings(prefLines, protectedPrefs)
                                anyMatch = true
                            }
''', r'''                            } else if (entry.name == PREFS_FILE_NAME || entry.name == PROTECTED_PREFS_FILE_NAME) {
                                require(!settingsFiles.containsKey(entry.name)) { "Duplicate settings backup file" }
                                settingsFiles[entry.name] = String(zip.readBytes(), Charsets.UTF_8).split("\n")
                                anyMatch = true
                            }
''', 'defer both preference files')
b = replace_once(b, r'''                Database.copyFromDb(restoredDb, ctx)''', r'''                restoreSettingsFiles(settingsFiles, ctx.prefs(), ctx.protectedPrefs()) {
                    preferencesChanged = true
                }
                Database.copyFromDb(restoredDb, ctx)''', 'validate before applying')
b = replace_once(b, r'''                Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()''', r'''                Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
                restoreSucceeded = true''', 'restore success')
b = replace_once(b, r'''                ctx.prefs().edit {''', r'''                if (preferencesChanged) {
                ctx.prefs().edit {''', 'guard preference rollback')
b = replace_once(b, r'''                onError("r" + t.message)''', r'''                }
                onError("r" + t.message)''', 'end preference rollback guard')
b = replace_once(b, r'''        checkVersionUpgrade(ctx)''', r'''        if (!restoreSucceeded) {
            Settings.getInstance().startListener()
            return@filePicker
        }
        checkVersionUpgrade(ctx)''', 'skip postprocessing on rejection')
b = replace_once(b, r'''private fun readJsonLinesToSettings(list: List<String>, prefs: SharedPreferences): Boolean {
    val i = list.iterator()
    val e = prefs.edit()
    try {
        while (i.hasNext()) {
            when (i.next()) {
                "boolean settings" -> Json.decodeFromString<Map<String, Boolean>>(i.next()).forEach { e.putBoolean(it.key, it.value) }
                "int settings" -> Json.decodeFromString<Map<String, Int>>(i.next()).forEach { e.putInt(it.key, it.value) }
                "long settings" -> Json.decodeFromString<Map<String, Long>>(i.next()).forEach { e.putLong(it.key, it.value) }
                "float settings" -> Json.decodeFromString<Map<String, Float>>(i.next()).forEach { e.putFloat(it.key, it.value) }
                "string settings" -> Json.decodeFromString<Map<String, String>>(i.next()).forEach { e.putString(it.key, it.value) }
                "string set settings" -> Json.decodeFromString<Map<String, Set<String>>>(i.next()).forEach { e.putStringSet(it.key, it.value) }
            }
        }
        e.apply()
        return true
    } catch (e: Exception) {
        return false
    }
}

''', r'''// Validate every settings file before acquiring an editor or clearing either store.
internal fun restoreSettingsFiles(
    files: Map<String, List<String>>,
    prefs: SharedPreferences,
    protectedPrefs: SharedPreferences,
    beforeApply: () -> Unit = {},
) {
    val parsed = files.mapValues { (name, lines) ->
        require(name == PREFS_FILE_NAME || name == PROTECTED_PREFS_FILE_NAME) {
            "Invalid settings backup: unknown file"
        }
        try {
            parseSettingsLines(lines)
        } catch (_: Exception) {
            // Never include parser messages, keys or values in diagnostics.
            throw IllegalArgumentException("Invalid settings backup: $name")
        }
    }
    if (parsed.isEmpty()) return // File-only backups are still supported.
    beforeApply()
    parsed.forEach { (name, values) ->
        val target = if (name == PREFS_FILE_NAME) prefs else protectedPrefs
        target.edit {
            clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }
}

private fun parseSettingsLines(lines: List<String>): Map<String, Any> {
    val sections = setOf("boolean settings", "int settings", "long settings",
        "float settings", "string settings", "string set settings")
    val seen = mutableSetOf<String>()
    val values = linkedMapOf<String, Any>()
    // The writer emits exactly six header/object pairs, without a trailing newline.
    val input = lines.dropLastWhile { it.isBlank() }
    require(input.size == sections.size * 2)
    for (pair in input.chunked(2)) {
        val section = pair[0]
        require(section in sections && seen.add(section))
        val obj = Json.parseToJsonElement(pair[1]) as? kotlinx.serialization.json.JsonObject
            ?: error("Expected settings object")
        obj.forEach { (key, element) ->
            require(!values.containsKey(key))
            val primitive = element as? kotlinx.serialization.json.JsonPrimitive
            val value: Any = when (section) {
                "string set settings" -> {
                    val array = element as? kotlinx.serialization.json.JsonArray
                        ?: error("Expected string set")
                    array.map {
                        val item = it as? kotlinx.serialization.json.JsonPrimitive
                            ?: error("Expected string")
                        require(item.isString)
                        item.content
                    }.toSet()
                }
                "string settings" -> {
                    require(primitive != null && primitive.isString)
                    primitive.content
                }
                else -> {
                    require(primitive != null && !primitive.isString)
                    val raw = primitive.content
                    when (section) {
                        "boolean settings" -> raw.toBooleanStrict()
                        "int settings" -> raw.toInt()
                        "long settings" -> raw.toLong()
                        "float settings" -> raw.toFloat().also { require(it.isFinite()) }
                        else -> error("Unknown settings section")
                    }
                }
            }
            values[key] = value
        }
    }
    require(seen == sections)
    return values
}

''', 'strict side-effect-free parser')

rollback_start = b.index("                if (preferencesChanged) {\n") + len("                if (preferencesChanged) {\n")
rollback_end = b.index('                }\n                onError("r" + t.message)', rollback_start)
rollback = "".join(
    "    " + line if line.strip() else line
    for line in b[rollback_start:rollback_end].splitlines(keepends=True)
)
b = b[:rollback_start] + rollback + b[rollback_end:]
backup.write_text(b, encoding="utf-8")

# K01: disk snapshot preflight before moving/deleting original files.
b = replace_once(b, r'''            filesDir.renameTo(filesDir2)
            deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir2)
            try {
                var anyMatch = false
                ctx.getActivity()?.contentResolver?.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->''', r'''            var filesChanged = false
            try {
                val resolver = ctx.getActivity()?.contentResolver
                    ?: error("Backup source unavailable")
                resolver.openInputStream(uri)?.use { input ->
                withValidatedRestoreArchive(input, ctx.cacheDir, filesDir, deviceProtectedFilesDir) { snapshot ->
                filesChanged = true
                filesDir.renameTo(filesDir2)
                deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir2)
                var anyMatch = false
                    ZipInputStream(snapshot.inputStream().buffered()).use { zip ->''', 'K01 preflight before original data moves')
b = replace_once(b, r'''                            if (entry.name.startsWith("unprotected${File.separator}")) {''', r'''                            if (entry.isDirectory) {
                                // Directories alone are not restorable data.
                            } else if (entry.name.startsWith("unprotected${File.separator}")) {''', 'K01 ignore directory entries')
b = replace_once(b, r'''                                    if (!restoreEntryToDir(zip, deviceProtectedFilesDir, adjustedName)) {
                                        Log.w("AdvancedScreen", "skipping unsafe backup entry $adjustedName")
                                    }
                                }
                                anyMatch = true''', r'''                                    anyMatch = restoreEntryToDir(zip, deviceProtectedFilesDir, adjustedName) || anyMatch
                                }''', 'K01 only count restored protected files')
b = replace_once(b, r'''                }
                if (!anyMatch)''', r'''                if (!anyMatch)''', 'K01 keep validated snapshot scope')
b = replace_once(b, r'''                restoreSucceeded = true
            } catch''', r'''                restoreSucceeded = true
                }
                } ?: error("Backup source unavailable")
            } catch''', 'K01 close snapshot before rollback')
b = replace_once(b, r'''                filesDir.deleteRecursively()
                filesDir2.renameTo(filesDir)
                deviceProtectedFilesDir.deleteRecursively()
                deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir)''', r'''                if (filesChanged) {
                    filesDir.deleteRecursively()
                    filesDir2.renameTo(filesDir)
                    deviceProtectedFilesDir.deleteRecursively()
                    deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir)
                }''', 'K01 do not roll back untouched original data')
b = replace_once(b, r'''    val file = File(baseDir, entryName)
    val canonicalBase = baseDir.canonicalFile
    val canonicalTarget = file.canonicalFile
    if (canonicalTarget.path != canonicalBase.path
        && !canonicalTarget.path.startsWith(canonicalBase.path + File.separator)
    ) return false''', r'''    if (!isSafeRestorePath(baseDir, entryName)) return false
    val file = File(baseDir, entryName)''', 'K01 share canonical path guard')
b += r'''
// Snapshot the URI once on disk and validate before allowing any user-data mutation.
internal fun withValidatedRestoreArchive(
    input: java.io.InputStream,
    cacheDir: File,
    filesDir: File,
    protectedFilesDir: File,
    apply: (File) -> Unit,
) {
    val snapshot = File.createTempFile("keynova-restore-", ".zip", cacheDir)
    try {
        try {
            snapshot.outputStream().use { input.copyTo(it) }
            // ZipInputStream alone accepts a truncated/missing central directory.
            java.util.zip.ZipFile(snapshot).use { }
            var usable = false
            val settings = mutableSetOf<String>()
            ZipInputStream(snapshot.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val current = entry
                    val isProtected = current.name.startsWith("unprotected/")
                    val name = if (isProtected) current.name.removePrefix("unprotected/") else current.name
                    val base = if (isProtected) protectedFilesDir else filesDir
                    require((current.isDirectory && isProtected && name.isEmpty()) ||
                        isSafeRestorePath(base, name))
                    if (!current.isDirectory) {
                        if (!isProtected && (name == PREFS_FILE_NAME || name == PROTECTED_PREFS_FILE_NAME)) {
                            require(settings.add(name))
                            parseSettingsLines(String(zip.readBytes(), Charsets.UTF_8).split("\n"))
                            usable = true
                        } else {
                            val allowed = (!isProtected && name == Database.NAME) ||
                                backupFilePatterns.any { name.matches(it) }
                            var bytes = 0L
                            var count = zip.read(buffer)
                            while (count != -1) {
                                bytes += count
                                count = zip.read(buffer)
                            }
                            if (allowed && bytes > 0) usable = true
                        }
                    }
                    zip.closeEntry() // validates CRC, including ignored entries
                    entry = zip.nextEntry
                }
            }
            require(usable)
        } catch (_: Exception) {
            // Do not expose paths, settings, ZIP entry names or parser messages.
            throw IllegalArgumentException("Invalid or unusable backup archive")
        }
        apply(snapshot)
    } finally {
        snapshot.delete() // Delete only the snapshot owned by this operation.
    }
}

private fun isSafeRestorePath(baseDir: File, entryName: String): Boolean {
    if (entryName.startsWith("/") || entryName.isEmpty()) return false
    val base = baseDir.canonicalFile
    val target = File(baseDir, entryName).canonicalFile
    return target.path.startsWith(base.path + File.separator)
}
'''
mutation_start = b.index("                filesChanged = true\n")
mutation_end = b.index('                }\n                } ?: error("Backup source unavailable")', mutation_start)
b = b[:mutation_start] + "".join("        " + line if line.strip() else line for line in b[mutation_start:mutation_end].splitlines(keepends=True)) + b[mutation_end:]
b = replace_once(b, r'''                                        if (!restoreEntryToDir(zip, filesDir, entry.name)) {
                                            Log.w("AdvancedScreen", "skipping unsafe backup entry ${entry.name}")
                                        }
                                        anyMatch = true''', r'''                                        anyMatch = restoreEntryToDir(zip, filesDir, entry.name) || anyMatch''', 'K01 only count restored normal files')
b = replace_once(b, '                withValidatedRestoreArchive(input,', '                    withValidatedRestoreArchive(input,', 'K01 indent archive boundary')
b = replace_once(b, '                }\n                } ?: error("Backup source unavailable")', '                    }\n                } ?: error("Backup source unavailable")', 'K01 indent archive close')
zip_start = b.index('                            ZipInputStream(snapshot.inputStream().buffered())')
zip_end = b.index('                        if (!anyMatch)', zip_start)
b = b[:zip_start] + "".join(line[4:] if line.strip() else line for line in b[zip_start:zip_end].splitlines(keepends=True)) + b[zip_end:]
backup.write_text(b, encoding="utf-8")
