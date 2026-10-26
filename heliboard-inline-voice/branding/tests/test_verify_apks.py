"""Security regressions for certificate parsing, integrity and both APK variants."""
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

BRANDING = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BRANDING))
import verify_apks as verify
import prepare_signing_key as prepare

A, B = 'a' * 64, 'b' * 64


def output(label='V2 Signer:', digest=A, v3=False):
    return ('Verifies\nVerified using v1 scheme (JAR signing): true\n'
            'Verified using v2 scheme (APK Signature Scheme v2): true\n'
            f'Verified using v3 scheme (APK Signature Scheme v3): {str(v3).lower()}\n'
            'Verified using v4 scheme (APK Signature Scheme v4): false\n'
            'Number of signers: 1\n' + label + ' certificate SHA-256 digest: ' + digest + '\n')


def badging(package):
    return (f"package: name='{package}' versionCode='4301' versionName='4.3-beta1'\n"
            "sdkVersion:'21'\ntargetSdkVersion:'37'\napplication-label:'KEYNOVA SMART'\n"
            "application-label-ar:'كينوفا سمارت'\napplication-icon-160:'res/icon.png'\n")


class CertificateTests(unittest.TestCase):
    def test_unversioned_and_all_supported_versioned_labels(self):
        for label in ['Signer #1', 'Signer #1:', 'Signer:', 'V1 Signer:', 'V2 Signer:',
                      'V3 Signer:', 'V3.1 Signer:', 'V3.2 Signer #1:', 'V2 Signer #1']:
            with self.subTest(label=label):
                self.assertEqual(A, verify.certificate_from_output(output(label)))

    def test_normalizes_complete_case_and_colon_fingerprints(self):
        self.assertEqual(A, verify.certificate_from_output(output(digest=A.upper())))
        colon = ':'.join([A[i:i+2].upper() for i in range(0, 64, 2)])
        self.assertEqual(A, verify.certificate_from_output(output(digest=colon)))

    def test_duplicate_scheme_references_are_one_identity(self):
        text = output() + output('V1 Signer #1') + output('V3 Signer:')
        self.assertEqual(A, verify.certificate_from_output(text))

    def test_missing_metadata_and_public_key_digest_are_rejected(self):
        for text in ['', 'Verifies', 'Signer #1 public key SHA-256 digest: ' + A]:
            with self.subTest(text=text), self.assertRaises(verify.VerificationError):
                verify.certificate_from_output(text)

    def test_invalid_or_truncated_digest_is_never_ignored(self):
        for value in ['', 'a' * 63, 'a' * 65, 'z' * 64, A + ' garbage', ':'.join(['aa'] * 31)]:
            with self.subTest(value=value), self.assertRaises(verify.VerificationError):
                verify.certificate_from_output(output() + output(digest=value))

    def test_unknown_labels_are_rejected(self):
        with self.assertRaises(verify.VerificationError):
            verify.certificate_from_output(output('Unexpected Signer:'))

    def test_multiple_distinct_identities_are_rejected(self):
        with self.assertRaises(verify.VerificationError):
            verify.certificate_from_output(output() + output('V3 Signer:', B))

    def test_multiple_logical_signers_are_rejected_even_with_same_fingerprint(self):
        for text in [output().replace('signers: 1', 'signers: 2'), output() + output('Signer #2')]:
            with self.subTest(text=text), self.assertRaises(verify.VerificationError):
                verify.certificate_from_output(text)


class VariantTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='keynova-apks-', dir='/tmp')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for variant in ['debug', 'themes']:
            path = self.root / variant / f'HeliBoard_4.3-beta1-{variant}.apk'
            path.parent.mkdir(); path.write_bytes(('test bytes ' + variant).encode())
        self.executed = []
        self.failure = None
        self.cert = {'debug': A, 'themes': A}

    def command(self, args, **kwargs):
        self.executed.append(args)
        variant = 'themes' if 'themes.apk' in args[-1] else 'debug'
        if self.failure == (variant, args[0]):
            return subprocess.CompletedProcess(args, 7, output(), 'verification failed')
        package = 'helium314.keyboard.compactthemesdebug' if variant == 'themes' else 'helium314.keyboard.compactdebug'
        return subprocess.CompletedProcess(args, 0, badging(package) if args[0] == 'aapt' else output(digest=self.cert[variant]), '')

    def verify(self, **kwargs):
        with patch.object(verify, 'keystore_certificate', return_value=A), patch('subprocess.run', side_effect=self.command):
            return verify.verify_variants(self.root, self.root / 'key', 'apksigner', 'aapt', **kwargs)

    def test_both_variants_match_selected_identity_and_record_real_file_hashes(self):
        report = self.verify()
        self.assertEqual(['debug', 'themes'], [a['variant'] for a in report['apks']])
        for a in report['apks']:
            self.assertEqual(A, a['certificate_sha256'])
            self.assertEqual(hashlib.sha256(Path(a['file']).read_bytes()).hexdigest(), a['sha256'])
            self.assertTrue(Path(a['file'] + '.sha256').is_file())
        self.verify(check_checksums=True)

    def test_each_variant_is_independently_checked_for_mismatch(self):
        for variant in ['debug', 'themes']:
            self.cert = {'debug': A, 'themes': A}; self.cert[variant] = B
            with self.subTest(variant=variant), self.assertRaisesRegex(verify.VerificationError, 'certificate mismatch'):
                self.verify()
        self.assertEqual(4, sum(c[0] == 'apksigner' for c in self.executed))
        self.assertFalse(list(self.root.rglob('*.sha256')))

    def test_nonzero_verify_status_is_rejected_despite_good_metadata(self):
        self.failure = ('debug', 'apksigner')
        with self.assertRaisesRegex(verify.VerificationError, 'apksigner-verify failed'):
            self.verify()
        self.assertTrue(any('themes.apk' in c[-1] for c in self.executed))
        self.assertFalse(list(self.root.rglob('*.sha256')))

    def test_nonzero_package_inspection_status_is_rejected(self):
        self.failure = ('themes', 'aapt')
        with self.assertRaisesRegex(verify.VerificationError, 'package-metadata failed'):
            self.verify()

    def test_missing_apk_is_rejected(self):
        next((self.root / 'themes').glob('*.apk')).unlink()
        with self.assertRaisesRegex(verify.VerificationError, 'APK is missing'):
            self.verify()

    def test_wrong_package_version_branding_or_sdk_is_rejected(self):
        valid = badging('helium314.keyboard.compactdebug')
        for before, after in [('compactdebug', 'wrong'), ('4301', '4300'), ('SMART', 'OTHER'), ("sdkVersion:'21'", "sdkVersion:'23'")]:
            with self.subTest(before=before), patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, valid.replace(before, after), '')):
                with self.assertRaises(verify.VerificationError):
                    verify.apk_metadata('aapt', 'apk', 'helium314.keyboard.compactdebug')

    def test_checksum_mismatch_missing_or_wrong_filename_are_rejected(self):
        self.verify()
        sidecar = next((self.root / 'debug').glob('*.sha256'))
        for content in ['b' * 64 + '  wrong.apk', 'a' * 63 + '  wrong.apk', '']:
            sidecar.write_text(content)
            with self.subTest(content=content), self.assertRaises(verify.VerificationError):
                self.verify(check_checksums=True)
        sidecar.unlink()
        with self.assertRaisesRegex(verify.VerificationError, 'checksum file is missing'):
            self.verify(check_checksums=True)

    def test_required_schemes_remain_enforced_and_optional_v3_v4_absence_is_valid(self):
        apk = next((self.root / 'debug').glob('*.apk'))
        for scheme in ['v1', 'v2']:
            text = output().replace(f'Verified using {scheme} scheme', 'Other scheme')
            with self.subTest(scheme=scheme), patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, text, '')):
                with self.assertRaises(verify.VerificationError):
                    verify.verified_certificate('apksigner', apk)
        with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, output(v3=False), '')):
            self.assertEqual(A, verify.verified_certificate('apksigner', apk)[0])
        # v3 can satisfy the modern platform requirement without v2.
        v3_only = output(v3=True).replace('v2 scheme (APK Signature Scheme v2): true', 'v2 scheme (APK Signature Scheme v2): false')
        with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, v3_only, '')):
            self.assertEqual(A, verify.verified_certificate('apksigner', apk)[0])


