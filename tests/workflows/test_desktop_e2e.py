"""Electron orchestration failure propagation; disposable reports, no app launches."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from ide_ci import aggregate_desktop, classify

SPEC = importlib.util.spec_from_file_location('desktop_e2e', ROOT / 'scripts/desktop_e2e.py')
E2E = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(E2E)


def report(mode='source', count=1):
    negative = mode == 'negative'
    error = {'message': "expect(locator).toBeVisible failed: getByRole('navigation'); element(s) not found",
             'stack': 'at Desktop.start (desktop.ts:77)'}
    result = {'status': 'failed' if negative else 'passed', 'retry': 0, 'errors': [error] if negative else []}
    test = {'expectedStatus': 'passed', 'annotations': [], 'results': [result],
            'status': 'unexpected' if negative else 'expected'}
    spec = {'title': E2E.PROBE_TITLE if negative else 'D01 real Electron @smoke', 'ok': not negative,
            'file': 'disconnected-preload.spec.ts' if negative else 'startup.spec.ts', 'line': 1, 'tests': [test]}
    specs = [copy.deepcopy(spec) for _ in range(count)]
    if negative:
        startup = copy.deepcopy(spec)
        startup['title'] = E2E.STARTUP_PROBE_TITLE
        startup['file'] = 'startup-error.spec.ts'
        startup['tests'][0]['results'][0]['errors'][0]['message'] = f'renderer startup errors: expect.toEqual {E2E.STARTUP_MARKER}'
        specs.append(startup)
    return {'config': {'workers': 1, 'forbidOnly': True, 'configFile': E2E.MODES[mode][0],
                       'projects': [{'retries': 0, 'repeatEach': count}]},
            'suites': [{'specs': specs}], 'errors': [],
            'stats': {'expected': 0 if negative else count, 'unexpected': 2 if negative else 0,
                      'flaky': 0, 'skipped': 0}}


def load_yaml(path):
    data = subprocess.check_output(['node', '-e',
        'const fs=require("fs");const yaml=require("js-yaml");process.stdout.write(JSON.stringify(yaml.load(fs.readFileSync(process.argv[1],"utf8"))))',
        str(ROOT / path)], cwd=ROOT, text=True)
    return json.loads(data)


class ReportTests(unittest.TestCase):
    def test_pass_requires_real_nonzero_count_and_matching_totals(self):
        self.assertEqual(E2E.validate_report(report(count=20), 0, 'source')['executed'], 20)
        self.assertEqual(E2E.validate_report(report('smoke'), 0, 'smoke')['passed'], 1)

    def test_empty_all_skipped_missing_results_and_global_errors_fail(self):
        fixtures = []
        empty = report(); empty['suites'] = []; fixtures.append(empty)
        skipped = report(); skipped['stats']['skipped'] = 1; fixtures.append(skipped)
        missing = report(); missing['suites'][0]['specs'][0]['tests'][0]['results'] = []; fixtures.append(missing)
        global_error = report(); global_error['errors'] = [{'message': 'build failed'}]; fixtures.append(global_error)
        mismatch = report(); mismatch['stats']['expected'] = 2; fixtures.append(mismatch)
        for fixture in fixtures:
            with self.subTest(fixture=fixture), self.assertRaises(ValueError):
                E2E.validate_report(fixture, 0, 'source')

    def test_failures_timeouts_interruptions_expected_failures_and_flaky_fail(self):
        for status in ('failed', 'timedOut', 'interrupted', 'skipped'):
            fixture = report(); fixture['suites'][0]['specs'][0]['tests'][0]['results'][0]['status'] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                E2E.validate_report(fixture, 0, 'source')
        fixture = report(); fixture['suites'][0]['specs'][0]['tests'][0]['expectedStatus'] = 'failed'
        with self.assertRaises(ValueError): E2E.validate_report(fixture, 0, 'source')
        fixture = report(); fixture['stats']['flaky'] = 1
        with self.assertRaises(ValueError): E2E.validate_report(fixture, 0, 'source')
        fixture = report(); fixture['suites'][0]['specs'][0]['tests'][0]['annotations'] = [{'type': 'fixme'}]
        with self.assertRaises(ValueError): E2E.validate_report(fixture, 0, 'source')

    def test_subprocess_error_cannot_borrow_a_passing_report(self):
        for code in (1, 2, -15, None):
            with self.subTest(code=code), self.assertRaises(ValueError):
                E2E.validate_report(report(), code, 'source')

    def test_retry_or_configuration_override_is_rejected(self):
        for key, value in [('workers', 2), ('forbidOnly', False), ('projects', []), ('configFile', 'other.config.ts')]:
            fixture = report(); fixture['config'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): E2E.validate_report(fixture, 0, 'source')
        fixture = report(); fixture['config']['projects'][0]['retries'] = 1
        with self.assertRaises(ValueError): E2E.validate_report(fixture, 0, 'source')
        fixture = report(); fixture['suites'][0]['specs'][0]['tests'][0]['results'][0]['retry'] = 1
        with self.assertRaises(ValueError): E2E.validate_report(fixture, 0, 'source')
        fixture = report(); fixture['suites'][0]['specs'][0]['tests'][0]['results'] *= 2
        with self.assertRaises(ValueError): E2E.validate_report(fixture, 0, 'source')

    def test_only_the_exact_deliberate_preload_failure_is_a_negative_pass(self):
        self.assertEqual(E2E.validate_report(report('negative'), 1, 'negative')['failed'], 2)
        for change in ('title', 'file', 'message', 'stack', 'timeout', 'extra'):
            fixture = report('negative'); spec = fixture['suites'][0]['specs'][0]
            result = spec['tests'][0]['results'][0]
            if change in ('title', 'file'): spec[change] = 'other failure'
            elif change in ('message', 'stack'): result['errors'][0][change] = 'Electron failed to launch'
            elif change == 'timeout': result['status'] = 'timedOut'
            else: result['errors'].append({'message': 'cleanup failed'})
            with self.subTest(change=change), self.assertRaises(ValueError): E2E.validate_report(fixture, 1, 'negative')
        for code in (0, 2, -15):
            with self.subTest(code=code), self.assertRaises(ValueError): E2E.validate_report(report('negative'), code, 'negative')

    def test_playwright_errors_may_embed_the_stack_in_message_only(self):
        fixture = report('negative')
        for spec in fixture['suites'][0]['specs']:
            error = spec['tests'][0]['results'][0]['errors'][0]
            error['message'] += '\n' + error.pop('stack')
        self.assertEqual(E2E.validate_report(fixture, 1, 'negative')['failed'], 2)

    def test_missing_or_invalid_report_is_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'report.json'
            with self.assertRaises(ValueError): E2E.read_json(path)
            path.write_text('{broken')
            with self.assertRaises(ValueError): E2E.read_json(path)


class SelectorTests(unittest.TestCase):
    def test_safe_selection_and_bounded_repetition_are_argument_arrays(self):
        selected = E2E.selectors(['--', 'tests/e2e/desktop/startup.spec.ts', '--grep', 'D01|D02', '--repeat-each=20'], 'source')
        invocation = E2E.command('source', selected)
        self.assertEqual(invocation[-5:], ['tests/e2e/desktop/startup.spec.ts', '--grep', 'D01|D02', '--repeat-each', '20'])
        self.assertEqual(E2E.selectors(['--repeat-each', '20'], 'smoke'), ['--repeat-each', '20'])
        literal = '$(do-not-execute); file'
        self.assertEqual(E2E.selectors([literal], 'source'), [literal])
        self.assertEqual(E2E.command('smoke', [])[-2:], ['--grep', '@smoke'])

    def test_gate_override_and_unsupported_flags_are_rejected(self):
        for args in [['--retries=1'], ['--workers', '2'], ['--config=other.ts'], ['--reporter=list'],
                     ['--pass-with-no-tests'], ['--list'], ['--shard=1/2'], ['--update-snapshots'],
                     ['--grep'], ['--grep='], ['--repeat-each=0'], ['--repeat-each=-1'], ['--repeat-each=101'],
                     ['--repeat-each=1.5'], ['--repeat-each=20;bad'], ['\0']]:
            with self.subTest(args=args), self.assertRaises(ValueError): E2E.selectors(args, 'source')
        for mode in ('negative', 'packaged'):
            with self.assertRaises(ValueError): E2E.selectors(['other.spec.ts'], mode)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = self.directory / 'disconnected-preload-probe'; process = self.root / 'primary-process-1'; process.mkdir(parents=True)
        (self.root / 'logs').mkdir()
        (process / 'window.png').write_bytes(b'\x89PNG\r\n\x1a\nfixture')
        (process / 'launch-error.txt').write_text("getByRole('navigation') element(s) not found")
        (process / 'exit.json').write_text(json.dumps({'pid': 123, 'code': 0, 'signal': None}))
        (process / 'runtime.json').write_text(json.dumps({'noSandbox': False, 'renderer': {'sandboxed': True}}))
        (self.root / 'logs/main.log').write_text('REQWS_E2E_EXTERNAL_MAIN 123')
        (self.root / 'disk.json').write_text(json.dumps({'state': None, 'files': {}}))
        (self.root / 'renderer-errors.json').write_text(json.dumps([
            'Unable to load preload script: /tmp/broken-app/build/preload.js',
            'ENOENT /tmp/broken-app/build/preload.js']))
        self.trace = process / 'trace.zip'
        with zipfile.ZipFile(self.trace, 'w') as archive:
            archive.writestr('trace.trace', '\n'.join(json.dumps(event) for event in [
                {'type': 'context-options', 'browserName': 'electron', 'options': {'chromiumSandbox': True}},
                {'type': 'frame-snapshot'}, {'type': 'console', 'text': 'Unable to load preload script:'}]))
            archive.writestr('screencast/window.jpeg', b'fixture')
            archive.writestr('resources/window.html', '<html>real trace fixture</html>')
        import shutil
        startup = self.directory / 'startup-error-probe'
        shutil.copytree(self.root, startup)
        (startup / 'primary-process-1/launch-error.txt').write_text(f'renderer startup errors {E2E.STARTUP_MARKER}')
        (startup / 'renderer-errors.json').write_text(json.dumps([E2E.STARTUP_MARKER]))
        with zipfile.ZipFile(startup / 'primary-process-1/trace.zip', 'w') as archive:
            archive.writestr('trace.trace', '\n'.join(json.dumps(event) for event in [
                {'type': 'context-options', 'browserName': 'electron', 'options': {'chromiumSandbox': True}},
                {'type': 'frame-snapshot'}, {'type': 'console', 'text': E2E.STARTUP_MARKER}]))
            archive.writestr('screencast/window.jpeg', b'fixture')
            archive.writestr('resources/window.html', '<html>real trace fixture</html>')

    def test_complete_real_trace_contract_is_required(self):
        self.assertIn(str(self.root), E2E.validate_negative_evidence(self.directory))
        self.trace.write_bytes(b'not a zip')
        with self.assertRaises(zipfile.BadZipFile): E2E.validate_negative_evidence(self.directory)

    def test_missing_empty_or_stale_evidence_is_rejected(self):
        target = self.root / 'logs/main.log'; original = target.read_bytes()
        target.unlink()
        with self.assertRaises(ValueError): E2E.validate_negative_evidence(self.directory)
        target.write_bytes(b'')
        with self.assertRaises(ValueError): E2E.validate_negative_evidence(self.directory)
        target.write_bytes(original)
        with self.assertRaises(ValueError): E2E.validate_negative_evidence(self.directory, target.stat().st_mtime + 1)

    def test_unrelated_renderer_error_and_forced_process_cleanup_do_not_count(self):
        (self.root / 'renderer-errors.json').write_text(json.dumps(['unrelated failure']))
        with self.assertRaises(ValueError): E2E.validate_negative_evidence(self.directory)
        (self.root / 'primary-process-1/exit.json').write_text(json.dumps({'pid': 123, 'code': None, 'signal': 'SIGKILL'}))
        with self.assertRaises(ValueError): E2E.validate_negative_evidence(self.directory)

    def test_zip_without_electron_dom_or_screenshots_does_not_count(self):
        with zipfile.ZipFile(self.trace, 'w') as archive: archive.writestr('empty.trace', '{}')
        with self.assertRaises(ValueError): E2E.validate_negative_evidence(self.directory)

    def remove_trace_console_events(self):
        for filename in self.directory.glob('*/primary-process-1/trace.zip'):
            with zipfile.ZipFile(filename) as archive:
                contents = {name: archive.read(name) for name in archive.namelist()}
            for name, content in contents.items():
                if name.endswith('.trace'):
                    contents[name] = '\n'.join(line for line in content.decode().splitlines()
                                                if json.loads(line).get('type') != 'console').encode()
            with zipfile.ZipFile(filename, 'w') as archive:
                for name, content in contents.items(): archive.writestr(name, content)

    def test_early_console_fault_can_be_cached_before_trace_starts(self):
        self.remove_trace_console_events()
        self.assertEqual(len(E2E.validate_negative_evidence(self.directory)), 2)

    def test_early_fault_still_requires_correct_cached_and_launch_sidecars(self):
        self.remove_trace_console_events()
        startup = self.directory / 'startup-error-probe'
        for name, replacement in [('renderer-errors.json', json.dumps(['UNRELATED_STARTUP_ERROR'])),
                                  ('primary-process-1/launch-error.txt', 'renderer startup errors UNRELATED_STARTUP_ERROR')]:
            filename = startup / name
            original = filename.read_text()
            try:
                filename.write_text(replacement)
                with self.subTest(name=name), self.assertRaises(ValueError):
                    E2E.validate_negative_evidence(self.directory)
                filename.unlink()
                with self.subTest(name=name, missing=True), self.assertRaises(ValueError):
                    E2E.validate_negative_evidence(self.directory)
            finally:
                filename.write_text(original)


class HostedRunnerTests(unittest.TestCase):
    def setUp(self):
        self.root = Path('/Users/runner/work/reqws-desktop/reqws-desktop')
        self.env = {'CI': 'true', 'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
                    'RUNNER_OS': 'macOS', 'RUNNER_ARCH': 'ARM64',
                    'RUNNER_TEMP': '/Users/runner/work/_temp', 'GITHUB_WORKSPACE': str(self.root)}
        self.runtime = {'platform': 'darwin', 'arch': 'arm64', 'username': 'runner',
                        'userHome': '/Users/runner', 'home': '/Users/runner'}

    def test_only_real_hosted_environment_contract_is_accepted(self):
        with patch.object(Path, 'is_dir', return_value=True):
            E2E.assert_hosted_runner(self.env, self.runtime, self.root)
            for name in ('CI', 'GITHUB_ACTIONS', 'RUNNER_ENVIRONMENT', 'RUNNER_OS', 'RUNNER_ARCH'):
                for value in ('', 'false', 'self-hosted'):
                    with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                        E2E.assert_hosted_runner({**self.env, name: value}, self.runtime, self.root)

    def test_forged_ci_variables_cannot_enable_local_fred_account(self):
        with self.assertRaisesRegex(ValueError, 'local accounts'):
            E2E.assert_hosted_runner(self.env, {**self.runtime, 'username': 'fred', 'userHome': '/Users/fred'}, self.root)
        for key, value in [('platform', 'linux'), ('arch', 'x64'), ('home', '/Users/fred'), ('userHome', '/tmp/runner')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                E2E.assert_hosted_runner(self.env, {**self.runtime, key: value}, self.root)

    def test_temp_workspace_profile_and_source_controls_fail_closed(self):
        for key, value in [('RUNNER_TEMP', '/tmp'), ('RUNNER_TEMP', '/Users/runner-evil/work/_temp'),
                           ('GITHUB_WORKSPACE', '/Users/fred/project'), ('REQWS_BUILD_PROFILE', 'personal-release'),
                           ('REQWS_E2E_CONFIG', '/tmp/control.json'), ('REQWS_E2E_PROCESS_REGISTRY', '/tmp/registry')]:
            with patch.object(Path, 'is_dir', return_value=True), self.subTest(key=key, value=value), self.assertRaises(ValueError):
                E2E.assert_hosted_runner({**self.env, key: value}, self.runtime, self.root)

    def test_wrong_missing_symlink_or_unverified_candidate_never_launches(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(); app = root / E2E.APP_RELATIVE; app.mkdir(parents=True)
            (root / 'package.json').write_text(json.dumps({'version': '1.2.3'}))
            runtime = json.dumps(self.runtime)
            with patch.object(E2E, 'assert_hosted_runner'), patch.object(E2E.subprocess, 'check_output', return_value=runtime), \
                    patch.object(E2E.subprocess, 'run') as verify:
                for candidate in (None, root / 'different.app'):
                    with self.assertRaises(ValueError): E2E.authorize_packaged(candidate, root)
                verify.assert_not_called()
                verify.side_effect = subprocess.CalledProcessError(1, ['codesign'])
                with self.assertRaises(subprocess.CalledProcessError): E2E.authorize_packaged(app, root)
                verify.side_effect = None
                result = E2E.authorize_packaged(app, root)
                self.assertEqual(result['executable'], str(app / 'Contents/MacOS/ReqWS'))
                self.assertTrue(result['signatureVerified'])
                self.assertIn('validateAppBundle', verify.call_args.args[0][3])
                self.assertNotIn('package:macos', str(verify.call_args))


class InvocationTests(unittest.TestCase):
    def test_stale_report_is_removed_and_missing_new_report_stays_failed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(); results = root / 'test-results'; results.mkdir()
            filename = results / 'desktop-report.json'; filename.write_text(json.dumps(report()))
            with patch.object(E2E, 'ROOT', root), patch.object(E2E, 'identity', return_value={'mode': 'source'}), \
                    patch.object(E2E, 'run_child', return_value=(0, 'fake child output')):
                with self.assertRaisesRegex(ValueError, 'Missing or invalid JSON'): E2E.main(['--mode', 'source'])
            self.assertFalse(filename.exists())
            self.assertEqual(json.loads((results / 'source-result.json').read_text())['status'], 'failed')

    def test_timeout_kills_only_the_owned_process_group_and_stays_failed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'test-results').mkdir()
            with patch.object(E2E, 'ROOT', root), patch.object(E2E.subprocess, 'Popen') as popen, \
                    patch.object(E2E.os, 'killpg') as kill, patch.object(E2E, 'cleanup_owned_processes', return_value=[]):
                process = popen.return_value; process.pid = 5678
                process.stdout = iter(['fixture stdout\n'])
                process.wait.side_effect = [subprocess.TimeoutExpired(['playwright'], 1), 0]
                log = root / 'test-results/source-runner.log'
                with self.assertRaisesRegex(ValueError, 'timed out'): E2E.run_child(['playwright'], 1, root / 'registry', log)
                kill.assert_called_once_with(5678, E2E.signal.SIGTERM)
                self.assertTrue(popen.call_args.kwargs['start_new_session'])
                self.assertFalse(popen.call_args.kwargs['shell'])
                self.assertIn('fixture stdout', log.read_text())


class ProcessRegistryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.filename = Path(temporary.name) / 'processes.jsonl'
        self.entry = {'event': 'start', 'id': '01234567-89ab-cdef-0123-456789abcdef', 'pid': 4321,
                      'signature': '4321 4321 Sat Sep 26 10:00:00 2026'}
        self.write(self.entry)

    def write(self, *records):
        self.filename.write_text(''.join(json.dumps(record) + '\n' for record in records))

    def test_missing_empty_unsafe_or_wrong_group_registry_is_rejected(self):
        self.filename.unlink()
        with self.assertRaises(ValueError): E2E.read_process_registry(self.filename)
        self.filename.write_text('')
        with self.assertRaises(ValueError): E2E.read_process_registry(self.filename)
        self.write({**self.entry, 'signature': '4321 9999 Sat Sep 26 10:00:00 2026'})
        with self.assertRaisesRegex(ValueError, 'ownership'): E2E.read_process_registry(self.filename)
        target = self.filename.with_name('target'); target.write_text('')
        self.filename.unlink(); self.filename.symlink_to(target)
        with self.assertRaises(ValueError): E2E.read_process_registry(self.filename)

    def test_unmatched_close_duplicate_start_and_modified_signature_are_rejected(self):
        for records in [({**self.entry, 'event': 'close'},), (self.entry, self.entry),
                        (self.entry, {**self.entry, 'event': 'close', 'signature': '4321 4321 different time'})]:
            self.write(*records)
            with self.subTest(records=records), self.assertRaises(ValueError): E2E.read_process_registry(self.filename)
        self.write(self.entry, {**self.entry, 'event': 'close'})
        self.assertEqual(E2E.read_process_registry(self.filename), [])

    def test_stale_or_unowned_pid_is_never_killed(self):
        with patch.object(E2E, 'process_signature', return_value='4321 4321 different creation time'), patch.object(E2E.os, 'killpg') as kill:
            with self.assertRaisesRegex(ValueError, 'signature changed'): E2E.cleanup_owned_processes(self.filename)
            kill.assert_not_called()
        with patch.object(E2E, 'process_signature', return_value=None), patch.object(E2E.os, 'killpg') as kill:
            self.assertEqual(E2E.cleanup_owned_processes(self.filename), [])
            kill.assert_not_called()

    def test_only_registered_identical_group_is_killed_and_waited_for(self):
        with patch.object(E2E, 'process_signature', side_effect=[self.entry['signature'], None]), patch.object(E2E.os, 'killpg') as kill:
            self.assertEqual(E2E.cleanup_owned_processes(self.filename), [4321])
            kill.assert_called_once_with(4321, E2E.signal.SIGKILL)

    def test_permission_failure_or_a_group_that_wont_exit_is_not_green(self):
        with patch.object(E2E, 'process_signature', return_value=self.entry['signature']), \
                patch.object(E2E.os, 'killpg', side_effect=PermissionError('denied')):
            with self.assertRaisesRegex(ValueError, 'cleanup failed'): E2E.cleanup_owned_processes(self.filename)
        with patch.object(E2E, 'process_signature', return_value=self.entry['signature']), \
                patch.object(E2E.os, 'killpg'), patch.object(E2E.time, 'monotonic', side_effect=[0, 6]):
            with self.assertRaisesRegex(ValueError, 'did not exit'): E2E.cleanup_owned_processes(self.filename)

    def test_ps_uses_exact_pid_and_kernel_creation_time_without_process_scanning(self):
        with patch.object(E2E.subprocess, 'run') as query:
            query.return_value.returncode = 0; query.return_value.stdout = self.entry['signature']
            self.assertEqual(E2E.process_signature(4321), self.entry['signature'])
            self.assertEqual(query.call_args.args[0], ['/bin/ps', '-p', '4321', '-o', 'pid=,pgid=,lstart='])
            self.assertFalse(query.call_args.kwargs['shell'])


class WorkflowTests(unittest.TestCase):
    def test_impact_preserves_docs_only_and_unknown_fails_closed(self):
        self.assertFalse(classify(['docs/README.md'])['desktop'])
        self.assertFalse(classify(['integrations/goland/src/test/kotlin/Test.kt'])['desktop'])
        for paths in [[], ['unknown/new.file'], ['src/main/index.ts'], ['src/preload/index.ts'],
                      ['src/renderer/App.tsx'], ['src/shared/goland-workspace.ts'], ['package-lock.json'],
                      ['forge.config.ts'], ['playwright.packaged.config.ts'], ['tests/e2e/desktop/example.spec.ts'],
                      ['integrations/goland/src/main/kotlin/com/reqws/goland/project/ReqwsProjectService.kt']]:
            with self.subTest(paths=paths): self.assertTrue(classify(paths)['desktop'])

    def test_every_required_failure_cancel_skip_or_missing_result_is_red(self):
        impact = {'docsOnly': False, 'desktop': True}
        baseline = {'impact': 'success', 'docs': 'skipped', 'checks': 'success', 'package': 'success', 'e2e': 'success'}
        self.assertEqual(aggregate_desktop(impact, baseline), 'passed')
        for key in ('impact', 'checks', 'package', 'e2e'):
            for value in ('failure', 'cancelled', 'skipped', None):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    aggregate_desktop(impact, {**baseline, key: value})

    def test_docs_and_explicit_no_desktop_impact_have_exact_skip_contracts(self):
        self.assertEqual(aggregate_desktop({'docsOnly': True, 'desktop': False},
            {'impact': 'success', 'docs': 'success', 'checks': 'skipped', 'package': 'skipped', 'e2e': 'skipped'}), 'passed')
        self.assertEqual(aggregate_desktop({'docsOnly': False, 'desktop': False},
            {'impact': 'success', 'docs': 'skipped', 'checks': 'success', 'package': 'success', 'e2e': 'skipped'}), 'passed')
        for invalid in ({'docsOnly': True}, {'desktop': True}, {'docsOnly': '', 'desktop': False}, {'docsOnly': True, 'desktop': True}):
            with self.assertRaises(ValueError): aggregate_desktop(invalid, {'impact': 'success'})

    def test_actual_yaml_aggregate_rejects_cancelled_e2e_and_missing_impact(self):
        gate = load_yaml('.github/workflows/ci.yml')['jobs']['checks']
        self.assertEqual(gate['name'], 'Checks and macOS package smoke')
        self.assertEqual(gate['if'], '${{ always() }}')
        self.assertIn('desktop-e2e', gate['needs'])
        script = gate['steps'][-1]['run']
        environment = {**os.environ, 'DOCS_ONLY': 'false', 'DESKTOP_REQUIRED': 'true', 'DOCS_RESULT': 'skipped',
                       'IMPACT_RESULT': 'success', 'CHECK_RESULT': 'success', 'PACKAGE_RESULT': 'success', 'E2E_RESULT': 'success'}
        passing = subprocess.run(['bash', '-e', '-c', script], cwd=ROOT, env=environment, capture_output=True)
        self.assertEqual(passing.returncode, 0, passing.stderr)
        for key, value in [('E2E_RESULT', 'cancelled'), ('E2E_RESULT', ''), ('DESKTOP_REQUIRED', ''), ('DOCS_ONLY', ''), ('IMPACT_RESULT', 'failure')]:
            with self.subTest(key=key, value=value):
                self.assertNotEqual(subprocess.run(['bash', '-e', '-c', script], cwd=ROOT,
                    env={**environment, key: value}, capture_output=True).returncode, 0)

    def test_jobs_run_source_and_exact_existing_package_keep_short_failure_artifacts(self):
        ci = load_yaml('.github/workflows/ci.yml'); jobs = ci['jobs']
        source = jobs['desktop-e2e']; packaged = jobs['macos-package']
        self.assertEqual(source['if'], "${{ needs.impact.outputs.desktop == 'true' }}")
        self.assertEqual(source['runs-on'], 'macos-15')
        source_runs = [step.get('run', '') for step in source['steps']]
        self.assertIn('python3 scripts/desktop_e2e.py --mode source', source_runs)
        self.assertIn('python3 scripts/desktop_e2e.py --mode negative', source_runs)
        package_runs = [step.get('run', '') for step in packaged['steps']]
        self.assertEqual(sum('package:macos' in step for step in package_runs), 1)
        smoke = 'python3 scripts/desktop_e2e.py --mode packaged --app out/ReqWS-darwin-arm64/ReqWS.app'
        self.assertIn(smoke, package_runs)
        self.assertLess(next(index for index, step in enumerate(package_runs) if 'package:macos' in step), package_runs.index(smoke))
        for job in (source, packaged):
            upload = job['steps'][-1]
            self.assertEqual(upload['if'], '${{ always() }}')
            self.assertLessEqual(upload['with']['retention-days'], 7)
            self.assertNotIn('/Users/runner', upload['with']['path'])
            self.assertNotIn('continue-on-error', job)
            self.assertFalse(any(step.get('continue-on-error') for step in job['steps']))
        for forbidden in ('run_local_ide.py', 'runIdeWithDriver', 'JETBRAINS_LICENSE_SERVER', 'secrets.', 'id-token'):
            self.assertNotIn(forbidden, json.dumps(ci))
        config = (ROOT / 'playwright.packaged.config.ts').read_text()
        self.assertNotIn('globalSetup:', config)
        self.assertIn('workers: 1', config); self.assertIn('retries: 0', config)


if __name__ == '__main__':
    unittest.main()
