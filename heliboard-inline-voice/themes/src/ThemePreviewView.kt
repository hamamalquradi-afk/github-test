// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.keyboard.*
import helium314.keyboard.keyboard.internal.KeyboardBuilder
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs

/** Renders the real parser, key coordinates, drawables, fonts and KeyboardView into a bounded thumbnail.
 * No forceTheme globals, preference writes or live IME changes are involved in browsing. */
class ThemePreviewView(context: Context) : View(context) {
    private var spec: ThemeSpec? = null
    private var language = "current"
    private var thumbnail: Bitmap? = null
    fun show(theme: ThemeSpec, language: String = "current") {
        if (this.spec == theme && this.language == language) return
        this.spec = theme; this.language = language
        thumbnail = null
        contentDescription = theme.displayName(context)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val theme = spec ?: return
        if (width < 1 || height < 1) return
        val bitmap = thumbnail ?: render(context, theme, language, width.coerceAtMost(640)).also { thumbnail = it }
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), null)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { thumbnail = null }
    override fun onDetachedFromWindow() { thumbnail = null; super.onDetachedFromWindow() }

    companion object {
        private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        fun aspectRatio(context: Context): Float {
            val settings = Settings.getValues()
            return ResourceUtils.getKeyboardWidth(context, settings).toFloat() /
                ResourceUtils.getKeyboardHeight(context.resources, settings).coerceAtLeast(1)
        }
        fun subtype(language: String): RichInputMethodSubtype = when (language) {
            "ar", "en" -> RichInputMethodSubtype.get(InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeLocale(if (language == "ar") "ar" else "en_US")
                .setSubtypeMode("keyboard").setIsAsciiCapable(language == "en")
                .setSubtypeExtraValue("KeyboardLayoutSet=" + if (language == "ar") "arabic" else "qwerty")
                .build())
            else -> RichInputMethodManager.getInstance().currentSubtype
        }

        fun buildKeyboard(context: Context, theme: ThemeSpec, language: String): Keyboard {
            val settings = Settings.getValues()
            val colors = ThemeGalleryRepository.colors(context, theme, true)
            val themed = ThemePreviewContext(context,
                KeyboardTheme.getKeyboardThemeForStyle(theme.style, colors.hasKeyBorders).mStyleId, colors)
            val params = KeyboardLayoutSet.Params().apply {
                editorInfo = EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
                subtype = ThemePreviewView.subtype(language)
                settingsValues = settings
                keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings)
                keyboardHeight = ResourceUtils.getKeyboardHeight(context.resources, settings)
                deviceLocked = false
            }
            // Build directly: the IME's keyboard cache intentionally has no per-preview theme key.
            return KeyboardBuilder(themed, KeyboardParams()).load(KeyboardId(KeyboardElement.ALPHABET, params)).build()
        }

        fun render(context: Context, theme: ThemeSpec, language: String, width: Int): Bitmap {
            val key = "${theme.hashCode()}:$language:$width:${context.resources.configuration}:${context.prefs().all.hashCode()}"
            cache.get(key)?.let { return it }
            val colors = ThemeGalleryRepository.colors(context, theme, true)
            val themed = ThemePreviewContext(context,
                KeyboardTheme.getKeyboardThemeForStyle(theme.style, colors.hasKeyBorders).mStyleId, colors)
            val keyboard = buildKeyboard(context, theme, language)
            val realWidth = keyboard.mOccupiedWidth.coerceAtLeast(1)
            val realHeight = keyboard.mOccupiedHeight.coerceAtLeast(1)
            val view = KeyboardView(themed, null, R.attr.keyboardViewStyle, colors)
            view.setKeyboard(keyboard)
            view.measure(MeasureSpec.makeMeasureSpec(realWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(realHeight, MeasureSpec.EXACTLY))
            view.layout(0, 0, realWidth, realHeight)
            // The IME applies MAIN_BACKGROUND to its parent, so draw that same background first.
            colors.setBackground(view, ColorType.MAIN_BACKGROUND)
            val outWidth = width.coerceIn(1, 640)
            val output = Bitmap.createBitmap(outWidth, (realHeight.toFloat() * outWidth / realWidth).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.scale(outWidth.toFloat() / realWidth, output.height.toFloat() / realHeight)
            view.draw(canvas)
            view.deallocateMemory()
            cache.put(key, output)
            return output
        }
    }
}
