// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.*
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.json.Json
import java.io.File
import java.util.EnumMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

object ThemeGalleryRepository {
    const val SELECTED = "compact_gallery_selected_v1"
    const val SAVED = "compact_gallery_saved_v1"
    private const val HIDE_IMAGES = "compact_gallery_hide_legacy_images"
    private const val IMAGE_PREFIX = "compact_gallery_image_"
    private val json = Json { ignoreUnknownKeys = true }
    private var savedJson: String? = null
    private var savedCache: List<ThemeSpec> = emptyList()

    fun saved(context: Context): List<ThemeSpec> {
        val raw = context.prefs().getString(SAVED, "[]") ?: "[]"
        if (raw != savedJson) {
            savedCache = runCatching { json.decodeFromString<List<ThemeSpec>>(raw).filter { runCatching { it.validate() }.isSuccess } }.getOrDefault(emptyList())
            savedJson = raw
        }
        return savedCache
    }

    @JvmStatic fun selected(context: Context): ThemeSpec? {
        val id = context.prefs().getString(SELECTED, "")?.takeIf { it.isNotBlank() } ?: return null
        return ThemeCatalog.load(context).firstOrNull { it.id == id } ?: saved(context).firstOrNull { it.id == id }
    }

    fun apply(context: Context, spec: ThemeSpec) {
        spec.validate()
        require(ThemeCatalog.load(context).any { it.id == spec.id } || saved(context).any { it.id == spec.id })
        // Selection is a user transaction: complete its small disk write before reporting success.
        context.prefs().edit(commit = true) { putString(SELECTED, spec.id) }
        reload()
    }

    fun useOriginal(context: Context) {
        context.prefs().edit(commit = true) { remove(SELECTED); remove(HIDE_IMAGES) }
        reload()
    }

    /** Only appearance preferences are reset. Layouts, fonts, geometry, voice and user palettes survive. */
    fun restoreDefault(context: Context) {
        // Make the current appearance re-applicable even after restoring defaults.
        if (selected(context) == null && !context.prefs().getBoolean(HIDE_IMAGES, false)) save(context, capture(context), context.getString(helium314.keyboard.latin.R.string.gallery_previous))
        context.prefs().edit(commit = true) {
            remove(SELECTED)
            putBoolean(HIDE_IMAGES, true)
            remove(Settings.PREF_THEME_STYLE)
            remove(Settings.PREF_THEME_KEY_BORDERS)
            remove(Settings.PREF_THEME_COLORS)
            remove(Settings.PREF_THEME_COLORS_NIGHT)
            remove(Settings.PREF_THEME_DAY_NIGHT)
        }
        reload()
    }

    /** Changing an existing appearance control intentionally returns control to the native engine. */
    @JvmStatic fun legacyPreferenceChanged(context: Context, key: String?) {
        if (key == null || key !in setOf(Settings.PREF_THEME_STYLE, Settings.PREF_THEME_KEY_BORDERS,
                Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT, Settings.PREF_THEME_DAY_NIGHT)
            && !key.startsWith(Settings.PREF_USER_COLORS_PREFIX)
            && !key.startsWith(Settings.PREF_USER_MORE_COLORS_PREFIX)
            && !key.startsWith(Settings.PREF_USER_ALL_COLORS_PREFIX)) return
        if (context.prefs().contains(SELECTED)) {
            context.prefs().edit(commit = true) { remove(SELECTED) }
            reload()
        }
    }

    fun save(context: Context, source: ThemeSpec, name: String): ThemeSpec {
        val cleanName = name.trim().take(60).ifBlank { context.getString(helium314.keyboard.latin.R.string.gallery_custom) }
        val spec = source.copy(id = "user_" + UUID.randomUUID().toString().replace("-", ""),
            name = cleanName, nameAr = cleanName, category = "custom").validate()
        context.prefs().edit(commit = true) { putString(SAVED, json.encodeToString(saved(context) + spec)) }
        return spec
    }

    fun delete(context: Context, spec: ThemeSpec) {
        require(spec.category == "custom")
        if (selected(context)?.id == spec.id) useOriginal(context)
        context.prefs().edit(commit = true) { putString(SAVED, json.encodeToString(saved(context).filterNot { it.id == spec.id })) }
        pruneImages(context)
    }

