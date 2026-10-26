"""Host regressions using actual POSIX commands and ADB's documented transport contracts."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).with_name('prepare-emulator-fixture.py')
PACKAGES = ('helium314.keyboard.compactdebug', 'helium314.keyboard.compactthemesdebug')
FAKE_ADB = r'''#!/usr/bin/env python3
import os, shlex, subprocess, sys
from pathlib import Path
args = sys.argv[1:]
if args[0] == 'features':
    print('shell_v2' if os.environ.get('FAIL') != 'legacy' else 'cmd'); sys.exit(0)
if args[:2] == ['shell', 'getprop']:
    print('1' if os.environ.get('FAIL') != 'physical' else '0'); sys.exit(0)
if args[:3] == ['shell', 'am', 'force-stop']: sys.exit(0)
legacy = args[0] == 'exec-out'
if not legacy:
    assert args[:2] == ['shell', '-T'], args
    parts = shlex.split(args[2])
else: parts = args[1:]
assert parts[0] == 'run-as', parts
if os.environ.get('FAIL') == 'permission':
    print('run-as: permission denied', file=sys.stderr); sys.exit(1)
pkg = parts[1]
assert pkg in ('helium314.keyboard.compactdebug', 'helium314.keyboard.compactthemesdebug')
base = Path(os.environ['FIXTURE_ROOT']) / pkg
command = [p.replace('/data/user_de/0/' + pkg, str(base)) for p in parts[2:]]
payload = sys.stdin.buffer.read()
if os.environ.get('FAIL') == 'truncated' and any('cat >' in arg for arg in command): payload = payload[:7]
result = subprocess.run(command, input=payload, stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT if legacy else subprocess.PIPE)
if os.environ.get('FAIL') == 'banner': sys.stdout.buffer.write(b'unexpected shell output\n')
sys.stdout.buffer.write(result.stdout)
if not legacy: sys.stderr.buffer.write(result.stderr)
# exec-out returns transport success even if the remote command failed.
sys.exit(0 if legacy else result.returncode)
'''


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        adb = self.base / 'adb'
        adb.write_text(FAKE_ADB)
        adb.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.base) + os.pathsep + os.environ['PATH'],
                        FIXTURE_ROOT=str(self.base))
        for pkg in PACKAGES:
            (self.base / pkg).mkdir()

    def path(self, pkg=PACKAGES[1]):
        return self.base / pkg / 'shared_prefs' / (pkg + '_preferences.xml')

    def seed(self, data, pkg=PACKAGES[1]):
        p = self.path(pkg)
        p.parent.mkdir(exist_ok=True)
        p.write_bytes(data)
        return p

    def run_fixture(self, pkg=PACKAGES[1], failure=None):
        env = dict(self.env)
        if failure: env['FAIL'] = failure
        return subprocess.run([sys.executable, str(SCRIPT), pkg], env=env, capture_output=True)

    def test_legacy_probe_reproduces_confirmed_false_presence(self):
        pkg = PACKAGES[1]
        path = f'/data/user_de/0/{pkg}/shared_prefs/{pkg}_preferences.xml'
        probe = subprocess.run(['adb', 'exec-out', 'run-as', pkg, 'test', '-f', path], env=self.env, capture_output=True)
        self.assertEqual(probe.returncode, 0)  # Old code enters its XML-read branch.
        read = subprocess.run(['adb', 'exec-out', 'run-as', pkg, 'cat', path], env=self.env, capture_output=True)
        self.assertEqual(read.returncode, 0)
        self.assertIn(b'No such file', read.stdout)
        with self.assertRaises(ET.ParseError): ET.fromstring(read.stdout)

    def test_fresh_variants_missing_directory_and_file(self):
        for pkg in PACKAGES:
            for directory_exists in (False, True):
                with self.subTest(pkg=pkg, directory=directory_exists):
                    p = self.path(pkg)
                    if p.exists(): p.unlink()
                    if directory_exists: p.parent.mkdir(exist_ok=True)
                    result = self.run_fixture(pkg)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    root = ET.parse(p).getroot()
                    self.assertEqual(root.tag, 'map')
                    self.assertIn('MAIN:arabic', root.find("string[@name='selected_subtype']").text)

    def test_preserves_valid_settings_both_packages_and_is_repeatable(self):
        for pkg in PACKAGES:
            with self.subTest(pkg=pkg):
                p = self.seed(b'<map><string name="compact_gallery_selected">gold_royal_gold</string><boolean name="voice" value="true"/><set name="custom"><string>mine</string></set></map>', pkg)
                for _ in range(2):
                    result = self.run_fixture(pkg)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    root = ET.parse(p).getroot()
                    self.assertEqual(root.find("string[@name='compact_gallery_selected']").text, 'gold_royal_gold')
                    self.assertEqual(root.find("boolean[@name='voice']").get('value'), 'true')
                    self.assertEqual(root.find('set/string').text, 'mine')
                    self.assertEqual(len(root.findall("string[@name='selected_subtype']")), 1)

    def test_existing_invalid_content_is_never_overwritten(self):
        for data in (b'', b'  \n', b'<map>', b'not XML', b'cat: missing: No such file or directory\n', b'<wrong/>'):
            with self.subTest(data=data):
                p = self.seed(data)
                self.assertNotEqual(self.run_fixture().returncode, 0)
                self.assertEqual(p.read_bytes(), data)

    def test_access_failure_preserves_original(self):
        p = self.seed(b'<map><int name="keep" value="7"/></map>')
        before = p.read_bytes()
        result = self.run_fixture(failure='permission')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'permission denied', result.stderr)
        self.assertEqual(p.read_bytes(), before)

    def test_unexpected_shell_output_cannot_replace_valid_data(self):
        p = self.seed(b'<map><int name="keep" value="7"/></map>')
        before = p.read_bytes()
        self.assertNotEqual(self.run_fixture(failure='banner').returncode, 0)
        self.assertEqual(p.read_bytes(), before)

    def test_non_regular_preferences_are_refused(self):
        p = self.path()
        p.mkdir(parents=True)
        self.assertNotEqual(self.run_fixture().returncode, 0)
        self.assertTrue(p.is_dir())

    def test_linked_preferences_are_not_followed(self):
        p = self.path()
        p.parent.mkdir()
        original = self.base / 'original.xml'
        original.write_bytes(b'<map/>')
        p.symlink_to(original)
        self.assertNotEqual(self.run_fixture().returncode, 0)
        self.assertTrue(p.is_symlink())
        self.assertEqual(original.read_bytes(), b'<map/>')

    def test_pending_backup_is_not_discarded(self):
        p = self.seed(b'<map/>')
        backup = Path(str(p) + '.bak')
        backup.write_bytes(b'<map><int name="keep" value="8"/></map>')
        before = backup.read_bytes()
        self.assertNotEqual(self.run_fixture().returncode, 0)
        self.assertEqual(p.read_bytes(), b'<map/>')
        self.assertEqual(backup.read_bytes(), before)

    def test_truncated_upload_keeps_valid_original(self):
        p = self.seed(b'<map><int name="keep" value="7"/></map>')
        before = p.read_bytes()
        result = self.run_fixture(failure='truncated')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'upload did not match', result.stderr)
        self.assertEqual(p.read_bytes(), before)
        self.assertEqual(list(p.parent.glob('*.fixture-*')), [])

    def test_wrong_storage_path_is_not_treated_as_missing_preferences(self):
        (self.base / PACKAGES[1]).rmdir()
        result = self.run_fixture()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.path().exists())

    def test_physical_device_and_legacy_transport_are_refused(self):
        for mode in ('physical', 'legacy'):
            with self.subTest(mode=mode):
                self.assertNotEqual(self.run_fixture(failure=mode).returncode, 0)
                self.assertFalse(self.path().exists())


if __name__ == '__main__':
    unittest.main()
