"""One credential policy for every signing subprocess; no defaults or key creation."""
import os
import re


class VerificationError(RuntimeError):
    pass


def credentials(password_env='KEYNOVA_SIGNING_PASSWORD', alias=None):
    env = os.environ.copy()
    password = env.get(password_env, '').strip()
    selected_alias = (alias if alias is not None else env.get('KEYNOVA_SIGNING_KEY_ALIAS', '')).strip()
    if not password or not selected_alias:
        raise VerificationError('Signing password or key alias is missing')
    env[password_env] = password
    env['KEYNOVA_SIGNING_KEY_ALIAS'] = selected_alias
    return env, selected_alias


def sanitized(value):
    text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', value)
    for name in ['KEYNOVA_SIGNING_PASSWORD', 'KEYNOVA_SIGNING_KEY_ALIAS',
                 'KEYNOVA_SIGNING_KEYSTORE_B64', 'KEYSTORE_B64']:
        raw = os.environ.get(name, '')
        for secret in sorted({raw, raw.strip()}, key=len, reverse=True):
            if secret:
                text = text.replace(secret, '[REDACTED]')
    return text


def diagnostic(folder, name, result):
    if folder is not None:
        from pathlib import Path
        path = Path(folder); path.mkdir(parents=True, exist_ok=True)
        path.joinpath(name + '.txt').write_text(sanitized(
            f'operation={name}\nexit_status={result.returncode}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}\n'))


def checked(result, operation, folder=None):
    diagnostic(folder, operation, result)
    if result.returncode:
        detail = sanitized((result.stderr or result.stdout).strip())[:2400]
        raise VerificationError(f'{operation} failed (exit {result.returncode}): {detail}')
    return result


if __name__ == '__main__':
    import subprocess
    import sys
    try:
        env, _ = credentials()
        if len(sys.argv) < 2:
            raise VerificationError('A signing command is required')
        process = subprocess.Popen(sys.argv[1:], env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            sys.stdout.write(sanitized(line))
        raise SystemExit(process.wait())
    except (VerificationError, OSError) as error:
        raise SystemExit(sanitized(str(error))) from error
