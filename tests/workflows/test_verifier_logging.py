"""Real disposable child processes prove streaming, liveness and scoped timeout cleanup."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from verifier_process import collect_diagnostics, process_group_rows, run_logged_process
import run_ide_verifier as runner
from ide_compatibility import digest


class ObservedOutput(io.StringIO):
    def __init__(self, needle):
        super().__init__()
        self.needle = needle
        self.observed = threading.Event()

    def write(self, value):
        result = super().write(value)
        if self.needle in self.getvalue():
            self.observed.set()
        return result


class VerifierLoggingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.log = self.root / 'gradle.log'

    def test_partial_output_reaches_console_before_child_exits_and_keeps_utf8_and_stderr(self):
        release = self.root / 'release'
        code = """import os, pathlib, sys, time
os.write(1, b'> Task :verifyPlugin\\nLIVE ' + '好'.encode()[:1])
while not pathlib.Path(sys.argv[1]).exists(): time.sleep(.01)
os.write(1, '好'.encode()[1:] + b'\\n')
os.write(2, b'final stderr\\n')
sys.exit(7)
"""
        output = ObservedOutput('LIVE ')
        with redirect_stdout(output), ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(run_logged_process, [sys.executable, '-c', code, str(release)], self.log,
                                 'GO-test', timeout=5, heartbeat=.1, silence=10)
            try:
                self.assertTrue(output.observed.wait(3), 'output was buffered until completion')
                self.assertFalse(future.done())
            finally:
                release.touch()
            self.assertEqual(future.result(timeout=5), 7)
        self.assertIn('LIVE 好\nfinal stderr\n', self.log.read_text())
        self.assertIn('LIVE ', output.getvalue())
        self.assertIn('好\nfinal stderr\n', output.getvalue())
        self.assertNotIn('\ufffd', output.getvalue())
        progress = json.loads((self.root / 'progress.json').read_text())
        self.assertEqual((progress['state'], progress['exitCode'], progress['phase']), ('exited', 7, ':verifyPlugin'))

    def test_silent_child_emits_heartbeat_and_bounded_diagnostics_without_changing_exit(self):
        release = self.root / 'release'
        captures = []
        def diagnose(process, directory, reason, target):
            captures.append((process.pid, reason, target))
            if len(captures) == 3:
                release.touch()
            raise OSError('diagnostic fixture is unavailable')
        code = "import pathlib,sys,time\np=pathlib.Path(sys.argv[1])\nwhile not p.exists(): time.sleep(.01)"
        with redirect_stdout(io.StringIO()) as output:
            result = run_logged_process([sys.executable, '-c', code, str(release)], self.log,
                                        'GO-quiet', timeout=5, heartbeat=.02, silence=.05, diagnose=diagnose)
        self.assertEqual(result, 0)
        self.assertEqual(len(captures), 3)
        self.assertIn('heartbeat:', output.getvalue())
        self.assertIn('silent=', output.getvalue())
        self.assertIn('diagnostic failed: OSError', output.getvalue())

    def test_timeout_keeps_output_stops_only_owned_group_and_diagnoses_before_stop(self):
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], start_new_session=True)
        samples = []
        def diagnose(process, directory, reason, target):
            samples.append((process.pid, reason, process.poll()))
        try:
            with redirect_stdout(io.StringIO()), self.assertRaises(subprocess.TimeoutExpired):
                run_logged_process([sys.executable, '-c', "import time; print('before stall', flush=True); time.sleep(30)"],
                                   self.log, 'GO-timeout', timeout=1, heartbeat=.05, silence=10, diagnose=diagnose)
            self.assertEqual(samples[0][1:], ('timeout', None))
            with self.assertRaises(ProcessLookupError): os.kill(samples[0][0], 0)
            self.assertIsNone(unrelated.poll())
            self.assertIn('before stall', self.log.read_text())
            self.assertEqual(json.loads((self.root / 'progress.json').read_text())['state'], 'stopped')
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=3)

    def test_process_sample_excludes_other_groups_and_command_arguments(self):
        sample = (' 10 1 10 00:10 0.0 500 S /sdk/bin/java\n'
                  ' 11 10 10 00:09 35.2 1000 R /sdk with spaces/bin/java\n'
                  ' 20 1 20 01:00 1.0 700 S /daily/GoLand.app/Contents/MacOS/goland\n')
        rows = process_group_rows(sample, 10)
        self.assertEqual([row['pid'] for row in rows], [10, 11])
        self.assertEqual([row['executable'] for row in rows], ['java', 'java'])
        self.assertNotIn('/sdk', json.dumps(rows))

    def test_cancellation_during_diagnostics_is_not_suppressed(self):
        captured = []
        def diagnose(process, directory, reason, target):
            captured.append(process.pid)
            raise KeyboardInterrupt('cancelled during diagnostic')
        with redirect_stdout(io.StringIO()), self.assertRaisesRegex(KeyboardInterrupt, 'cancelled during diagnostic'):
            run_logged_process([sys.executable, '-c', 'import time; time.sleep(30)'], self.log,
                               'GO-cancel', timeout=5, heartbeat=.02, silence=.05, diagnose=diagnose)
        with self.assertRaises(ProcessLookupError): os.kill(captured[0], 0)

    def test_diagnostic_failure_is_preserved_without_raising(self):
        class Process:
            pid = 999999
        with patch('verifier_process.subprocess.run', side_effect=OSError('ps unavailable')), redirect_stdout(io.StringIO()):
            collect_diagnostics(Process(), self.root / 'diagnostics', 'silent', 'GO-test')
        self.assertIn('ps unavailable', (self.root / 'diagnostics/diagnostic-error.txt').read_text())

    def test_timeout_or_cancellation_cannot_produce_a_passing_target_result(self):
        archive = self.root / 'candidate.zip'; archive.write_bytes(b'candidate')
        snapshot = self.root / 'snapshot.json'; snapshot.write_text('{}')
        target = {'id': 'GO-262.8665.270', 'version': '2026.2', 'build': '262.8665.270'}
        for name, failure in [('timeout', subprocess.TimeoutExpired(['gradlew'], 5400)),
                              ('cancelled', InterruptedError('cancelled by SIGTERM')),
                              ('keyboard', KeyboardInterrupt())]:
            with self.subTest(name=name), patch.object(runner, 'validate_plugin', return_value={'sha256': digest(archive)}), \
                    patch.object(runner, 'audit_archive', return_value={'present': False}), \
                    patch.object(runner, 'run_logged_process', side_effect=failure), redirect_stdout(io.StringIO()):
                result = runner.run_target(snapshot, target, archive, '1.2.3', self.root / name)
            self.assertEqual(result['status'], 'infrastructure-blocked')
            self.assertIsNone(result['exitCode'])
            self.assertTrue((self.root / name / 'result.json').is_file())
            self.assertGreaterEqual(result['durationSeconds'], 0)


if __name__ == '__main__':
    unittest.main()
