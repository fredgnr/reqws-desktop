"""Run real Electron checks and reject incomplete or misleading Playwright results."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import threading
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODES = {
    'source': ('playwright.config.ts', 'desktop-report.json', 'desktop'),
    'smoke': ('playwright.config.ts', 'desktop-report.json', 'desktop'),
    'negative': ('playwright.probes.config.ts', 'probe-report.json', 'probes'),
    'packaged': ('playwright.packaged.config.ts', 'packaged-report.json', 'packaged'),
}
PROBE_TITLE = 'S0 disconnected real preload must fail readiness'
STARTUP_PROBE_TITLE = 'S0 an early renderer error must fail even when the UI becomes ready'
STARTUP_MARKER = 'REQWS_E2E_STARTUP_ERROR_PROBE'
APP_RELATIVE = Path('out/ReqWS-darwin-arm64/ReqWS.app')


def read_json(filename):
    try:
        return json.loads(filename.read_text())
    except (OSError, ValueError) as error:
        raise ValueError(f'Missing or invalid JSON: {filename}') from error


def selectors(arguments, mode):
    """Only selection and bounded repetition may override the frozen config."""
    selected = []
    if arguments[:1] == ['--']:
        arguments = arguments[1:]
    if arguments and mode in ('negative', 'packaged'):
        raise ValueError(f'{mode} uses a fixed test and does not accept selectors')
    index = 0
    while index < len(arguments):
        value = arguments[index]
        option, separator, inline = value.partition('=')
        if option in ('--grep', '-g', '--grep-invert', '--repeat-each'):
            if separator:
                argument = inline
            else:
                index += 1
                argument = arguments[index] if index < len(arguments) else ''
            if not argument or '\0' in argument or argument.startswith('--'):
                raise ValueError(f'{option} requires a value')
            if option == '--repeat-each' and not re.fullmatch(r'(?:[1-9]|[1-9][0-9]|100)', argument):
                raise ValueError('--repeat-each must be an integer from 1 to 100')
            selected.extend([option, argument])
        elif value.startswith('-') or '\0' in value or not value:
            raise ValueError(f'Unsupported Playwright option: {value}')
        else:
            # Paths are passed as single subprocess arguments, never shell code.
            selected.append(value)
        index += 1
    return selected


def command(mode, selected):
    config, _, _ = MODES[mode]
    args = ['node', str(ROOT / 'node_modules/@playwright/test/cli.js'), 'test', '--config', config]
    if mode == 'smoke':
        args.extend(['--grep', '@smoke'])
    args.extend(selected)
    return args


def assert_hosted_runner(environment, runtime, root=ROOT):
    """An accidental-use guard; do not emulate this environment on a local host."""
    required = {'CI': 'true', 'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
                'RUNNER_OS': 'macOS', 'RUNNER_ARCH': 'ARM64'}
    if any(environment.get(key) != value for key, value in required.items()):
        raise ValueError('Packaged smoke requires a real GitHub-hosted macOS arm64 runner')
    home = Path('/Users/runner')
    if (runtime.get('platform') != 'darwin' or runtime.get('arch') != 'arm64'
            or runtime.get('username') != 'runner' or runtime.get('userHome') != str(home)
            or runtime.get('home') != str(home) or home.resolve() != home):
        raise ValueError('Packaged smoke refuses local accounts or a noncanonical runner home')
    temporary = Path(environment.get('RUNNER_TEMP', ''))
    workspace = Path(environment.get('GITHUB_WORKSPACE', ''))
    if (not temporary.is_absolute() or not temporary.is_dir()
            or temporary.resolve() != temporary or not temporary.is_relative_to(home / 'work')
            or not workspace.is_absolute() or workspace.resolve() != root.resolve()
            or not root.resolve().is_relative_to(home / 'work')):
        raise ValueError('Runner temp/workspace must belong to the disposable hosted runner')
    if environment.get('REQWS_BUILD_PROFILE', 'local') != 'local':
        raise ValueError('This smoke verifies only the CI ad-hoc package profile')
    if any(key.startswith('REQWS_E2E_') and key != 'REQWS_E2E_PROCESS_REGISTRY' for key in environment):
        raise ValueError('Packaged smoke must not use source-test controls')
    registry = environment.get('REQWS_E2E_PROCESS_REGISTRY')
    if registry and Path(registry) != root / 'test-results/packaged-processes.jsonl':
        raise ValueError('Packaged process registry must use the fixed candidate report path')
    if registry and Path(registry).is_symlink():
        raise ValueError('Packaged process registry must not be a symbolic link')


def authorize_packaged(app, root=ROOT):
    # Read the kernel account through Node, rather than trusting USER/LOGNAME/HOME.
    runtime = json.loads(subprocess.check_output(['node', '-e',
        'const os=require("node:os");const user=os.userInfo();process.stdout.write(JSON.stringify('
        '{platform:process.platform,arch:process.arch,username:user.username,userHome:user.homedir,home:os.homedir()}));'],
        cwd=root, text=True, timeout=10))
    assert_hosted_runner(os.environ, runtime, root)
    if app is None:
        raise ValueError('Packaged smoke requires an explicit --app')
    app = Path(os.path.abspath(app))
    expected = root / APP_RELATIVE
    if app != expected or not app.is_dir() or app.resolve() != expected:
        raise ValueError(f'Packaged smoke requires the unchanged candidate at {expected}')
    version = read_json(root / 'package.json')['version']
    # Reuse the production bundle/signature verifier without rebuilding, signing,
    # installing, changing fuses or substituting the packaged Main entry.
    verification = r'''
      import { validateAppBundle, parseCodeSigningDetails } from './scripts/install-macos.mts';
      import { listPackage, extractFile } from '@electron/asar';
      import { spawnSync } from 'node:child_process';
      import path from 'node:path';
      await validateAppBundle(process.argv[1], { arch: 'arm64', version: process.argv[2], profile: 'local' });
      const details = spawnSync('/usr/bin/codesign', ['--display', '--verbose=4', process.argv[1]],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], shell: false, timeout: 15000 });
      if (details.error || details.status !== 0) throw new Error('Unable to read candidate signing profile');
      const signing = parseCodeSigningDetails(`${details.stdout}\n${details.stderr}`);
      if (!signing.adHoc || signing.hardenedRuntime) throw new Error('Candidate is not the CI ad-hoc signing profile');
      const archive = path.join(process.argv[1], 'Contents/Resources/app.asar');
      const entries = listPackage(archive);
      const manifest = JSON.parse(extractFile(archive, 'package.json').toString());
      if (manifest.main !== '.vite/build/main.js' || manifest.version !== process.argv[2]
        || !['/.vite/build/main.js', '/.vite/build/preload.js', '/.vite/renderer/main_window/index.html'].every(name => entries.includes(name))) {
        throw new Error('Production package entry/version/resources do not match the candidate');
      }
      if (entries.some(name => /(?:^|\/)(?:tests|e2e)(?:\/|$)|test-main|disconnected-preload/u.test(name))) {
        throw new Error('Source E2E files leaked into the production archive');
      }
      for (const name of entries.filter(name => /\.(?:js|json|html)$/u.test(name) && !name.includes('/node_modules/'))) {
        if (/REQWS_E2E_|__reqwsE2E|disconnected-preload|faultPlan|NativeControl/u.test(extractFile(archive, name.replace(/^\//u, '')).toString())) {
          throw new Error('Test control/fault logic leaked into the production archive');
        }
      }
    '''
    subprocess.run(['node', '--input-type=module', '-e', verification, str(app), version],
                   cwd=root, check=True, timeout=120, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return {'app': str(app), 'executable': str(app / 'Contents/MacOS/ReqWS'),
            'resources': str(app / 'Contents/Resources/app.asar'), 'version': version,
            'profile': 'ci-adhoc', 'runner': 'github-hosted', 'signatureVerified': True}


def all_tests(suites):
    for suite in suites:
        for spec in suite.get('specs', []):
            for test in spec.get('tests', []):
                yield spec, test
        yield from all_tests(suite.get('suites', []))


def validate_report(report, returncode, mode):
    if report.get('errors'):
        raise ValueError('Playwright reported a runner/global-setup error')
    config = report.get('config', {})
    projects = config.get('projects', [])
    if (config.get('workers') != 1 or config.get('forbidOnly') is not True or not projects
            or any(project.get('retries') != 0 for project in projects)):
        raise ValueError('Playwright must retain one worker, forbidOnly and zero retries')
    if Path(config.get('configFile', '')).name != MODES[mode][0]:
        raise ValueError('Unexpected Playwright configuration')
    tests = list(all_tests(report.get('suites', [])))
    if not tests:
        raise ValueError('No Playwright tests executed')
    stats = report.get('stats', {})
    if stats.get('skipped') != 0 or stats.get('flaky') != 0:
        raise ValueError('Skipped or flaky tests cannot satisfy an Electron gate')
    if mode == 'negative' and {(spec.get('title'), Path(spec.get('file', '')).name) for spec, _ in tests} != {
            (PROBE_TITLE, 'disconnected-preload.spec.ts'), (STARTUP_PROBE_TITLE, 'startup-error.spec.ts')}:
        raise ValueError('Negative probes must match the exact two deliberate failures')
    for spec, test in tests:
        results = test.get('results', [])
        if (test.get('expectedStatus') != 'passed' or len(results) != 1
                or results[0].get('retry') != 0
                or any(annotation.get('type') in ('skip', 'fixme', 'fail') for annotation in test.get('annotations', []))):
            raise ValueError('Skipped, expected-failure or retried tests cannot satisfy an Electron gate')
        result = results[0]
        if mode == 'negative':
            errors = result.get('errors', [])
            message = '\n'.join(error.get('message', '') for error in errors)
            stack = '\n'.join(error.get('message', '') + '\n' + error.get('stack', '') for error in errors)
            preload_failure = (spec.get('title') == PROBE_TITLE and "getByRole('navigation')" in message
                               and 'element(s) not found' in message and 'toBeVisible' in message)
            startup_failure = (spec.get('title') == STARTUP_PROBE_TITLE and 'renderer startup errors' in message
                               and STARTUP_MARKER in message and 'toEqual' in message)
            if (len(tests) != 2 or result.get('status') != 'failed' or test.get('status') != 'unexpected'
                    or len(errors) != 1 or not (preload_failure or startup_failure) or 'Desktop.start' not in stack):
                raise ValueError('Negative probe failed for a reason other than its deliberate readiness/startup fault')
        elif (result.get('status') != 'passed' or result.get('errors') or spec.get('ok') is not True
              or test.get('status') != 'expected'):
            raise ValueError('An Electron test failed, timed out, was interrupted or did not execute')
    expected_stats = {'expected': 0, 'unexpected': 2} if mode == 'negative' else {'expected': len(tests), 'unexpected': 0}
    if any(stats.get(key) != value for key, value in expected_stats.items()):
        raise ValueError('Playwright totals do not match executed tests')
    if returncode != (1 if mode == 'negative' else 0):
        raise ValueError(f'Unexpected Playwright subprocess exit: {returncode}')
    return {'executed': len(tests), 'passed': expected_stats['expected'],
            'failed': expected_stats['unexpected'], 'skipped': 0, 'flaky': 0,
            'selectors': [f'{spec.get("file")}:{spec.get("line")} {spec.get("title")}' for spec, _ in tests]}


def validate_negative_evidence(directory, started=0):
    roots = [entry for entry in directory.iterdir() if entry.is_dir()]
    if (len(roots) != 2 or any(root.is_symlink() for root in roots)
            or sum(root.name.startswith('disconnected-preload-') for root in roots) != 1
            or sum(root.name.startswith('startup-error-') for root in roots) != 1):
        raise ValueError('Negative probes must have exactly the two fault evidence directories')
    return [validate_probe_evidence(root, started) for root in roots]


def validate_probe_evidence(root, started):
    preload = root.name.startswith('disconnected-preload-')
    required = ['primary-process-1/trace.zip', 'primary-process-1/window.png',
                'primary-process-1/launch-error.txt', 'primary-process-1/exit.json',
                'primary-process-1/runtime.json', 'logs/main.log', 'disk.json', 'renderer-errors.json']
    for name in required:
        filename = root / name
        if (filename.is_symlink() or not filename.is_file() or filename.stat().st_size == 0
                or filename.stat().st_mtime < started):
            raise ValueError(f'Negative probe evidence is missing, empty or stale: {name}')
    if not (root / 'primary-process-1/window.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError('Negative probe screenshot is not a PNG')
    if 'REQWS_E2E_EXTERNAL_MAIN' not in (root / 'logs/main.log').read_text():
        raise ValueError('Negative probe has no real Main-process evidence')
    disk = read_json(root / 'disk.json')
    if not isinstance(disk, dict) or not {'state', 'files'} <= disk.keys():
        raise ValueError('Negative probe has no independent disk evidence')
    exit_result = read_json(root / 'primary-process-1/exit.json')
    if (exit_result.get('code') != 0 or exit_result.get('signal') is not None
            or not isinstance(exit_result.get('pid'), int)):
        raise ValueError('Negative probe did not cleanly terminate its Electron process')
    runtime = read_json(root / 'primary-process-1/runtime.json')
    if runtime.get('noSandbox') is not False or runtime.get('renderer', {}).get('sandboxed') is not True:
        raise ValueError('Negative probe did not retain the renderer OS sandbox')
    launch_error = (root / 'primary-process-1/launch-error.txt').read_text()
    if (preload and ("getByRole('navigation')" not in launch_error or 'element(s) not found' not in launch_error)
            or not preload and ('renderer startup errors' not in launch_error or STARTUP_MARKER not in launch_error)):
        raise ValueError('Negative probe launch error does not identify failed readiness')
    renderer = read_json(root / 'renderer-errors.json')
    if (not isinstance(renderer, list) or not renderer or any(not isinstance(error, str) for error in renderer)):
        raise ValueError('Negative probe has no renderer fault evidence')
    if preload and (not any('Unable to load preload script:' in error and '/broken-app/build/preload.js' in error for error in renderer)
                    or not any('ENOENT' in error and '/broken-app/build/preload.js' in error for error in renderer)):
        raise ValueError('Negative probe does not prove the real preload was disconnected')
    allowed = ('Unable to load preload script:', 'ENOENT', "Cannot read properties of undefined (reading 'operations')") if preload else (STARTUP_MARKER,)
    if any(not any(fragment in error for fragment in allowed) for error in renderer):
        raise ValueError('Negative probe contains an unrelated renderer error')
    with zipfile.ZipFile(root / 'primary-process-1/trace.zip') as archive:
        if archive.testzip() is not None:
            raise ValueError('Negative trace is corrupt')
        names = archive.namelist()
        events = [json.loads(line) for name in names if name.endswith('.trace')
                  for line in archive.read(name).decode().splitlines() if line]
        if (not any(event.get('type') == 'context-options' and event.get('browserName') == 'electron'
                    and event.get('options', {}).get('chromiumSandbox') is True for event in events)
                or not any(event.get('type') == 'frame-snapshot' for event in events)
                or not any(name.startswith('screencast/') for name in names)
                or not any(name.startswith('resources/') and name.endswith('.html') for name in names)):
            raise ValueError('Negative trace does not cover real Electron DOM and screenshots')
        # Tracing starts after Electron launch and cannot replay early console
        # events. The report, launch error and cached renderer-error sidecar
        # bind the exact fault; the trace proves real DOM/screenshot coverage.
    return str(root.relative_to(ROOT)) if root.is_relative_to(ROOT) else str(root)


def identity(mode, args):
    package = read_json(ROOT / 'package.json')
    lock = read_json(ROOT / 'package-lock.json')
    return {'testedCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'prHead': os.environ.get('REQWS_PR_HEAD_SHA'), 'githubSha': os.environ.get('GITHUB_SHA'),
            'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True)),
            'appVersion': package['version'], 'electron': lock['packages']['node_modules/electron']['version'],
            'playwright': lock['packages']['node_modules/@playwright/test']['version'],
            'node': subprocess.check_output(['node', '--version'], text=True).strip(),
            'os': platform.platform(), 'arch': platform.machine(),
            'profile': 'ci-adhoc' if mode == 'packaged' else 'source-e2e', 'mode': mode,
            'command': args, 'startedAt': datetime.now(timezone.utc).isoformat()}


def read_process_registry(filename):
    if filename is None or filename.is_symlink() or not filename.is_file():
        raise ValueError('The owned-process registry is missing or unsafe')
    records = {}
    try:
        for line in filename.read_text().splitlines():
            record = json.loads(line)
            pid = record.get('pid')
            signature = record.get('signature', '')
            parts = signature.split(None, 2)
            if (record.get('event') not in ('start', 'close') or not isinstance(pid, int) or isinstance(pid, bool) or pid <= 1
                    or not re.fullmatch(r'[0-9a-f-]{36}', record.get('id', '')) or len(parts) != 3
                    or parts[:2] != [str(pid), str(pid)]):
                raise ValueError('Owned-process registry contains invalid group ownership')
            previous = records.get(record['id'])
            if record['event'] == 'start':
                if previous is not None:
                    raise ValueError('Owned-process registry contains a duplicate start')
                records[record['id']] = record
            elif (previous is None or previous['event'] != 'start' or previous['pid'] != pid
                  or previous['signature'] != signature):
                raise ValueError('Owned-process registry contains an unmatched close')
            else:
                records[record['id']] = record
    except (OSError, TypeError, json.JSONDecodeError) as error:
        raise ValueError('Owned-process registry is unreadable') from error
    if not records:
        raise ValueError('No owned processes were registered for this Electron run')
    return [record for record in records.values() if record['event'] == 'start']


def process_signature(pid):
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'pid=,pgid=,lstart='],
                            text=True, capture_output=True, timeout=5, shell=False)
    if result.returncode == 1 and not result.stdout.strip():
        return None
    if result.returncode != 0 or not result.stdout.strip():
        raise ValueError('Unable to confirm owned-process identity through ps')
    return result.stdout.strip()


def cleanup_owned_processes(filename):
    """Only the current run's registered, still-identical group leaders may die."""
    killed = []
    failures = []
    for record in read_process_registry(filename):
        try:
            signature = process_signature(record['pid'])
            if signature is None:
                continue
            if signature != record['signature']:
                raise ValueError('Registered process signature changed; refusing to kill a reused or unowned PID')
            try:
                os.killpg(record['pid'], signal.SIGKILL)
            except ProcessLookupError:
                continue
            killed.append(record['pid'])
            deadline = time.monotonic() + 5
            while process_signature(record['pid']) == signature:
                if time.monotonic() >= deadline:
                    raise ValueError('Owned process group did not exit after SIGKILL')
                time.sleep(0.05)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            failures.append(str(error))
    if failures:
        raise ValueError('Owned-process cleanup failed: ' + '; '.join(failures))
    return killed


