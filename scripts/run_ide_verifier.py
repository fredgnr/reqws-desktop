"""Verify one immutable candidate against a frozen matrix and require per-target reports."""

import argparse
import json
import signal
from pathlib import Path
import re
import subprocess
import sys
import time

from ide_compatibility import ROOT, digest, load_snapshot, write_json
from plugin_release import validate_plugin
from verifier_process import announce, run_logged_process, timestamp

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
    started_at, started = timestamp(), time.monotonic()
    announce(target['id'], f"validating candidate: version={version}; IDE={target['version']}; "
             f"archive={archive}; snapshot={snapshot_path}; evidence={output}")
    before = validate_plugin(archive, version, historical=historical)['sha256']
    reports = output / 'reports'
    command = [str(ROOT / 'integrations/goland/gradlew'), '-p', str(ROOT / 'integrations/goland'),
               'verifyPlugin', '--no-daemon', '--no-configuration-cache', '--console=plain', '--info', '--stacktrace',
               f'-PreleaseVersion={version}',
               f'-PreqwsPluginArchive={archive.resolve()}',
               f'-PreqwsVerificationSnapshot={snapshot_path.resolve()}',
               f"-PreqwsVerificationTarget={target['id']}", f'-PreqwsVerificationReports={reports.resolve()}']
    code = None
    log_path = output / 'gradle.log'
    try:
        announce(target['id'], f'launching Gradle; live output follows; complete log={log_path}; timeout=5400s')
        code = run_logged_process(command, log_path, target['id'])
        announce(target['id'], f'Gradle exited with code={code}; reading terminal verdict from {reports}')
        status, message = classify(reports, target, version, code, log_path.read_text(errors='replace'))
    except (OSError, subprocess.TimeoutExpired) as error:
        status, message = 'infrastructure-blocked', str(error)
    except KeyboardInterrupt as error:
        status, message = 'infrastructure-blocked', str(error) or 'Verification interrupted; owned process group stopped'
    if digest(archive) != before:
        status, message = 'infrastructure-blocked', 'Candidate bytes changed during verification'
    result = {'schemaVersion': 1, 'target': target, 'candidateSha256': before, 'version': version,
              'snapshotSha256': digest(snapshot_path), 'status': status, 'message': message, 'exitCode': code,
              'startedAt': started_at, 'finishedAt': timestamp(), 'durationSeconds': round(time.monotonic() - started, 1)}
    write_json(output / 'result.json', result)
    announce(target['id'], f"terminal status={status}; duration={result['durationSeconds']}s; {message}")
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
    # GitHub cancellation sends SIGINT/SIGTERM before escalation. Preserve the
    # current target's non-passing result and stop only its own process group.
    def interrupted(signum, _frame):
        # BaseException escapes best-effort diagnostic error handling as well.
        raise KeyboardInterrupt(f'Verification cancelled by signal {signum}; owned process group stopped')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Compatibility infrastructure blocked: {error}', file=sys.stderr)
        sys.exit(2)
