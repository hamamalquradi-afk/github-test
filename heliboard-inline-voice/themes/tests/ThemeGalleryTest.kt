// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.themes.*
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class ThemeGalleryTest {
    private val context = Robolectric.setupService(LatinIME::class.java)
    @Before fun clearGallery() {
        context.prefs().edit().remove(ThemeGalleryRepository.SELECTED).remove(ThemeGalleryRepository.SAVED).apply()
    }

    @Test fun bundledCatalogIsCompleteAndUnique() {
        val themes = ThemeCatalog.load(context)
        assertEquals(80, themes.size)
        assertEquals(80, themes.map { it.id }.toSet().size)
        assertEquals(ThemeCatalog.categories.toSet(), themes.map { it.category }.toSet())
        assertTrue(themes.groupingBy { it.category }.eachCount().values.all { it == 8 })
        themes.forEach { it.validate(); assertNull(it.image); assertNull(it.landscapeImage) }
    }

    @Test fun everyPresetAppliesPersistsAndUsesPreviewDefinition() {
        ThemeCatalog.load(context).forEach { theme ->
            ThemeGalleryRepository.apply(context, theme)
            assertEquals(theme.id, context.prefs().getString(ThemeGalleryRepository.SELECTED, null))
            assertEquals(theme, ThemeGalleryRepository.selected(context))
            val applied = KeyboardTheme.getColorsForCurrentTheme(context)
            val preview = ThemeGalleryRepository.colors(context, theme, true)
            assertEquals(theme.style, applied.themeStyle)
            assertEquals(theme.borders, applied.hasKeyBorders)
            ColorType.entries.forEach { assertEquals(preview.get(it), applied.get(it), "${theme.id}/$it") }
            assertEquals(KeyboardTheme.getKeyboardThemeForStyle(theme.style, theme.borders), KeyboardTheme.getKeyboardTheme(context))
        }
    }

    @Test fun presetsNeverRewriteExistingPreferencesOrImportedFiles() {
        val prefs = context.prefs()
        prefs.edit().putString("unrelated_voice_setting", "retained").putString(Settings.PREF_USER_COLORS_PREFIX + "mine", "[]").apply()
        val file = File(DeviceProtectedUtils.getFilesDir(context), "custom_layout_gallery_sentinel")
        file.writeText("user layout must survive")
        val before = prefs.all.toMutableMap().apply { remove(ThemeGalleryRepository.SELECTED) }
        ThemeCatalog.load(context).forEach { ThemeGalleryRepository.apply(context, it) }
        val after = prefs.all.toMutableMap().apply { remove(ThemeGalleryRepository.SELECTED) }
        assertEquals(before, after)
        assertEquals("user layout must survive", file.readText())
        file.delete()
    }

    @Test fun previewTypographyIsIsolatedFromTheAppliedTheme() {
        val themes = ThemeCatalog.load(context)
        ThemeGalleryRepository.apply(context, themes.first { it.category == "light" })
        val before = context.prefs().all.toMap()
        val active = ThemeGalleryRepository.selected(context)
        themes.forEach { theme ->
            val model = ThemePreviewView.buildKeyboard(context, theme, "en")
            val colors = ThemeGalleryRepository.colors(context, theme, true)
            val previewContext = ThemePreviewContext(context, KeyboardTheme.getKeyboardThemeForStyle(theme.style, theme.borders).mStyleId, colors)
            val view = helium314.keyboard.keyboard.KeyboardView(previewContext, null, helium314.keyboard.latin.R.attr.keyboardViewStyle, colors)
            view.setKeyboard(model)
            assertEquals(colors.get(ColorType.KEY_TEXT), view.keyVisualAttribute!!.mTextColor, theme.id)
            assertEquals(colors.get(ColorType.KEY_HINT_TEXT), view.keyVisualAttribute!!.mHintLetterColor, theme.id)
            model.mKeyVisualAttributes?.let { assertEquals(colors.get(ColorType.KEY_TEXT), it.mTextColor, theme.id) }
            view.deallocateMemory()
        }
        assertEquals(before, context.prefs().all)
        assertEquals(active, ThemeGalleryRepository.selected(context))
    }

    @Test fun bundledLabelsMeetNormalTextContrast() {
        ThemeCatalog.load(context).forEach { spec ->
            val colors = ThemeGalleryRepository.colors(context, spec)
            val surfaces = if (spec.borders) listOf(spec.keys, spec.functional) else listOf(spec.background, spec.secondary)
            surfaces.forEach {
                assertTrue(ColorUtils.calculateContrast(colors.get(ColorType.KEY_TEXT), Color.parseColor(it)) >= 4.5, spec.id)
            }
            assertTrue(ColorUtils.calculateContrast(colors.get(ColorType.ACTION_KEY_ICON), colors.get(ColorType.ACTION_KEY_BACKGROUND)) >= 4.5, spec.id)
        }
    }

    @Test fun customCopiesKeepSourceAndReapplyAllFields() {
        val original = ThemeCatalog.load(context).first()
        val custom = ThemeGalleryRepository.save(context, original.copy(label = "#FFEDAA", borders = false), "My saved appearance")
        ThemeGalleryRepository.apply(context, custom)
        ThemeGalleryRepository.apply(context, ThemeCatalog.load(context).last())
        ThemeGalleryRepository.apply(context, ThemeGalleryRepository.saved(context).single())
        assertEquals(custom, ThemeGalleryRepository.selected(context))
        assertEquals(original, ThemeCatalog.load(context).first())
        ThemeGalleryRepository.delete(context, custom)
        assertNull(ThemeGalleryRepository.selected(context))
        assertTrue(ThemeGalleryRepository.saved(context).isEmpty())
    }

    @Test fun corruptPreferencesAndImagesFallBackSafely() {
        context.prefs().edit().putString(ThemeGalleryRepository.SAVED, "broken JSON").putString(ThemeGalleryRepository.SELECTED, "removed_theme").apply()
        assertTrue(ThemeGalleryRepository.saved(context).isEmpty())
        assertNull(ThemeGalleryRepository.selected(context))
        assertNotNull(KeyboardTheme.getColorsForCurrentTheme(context))
        val corrupt = File(DeviceProtectedUtils.getFilesDir(context), "compact_gallery_image_corrupt.webp")
        corrupt.writeText("not an image")
        val source = ThemeCatalog.load(context).first().copy(image = corrupt.name)
        assertNotNull(ThemeGalleryRepository.colors(context, source))
        assertNotNull(ThemeGalleryRepository.colors(context, source.copy(image = "../../bad")))
        corrupt.delete()
    }

    @Test fun nativeAppearanceControlsCanTakeOverWithoutDeletingPresets() {
        val theme = ThemeCatalog.load(context).first()
        ThemeGalleryRepository.apply(context, theme)
        context.prefs().edit().putString(Settings.PREF_THEME_STYLE, "Material").apply()
        assertNull(ThemeGalleryRepository.selected(context))
        assertEquals(80, ThemeCatalog.load(context).size)
    }

    @Test fun defaultsPreserveNonAppearanceSettingsAndSavedCustomizations() {
        val theme = ThemeGalleryRepository.save(context, ThemeCatalog.load(context).first(), "Keep this")
        ThemeGalleryRepository.apply(context, theme)
        context.prefs().edit().putBoolean("voice_test_marker", true).apply()
        val image = Settings.getCustomBackgroundFile(context, false, false)
        image.writeText("preserve original bytes")
        ThemeGalleryRepository.restoreDefault(context)
        assertNull(ThemeGalleryRepository.selected(context))
        assertTrue(context.prefs().getBoolean("voice_test_marker", false))
        assertTrue(ThemeGalleryRepository.saved(context).contains(theme))
        assertEquals("preserve original bytes", image.readText())
        ThemeGalleryRepository.apply(context, theme)
        assertEquals(theme.id, ThemeGalleryRepository.selected(context)?.id)
        image.delete()
    }
}
