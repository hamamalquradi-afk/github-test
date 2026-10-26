#!/usr/bin/env python3
"""Prepare only emulator subtypes; preserve all unrelated preference data.

Use shell-v2 without a PTY: exec-out neither transports remote exit status nor
separates stderr, so it cannot implement a file-existence or XML-read check.
"""
import shlex
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

PACKAGES = ('helium314.keyboard.compactdebug', 'helium314.keyboard.compactthemesdebug')
MISSING = 44  # Explicit remote status, not inferred from cat's diagnostic text.


def adb(*args, **kwargs):
    return subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)


def remote(package, *args, **kwargs):
    command = shlex.join(('run-as', package, *args))
    result = adb('shell', '-T', command, **kwargs)
    if result.stderr.strip():
        raise RuntimeError(f'ADB/run-as diagnostic (status {result.returncode}): ' +
                           result.stderr.decode(errors='replace').strip())
    return result


def checked(package, *args, **kwargs):
    result = remote(package, *args, **kwargs)
    if result.returncode:
        raise RuntimeError(f'Remote command failed (status {result.returncode})')
    return result.stdout


def read_preferences(package, path):
    directory = path.rsplit('/', 1)[0]
    base = directory.rsplit('/', 1)[0]
    # Distinguish missing entries from inaccessible/wrong storage paths. Never
    # bypass SharedPreferences' backup recovery by replacing a pending .bak file.
    script = f'''
base={shlex.quote(base)}
dir={shlex.quote(directory)}
file={shlex.quote(path)}
[ -d "$base" ] && [ -r "$base" ] && [ -x "$base" ] || exit 2
if [ ! -e "$dir" ] && [ ! -L "$dir" ]; then exit {MISSING}; fi
[ -d "$dir" ] && [ -r "$dir" ] && [ -x "$dir" ] && [ ! -L "$dir" ] || exit 2
if [ -e "$file.bak" ] || [ -L "$file.bak" ]; then exit 3; fi
if [ ! -e "$file" ] && [ ! -L "$file" ]; then exit {MISSING}; fi
[ -f "$file" ] && [ ! -L "$file" ] || exit 2
cat "$file"
'''
    result = remote(package, 'sh', '-c', script)
    if result.returncode == MISSING and not result.stdout:
        return None
    if result.returncode:
        raise RuntimeError(f'Cannot read {package} preferences (status {result.returncode}); '
                           'check storage/access or pending .bak recovery')
    return result.stdout


def prepare(package):
    if package not in PACKAGES:
        raise RuntimeError('Fixture is restricted to the two experimental test packages')
    emulator = [adb('shell', 'getprop', prop, check=True).stdout.strip()
                for prop in ('ro.kernel.qemu', 'ro.boot.qemu')]
    if b'1' not in emulator:
        raise RuntimeError('Fixture must only be used on an Android emulator')
    if b'shell_v2' not in adb('features', check=True).stdout.strip().split(b','):
        raise RuntimeError('Fixture requires ADB shell-v2 remote exit status and separate stderr')
    adb('shell', 'am', 'force-stop', package, check=True)
    directory = f'/data/user_de/0/{package}/shared_prefs'
    path = f'{directory}/{package}_preferences.xml'
    original = read_preferences(package, path)
    if original is None:
        root = ET.Element('map')
    else:
        if not original.strip():
            raise RuntimeError(f'Existing preferences are empty for {package}; refusing to overwrite')
        try:
            root = ET.fromstring(original)
        except ET.ParseError as exc:
            raise RuntimeError(f'Existing preferences are not valid XML for {package}: {exc}; '
                               'refusing to overwrite') from exc
        if root.tag != 'map':
            raise RuntimeError(f'Unexpected preferences root for {package}: {root.tag}')
    for key, value in (
        ('enabled_subtypes', 'ar§KeyboardLayoutSet=MAIN:arabic;en-US§KeyboardLayoutSet=MAIN:qwerty'),
        ('selected_subtype', 'ar§KeyboardLayoutSet=MAIN:arabic'),
    ):
        for old in list(root):
            if old.get('name') == key:
                root.remove(old)
        ET.SubElement(root, 'string', name=key).text = value
    payload = ET.tostring(root, encoding='utf-8', xml_declaration=True)
    # Stage and verify a complete upload before atomic replacement. A failed or
    # truncated transfer must never truncate the existing user preferences.
    temporary = path + '.fixture-' + uuid.uuid4().hex
    checked(package, 'mkdir', '-p', directory)
    try:
        checked(package, 'sh', '-c', f'umask 077; cat > {shlex.quote(temporary)}', input=payload)
        if checked(package, 'cat', temporary) != payload:
            raise RuntimeError('Preference upload did not match; original file retained')
        if read_preferences(package, path) != original:
            raise RuntimeError('Preferences changed during preparation; refusing to replace them')
        checked(package, 'mv', temporary, path)
    finally:
        checked(package, 'rm', '-f', temporary)  # Only this invocation's staging file.
    print('Prepared Arabic/English emulator fixture; appearance preferences retained')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise RuntimeError('Usage: prepare-emulator-fixture.py <experimental-package>')
        prepare(sys.argv[1])
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        raise SystemExit(str(exc)) from exc
