#!/usr/bin/env python3
"""Install gallery extensions on the exact source already patched by apply_overlay.py."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
overlay = Path(__file__).resolve().parent
java = root / 'app/src/main/java'

def change(path, old, new):
    target = root / path
    text = target.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f'Gallery source drift: {path}: {old[:70]!r}: {text.count(old)} matches')
    target.write_text(text.replace(old, new, 1))

for source in (overlay / 'src').glob('*.kt'):
    dest = java / 'helium314/keyboard/latin/themes' / source.name
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, dest)
for source in (overlay / 'res').rglob('*.xml'):
    dest = root / 'app/src/main/res' / source.relative_to(overlay / 'res')
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, dest)
shutil.copy2(overlay / 'assets/compact_themes.json', root / 'app/src/main/assets/compact_themes.json')
for source in (overlay / 'tests').glob('*.kt'):
    shutil.copy2(source, root / 'app/src/test/java/helium314/keyboard' / source.name)
for source in (overlay / 'debug').glob('*.kt'):
    dest = root / 'app/src/debug/java/helium314/keyboard/latin/themes' / source.name
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, dest)
shutil.copy2(overlay / 'debug/AndroidManifest.xml', root / 'app/src/debug/AndroidManifest.xml')
with (root / 'app/proguard-rules.pro').open('a') as rules:
    rules.write('\n-keep class helium314.keyboard.latin.themes.ThemeSmokeInstrumentation { *; }\n'
                '-keep class helium314.keyboard.latin.themes.ThemeSmokeActivity { *; }\n'
                '-keepclassmembers class helium314.keyboard.keyboard.KeyboardSwitcher { helium314.keyboard.latin.LatinIME mLatinIME; }\n')

theme = 'app/src/main/java/helium314/keyboard/keyboard/KeyboardTheme.kt'
change(theme, '            val style = prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)\n            val borders = prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)\n',
    '            val gallery = helium314.keyboard.latin.themes.ThemeGalleryRepository.selected(context)\n'
    '            val style = gallery?.style ?: prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)!!\n'
    '            val borders = gallery?.let { it.borders || it.image != null || it.landscapeImage != null }\n'
    '                ?: prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)\n'
    '            return getKeyboardThemeForStyle(style, borders)\n'
    '        }\n\n'
    '        @JvmStatic\n'
    '        fun getKeyboardThemeForStyle(style: String, borders: Boolean): KeyboardTheme {\n')
change(theme, '        fun getColorsForCurrentTheme(context: Context): Colors {\n',
    '        fun getColorsForCurrentTheme(context: Context): Colors {\n'
    '            if (SettingsActivity.forceTheme == null)\n'
    '                helium314.keyboard.latin.themes.ThemeGalleryRepository.selected(context)?.let {\n'
    '                    return helium314.keyboard.latin.themes.ThemeGalleryRepository.colors(context, it)\n'
    '                }\n')

view = 'app/src/main/java/helium314/keyboard/keyboard/KeyboardView.java'
change(view, '    public KeyboardView(final Context context, final AttributeSet attrs, final int defStyle) {\n        super(context, attrs, defStyle);\n        mColors = Settings.getValues().mColors;',
    '    public KeyboardView(final Context context, final AttributeSet attrs, final int defStyle) {\n'
    '        this(context, attrs, defStyle, null);\n'
    '    }\n\n'
    '    /** Isolated renderer colors for gallery previews; normal IME construction is unchanged. */\n'
    '    public KeyboardView(final Context context, final AttributeSet attrs, final int defStyle,\n'
    '            @Nullable final Colors previewColors) {\n'
    '        super(context, attrs, defStyle);\n'
    '        mColors = previewColors != null ? previewColors : Settings.getValues().mColors;')

settings = 'app/src/main/java/helium314/keyboard/latin/settings/Settings.java'
change(settings, '    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {\n',
    '    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {\n'
    '        helium314.keyboard.latin.themes.ThemeGalleryRepository.legacyPreferenceChanged(mContext, key);\n')
file = root / settings
text = file.read_text()
start = text.index('        final boolean landscape =', text.index('@Nullable public static Drawable readUserBackgroundImage'))
end = text.index('\n    public static File getCustomBackgroundFile', start)
text = text[:start] + '        return helium314.keyboard.latin.themes.ThemeGalleryRepository.readLegacyImage(context, night);\n    }\n' + text[end:]
file.write_text(text)

appearance = 'app/src/main/java/helium314/keyboard/settings/screens/AppearanceScreen.kt'
change(appearance, '        R.string.settings_screen_theme,\n', '        R.string.settings_screen_theme,\n        "compact_theme_gallery",\n')
change(appearance, 'fun createAppearanceSettings(context: Context) = listOf(\n',
    'fun createAppearanceSettings(context: Context) = listOf(\n'
    '    Setting(context, "compact_theme_gallery", R.string.gallery_title, R.string.gallery_description) { setting ->\n'
    '        Preference(name = setting.title, description = stringResource(R.string.gallery_description),\n'
    '            onClick = { helium314.keyboard.settings.SettingsDestination.navigateTo(helium314.keyboard.settings.SettingsDestination.Themes) })\n'
    '    },\n')
nav = 'app/src/main/java/helium314/keyboard/settings/SettingsNavHost.kt'
change(nav, '        composable(SettingsDestination.Appearance) {\n',
    '        composable(SettingsDestination.Themes) {\n'
    '            helium314.keyboard.latin.themes.ThemeGalleryScreen(onClickBack = ::goBack)\n'
    '        }\n'
    '        composable(SettingsDestination.Appearance) {\n')
change(nav, '    const val Appearance = "appearance"', '    const val Themes = "themes"\n    const val Appearance = "appearance"')

# Also harden the existing image picker: invalid input must not overwrite a good image,
# and selecting an image there intentionally returns control to existing Appearance settings.
image = 'app/src/main/java/helium314/keyboard/settings/preferences/BackgroundImagePreference.kt'
change(image, '                getFile().delete()\n',
    '                getFile().delete()\n                helium314.keyboard.latin.themes.ThemeGalleryRepository.useOriginal(ctx)\n')
file = root / image
text = file.read_text()
start = text.index('    val imageFile =', text.index('private fun setBackgroundImage'))
text = text[:start] + '''    val imageFile = Settings.getCustomBackgroundFile(ctx, isNight, isLandscape)
    val imported = helium314.keyboard.latin.themes.ThemeGalleryRepository.importImage(ctx, uri) ?: return false
    val source = java.io.File(helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(ctx), imported)
    try {
        if (!source.renameTo(imageFile)) return false
        helium314.keyboard.latin.themes.ThemeGalleryRepository.useOriginal(ctx)
        Settings.clearCachedBackgroundImages()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        return true
    } finally {
        source.delete()
    }
}
'''
file.write_text(text)

# Increment only this experiment's update version; retain its application ID and providers.
change('app/build.gradle.kts', 'versionCode = 4300', 'versionCode = 4301')
print('Installed offline theme gallery, native renderer previews and bounded image handling')
