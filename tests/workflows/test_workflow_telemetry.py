"""Only the pinned, summary-only monitor is exempt from business-step gates."""

import copy
import json
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
TELEMETRY_STEP = {
    'name': 'Collect workflow telemetry',
    'uses': 'ycfreeman/workflow-telemetry-action@72ec425db8a31670fdc4419966940a6d980a0b49',
    'continue-on-error': True,
    'with': {
        'github_token': '${{ github.token }}',
        'metric_frequency': '5',
        'comment_on_pr': 'false',
        'job_summary': 'true',
        'proc_trace_chart_show': 'false',
        'proc_trace_table_show': 'false',
    },
}


EXPORT_STEP = {
    'name': 'Export workflow telemetry', 'id': 'telemetry-export',
    'if': '${{ always() }}', 'continue-on-error': True, 'timeout-minutes': 1,
    'shell': 'bash', 'run': 'python3 scripts/export_workflow_telemetry.py',
}
UPLOAD_STEP = {
    'name': 'Upload workflow telemetry',
    'if': "${{ always() && steps.telemetry-export.outputs.file != '' }}",
    'continue-on-error': True, 'timeout-minutes': 2,
    'uses': 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a',
    'with': {
        'name': '${{ steps.telemetry-export.outputs.artifact }}',
        'path': '${{ steps.telemetry-export.outputs.file }}',
        'if-no-files-found': 'error', 'retention-days': 7,
    },
}


def inline_export():
    return "python3 - <<'PYTHON'\n" + (ROOT / 'scripts/export_workflow_telemetry.py').read_text() + 'PYTHON\n'


def steps_before_export(testcase, job):
    """Exclude only the exact, bounded exporter pair; never a business gate."""
    steps = job.get('steps', [])
    testcase.assertGreaterEqual(len(steps), 3)
    expected = dict(EXPORT_STEP)
    if steps[-2].get('run') != expected['run']:
        expected['run'] = inline_export()
    testcase.assertEqual(steps[-2], expected)
    testcase.assertEqual(steps[-1], UPLOAD_STEP)
    for step in steps[-2:]:
        testcase.assertIs(step['continue-on-error'], True)
    testcase.assertFalse(any(step.get('id') == 'telemetry-export' for step in steps[:-2]))
    return steps[:-2]


def steps_after_telemetry(testcase, job):
    """Validate the exact first monitor, then retain every business step in order."""
    steps = steps_before_export(testcase, job)
    testcase.assertGreaterEqual(len(steps), 2)
    testcase.assertEqual(steps[0], TELEMETRY_STEP)
    testcase.assertIs(steps[0]['continue-on-error'], True)
    testcase.assertFalse(any('workflow-telemetry-action' in step.get('uses', '') for step in steps[1:]))
    return steps[1:]


