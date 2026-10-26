"""Exercise the real workflow helpers with old/new gh and ANSI job logs."""
import ast
from pathlib import Path
import subprocess
import textwrap
import unittest
from unittest.mock import patch

WORKFLOW = Path(__file__).resolve().parents[3] / '.github/workflows/heliboard-theme-verify.yml'


def diagnostic_helpers():
    text = WORKFLOW.read_text()
    blocks = text.split('          import json, os, re, subprocess\n')[1:]
    assert len(blocks) == 2, 'Both diagnostic entry points must retain coverage'
    for block in blocks:
        code = 'import json, os, re, subprocess\n' + textwrap.dedent(block.split('          PY\n', 1)[0])
        parsed = ast.parse(code)
        selected = [node for node in parsed.body if isinstance(node, (ast.Import, ast.FunctionDef)) or
                    isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in {'help_text', 'log_flags'} for t in node.targets)]
        yield compile(ast.Module(body=selected, type_ignores=[]), str(WORKFLOW), 'exec')


class DiagnosticsTests(unittest.TestCase):
    def check_cli(self, supports_flag):
        for helper in diagnostic_helpers():
            calls = []

            def fake_run(args, **kwargs):
                calls.append(args)
                if args[-1] == '--help':
                    return subprocess.CompletedProcess(args, 0, '--allow-escape-sequences' if supports_flag else '', '')
                is_log = args[2].endswith('/logs')
                if is_log and supports_flag and '--allow-escape-sequences' not in args:
                    return subprocess.CompletedProcess(args, 1, b'', b'response contains terminal escape sequences')
                return subprocess.CompletedProcess(args, 0, b'\x1b[31mCache save failed\x1b[0m' if is_log else b'{}', b'')

            with patch('subprocess.run', side_effect=fake_run):
                namespace = {}; exec(helper, namespace)
                raw = namespace['api']('repos/example/actions/jobs/1/logs')
                namespace['api']('repos/example/actions/caches')
            self.assertEqual('Cache save failed', namespace['sanitize'](raw.decode()))
            self.assertEqual(supports_flag, '--allow-escape-sequences' in calls[1])
            self.assertNotIn('--allow-escape-sequences', calls[2])

    def test_new_gh_explicitly_accepts_captured_ansi_logs(self):
        self.check_cli(True)

    def test_old_gh_does_not_receive_an_unsupported_flag(self):
        self.check_cli(False)

    def test_annotation_sanitizer_removes_ansi_and_redacts_urls(self):
        for helper in diagnostic_helpers():
            with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, '', '')):
                namespace = {}; exec(helper, namespace)
            self.assertEqual('error 10%25 [URL redacted]%0Aend',
                             namespace['sanitize']('\x1b[31merror\x1b[0m 10% https://example.invalid/private?sig=value\nend'))

    def test_api_failures_remain_visible_and_fatal_to_the_diagnostic(self):
        for helper in diagnostic_helpers():
            responses = [subprocess.CompletedProcess([], 0, '', ''),
                         subprocess.CompletedProcess([], 7, b'', b'API unavailable')]
            with patch('subprocess.run', side_effect=responses), patch('builtins.print') as report:
                namespace = {}; exec(helper, namespace)
                with self.assertRaises(SystemExit) as error:
                    namespace['api']('repos/example/actions/jobs/1/logs')
                self.assertEqual(7, error.exception.code)
                self.assertIn('API unavailable', report.call_args[0][0])


if __name__ == '__main__':
    unittest.main()
