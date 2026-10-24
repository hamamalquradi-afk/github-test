#!/usr/bin/env python3
"""Real-emulator startup probe. Never treats an APK build as a runtime pass."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import traceback


def adb(*args, check=True):
    return subprocess.run(['adb', *args], capture_output=True, text=True,
                          check=check, timeout=90).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk-dir', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--expect-startup-crash', action='store_true')
    parser.add_argument('--stable-apk-dir')
    args = parser.parse_args()
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    report = {'package': args.package, 'api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
              'device': adb('shell', 'getprop', 'ro.product.model').strip(),
              'expected_startup_crash': args.expect_startup_crash,
              'physical_device': False, 'checks': {}}
    try:
        apks = list(Path(args.apk_dir).rglob('*.apk'))
        assert len(apks) == 1, f'Expected exactly one APK, got {apks}'
        apk = apks[0]
        report['apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        report['apk_name'] = apk.name
        if args.stable_apk_dir:
            from runtime_smoke import install_stable
            install_stable(args, out, report)
        (out/'install.txt').write_text(adb('install', '-r', str(apk)), encoding='utf-8')
        adb('logcat', '-c')
        adb('shell', 'am', 'force-stop', args.package)
        result = adb('shell', 'am', 'start', '-W', '-n',
                     args.package+'/helium314.keyboard.settings.SettingsActivity', check=False)
        (out/'launch.txt').write_text(result, encoding='utf-8')
        time.sleep(4)
        log = adb('logcat', '-d', '-v', 'threadtime')
        (out/'logcat.txt').write_text(log, encoding='utf-8')
        crash = 'FATAL EXCEPTION' in log and ('Process: '+args.package) in log
        report['checks']['startup_crash_observed'] = crash
        report['process_after_launch'] = adb('shell', 'pidof', args.package, check=False).strip()
        if args.expect_startup_crash:
            assert crash, 'Reported broken APK did not reproduce a startup crash'
            assert 'Resources$NotFoundException' in log, 'Unexpected crash: inspect Logcat'
            assert 'resource ID #0x0' in log, 'Expected missing application label resource'
            report['checks']['missing_label_resource_crash_reproduced'] = True
            print('\n'.join(line for line in log.splitlines() if 'AndroidRuntime' in line)[:12000])
        else:
            assert not crash, 'App crashed: inspect Logcat'
            assert report['process_after_launch'], 'App process disappeared after launch'
            report['checks']['settings_launch'] = True
            from runtime_smoke import verify_ime
            verify_ime(args, out, report)
        report['status'] = 'passed'
    except Exception as e:
        report['status'] = 'failed'
        report['error'] = str(e)
        (out/'probe-error.txt').write_text(traceback.format_exc(), encoding='utf-8')
    finally:
        (out/'final-logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime', check=False), encoding='utf-8')
        (out/'package.txt').write_text(adb('shell', 'dumpsys', 'package', args.package, check=False), encoding='utf-8')
        (out/'crash-buffer.txt').write_text(adb('logcat', '-b', 'crash', '-d', check=False), encoding='utf-8')
        with (out/'screen.png').open('wb') as f:
            subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f, check=False, timeout=30)
        (out/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