class TelemetryStepTests(unittest.TestCase):
    def fixture(self):
        return {'steps': [copy.deepcopy(TELEMETRY_STEP), {'run': 'run-required-check'},
                          copy.deepcopy(EXPORT_STEP), copy.deepcopy(UPLOAD_STEP)]}

    def test_preserves_business_steps_and_does_not_mutate_the_job(self):
        job = self.fixture()
        job['steps'].insert(-2, {'uses': 'actions/checkout@pinned-fixture'})
        before = copy.deepcopy(job)
        remaining = steps_after_telemetry(self, job)
        self.assertEqual(remaining, before['steps'][1:-2])
        self.assertIs(remaining[0], job['steps'][1])
        self.assertEqual(job, before)

    def test_rejects_absent_reordered_and_duplicate_monitors(self):
        monitor, business, export, upload = self.fixture()['steps']
        for steps in ([], [monitor], [business], [business, monitor], [monitor, monitor, business]):
            with self.subTest(steps=steps), self.assertRaises(AssertionError):
                steps_after_telemetry(self, {'steps': steps + [export, upload]})
        with self.assertRaises(AssertionError):
            steps_after_telemetry(self, {})

    def test_rejects_unpinned_changed_or_privileged_monitor_settings(self):
        for key, value in (
            ('uses', 'ycfreeman/workflow-telemetry-action@v3.0.2'),
            ('uses', 'other/workflow-telemetry-action@' + 'a' * 40),
            ('continue-on-error', False), ('continue-on-error', 'true'), ('continue-on-error', 1),
            ('if', '${{ always() }}'), ('env', {'TOKEN': '${{ secrets.PRIVATE_TOKEN }}'}),
        ):
            job = self.fixture(); job['steps'][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(AssertionError):
                steps_after_telemetry(self, job)
        for key in TELEMETRY_STEP:
            job = self.fixture(); del job['steps'][0][key]
            with self.subTest(missing=key), self.assertRaises(AssertionError):
                steps_after_telemetry(self, job)
        for key in TELEMETRY_STEP['with']:
            for remove in (False, True):
                job = self.fixture()
                if remove:
                    del job['steps'][0]['with'][key]
                else:
                    job['steps'][0]['with'][key] = 'changed'
                with self.subTest(option=key, remove=remove), self.assertRaises(AssertionError):
                    steps_after_telemetry(self, job)

    def test_business_continue_on_error_is_not_filtered_out(self):
        job = self.fixture(); job['steps'][1]['continue-on-error'] = True
        remaining = steps_after_telemetry(self, job)
        self.assertEqual(remaining, [job['steps'][1]])
        # The Desktop gate's original assertion must still reject this mutation.
        with self.assertRaises(AssertionError):
            self.assertFalse(any(step.get('continue-on-error') for step in remaining))


    def test_export_pair_rejects_missing_reordered_duplicate_or_broadened_steps(self):
        for mutation in ('missing', 'reordered', 'duplicate', 'script', 'path', 'pin', 'condition', 'blocking'):
            job = self.fixture()
            if mutation == 'missing': job['steps'].pop()
            elif mutation == 'reordered': job['steps'][-2:] = job['steps'][-2:][::-1]
            elif mutation == 'duplicate': job['steps'].insert(1, copy.deepcopy(EXPORT_STEP))
            elif mutation == 'script': job['steps'][-2]['run'] = 'python3 other.py'
            elif mutation == 'path': job['steps'][-1]['with']['path'] = '${{ runner.temp }}'
            elif mutation == 'pin': job['steps'][-1]['uses'] = 'actions/upload-artifact@main'
            elif mutation == 'condition': job['steps'][-2]['if'] = '${{ success() }}'
            else: job['steps'][-1]['continue-on-error'] = False
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                steps_after_telemetry(self, job)


class TelemetryWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        paths = sorted(path.relative_to(ROOT).as_posix() for path in (ROOT / '.github/workflows').iterdir()
                       if path.suffix in ('.yml', '.yaml'))
        output = subprocess.check_output([
            'node', '-e',
            'const fs=require("fs");const yaml=require("js-yaml");'
            'process.stdout.write(JSON.stringify(Object.fromEntries(process.argv.slice(1).map('
            'path=>[path,yaml.load(fs.readFileSync(path,"utf8"))]))))',
            *paths,
        ], cwd=ROOT, text=True)
        cls.workflows = json.loads(output)

    def test_every_runner_job_starts_one_monitor_including_reusable_workflows(self):
        self.assertTrue(self.workflows)
        count = 0
        for path, workflow in self.workflows.items():
            for name, job in workflow['jobs'].items():
                with self.subTest(workflow=path, job=name):
                    if 'runs-on' in job:
                        steps_after_telemetry(self, job)
                        expected_run = inline_export() if path.endswith('/cleanup-pr-cache.yml') else EXPORT_STEP['run']
                        self.assertEqual(job['steps'][-2]['run'], expected_run)
                        count += 1
                    else:
                        self.assertNotIn('steps', job)
                        self.assertIn(job['uses'].removeprefix('./'), self.workflows)
        self.assertGreater(count, 0)

    def test_runner_and_reusable_caller_permissions_allow_reading_job_metadata(self):
        for path, workflow in self.workflows.items():
            for name, job in workflow['jobs'].items():
                with self.subTest(workflow=path, job=name):
                    permissions = job.get('permissions', workflow.get('permissions', {}))
                    self.assertIn(permissions.get('actions'), ('read', 'write'))
                    self.assertNotIn('pull-requests', permissions)

    def test_business_failures_remain_blocking_except_the_existing_cache_report(self):
        for path, workflow in self.workflows.items():
            for name, job in workflow['jobs'].items():
                with self.subTest(workflow=path, job=name):
                    self.assertNotIn('continue-on-error', job)
                    if 'runs-on' not in job:
                        continue
                    for step in steps_after_telemetry(self, job):
                        if step.get('continue-on-error'):
                            self.assertEqual((path, name, step.get('name')),
                                             ('.github/workflows/ci.yml', 'checks', 'Report repository cache usage'))
                            self.assertIs(step['continue-on-error'], True)


if __name__ == '__main__':
    unittest.main()
