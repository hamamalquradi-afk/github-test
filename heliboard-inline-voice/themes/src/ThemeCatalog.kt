// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import android.content.Context
import android.graphics.Color
import helium314.keyboard.keyboard.KeyboardTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One definition is consumed by both the IME and the real KeyboardView previews. */
@Serializable
data class ThemeSpec(
    val id: String,
    val name: String,
    val nameAr: String = name,
    val category: String = "custom",
    val background: String,
    val keys: String,
    val functional: String,
    val label: String,
    val accent: String,
    val secondary: String = background,
    val decoration: String = accent,
    val pattern: String = "solid",
    val style: String = KeyboardTheme.STYLE_ROUNDED,
    val borders: Boolean = true,
    val image: String? = null,
    val landscapeImage: String? = null,
    val allColors: Map<String, Int> = emptyMap(),
) {
    fun displayName(context: Context): String =
        if (context.resources.configuration.localesCompatLanguage() == "ar") nameAr else name

    fun validate(): ThemeSpec {
        require(id.matches(Regex("[a-z0-9_-]{1,80}")))
        require(name.isNotBlank() && nameAr.isNotBlank())
        require(category in ThemeCatalog.categories || category == "custom")
        require(style in KeyboardTheme.STYLES)
        require(pattern in ThemeArtwork.patterns)
        listOf(background, keys, functional, label, accent, secondary, decoration).forEach { Color.parseColor(it) }
        return this
    }
}

@Suppress("DEPRECATION")
private fun android.content.res.Configuration.localesCompatLanguage() =
    if (android.os.Build.VERSION.SDK_INT >= 24) locales[0].language else locale.language

object ThemeCatalog {
    val categories = listOf("dark", "light", "gold", "neon", "gradient", "minimal", "nature", "flowers", "geometry", "code")
    @Volatile private var cached: List<ThemeSpec>? = null
    fun load(context: Context): List<ThemeSpec> = cached ?: synchronized(this) {
        cached ?: Json.decodeFromString<List<ThemeSpec>>(
            context.assets.open("compact_themes.json").bufferedReader().use { it.readText() }
        ).also { themes ->
            require(themes.map { it.id }.toSet().size == themes.size)
            require(themes.map { it.copy(id = "x", name = "x", nameAr = "x", category = "custom") }.toSet().size == themes.size)
            themes.forEach { it.validate(); require(it.image == null && it.landscapeImage == null) }
            cached = themes
        }
    }
}
