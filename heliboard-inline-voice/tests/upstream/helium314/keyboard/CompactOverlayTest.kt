// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.ComponentName
import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardBuilder
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.UniqueKeysCache
import helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class CompactOverlayTest {
    private val ime = Robolectric.setupService(LatinIME::class.java)

    @Test fun applicationAndServiceHaveReadableLabelResources() {
        val ctx = RuntimeEnvironment.getApplication()
        assertTrue(ctx.applicationInfo.labelRes != 0, "WelcomeWizard requires a string resource")
        assertEquals("HeliBoard Compact Fix", ctx.getString(ctx.applicationInfo.labelRes))
        val service = ctx.packageManager.getServiceInfo(ComponentName(ctx, LatinIME::class.java), 0)
        assertTrue(service.labelRes != 0)
        assertEquals("HeliBoard Compact Fix", ctx.getString(service.labelRes))
    }

    @Test fun packagedJsonIsDecodedByTheActualLayoutParser() {
        for ((name, sizes) in listOf("arabic" to listOf(16, 11, 11, 10), "qwerty" to listOf(16, 10, 9, 7))) {
            val json = ime.assets.open("layouts/main/$name.txt").bufferedReader().readText()
            assertEquals(sizes, LayoutParser.parseJsonString(json).map { it.size })
        }
        val json = ime.assets.open("layouts/functional/functional_keys.json").bufferedReader().readText()
        assertEquals(listOf(2, 10), LayoutParser.parseJsonString(json).map { it.size })
    }

    @Test fun bilingualGeometryModifiersAndProgrammingPopups() {
        for ((locale, layout) in listOf(Locale.US to "qwerty", Locale.forLanguageTag("ar") to "arabic")) {
            for (element in listOf(KeyboardElement.ALPHABET, KeyboardElement.ALPHABET_MANUAL_SHIFTED)) {
                val rows = buildRows(locale, layout, element)
                assertEquals(5, rows.size, "$layout/$element row count")
                assertEquals(16, rows[0].size)
                val bottom = rows.last().map { it.mCode }
                for (code in listOf(KeyCode.CTRL, KeyCode.ALT, KeyCode.SHIFT, KeyCode.TAB, KeyCode.ESCAPE,
                        KeyCode.LANGUAGE_SWITCH, ' '.code, KeyCode.SYMBOL_ALPHA)) {
                    assertTrue(code in bottom, "$layout missing bottom control $code")
                }
                assertTrue(rows.last().first().mHeight < rows[2].first().mHeight)
                assertTrue(rows.flatten().all { it.mHeight > 0f && it.mWidth > 0f })
                val codes = rows.first().flatMap { key -> listOf(key.mCode) + (key.mPopupKeys?.map { it.mCode } ?: emptyList()) }.toSet()
                val asciiSymbols = (33..126).filter { !it.toChar().isLetterOrDigit() }.toSet()
                assertTrue(codes.containsAll(asciiSymbols), "$layout missing symbols ${asciiSymbols - codes}")
            }
        }
    }

    @Test fun arabicHamzasAndDiacriticsProduceRealCharacters() {
        val rows = buildRows(Locale.forLanguageTag("ar"), "arabic", KeyboardElement.ALPHABET)
        val codes = rows.flatten().flatMap { key -> listOf(key.mCode) + (key.mPopupKeys?.map { it.mCode } ?: emptyList()) }.toSet()
        for (char in "أإآءؤئةًٌٍَُِّْ") assertTrue(char.code in codes, "Missing Arabic $char")
        assertTrue('◌'.code !in codes, "Dotted-circle popup labels must not be inserted")
    }

    private fun buildRows(locale: Locale, name: String, element: KeyboardElement): List<List<helium314.keyboard.keyboard.Key.KeyParams>> {
        val subtype = SubtypeUtilsAdditional.createEmojiCapableAdditionalSubtype(locale, name, true)
        val p = KeyboardLayoutSet.Params().apply {
            editorInfo = EditorInfo()
            this.subtype = RichInputMethodSubtype.get(subtype)
            keyboardWidth = 500
            keyboardHeight = 300
        }
        val builder = KeyboardBuilder(ime, KeyboardParams(UniqueKeysCache.NO_CACHE))
        builder.load(KeyboardId(element, p))
        builder.build()
        val field = KeyboardBuilder::class.java.getDeclaredField("keysInRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(builder) as List<List<helium314.keyboard.keyboard.Key.KeyParams>>
    }
}
