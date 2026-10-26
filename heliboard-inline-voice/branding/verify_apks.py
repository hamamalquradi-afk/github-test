#!/usr/bin/env python3
"""Verify both existing KEYNOVA APKs against the selected keystore, fail closed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from signing_credentials import VerificationError, checked, credentials, sanitized


_CERTIFICATE = re.compile(
    r'(?P<label>(?:V[123](?:\.\d+)?\s+)?Signer(?:\s+#(?P<index>[1-9]\d*))?)'
    r'\s*:?\s+certificate SHA-256 digest:\s*(?P<digest>.*)', re.IGNORECASE)


def fingerprint(value):
    value = value.strip()
    if re.fullmatch(r'[0-9a-fA-F]{64}', value):
        return value.lower()
    if re.fullmatch(r'(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}', value):
        return value.replace(':', '').lower()
    raise VerificationError('Certificate fingerprint must be a complete SHA-256 digest')


def certificate_from_output(output):
    identities = []
    for raw in output.splitlines():
        line = raw.strip()
        if re.match(r'Number of signers:', line, re.IGNORECASE):
            if not re.fullmatch(r'Number of signers:\s*1', line, re.IGNORECASE):
                raise VerificationError('Expected exactly one APK signing identity')
        if 'certificate sha-256 digest' not in line.lower():
            continue  # Public-key digests are not certificate identities.
        record = _CERTIFICATE.fullmatch(line)
        if record is None or record['index'] not in (None, '1'):
            raise VerificationError('Unrecognized or ambiguous certificate signer metadata')
        identities.append(fingerprint(record['digest']))
    if not identities:
        raise VerificationError('APK signing certificate metadata is missing')
    if len(set(identities)) != 1:
        raise VerificationError('Multiple distinct APK signing certificate identities')
    return identities[0]  # Repeated v1/v2/v3 references to one cert are valid.


def keystore_certificate(path, password_env='KEYNOVA_SIGNING_PASSWORD', alias=None, keytool='keytool', diagnostics=None):
    import tempfile
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise VerificationError('Selected signing keystore is missing or is not a regular file')
    env, alias = credentials(password_env, alias)
    # Keep tool status/diagnostics separate from DER hashing. An error stream
    # can never become the expected certificate fingerprint.
    with tempfile.TemporaryDirectory(prefix='keynova-cert-') as temporary:
        certificate = Path(temporary) / 'certificate.der'
        result = subprocess.run([keytool, '-exportcert', '-storetype', 'PKCS12', '-keystore', str(path),
                                 '-alias', alias, '-storepass:env', password_env, '-file', str(certificate)],
                                capture_output=True, text=True, env=env)
        checked(result, 'keytool-export-certificate', diagnostics)
        if not certificate.is_file() or not certificate.stat().st_size:
            raise VerificationError('Selected keystore exported no certificate')
        checked(subprocess.run([keytool, '-printcert', '-file', str(certificate)], capture_output=True, text=True),
                'keytool-validate-certificate', diagnostics)
        return hashlib.sha256(certificate.read_bytes()).hexdigest()


def verified_certificate(apksigner, apk, require_modern_scheme=True, diagnostics=None):
    apk = Path(apk)
    if not apk.is_file():
        raise VerificationError(f'APK is missing: {apk.name}')
    result = subprocess.run([str(apksigner), 'verify', '--verbose', '--print-certs',
                             '--min-sdk-version', '21', str(apk)], capture_output=True, text=True)
    checked(result, apk.stem + '-apksigner-verify', diagnostics)
    schemes = {}
    for line in result.stdout.splitlines():
        record = re.fullmatch(r'Verified using (v[1-4](?:\.\d+)?) scheme(?: \([^\r\n]*\))?: (true|false)', line.strip())
        if record:
            scheme, passed = record.groups()
            if scheme in schemes and schemes[scheme] != (passed == 'true'):
                raise VerificationError('Conflicting APK signature scheme metadata')
            schemes[scheme] = passed == 'true'
    # API 21-23 needs v1. Android 11+ requires v2 OR v3 for targetSdk >= 30.
    # v3/v4 are optional when v1+v2 verifies; absence alone is not corruption.
    if schemes.get('v1') is not True:
        raise VerificationError('Required APK signing scheme v1 is missing for API 21 support')
    if require_modern_scheme and not (schemes.get('v2') or schemes.get('v3')):
        raise VerificationError('Required modern APK signing scheme v2 or v3 is missing')
    return certificate_from_output(result.stdout), schemes


def apk_metadata(aapt, apk, package, diagnostics=None):
    result = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], capture_output=True, text=True)
    checked(result, Path(apk).stem + '-package-metadata', diagnostics)
    text = result.stdout
    metadata = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", text, re.MULTILINE)
    if metadata is None or metadata[1] != package or metadata[2] != '4301':
        raise VerificationError('Unexpected APK application ID or version code')
    for record in ("sdkVersion:'21'", "targetSdkVersion:'37'", "application-label:'KEYNOVA SMART'",
                   "application-label-ar:'كينوفا سمارت'"):
        if record not in text.splitlines():
            raise VerificationError('Missing APK metadata/branding: ' + record)
    if 'application-icon-' not in text:
        raise VerificationError('APK launcher icon metadata is missing')
    return {'package': package, 'version_code': 4301, 'min_sdk': 21, 'target_sdk': 37}


def file_checksum(path):
    with Path(path).open('rb') as data:
        return hashlib.file_digest(data, 'sha256').hexdigest()


def verify_variants(apk_root, keystore, apksigner, aapt, password_env='KEYNOVA_SIGNING_PASSWORD', check_checksums=False,
                    alias=None, trusted_certificate=None, diagnostics=None):
    expected = keystore_certificate(keystore, password_env, alias, diagnostics=diagnostics)
    if trusted_certificate is not None and expected != fingerprint(trusted_certificate):
        raise VerificationError('Selected keystore does not match the approved permanent certificate')
    records, errors = [], []
    for variant, package in (('debug', 'helium314.keyboard.compactdebug'),
                             ('themes', 'helium314.keyboard.compactthemesdebug')):
        apk = Path(apk_root) / variant / f'HeliBoard_4.3-beta1-{variant}.apk'
        try:
            actual, schemes = verified_certificate(apksigner, apk, diagnostics=diagnostics)
            if actual != expected:
                raise VerificationError(f'Signing certificate mismatch: expected {expected}, actual {actual}')
            metadata = apk_metadata(aapt, apk, package, diagnostics=diagnostics)
            checksum = file_checksum(apk)
            if check_checksums:
                sidecar = Path(str(apk) + '.sha256')
                if not sidecar.is_file():
                    raise VerificationError('Retained APK checksum file is missing')
                fields = sidecar.read_text().strip().split(maxsplit=1)
                if (len(fields) != 2 or not re.fullmatch(r'[0-9a-fA-F]{64}', fields[0]) or
                        fields[0].lower() != checksum or Path(fields[1].lstrip('*')).name != apk.name):
                    raise VerificationError('Retained APK checksum does not match its bytes/name')
            records.append(dict(metadata, variant=variant, file=str(apk), sha256=checksum,
                                certificate_sha256=actual, schemes=schemes))
        except (VerificationError, OSError) as error:
            errors.append(f'{variant}: {error}')
    if errors:
        raise VerificationError('\n'.join(errors))
    # Publish trusted checksums only after BOTH independently passed every gate.
    if not check_checksums:
        for record in records:
            Path(record['file'] + '.sha256').write_text(f"{record['sha256']}  {record['file']}\n")
    return {'source_commit': os.environ.get('GITHUB_SHA'), 'expected_certificate_sha256': expected, 'apks': records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-root', required=True)
    parser.add_argument('--keystore', required=True)
    parser.add_argument('--apksigner', required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--password-env', default='KEYNOVA_SIGNING_PASSWORD')
    parser.add_argument('--alias')
    parser.add_argument('--expected-certificate', default=os.environ.get('KEYNOVA_EXPECTED_CERT_SHA256'))
    parser.add_argument('--diagnostics-dir', type=Path)
    parser.add_argument('--check-checksums', action='store_true')
    parser.add_argument('--report', type=Path)
    parser.add_argument('--source-commit', default=os.environ.get('GITHUB_SHA'))
    args = parser.parse_args()
    try:
        report = verify_variants(args.apk_root, args.keystore, args.apksigner, args.aapt,
                                 args.password_env, args.check_checksums, args.alias, args.expected_certificate, args.diagnostics_dir)
        report['source_commit'] = args.source_commit
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + '\n')
        for record in report['apks']:
            print(f"PASS: {record['variant']} {record['package']} SHA-256={record['sha256']} certificate={record['certificate_sha256']}")
    except (VerificationError, OSError) as error:
        parser.exit(1, sanitized(str(error)) + '\n')


if __name__ == '__main__':
    main()
