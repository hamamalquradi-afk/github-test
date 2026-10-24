#!/usr/bin/env python3
"""Fail on new regressions; retain and disclose failures reproduced upstream."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def read_results(directory):
    cases = {}
    for path in sorted(Path(directory).glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        for case in root.findall('testcase'):
            key = (case.attrib['classname'], case.attrib['name'])
            if key in cases:
                raise ValueError(f'Duplicate test: {key}')
            failures = case.findall('failure') + case.findall('error')
            cases[key] = ('failed', tuple((f.get('type'), f.get('message')) for f in failures)) if failures else (
                ('skipped', ()) if case.find('skipped') is not None else ('passed', ()))
    if len(cases) < 193:
        raise ValueError(f'Incomplete test run in {directory}: {len(cases)} / at least 193')
    return cases


def compare(baseline, patched):
    problems = []
    for key, result in baseline.items():
        if key not in patched:
            problems.append(f'Missing test: {key}')
        elif result[0] != 'skipped' and patched[key][0] == 'skipped':
            problems.append(f'Newly skipped test: {key}')
    for key, result in patched.items():
        if result[0] == 'failed' and baseline.get(key) != result:
            problems.append(f'New or changed failure: {key}: {result}')
        if key not in baseline and result[0] != 'passed':
            problems.append(f'New test did not pass: {key}')
    return problems


if __name__ == '__main__':
    baseline, patched = map(read_results, sys.argv[1:3])
    problems = compare(baseline, patched)
    for key, result in patched.items():
        if result[0] == 'failed' and baseline.get(key) == result:
            print(f'::warning::Reproduced on unmodified pinned upstream: {key[0]}.{key[1]}: {result[1]}')
    print(f'Baseline: {len(baseline)} tests, {sum(r[0] == "failed" for r in baseline.values())} failures')
    print(f'Compact: {len(patched)} tests, {sum(r[0] == "failed" for r in patched.values())} failures')
    for problem in problems:
        print(f'::error::{problem}')
    if problems:
        sys.exit(1)
    print('PASS: no new failures, missing tests, or newly skipped tests against pinned upstream')
