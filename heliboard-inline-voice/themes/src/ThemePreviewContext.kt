// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import android.content.Context
import android.view.ContextThemeWrapper
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings

/** Carries preview colors through typography parsing as well as background/icon rendering. */
class ThemePreviewContext(context: Context, style: Int, val previewColors: Colors) : ContextThemeWrapper(context, style) {
    companion object {
        @JvmStatic fun colorsFor(context: Context): Colors =
            (context as? ThemePreviewContext)?.previewColors ?: Settings.getValues().mColors
    }
}
