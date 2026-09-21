"""Verify one immutable candidate against a frozen matrix and require per-target reports."""

import argparse
import json
import os
import signal
from pathlib import Path
import re
import subprocess
import sys

from ide_compatibility import ROOT, digest, load_snapshot, write_json
from plugin_release import validate_plugin

# Verifier 1.410 emits these reports even when Gradle's process exit is successful.
BLOCKING_REPORTS = ('compatibility-problems.txt', 'internal-api-usages.txt',
                    'experimental-api-usages.txt', 'override-only-usages.txt', 'internal-api-kt-usages.txt',
                    'non-extendable-api-usages.txt', 'invalid-plugin.txt')


def classify(reports, target, version, returncode, task_log=''):
    # Only the verifier's report layout is evidence. Extracted dependency/plugin
    # contents elsewhere in its cache must never impersonate a terminal report.
    verdicts = [Path(reports) / identity / 'plugins' / 'com.reqws.workspace' / version / 'verification-verdict.txt'
                for identity in (target['id'], target['build'])]
    verdicts = [path for path in verdicts if path.is_file()]
    if len(verdicts) != 1:
        return 'infrastructure-blocked', 'Missing or ambiguous target verdict'
    verdict = verdicts[0].read_text().strip()
    lower = verdict.lower()
    if any(word in lower for word in ('failed to download', 'not found', 'unable to', 'failed to resolve')):
        return 'infrastructure-blocked', verdict
    directory = verdicts[0].parent
    if any((directory / name).is_file() and (directory / name).read_text().strip() for name in BLOCKING_REPORTS):
        return 'incompatible', verdict
    if any(word in lower for word in ('compatibility problem', 'invalid plugin', 'missing dependencies')):
        return 'incompatible', verdict
    if re.search(r'Verification failed with \[[A-Z_, ]+\] problems', task_log):
        return 'incompatible', 'Explicit blocking Gradle Plugin 2.18.1 verifier failure; consult the complete log'
    if returncode != 0:
        return 'infrastructure-blocked', 'Verifier task failed; consult the complete log'
    if not re.match(r'compatible(?:[\s.,]|$)', lower):
        return 'infrastructure-blocked', 'Unknown verifier terminal verdict: ' + verdict
    return 'passed', verdict


def run_target(snapshot_path, target, archive, version, output, historical=False):
    output.mkdir(parents=True, exist_ok=False)
    before = validate_plugin(archive, version, historical=historical)['sha256']
    reports = output / 'reports'
    command = [str(ROOT / 'integrations/goland/gradlew'), '-p', str(ROOT / 'integrations/goland'),
               'verifyPlugin', '--no-daemon', '--no-configuration-cache', f'-PreleaseVersion={version}',
               f'-PreqwsPluginArchive={archive.resolve()}',
               f'-PreqwsVerificationSnapshot={snapshot_path.resolve()}',
               f"-PreqwsVerificationTarget={target['id']}", f'-PreqwsVerificationReports={reports.resolve()}']
    code = None
    log_path = output / 'gradle.log'
    try:
        with log_path.open('w') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=5400)
            except subprocess.TimeoutExpired:
                # Only the process group created by this invocation, never a user's IDE.
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=20)
                except ProcessLookupError:
                    pass
                raise
        status, message = classify(reports, target, version, code, log_path.read_text())
    except (OSError, subprocess.TimeoutExpired) as error:
        status, message = 'infrastructure-blocked', str(error)
    if digest(archive) != before:
        status, message = 'infrastructure-blocked', 'Candidate bytes changed during verification'
    result = {'schemaVersion': 1, 'target': target, 'candidateSha256': before, 'version': version,
              'snapshotSha256': digest(snapshot_path), 'status': status, 'message': message, 'exitCode': code}
    write_json(output / 'result.json', result)
    print(f"{target['id']}: {status}: {message}", flush=True)
    return result


def summarize(snapshot_path, archive, results):
    snapshot = load_snapshot(snapshot_path)
    expected = {item['id']: item for item in snapshot['targets']}
    found = {}
    for path in Path(results).rglob('result.json'):
        result = json.loads(path.read_text())
        identity = result['target']['id']
        if identity in found or result['target'] != expected.get(identity):
            raise ValueError('Duplicate or unexpected target result')
        if result['candidateSha256'] != digest(archive) or result['snapshotSha256'] != digest(snapshot_path):
            raise ValueError('API result belongs to different candidate bytes or target snapshot')
        found[identity] = result
    if set(found) != set(expected):
        raise ValueError('Required API results are missing')
    if any(result['status'] != 'passed' for result in found.values()):
        raise ValueError('One or more required API targets did not pass')
    print(f'All {len(found)} frozen API targets passed for the exact candidate')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', required=True, type=Path)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--version')
    parser.add_argument('--target')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--summarize', action='store_true')
    parser.add_argument('--historical', action='store_true', help='API-only scan of an existing release; never stages/publishes it')
    args = parser.parse_args()
    snapshot = load_snapshot(args.snapshot, fresh=not args.summarize)
    if args.summarize:
        summarize(args.snapshot, args.archive, args.output)
        return
    if not args.version:
        raise ValueError('Candidate version is required')
    targets = [item for item in snapshot['targets'] if args.target is None or item['id'] == args.target]
    if not targets:
        raise ValueError('Target not present in frozen matrix')
    results = [run_target(args.snapshot, target, args.archive, args.version,
                          args.output / target['id'], args.historical) for target in targets]
    if any(result['status'] != 'passed' for result in results):
        sys.exit(1)
    if args.target is None:
        summarize(args.snapshot, args.archive, args.output)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Compatibility infrastructure blocked: {error}', file=sys.stderr)
        sys.exit(2)