    fun capture(context: Context): ThemeSpec {
        selected(context)?.let { return it }
        val colors = KeyboardTheme.getColorsForCurrentTheme(context)
        fun hex(type: ColorType) = String.format("#%08X", colors.get(type))
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val night = helium314.keyboard.latin.utils.ResourceUtils.isNight(context.resources) && context.prefs().getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
        fun image(wide: Boolean) = Settings.getCustomBackgroundFile(context, night, wide).takeIf { it.isFile && !context.prefs().getBoolean(HIDE_IMAGES, false) }?.name
        return ThemeSpec("draft", context.getString(helium314.keyboard.latin.R.string.gallery_custom),
            background = hex(ColorType.MAIN_BACKGROUND), keys = hex(ColorType.KEY_BACKGROUND),
            functional = hex(ColorType.FUNCTIONAL_KEY_BACKGROUND), label = hex(ColorType.KEY_TEXT),
            accent = hex(ColorType.ACTION_KEY_BACKGROUND), style = colors.themeStyle, borders = colors.hasKeyBorders,
            image = image(false), landscapeImage = image(true) ?: if (landscape) image(false) else null,
            allColors = ColorType.entries.associate { it.name to colors.get(it) })
    }

    fun colors(context: Context, spec: ThemeSpec, preview: Boolean = false): Colors {
        val wide = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val imageName = if (wide) spec.landscapeImage ?: spec.image else spec.image
        val drawable = imageName?.let { loadImage(context, it, if (preview) 640 else 1280) }?.let { image ->
            ThemeImageDrawable(image, Color.parseColor(spec.background))
        } ?: ThemeArtwork(spec)
        // Decorative backgrounds have opaque keys. Borderless text is checked against both endpoints.
        val hasBorders = spec.borders || imageName != null
        val surfaces = if (hasBorders) listOf(spec.keys, spec.functional) else listOf(spec.background, spec.secondary)
        val text = readable(Color.parseColor(spec.label), surfaces.map { opaque(Color.parseColor(it)) })
        val base: Colors = if (spec.allColors.isEmpty()) DefaultColors(spec.style, hasBorders,
            opaque(Color.parseColor(spec.accent)), opaque(Color.parseColor(spec.background)),
            opaque(Color.parseColor(spec.keys)), opaque(Color.parseColor(spec.functional)),
            opaque(Color.parseColor(spec.keys)), text, text, text, text,
            keyboardBackground = drawable)
        else AllColors(EnumMap<ColorType, Int>(ColorType::class.java).apply {
            spec.allColors.forEach { (key, value) -> runCatching { put(ColorType.valueOf(key), value) } }
        }, spec.style, hasBorders, drawable)
        return GalleryColors(base, drawable)
    }

    @JvmStatic fun readLegacyImage(context: Context, night: Boolean): Drawable? {
        if (context.prefs().getBoolean(HIDE_IMAGES, false)) return null
        val wide = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var file = Settings.getCustomBackgroundFile(context, night, wide)
        if (!file.isFile && wide) file = Settings.getCustomBackgroundFile(context, night, false)
        return loadImage(context, file.name, 1280)?.let { ThemeImageDrawable(it, Color.BLACK, false) }
    }