@unittest.skipUnless(shutil.which('keytool'), 'JDK keytool is unavailable')
class RealKeystoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='keynova-test-keys-', dir='/tmp')
        cls.root = Path(cls.temp.name)
        cls.password_env = 'KEYNOVA_TEST_KEYSTORE_PASSWORD'
        cls.environment = patch.dict(os.environ, {cls.password_env: 'test-only-strong-password-for-keynova-fixtures', 'KEYNOVA_SIGNING_KEY_ALIAS': 'test-keynova'})
        cls.environment.start()
        cls.keys = [cls.root / 'one.keystore', cls.root / 'two.keystore']
        for key in cls.keys:
            subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storepass:env', cls.password_env,
                            '-keypass:env', cls.password_env, '-alias', 'test-keynova', '-dname', 'CN=Test Only',
                            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1'], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.environment.stop(); cls.temp.cleanup()

    def test_expected_identity_is_derived_from_actual_der_certificate(self):
        raw = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(self.keys[0]),
                                       '-storepass:env', self.password_env, '-alias', 'test-keynova'])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), verify.keystore_certificate(self.keys[0], self.password_env))
        self.assertNotEqual(verify.keystore_certificate(self.keys[0], self.password_env),
                            verify.keystore_certificate(self.keys[1], self.password_env))

    def test_missing_corrupt_keystore_and_wrong_alias_are_rejected(self):
        corrupt = self.root / 'corrupt'; corrupt.write_text('not a keystore')
        for path in [corrupt, self.root / 'missing']:
            with self.subTest(path=path), self.assertRaises(verify.VerificationError):
                verify.keystore_certificate(path, self.password_env)
        with self.assertRaises(verify.VerificationError):
            verify.keystore_certificate(self.keys[0], self.password_env, alias='wrong')

    def test_missing_and_wrong_password_are_rejected(self):
        with patch.dict(os.environ, {self.password_env: ''}), self.assertRaises(verify.VerificationError):
            verify.keystore_certificate(self.keys[0], self.password_env)
        with patch.dict(os.environ, {self.password_env: 'incorrect'}), self.assertRaises(verify.VerificationError):
            verify.keystore_certificate(self.keys[0], self.password_env)

    def test_missing_key_is_never_replaced_with_a_generated_identity(self):
        path = self.root / 'must-not-be-generated'
        with self.assertRaisesRegex(verify.VerificationError, 'automatic key rotation is disabled'):
            prepare.prepare(path, password_env=self.password_env)
        self.assertFalse(path.exists())

    def test_original_backup_can_be_restored_without_overwriting_existing_keys(self):
        path = self.root / 'restored.keystore'
        backup = base64.b64encode(self.keys[0].read_bytes()).decode()
        expected = verify.keystore_certificate(self.keys[0], self.password_env)
        self.assertEqual(expected, prepare.prepare(path, backup, self.password_env))
        original = path.read_bytes()
        self.assertEqual(0o600, path.stat().st_mode & 0o777)
        self.assertEqual(expected, prepare.prepare(path, backup, self.password_env))
        other = base64.b64encode(self.keys[1].read_bytes()).decode()
        with self.assertRaisesRegex(verify.VerificationError, 'different identities'):
            prepare.prepare(path, other, self.password_env)
        self.assertEqual(original, path.read_bytes())
        path.unlink()

    def test_invalid_backup_does_not_publish_a_keystore(self):
        for value in ['not-base64!', base64.b64encode(b'not a key').decode()]:
            path = self.root / 'invalid-restored.keystore'
            with self.subTest(value=value), self.assertRaises(verify.VerificationError):
                prepare.prepare(path, value, self.password_env)
            self.assertFalse(path.exists())


if __name__ == '__main__':
    unittest.main()
