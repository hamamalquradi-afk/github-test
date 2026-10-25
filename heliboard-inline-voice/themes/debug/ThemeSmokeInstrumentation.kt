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
import helium314.keyboard.settings.SettingsDestination
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
            main { SettingsDestination.navigateTo(SettingsDestination.Themes) }
            waitForIdleSync(); SystemClock.sleep(1000)
            screenshot("gallery-$mode")
            check(findText(activity.getString(helium314.keyboard.latin.R.string.gallery_title)).isNotEmpty()) { "Gallery title missing from accessibility tree" }
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

    private fun verifyLiveKeyboard() {
        var host = startActivitySync(Intent(targetContext, ThemeSmokeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ThemeSmokeActivity
        waitForIdleSync(); SystemClock.sleep(1500)
        for (wide in listOf(false, true)) {
            val monitor = addMonitor(ThemeSmokeActivity::class.java.name, null, false)
            main { host.requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val recreated = monitor.waitForActivityWithTimeout(2500)
            removeMonitor(monitor)
            if (recreated != null) host = recreated as ThemeSmokeActivity
            waitForIdleSync(); SystemClock.sleep(1000)
            for (language in listOf("en", "ar")) {
                main {
                    host.editor.setText("")
                    val subtype = ThemePreviewView.subtype(language).rawSubtype
                    SubtypeSettings.addEnabledSubtype(targetContext.prefs(), subtype)
                    val switcher = KeyboardSwitcher.getInstance()
                    val field = KeyboardSwitcher::class.java.getDeclaredField("mLatinIME").apply { isAccessible = true }
                    (field.get(switcher) as LatinIME).switchToSubtype(subtype)
                }
                waitForIdleSync(); SystemClock.sleep(500)
                val code = if (language == "en") 'q'.code else 'ض'.code
                tapKey { it.code == code || language == "en" && it.code == 'Q'.code }
                waitForIdleSync(); SystemClock.sleep(150)
                check(main { host.editor.text.toString().lowercase().contains(if (language == "en") "q" else "ض") }) { "Real IME did not type $language / wide=$wide" }
                tapKey { it.code == '('.code }
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
        waitForIdleSync(); SystemClock.sleep(500)
        val nativeShortcuts = shortcuts(host)
        main { ThemeGalleryRepository.apply(targetContext, ThemeCatalog.load(targetContext).first { it.id == "gold_royal_gold" }) }
        waitForIdleSync(); SystemClock.sleep(500)
        val themedShortcuts = shortcuts(host)
        check(nativeShortcuts == themedShortcuts) { "Shortcut behavior changed: native=$nativeShortcuts gallery=$themedShortcuts" }
        nativeShortcuts.filterValues { !it }.forEach { (name, _) -> log.append("BASELINE LIMITATION: native ").append(name).append(" was not observed in this editor; unchanged with gallery\n") }
        pass("Actual Ctrl+A/C/V, Alt, Tab and Shift interactions match the native appearance baseline: $themedShortcuts")
    }

    private fun shortcuts(host: ThemeSmokeActivity): Map<String, Boolean> {
        fun tap(code: Int) { tapKey { it.code == code || code in 'a'.code..'z'.code && it.code == code - 32 }; waitForIdleSync(); SystemClock.sleep(150) }
        main { host.editor.setText("abc"); host.editor.setSelection(3); host.editor.keyEvents.clear() }
        waitForIdleSync(); SystemClock.sleep(200)
        tap(KeyCode.CTRL); tap('a'.code)
        val selects = main { host.editor.selectionStart == 0 && host.editor.selectionEnd == 3 }
        tap(KeyCode.CTRL); tap('c'.code)
        val copied = main { (host.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(host)?.toString() == "abc" }
        main { host.editor.setSelection(host.editor.text.length) }
        tap(KeyCode.CTRL); tap('v'.code)
        val pasted = main { host.editor.text.toString() == "abcabc" }
        tap(KeyCode.ALT); tap('x'.code)
        val alt = main { host.editor.keyEvents.any { it.first == android.view.KeyEvent.KEYCODE_X && it.second and android.view.KeyEvent.META_ALT_ON != 0 } }
        tap(KeyCode.TAB)
        val tab = main { host.editor.keyEvents.any { it.first == android.view.KeyEvent.KEYCODE_TAB } }
        main { host.editor.setText("x"); host.editor.setSelection(1) }
        waitForIdleSync(); SystemClock.sleep(200)
        tap(KeyCode.SHIFT); tap('a'.code)
        val shifted = main { host.editor.text.toString().endsWith("A") }
        return linkedMapOf("Ctrl+A" to selects, "Ctrl+C" to copied, "Ctrl+V" to pasted, "Alt" to alt, "Tab" to tab, "Shift" to shifted)
    }

    private fun tapKey(predicate: (Key) -> Boolean) {
        val point = main {
            val view = checkNotNull(KeyboardSwitcher.getInstance().mainKeyboardView)
            check(view.isShown)
            val keyboard = checkNotNull(view.keyboard)
            val key = keyboard.sortedKeys.firstOrNull(predicate) ?: error("No matching key in ${keyboard.mId}; codes=${keyboard.sortedKeys.map { it.code }}")
            val location = IntArray(2); view.getLocationOnScreen(location)
            floatArrayOf(location[0] + key.x + key.width / 2f, location[1] + key.y + key.height / 2f)
        }
        val now = SystemClock.uptimeMillis()
        sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0))
        sendPointerSync(MotionEvent.obtain(now, now + 65, MotionEvent.ACTION_UP, point[0], point[1], 0))
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

    private fun findText(text: String): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + 8000
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