def run_child(args, timeout, registry, log_path):
    process = subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, start_new_session=True, shell=False)
    output = []
    log_errors = []

    def tee():
        try:
            with log_path.open('w') as stream:
                for line in process.stdout:
                    stream.write(line); stream.flush()
                    output.append(line)
                    print(line, end='', flush=True)
        except (OSError, ValueError) as error:
            log_errors.append(str(error))

    reader = threading.Thread(target=tee, daemon=True)
    reader.start()
    timed_out = False
    try:
        process.wait(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        timed_out = True
        # Electron and git-http-backend are detached group leaders; this Node
        # group signal first gives their fixture a chance to close them normally.
        try: os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError: pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait(timeout=5)
    finally:
        try:
            killed = cleanup_owned_processes(registry)
        finally:
            reader.join(timeout=5)
    if reader.is_alive() or log_errors:
        raise ValueError('Electron runner log capture did not finish: ' + '; '.join(log_errors))
    if timed_out:
        raise ValueError('Electron runner timed out or was interrupted; owned process groups were checked')
    if killed:
        raise ValueError('Electron runner left live owned process groups after completion')
    return process.returncode, ''.join(output)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=MODES, default='source')
    parser.add_argument('--app', type=Path)
    parser.add_argument('--timeout', type=int, default=1800)
    parser.add_argument('--authorize-only', action='store_true', help=argparse.SUPPRESS)
    args, remaining = parser.parse_known_args(argv)
    if not 1 <= args.timeout <= 7200:
        parser.error('--timeout must be from 1 to 7200 seconds')
    selected = selectors(remaining, args.mode)
    if args.app is not None and args.mode != 'packaged':
        raise ValueError('--app is only supported for packaged smoke')
    if args.authorize_only and args.mode != 'packaged':
        raise ValueError('--authorize-only is only supported for packaged smoke')
    candidate = authorize_packaged(args.app) if args.mode == 'packaged' else None
    if args.authorize_only:
        print(json.dumps(candidate))
        return
    if candidate:
        os.environ['REQWS_PACKAGED_APP'] = candidate['app']
    results = ROOT / 'test-results'
    results.mkdir(exist_ok=True)
    if results.is_symlink() or results.resolve() != results:
        raise ValueError('Reports must use the canonical test-results directory')
    summary_path = results / f'{args.mode}-result.json'
    report_path = results / MODES[args.mode][1]
    registry_path = results / f'{args.mode}-processes.jsonl'
    for filename in (summary_path, report_path, registry_path):
        if filename.is_symlink():
            raise ValueError('Refusing a symbolic-link report path')
        filename.unlink(missing_ok=True)
    registry_path.touch(mode=0o600)
    os.environ['REQWS_E2E_PROCESS_REGISTRY'] = str(registry_path)
    invocation = command(args.mode, selected)
    summary = {'status': 'failed', 'identity': identity(args.mode, invocation), 'candidate': candidate}
    started = time.time()
    try:
        returncode, _ = run_child(invocation, args.timeout, registry_path, results / f'{args.mode}-runner.log')
        summary['returncode'] = returncode
        summary['tests'] = validate_report(read_json(report_path), returncode, args.mode)
        if args.mode == 'negative':
            summary['evidence'] = validate_negative_evidence(results / 'probes', started)
        summary['status'] = 'passed'
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        summary['error'] = str(error)
        raise
    finally:
        summary['finishedAt'] = datetime.now(timezone.utc).isoformat()
        summary_path.write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'status': summary['status'], 'mode': args.mode,
                      'executed': summary['tests']['executed'], 'failed': summary['tests']['failed']}))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'Desktop E2E failed: {error}', file=sys.stderr)
        sys.exit(1)
