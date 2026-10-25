// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import helium314.keyboard.latin.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrandingTest {
    @Test fun identityResolvesInEnglishAndArabic() {
        val app = RuntimeEnvironment.getApplication()
        for ((language, name, slogan) in listOf(
            Triple("en", "KEYNOVA SMART", "TYPE • SPEAK • CREATE"),
            Triple("ar", "كينوفا سمارت", "اكتب • تحدث • أبدع")
        )) {
            val config = Configuration(app.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language))
            val context = app.createConfigurationContext(config)
            assertEquals(name, context.getString(R.string.compact_ime_name))
            assertEquals(slogan, context.getString(R.string.keynova_tagline))
            assertEquals(slogan, context.getString(R.string.setup_welcome_additional_description))
            assertTrue(context.getString(R.string.setup_welcome_title, name).contains(name))
            assertTrue(context.getString(R.string.ime_settings).contains(name))
        }
    }

    @Test fun launcherResourcesLoadAndBackupIdentityIsPreserved() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(app.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
        assertNotNull(app.getDrawable(R.drawable.keynova_monochrome))
        assertNotNull(app.getDrawable(R.drawable.keynova_master))
        assertEquals("HeliBoard debug", app.getString(R.string.english_ime_name))
        assertEquals(R.string.compact_ime_name, app.applicationInfo.labelRes)
    }
}
