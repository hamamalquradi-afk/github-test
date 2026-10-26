#!/usr/bin/env python3
"""Recover a selected key from cache/owner backup; never create a new identity."""
import argparse
import base64
import binascii
import os
from pathlib import Path
import subprocess
import tempfile

from verify_apks import VerificationError, keystore_certificate, fingerprint
from signing_credentials import credentials, checked, sanitized


def validate_key(path, password_env, alias=None, diagnostics=None):
    env, alias = credentials(password_env, alias)
    identity = keystore_certificate(path, password_env, alias, diagnostics=diagnostics)
    result = subprocess.run(['keytool', '-J-Duser.language=en', '-J-Duser.country=US', '-list', '-v',
                             '-keystore', str(path), '-alias', alias,
                             '-storepass:env', password_env], capture_output=True, text=True, env=env)
    checked(result, 'keytool-validate-private-entry', diagnostics)
    if 'Entry type: PrivateKeyEntry' not in result.stdout.splitlines():
        raise VerificationError('Selected signing alias must contain a private-key entry')
    return identity


def prepare(path, backup='', password_env='KEYNOVA_SIGNING_PASSWORD', alias=None, expected_certificate=None, diagnostics=None):
    path = Path(path)
    if path.is_symlink():
        raise VerificationError('Refusing a symlink as the selected signing keystore')
    current = validate_key(path, password_env, alias, diagnostics) if path.exists() else None
    if current is not None and expected_certificate is not None and current != fingerprint(expected_certificate):
        raise VerificationError('Existing key differs from the approved permanent certificate')
    if not backup:
        if current is None:
            raise VerificationError('No retained signing key: restore the original through KEYNOVA_SIGNING_KEYSTORE_B64; automatic key rotation is disabled')
        return current
    try:
        encoded = ''.join(backup.split())
        if len(encoded) > 2_000_000:
            raise VerificationError('Keystore backup exceeds the supported size')
        data = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise VerificationError('Signing-key backup is not valid base64') from error
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(prefix='.keynova-key-', dir=path.parent)
    candidate = Path(temporary)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data); output.flush(); os.fsync(output.fileno())
        recovered = validate_key(candidate, password_env, alias, diagnostics)
        if expected_certificate is not None and recovered != fingerprint(expected_certificate):
            raise VerificationError('Backup differs from the approved permanent certificate')
        if current is not None:
            if recovered != current:
                raise VerificationError('Cached key and owner backup have different identities; refusing to replace either')
        else:
            # Link is an atomic create, unlike replace/rename: never overwrite a
            # keystore that appeared concurrently while the backup was checked.
            os.link(candidate, path)
        return recovered
    finally:
        candidate.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--diagnostics-dir', type=Path)
    args = parser.parse_args()
    try:
        env, _ = credentials()
        if len(env['KEYNOVA_SIGNING_PASSWORD']) < 32:
            raise VerificationError('Signing password must contain at least 32 characters')
        result = prepare(os.environ['KEYNOVA_DEBUG_KEYSTORE'], os.environ.pop('KEYNOVA_SIGNING_KEYSTORE_B64', ''), expected_certificate=os.environ.get('KEYNOVA_EXPECTED_CERT_SHA256'), diagnostics=args.diagnostics_dir)
        print('Selected signing certificate SHA-256:', result)
    except (VerificationError, OSError, KeyError) as error:
        message = sanitized(str(error))
        if args.diagnostics_dir:
            args.diagnostics_dir.mkdir(parents=True, exist_ok=True)
            (args.diagnostics_dir / 'restore-failure.txt').write_text(message + '\n')
        raise SystemExit(message) from error
