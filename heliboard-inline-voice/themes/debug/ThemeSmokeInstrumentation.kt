// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import helium314.keyboard.keyboard.*
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import java.io.File

/** Runs against the installed, minified APK. Never packaged into a release variant. */
class ThemeSmokeInstrumentation : Instrumentation() {
    private lateinit var mode: String
    private val log = StringBuilder()
    private val output get() = File(targetContext.getExternalFilesDir(null), "gallery-smoke").apply { mkdirs() }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode") ?: "full"; start() }
    override fun onStart() {
        try {
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (mode == "persist") {
                main { check(ThemeGalleryRepository.selected(targetContext)?.id == "gold_royal_gold") {
                    "Selection did not survive process restart: raw=" + targetContext.prefs().getString(ThemeGalleryRepository.SELECTED, "<missing>") +
                        ", resolved=" + ThemeGalleryRepository.selected(targetContext)?.id
                } }
                pass("Selected appearance survived a force-stop and fresh process")
            }
            val activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            // After a force-stop, Android can restore Settings with its welcome
            // wizard above the NavHost even though the IME has since been selected.
            // The persistence failure screenshot confirmed exactly this state.
            // Follow the REAL wizard's Configure action only after verifying the
            // system-selected IME, rather than clearing preferences or hiding tests.
            // Both cold-start (full) and restart (persist/rtl) tests can
            // display the remembered welcome wizard above the real settings
            // screen, even after the emulator has selected this IME. Close it
            // through its actual visible Configure action, never by clearing
            // persistent app data or skipping the accessibility assertion.
            val configure = activity.getString(helium314.keyboard.latin.R.string.setup_step3_action)
            if (findText(configure, 1200).isNotEmpty()) {
                val selectedIme = android.provider.Settings.Secure.getString(
                    targetContext.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                val expectedIme = "${targetContext.packageName}/${LatinIME::class.java.name}"
                check(selectedIme == expectedIme) {
                    "Welcome wizard requires setup; selectedIme=$selectedIme, expected=$expectedIme"
                }
                screenshot("welcome-before-gallery-$mode")
                clickText(configure)
                pass("Closed visible setup wizard via Configure after confirming selected IME")
            }
            // Exercise the same visible navigation path as a real user.
            // The upstream SettingsDestination.navigateTo flow is a short-lived
            // asynchronous event; on a cold start it can reset before Compose
            // observes it, leaving the main settings page visible.
            clickText(activity.getString(helium314.keyboard.latin.R.string.settings_screen_appearance))
            clickText(activity.getString(helium314.keyboard.latin.R.string.gallery_title))
            waitForIdleSync(); SystemClock.sleep(500)
            screenshot("gallery-$mode")
            pass("Opened Themes through actual Appearance setting rather than an ephemeral navigation event")
            check(findText(activity.getString(helium314.keyboard.latin.R.string.gallery_title)).isNotEmpty()) {
                "Gallery title missing from accessibility tree; mode=$mode, selectedIme=" +
                    android.provider.Settings.Secure.getString(targetContext.contentResolver,
                        android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            }
            if (mode == "full") {
                verifyCatalog()
                verifyCustomImages()
                main { ThemeGalleryRepository.apply(targetContext, ThemeCatalog.load(targetContext).first()) }
                waitForIdleSync(); SystemClock.sleep(300)
                clickText("Obsidian")
                waitForIdleSync(); SystemClock.sleep(300)
                screenshot("preview-dialog")
                clickText(activity.getString(helium314.keyboard.latin.R.string.gallery_apply))
                waitForIdleSync()
                pass("Actual gallery card opens a preview dialog and Apply selects the theme")
                clickText(activity.getString(helium314.keyboard.latin.R.string.gallery_restore_default))
                waitForIdleSync()
                main { check(ThemeGalleryRepository.selected(targetContext) == null) }
                pass("Visible Restore default toolbar action resets the selected appearance")
                main { ThemeGalleryRepository.apply(targetContext, ThemeCatalog.load(targetContext).first { it.id == "gold_royal_gold" }) }
            } else if (mode == "rtl") {
                check(activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
                pass("Arabic resources and RTL gallery displayed")
            }
            if (mode != "full") verifyLiveKeyboard()
            waitForIdleSync()
            main {
                check(targetContext.prefs().getString(ThemeGalleryRepository.SELECTED, null) == "gold_royal_gold")
                val prefsFile = File(DeviceProtectedUtils.getFilesDir(targetContext).parentFile,
                    "shared_prefs/${targetContext.packageName}_preferences.xml")
                check(prefsFile.readText().contains("gold_royal_gold")) { "Selected theme was not written to persistent storage" }
            }
            pass("Final selected theme is present in memory and on disk before process termination")
            File(output, "report-$mode.txt").writeText(log.toString())
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "THEME_SMOKE_PASS ($mode)\n$log") })
        } catch (error: Throwable) {
            runCatching { screenshot("failure-$mode") }
            File(output, "report-$mode.txt").writeText(log.toString() + android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "THEME_SMOKE_FAIL ($mode)\n" + android.util.Log.getStackTraceString(error)) })
        }
    }

    private fun verifyCatalog() {
        val themes = ThemeCatalog.load(targetContext)
        check(themes.size == 80)
        val before = main { targetContext.prefs().all.filterKeys { it != ThemeGalleryRepository.SELECTED } }
        val modelSignatures = hashMapOf<String, List<Int>>()
        for (theme in themes) main {
            ThemeGalleryRepository.apply(targetContext, theme)
            check(targetContext.prefs().getString(ThemeGalleryRepository.SELECTED, null) == theme.id)
            check(ThemeGalleryRepository.selected(targetContext) == theme)
            val applied = KeyboardTheme.getColorsForCurrentTheme(targetContext)
            val preview = ThemeGalleryRepository.colors(targetContext, theme, true)
            ColorType.entries.forEach { check(applied.get(it) == preview.get(it)) { "Preview mismatch ${theme.id}/$it" } }
            for (orientation in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
                val config = Configuration(targetContext.resources.configuration).apply { this.orientation = orientation }
                val ctx = targetContext.createConfigurationContext(config)
                for (language in listOf("en", "ar")) {
                    val bitmap = ThemePreviewView.render(ctx, theme, language, 400)
                    check(bitmap.width == 400 && bitmap.height > 20)
                    val model = ThemePreviewView.buildKeyboard(ctx, theme, language)
                    val signature = model.sortedKeys.filterNot { it.isSpacer }.map { it.code }
                    val key = "$orientation:$language"
                    if (key !in modelSignatures) modelSignatures[key] = signature
                    check(modelSignatures[key] == signature) { "Theme changed key codes: ${theme.id}/$key" }
                    check(signature.containsAll(listOf(KeyCode.CTRL, KeyCode.ALT, KeyCode.SHIFT, KeyCode.TAB, '('.code)))
                }
            }
        }
        val after = main { targetContext.prefs().all.filterKeys { it != ThemeGalleryRepository.SELECTED } }
        check(before == after) { "Applying a preset changed unrelated preferences" }
        pass("All 80 themes apply and persist; 320 Arabic/English portrait/landscape previews render with matching palettes and unchanged key codes")
    }

    private fun verifyCustomImages() {
        val ctx = targetContext
        val input = File(ctx.cacheDir, "gallery-input.png")
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        val image = checkNotNull(ThemeGalleryRepository.importImage(ctx, Uri.fromFile(input)))
        main {
            val saved = ThemeGalleryRepository.save(ctx, ThemeCatalog.load(ctx).first().copy(image = image), "Smoke custom image")
            ThemeGalleryRepository.apply(ctx, saved)
            check(ThemeGalleryRepository.selected(ctx) == saved)
            ThemePreviewView.render(ctx, saved, "en", 400)
            ThemeGalleryRepository.apply(ctx, ThemeCatalog.load(ctx).last())
            ThemeGalleryRepository.apply(ctx, saved)
            check(ThemeGalleryRepository.selected(ctx)?.image == image)
            // Force cache invalidation with a fresh invalid path as well as a missing private image.
            val corrupt = File(DeviceProtectedUtils.getFilesDir(ctx), "compact_gallery_image_broken.webp").apply { writeText("invalid image") }
            ThemePreviewView.render(ctx, saved.copy(image = corrupt.name), "ar", 400)
            ThemePreviewView.render(ctx, saved.copy(image = "compact_gallery_image_missing.webp"), "en", 400)
            corrupt.delete()
            ThemeGalleryRepository.restoreDefault(ctx)
            check(ThemeGalleryRepository.selected(ctx) == null)
            check(ThemeGalleryRepository.saved(ctx).contains(saved))
            check(File(DeviceProtectedUtils.getFilesDir(ctx), image).isFile)
            ThemeGalleryRepository.apply(ctx, saved)
            ThemeGalleryRepository.delete(ctx, saved)
        }
        input.writeText("invalid image")
        check(ThemeGalleryRepository.importImage(ctx, Uri.fromFile(input)) == null)
        input.delete()
        pass("Image import, bounded decoding, save/reapply, missing/corrupt fallback and default restoration passed")
    }

    private fun currentIme(): LatinIME {
        val field = KeyboardSwitcher::class.java.getDeclaredField("mLatinIME").apply { isAccessible = true }
        return field.get(KeyboardSwitcher.getInstance()) as LatinIME
    }

    private data class KeyboardFrame(val view: MainKeyboardView, val keyboard: Keyboard,
        val x: Int, val y: Int, val width: Int, val height: Int, val imeInset: Int)

    /** Called on the UI thread; a visible view alone can still be sliding into place. */
    private fun keyboardFrame(host: ThemeSmokeActivity): KeyboardFrame? {
        val editor = host.editor
        val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val ime = currentIme()
        val view = KeyboardSwitcher.getInstance().mainKeyboardView ?: return null
        val keyboard = view.keyboard ?: return null
        val insets = editor.rootWindowInsets ?: return null
        if (!editor.hasWindowFocus() || !editor.hasFocus() || !manager.isActive(editor) ||
            !ime.currentInputStarted || !ime.isInputViewShown || ime.currentInputConnection == null ||
            ime.currentInputEditorInfo?.fieldId != editor.id ||
            host.imeAnimating || !insets.isVisible(android.view.WindowInsets.Type.ime()) ||
            !view.isShown || !view.isAttachedToWindow || !view.isLaidOut || view.isLayoutRequested) return null
        val location = IntArray(2); view.getLocationOnScreen(location)
        return KeyboardFrame(view, keyboard, location[0], location[1], view.width, view.height,
            insets.getInsets(android.view.WindowInsets.Type.ime()).bottom)
    }

    private fun awaitEditorKeyboard(host: ThemeSmokeActivity, expectedLetter: Int? = null): KeyboardFrame {
        val start = SystemClock.uptimeMillis()
        val deadline = start + 10000
        var restoredForeground = false
        var requestedIme = false
        var forcedIme = false
        var previous: KeyboardFrame? = null
        do {
            waitForIdleSync()
            val frame = main { keyboardFrame(host)?.takeIf { frame ->
                expectedLetter == null || frame.keyboard.sortedKeys.any { it.code == expectedLetter || it.code == expectedLetter - 32 }
            } }
            // The failed RTL run tapped the editor during the IME slide. Require
            // completed insets animation AND unchanged real screen coordinates.
            if (frame != null && frame == previous) return frame
            previous = frame
            if (frame == null) main {
                if (host.editor.hasWindowFocus()) {
                    host.editor.requestFocus()
                    val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    if (!requestedIme) {
                        manager.showSoftInput(host.editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        requestedIme = true
                    } else if (!forcedIme && SystemClock.uptimeMillis() - start > 1200) {
                        host.editor.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
                        manager.showSoftInput(host.editor, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                        forcedIme = true
                        log.append("Explicitly requested IME after editor retained focus\n")
                    }
                } else if (!restoredForeground && SystemClock.uptimeMillis() - start > 1200) {
                    host.startActivity(Intent(host, ThemeSmokeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    restoredForeground = true
                    log.append("Reordered existing smoke editor to restore window focus\n")
                }
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        val state = main {
            val view = KeyboardSwitcher.getInstance().mainKeyboardView
            val ime = currentIme()
            "editorWindowFocus=${host.editor.hasWindowFocus()}, editorHasFocus=${host.editor.hasFocus()}, " +
                "imeAnimating=${host.imeAnimating}, imeVisible=${host.editor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())}, " +
                "inputStarted=${ime.currentInputStarted}, inputViewShown=${ime.isInputViewShown}, " +
                "fieldId=${ime.currentInputEditorInfo?.fieldId}/${host.editor.id}, " +
                "viewShown=${view?.isShown}, layoutPending=${view?.isLayoutRequested}, frame=${keyboardFrame(host)}"
        }
        error("Keyboard readiness timed out: $state")
    }

    private fun verifyLiveKeyboard() {
        var host = startActivitySync(Intent(targetContext, ThemeSmokeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ThemeSmokeActivity
        waitForIdleSync()
        awaitEditorKeyboard(host)
        for (wide in listOf(false, true)) {
            val monitor = addMonitor(ThemeSmokeActivity::class.java.name, null, false)
            main { host.requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val recreated = monitor.waitForActivityWithTimeout(5000)
            removeMonitor(monitor)
            if (recreated != null) host = recreated as ThemeSmokeActivity
            waitForIdleSync()
            awaitEditorKeyboard(host)
            for (language in listOf("en", "ar")) {
                main {
                    host.editor.setText("")
                    val subtype = ThemePreviewView.subtype(language).rawSubtype
                    SubtypeSettings.addEnabledSubtype(targetContext.prefs(), subtype)
                    val switcher = KeyboardSwitcher.getInstance()
                    val field = KeyboardSwitcher::class.java.getDeclaredField("mLatinIME").apply { isAccessible = true }
                    val ime = field.get(switcher) as LatinIME
                    // An IME restored after force-stop can still display the cached
                    // English keyboard even if its selected subtype already is Arabic.
                    // Move through the other subtype to avoid the same-subtype fast path.
                    val alternate = ThemePreviewView.subtype(if (language == "en") "ar" else "en").rawSubtype
                    SubtypeSettings.addEnabledSubtype(targetContext.prefs(), alternate)
                    ime.switchToSubtype(alternate)
                    ime.switchToSubtype(subtype)
                }
                awaitEditorKeyboard(host)
                val code = if (language == "en") 'q'.code else 'ض'.code
                // Subtype changes propagate to the displayed KeyboardView asynchronously.
                // Do not tap a stale layout: this would create a false persistence failure.
                val deadline = SystemClock.uptimeMillis() + 6000
                do {
                    waitForIdleSync()
                    val ready = main {
                        KeyboardSwitcher.getInstance().mainKeyboardView.keyboard?.sortedKeys?.any {
                            it.code == code || (language == "en" && it.code == 'Q'.code)
                        } == true
                    }
                    if (ready) break
                    SystemClock.sleep(150)
                } while (SystemClock.uptimeMillis() < deadline)
                tapKey(host) { it.code == code || language == "en" && it.code == 'Q'.code }
                waitForIdleSync(); SystemClock.sleep(150)
                check(main { host.editor.text.toString().lowercase().contains(if (language == "en") "q" else "ض") }) { "Real IME did not type $language / wide=$wide" }
                tapKey(host) { it.code == '('.code }
                waitForIdleSync()
                check(main { host.editor.text.toString().contains("(") })
                screenshot("keyboard-$mode-$language-${if (wide) "landscape" else "portrait"}")
            }
        }
        // Confirm modifier controls still exist in the real, currently displayed keyboard.
        main {
            val codes = KeyboardSwitcher.getInstance().mainKeyboardView.keyboard!!.sortedKeys.map { it.code }
            check(codes.containsAll(listOf(KeyCode.CTRL, KeyCode.ALT, KeyCode.SHIFT, KeyCode.TAB)))
        }
        pass("Actual on-screen keyboard typed Arabic, English and a programming symbol in portrait and landscape; modifier controls retained")
        main {
            val field = KeyboardSwitcher::class.java.getDeclaredField("mLatinIME").apply { isAccessible = true }
            (field.get(KeyboardSwitcher.getInstance()) as LatinIME).switchToSubtype(ThemePreviewView.subtype("en").rawSubtype)
            ThemeGalleryRepository.useOriginal(targetContext)
        }
        waitForIdleSync()
        val nativeShortcuts = shortcuts(host)
        main { ThemeGalleryRepository.apply(targetContext, ThemeCatalog.load(targetContext).first { it.id == "gold_royal_gold" }) }
        waitForIdleSync()
        val themedShortcuts = shortcuts(host)
        check(nativeShortcuts.values.all { it } && themedShortcuts.values.all { it } && nativeShortcuts == themedShortcuts) { "Shortcut behavior changed: native=$nativeShortcuts gallery=$themedShortcuts" }
        pass("Actual Ctrl+A/C/V, Alt, Tab and Shift interactions match the native appearance baseline: $themedShortcuts")
    }

    private fun shortcuts(host: ThemeSmokeActivity): Map<String, Boolean> {
        awaitEditorKeyboard(host, 'q'.code)
        fun tap(code: Int) { tapKey(host) { it.code == code || code in 'a'.code..'z'.code && it.code == code - 32 }; waitForIdleSync(); SystemClock.sleep(150) }
        val clipboard = host.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val previousClip = main { clipboard.primaryClip }
        try {
            main {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Smoke sentinel", "not-the-copied-text"))
                host.editor.setText("abc"); host.editor.setSelection(3); host.editor.keyEvents.clear()
            }
            fun observedCtrl(key: Int) = host.editor.keyEvents.any {
                it.first == key && it.second and android.view.KeyEvent.META_CTRL_ON != 0
            }
            waitForIdleSync(); SystemClock.sleep(200)
            tap(KeyCode.CTRL); tap('a'.code)
            val selects = main { host.editor.selectionStart == 0 && host.editor.selectionEnd == 3 &&
                host.editor.text.toString() == "abc" && observedCtrl(android.view.KeyEvent.KEYCODE_A) }
            tap(KeyCode.CTRL); tap('c'.code)
            val copied = main { clipboard.primaryClip?.getItemAt(0)?.coerceToText(host)?.toString() == "abc" &&
                observedCtrl(android.view.KeyEvent.KEYCODE_C) }
            main { host.editor.setSelection(host.editor.text.length) }
            tap(KeyCode.CTRL); tap('v'.code)
            // Clipboard delivery and InputConnection text updates are asynchronous
            // on some Android emulators. Keep the real touch-injected Ctrl+V and
            // exact editor assertion, but allow bounded completion before failing.
            val pasteDeadline = SystemClock.uptimeMillis() + 2500
            var pasted = false
            do {
                waitForIdleSync()
                pasted = main { host.editor.text.toString() == "abcabc" && observedCtrl(android.view.KeyEvent.KEYCODE_V) }
                if (pasted) break
                SystemClock.sleep(100)
            } while (SystemClock.uptimeMillis() < pasteDeadline)
            if (!pasted) {
                val editorState = main {
                    "text=" + host.editor.text.toString() +
                        ", selection=" + host.editor.selectionStart + ".." + host.editor.selectionEnd +
                        ", windowFocus=" + host.editor.hasWindowFocus() +
                        ", inputFocus=" + host.editor.hasFocus()
                }
                val clipboardState = main {
                    (host.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString()
                }
                log.append("Ctrl+V failed after bounded wait: ")
                    .append(editorState).append(", clipboard=").append(clipboardState).append(System.lineSeparator())
            }
            tap(KeyCode.ALT); tap('x'.code)
            val alt = main { host.editor.keyEvents.any { it.first == android.view.KeyEvent.KEYCODE_X && it.second and android.view.KeyEvent.META_ALT_ON != 0 } }
            tap(KeyCode.TAB)
            val tab = main { host.editor.keyEvents.any { it.first == android.view.KeyEvent.KEYCODE_TAB } }
            main { host.editor.setText("x"); host.editor.setSelection(1) }
            waitForIdleSync(); SystemClock.sleep(200)
            tap(KeyCode.SHIFT); tap('a'.code)
            val shifted = main { host.editor.text.toString().endsWith("A") }
            return linkedMapOf("Ctrl+A" to selects, "Ctrl+C" to copied, "Ctrl+V" to pasted, "Alt" to alt, "Tab" to tab, "Shift" to shifted)
        } finally {
            main { if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip() }
        }
    }

    private fun tapKey(host: ThemeSmokeActivity, predicate: (Key) -> Boolean) {
        val frame = awaitEditorKeyboard(host)
        val point = main {
            check(keyboardFrame(host) == frame) { "Keyboard moved before touch injection" }
            val view = checkNotNull(KeyboardSwitcher.getInstance().mainKeyboardView)
            check(view.isShown)
            val keyboard = checkNotNull(view.keyboard)
            val key = keyboard.sortedKeys.firstOrNull(predicate) ?: error("No matching key in ${keyboard.mId}; codes=${keyboard.sortedKeys.map { it.code }}")
            val location = IntArray(2); view.getLocationOnScreen(location)
            val x = location[0] + view.paddingLeft + key.x + key.width / 2f
            val y = location[1] + view.paddingTop + key.y + key.height / 2f
            val visible = android.graphics.Rect()
            check(view.getGlobalVisibleRect(visible) && visible.contains(x.toInt(), y.toInt())) { "Key center is outside the visible IME: $visible" }
            check(uiAutomation.windows.any { window ->
                val bounds = android.graphics.Rect(); window.getBoundsInScreen(bounds)
                window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && bounds.contains(x.toInt(), y.toInt())
            }) { "Key center is not in the input-method window" }
            log.append("TOUCH code=${key.code}, point=$x,$y, frame=$frame\n")
            floatArrayOf(x, y)
        }
        val now = SystemClock.uptimeMillis()
        // IME windows are managed separately from the editor activity. Android 15's
        // sendPointerSync targets the instrumented UID and may reject that window.
        // UiAutomation is the supported cross-window injector; keep every actual
        // editor-text assertion above, so a misplaced/rejected tap still fails.
        fun inject(action: Int) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(uiAutomation.injectInputEvent(event, true)) { "Touch injection rejected at ${point.toList()}" }
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(65)
        inject(MotionEvent.ACTION_UP)
    }

    private fun clickText(text: String) {
        val nodes = findText(text)
        check(nodes.isNotEmpty()) { "Missing UI text: $text" }
        var node: AccessibilityNodeInfo? = nodes.firstOrNull { it.text?.toString() == text } ?: nodes.first()
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not click: $text" }
        waitForIdleSync()
        SystemClock.sleep(300)
    }

    private fun findText(text: String, timeoutMs: Long = 8000): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) found.add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(uiAutomation.rootInActiveWindow)
            if (found.isNotEmpty()) return found
            SystemClock.sleep(200)
        } while (SystemClock.uptimeMillis() < deadline)
        return emptyList()
    }

    private fun screenshot(name: String) {
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun pass(message: String) { log.append("PASS: ").append(message).append('\n') }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
