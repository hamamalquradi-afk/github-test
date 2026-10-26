"""Run the actual workflow stages as independent processes with CR/LF secrets."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

BRANDING = Path(__file__).resolve().parents[1]
REPO = BRANDING.parents[1]
sys.path.insert(0, str(BRANDING))
from signing_credentials import VerificationError, credentials, sanitized


def workflow_script(name):
    text = (REPO / '.github/workflows/heliboard-inline-voice.yml').read_text()
    block = text.split('      - name: ' + name + '\n', 1)[1].split('      - name:', 1)[0]
    lines = block.split('        run: |\n', 1)[1].splitlines()
    return '\n'.join(line[10:] if line.startswith('          ') else '' for line in lines)


class CredentialTests(unittest.TestCase):
    def test_missing_credentials_do_not_fall_back_to_debug_signing(self):
        for values in [{}, {'KEYNOVA_SIGNING_PASSWORD': 'x'}, {'KEYNOVA_SIGNING_KEY_ALIAS': 'custom'}]:
            with self.subTest(values=values), patch.dict(os.environ, values, clear=True), self.assertRaises(VerificationError):
                credentials()

    def test_normalization_is_shared_and_does_not_modify_original_environment(self):
        for ending in ['', '\n', '\r\n']:
            values = {'KEYNOVA_SIGNING_PASSWORD': 'long-test-password' + ending, 'KEYNOVA_SIGNING_KEY_ALIAS': 'custom-key' + ending}
            with self.subTest(ending=repr(ending)), patch.dict(os.environ, values, clear=True):
                env, alias = credentials()
                self.assertEqual('long-test-password', env['KEYNOVA_SIGNING_PASSWORD'])
                self.assertEqual('custom-key', alias)
                self.assertEqual(values['KEYNOVA_SIGNING_PASSWORD'], os.environ['KEYNOVA_SIGNING_PASSWORD'])

    def test_diagnostics_redact_raw_and_normalized_secrets(self):
        values = {'KEYNOVA_SIGNING_PASSWORD': 'never-print-this-password\r\n', 'KEYNOVA_SIGNING_KEY_ALIAS': 'private-alias\n'}
        with patch.dict(os.environ, values):
            self.assertNotIn('never-print', sanitized('never-print-this-password and private-alias'))
            self.assertNotIn('private-alias', sanitized('never-print-this-password and private-alias'))

    def test_command_wrapper_keeps_real_exit_status_and_redacts_output(self):
        values = dict(os.environ, KEYNOVA_SIGNING_PASSWORD='test-password-must-be-redacted\n', KEYNOVA_SIGNING_KEY_ALIAS='fixture-alias\r\n')
        result = subprocess.run([sys.executable, str(BRANDING / 'signing_credentials.py'), sys.executable, '-c',
                                 'import os,sys; print(os.environ["KEYNOVA_SIGNING_PASSWORD"]); sys.exit(37)'], env=values, capture_output=True, text=True)
        self.assertEqual(37, result.returncode)
        self.assertIn('[REDACTED]', result.stdout)
        self.assertNotIn('test-password', result.stdout + result.stderr)


@unittest.skipUnless(shutil.which('keytool'), 'JDK keytool unavailable')
class WorkflowBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='keynova-workflow-credentials-', dir='/tmp')
        cls.root = Path(cls.temp.name)
        cls.password = secrets.token_urlsafe(36)
        cls.env = dict(os.environ, KEYNOVA_SIGNING_PASSWORD=cls.password, KEYNOVA_SIGNING_KEY_ALIAS='keynova-smart')
        cls.key = cls.root / 'original.p12'
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(cls.key), '-storetype', 'PKCS12', '-alias', 'keynova-smart',
                        '-storepass:env', 'KEYNOVA_SIGNING_PASSWORD', '-keypass:env', 'KEYNOVA_SIGNING_PASSWORD',
                        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', 'CN=Disposable Test Fixture'], env=cls.env, capture_output=True, check=True)
        der = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(cls.key), '-alias', 'keynova-smart',
                                       '-storepass:env', 'KEYNOVA_SIGNING_PASSWORD'], env=cls.env)
        cls.cert = hashlib.sha256(der).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def fixture(self, name):
        root = self.root / name; root.mkdir()
        # Reports must stay within this test's disposable folder, not the source.
        (root / 'heliboard-inline-voice/branding').mkdir(parents=True)
        for path in BRANDING.glob('*.py'):
            (root / 'heliboard-inline-voice/branding' / path.name).symlink_to(path)
        tools = root / 'sdk/build-tools/37.0.0'; tools.mkdir(parents=True)
        signer = tools / 'apksigner'
        signer.write_text('#!/usr/bin/env python3\nprint(' + repr('Verified using v1 scheme (JAR signing): true\nVerified using v2 scheme (APK Signature Scheme v2): true\nNumber of signers: 1\nV2 Signer: certificate SHA-256 digest: ' + self.cert) + ')\n')
        aapt = tools / 'aapt'
        aapt.write_text('''#!/usr/bin/env python3
import sys
package = 'helium314.keyboard.compactthemesdebug' if 'themes.apk' in sys.argv[-1] else 'helium314.keyboard.compactdebug'
print("package: name='"+package+"' versionCode='4301' versionName='4.3-beta1'")
print("sdkVersion:'21'\\ntargetSdkVersion:'37'\\napplication-label:'KEYNOVA SMART'\\napplication-label-ar:'كينوفا سمارت'\\napplication-icon-160:'res/icon.png'")
''')
        signer.chmod(0o755); aapt.chmod(0o755)
        for variant in ['debug', 'themes']:
            apk = root / f'heliboard-source/app/build/outputs/apk/{variant}/HeliBoard_4.3-beta1-{variant}.apk'
            apk.parent.mkdir(parents=True); apk.write_bytes(b'fixture bytes; command responses are mocked')
        return root

    def test_restore_and_verify_accept_identical_normalization_across_step_boundaries(self):
        for name, p_end, a_end in [('clean', '', ''), ('password_lf', '\n', ''), ('alias_lf', '', '\n'), ('both_crlf', '\r\n', '\r\n')]:
            with self.subTest(name=name):
                root = self.fixture(name)
                env = dict(self.env, RUNNER_TEMP=str(root / 'runner'), GITHUB_ENV=str(root / 'github-env'),
                           KEYNOVA_SIGNING_KEYSTORE_B64=base64.b64encode(self.key.read_bytes()).decode(),
                           KEYNOVA_EXPECTED_CERT_SHA256=self.cert, ANDROID_HOME=str(root / 'sdk'),
                           KEYNOVA_SIGNING_PASSWORD=self.password + p_end, KEYNOVA_SIGNING_KEY_ALIAS='keynova-smart' + a_end)
                restored = subprocess.run(['bash', '-euo', 'pipefail', '-c', workflow_script('Restore permanent KEYNOVA signing identity from repository secrets')], cwd=root, env=env, capture_output=True, text=True)
                self.assertEqual(0, restored.returncode, restored.stderr)
                env['KEYNOVA_DEBUG_KEYSTORE'] = (root / 'github-env').read_text().strip().split('=', 1)[1]
                before = Path(env['KEYNOVA_DEBUG_KEYSTORE']).read_bytes()
                # Fresh process receives original raw secrets exactly as Actions does.
                verified = subprocess.run(['bash', '-euo', 'pipefail', '-c', workflow_script('Verify APK signature and record checksum')], cwd=root, env=env, capture_output=True, text=True)
                self.assertEqual(0, verified.returncode, verified.stderr)
                self.assertEqual(before, Path(env['KEYNOVA_DEBUG_KEYSTORE']).read_bytes())
                report = json.loads((root / 'heliboard-inline-voice/test-results/signing/verified.json').read_text())
                self.assertEqual(2, len(report['apks']))
                self.assertEqual(self.cert, report['expected_certificate_sha256'])
                self.assertNotIn(self.password, verified.stdout + verified.stderr)