    private val images = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun loadImage(context: Context, name: String, maxEdge: Int): Bitmap? {
        // References from backups are untrusted; never allow paths outside private app storage.
        if (name != File(name).name || !(name.startsWith(IMAGE_PREFIX) || name.startsWith("custom_background_image"))) return null
        val file = File(DeviceProtectedUtils.getFilesDir(context), name)
        if (!file.isFile) return null
        val cacheKey = "${file.path}:${file.lastModified()}:${file.length()}:$maxEdge"
        images.get(cacheKey)?.let { return it }
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = 1
            while (max(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge ||
                options.outWidth.toLong() * options.outHeight / options.inSampleSize / options.inSampleSize > 1_100_000)
                options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.path, options)?.also { images.put(cacheKey, it) }
        }.getOrNull()
    }

    /** Copies provider bytes, bounds decoding and compresses before publishing a private image. */
    fun importImage(context: Context, uri: Uri): String? {
        val dir = DeviceProtectedUtils.getFilesDir(context)
        val temp = File.createTempFile("gallery_import_", ".tmp", dir)
        var decoded: Bitmap? = null
        var transformed: Bitmap? = null
        var result: File? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 20 * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, options)
            require(options.outWidth > 0 && options.outHeight > 0)
            options.inSampleSize = 1
            while (max(options.outWidth, options.outHeight) / options.inSampleSize > 1280 ||
                options.outWidth.toLong() * options.outHeight / options.inSampleSize / options.inSampleSize > 1_100_000)
                options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            decoded = BitmapFactory.decodeFile(temp.path, options) ?: return null
            val orientation = runCatching { android.media.ExifInterface(temp.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
                }
            }
            transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val name = IMAGE_PREFIX + UUID.randomUUID().toString() + ".webp"
            result = File(dir, name)
            result.outputStream().use {
                @Suppress("DEPRECATION")
                val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                require(transformed.compress(format, 88, it))
            }
            return name
        } catch (_: Exception) { result?.delete(); return null }
        finally {
            temp.delete()
            transformed?.takeIf { it !== decoded }?.recycle()
            decoded?.recycle()
        }
    }

    fun pruneImages(context: Context) {
        val used = saved(context).flatMap { listOfNotNull(it.image, it.landscapeImage) }.toSet()
        File(DeviceProtectedUtils.getFilesDir(context).path).listFiles()?.filter { it.name.startsWith(IMAGE_PREFIX) && it.name !in used }?.forEach { it.delete() }
        images.evictAll()
    }

    private fun reload() {
        Settings.clearCachedBackgroundImages()
        KeyboardLayoutSet.onKeyboardThemeChanged()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    fun readable(preferred: Int, surfaces: List<Int>): Int {
        fun contrast(color: Int) = surfaces.minOf { ColorUtils.calculateContrast(opaque(color), opaque(it)) }
        if (contrast(preferred) >= 4.5) return opaque(preferred)
        return if (contrast(Color.WHITE) >= contrast(Color.BLACK)) Color.WHITE else Color.BLACK
    }
    private fun opaque(color: Int) = color or (0xFF shl 24)
}

/** Keep vector artwork vector; native Colors otherwise rasterizes the entire keyboard background. */
private class GalleryColors(private val base: Colors, private val artwork: Drawable) : Colors by base {
    private val actionIcon = ThemeGalleryRepository.readable(base.get(ColorType.ACTION_KEY_ICON), listOf(base.get(ColorType.ACTION_KEY_BACKGROUND)))
    override fun get(color: ColorType) = if (color == ColorType.ACTION_KEY_ICON) actionIcon else base.get(color)
    override fun setBackground(view: View, color: ColorType) {
        if (color == ColorType.MAIN_BACKGROUND) view.background = artwork else base.setBackground(view, color)
    }
    override fun setColor(drawable: Drawable, color: ColorType) {
        if (color == ColorType.ACTION_KEY_ICON) drawable.setColorFilter(actionIcon, PorterDuff.Mode.SRC_IN)
        else base.setColor(drawable, color)
    }
    override fun setColor(view: ImageView, color: ColorType) {
        if (color == ColorType.ACTION_KEY_ICON) view.setColorFilter(actionIcon, PorterDuff.Mode.SRC_IN)
        else base.setColor(view, color)
    }
}

private class ThemeImageDrawable(private val bitmap: Bitmap, private val background: Int, private val scrim: Boolean = true) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas: Canvas) {
        val scale = max(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        val left = bounds.exactCenterX() - w / 2; val top = bounds.exactCenterY() - h / 2
        val count = canvas.save(); canvas.clipRect(bounds)
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
        if (scrim) {
            paint.color = ColorUtils.setAlphaComponent(background, 175)
            canvas.drawRect(bounds, paint); paint.color = Color.WHITE
        }
        canvas.restoreToCount(count)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}
