#!/usr/bin/env python3
"""Apply Phase 01 text identity after the existing Compact/theme overlays.

Keep package IDs, signing, preference keys and backup naming untouched.
Icon master follows the user-approved tilted Nova Pulse design.
"""
from pathlib import Path
import sys
import shutil
from xml.sax.saxutils import escape

root = Path(sys.argv[1]).resolve()
for variant in ('debug', 'themes'):
    shutil.copytree(Path(__file__).parent / 'res', root / f'app/src/{variant}/res', dirs_exist_ok=True)
    for locale, name, slogan, settings in (
        ('values', 'KEYNOVA SMART', 'TYPE • SPEAK • CREATE', 'KEYNOVA SMART Settings'),
        ('values-ar', 'كينوفا سمارت', 'اكتب • تحدث • أبدع', 'إعدادات كينوفا سمارت'),
    ):
        folder = root / f'app/src/{variant}/res/{locale}'
        folder.mkdir(parents=True, exist_ok=True)
        # Replace the overlay-owned resource, not upstream attribution or backup names.
        (folder / 'compact_ime_name.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            f'    <string name="compact_ime_name">{escape(name)}</string>\n'
            '</resources>\n', encoding='utf-8')
        strings = {
            'keynova_tagline': slogan,
            'setup_welcome_additional_description': slogan,
            'ime_settings': settings,
        }
        if locale == 'values':
            # Upstream debug already declares ime_settings in this source set.
            # Update that declaration rather than introducing a duplicate resource.
            existing = folder / 'strings.xml'
            source = existing.read_text(encoding='utf-8')
            old = '<string name="ime_settings" translatable="false">HeliBoard debug Settings</string>'
            assert source.count(old) == 1, 'Pinned debug settings resource drift'
            existing.write_text(source.replace(old,
                f'<string name="ime_settings">{escape(settings)}</string>'), encoding='utf-8')
            del strings['ime_settings']
        (folder / 'keynova_branding.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + ''.join(
                f'    <string name="{key}">{escape(value)}</string>\n'
                for key, value in strings.items()) + '</resources>\n', encoding='utf-8')

# Scope About branding to the visible title/subtitle: english_ime_name remains
# unchanged because BackupRestorePreference uses it to identify backup archives.
about = root / 'app/src/main/java/helium314/keyboard/settings/screens/AboutScreen.kt'
source = about.read_text(encoding='utf-8')
old = 'Setting(context, SettingsWithoutKey.APP, R.string.english_ime_name, R.string.app_slogan)'
assert source.count(old) == 1, 'Pinned About screen drift'
about.write_text(source.replace(old,
    'Setting(context, SettingsWithoutKey.APP, R.string.compact_ime_name, R.string.keynova_tagline)'), encoding='utf-8')
print('Applied KEYNOVA SMART bilingual identity and approved Nova Pulse icon assets')

# Use the approved raster directly: Compose painterResource supports PNGs, while
# Android bitmap/layer-list XML is intended for launcher Drawable loading.
welcome = root / 'app/src/main/java/helium314/keyboard/settings/WelcomeWizard.kt'
source = welcome.read_text(encoding='utf-8')
old = 'Image(painterResource(R.drawable.setup_welcome_image), null)'
assert source.count(old) == 1, 'Pinned welcome artwork drift'
welcome.write_text(source.replace(old,
    'Image(painterResource(R.drawable.keynova_master), null, modifier = Modifier.size(200.dp))'), encoding='utf-8')
shutil.copy2(Path(__file__).parent / 'BrandingTest.kt',
             root / 'app/src/test/java/helium314/keyboard/BrandingTest.kt')
