"""Keep API-only verification off scarce native runners without reducing its gates."""

import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load_workflow(name):
    return json.loads(subprocess.check_output([
        'node', '-e',
        'const fs=require("fs"),yaml=require("js-yaml");'
        'process.stdout.write(JSON.stringify(yaml.load(fs.readFileSync(process.argv[1],"utf8"))))',
        str(ROOT / '.github/workflows' / name),
    ], cwd=ROOT, text=True))


class ApiRunnerPerformanceTests(unittest.TestCase):
    def test_full_api_matrix_uses_linux_and_the_existing_immutable_candidate(self):
        job = load_workflow('goland-verification.yml')['jobs']['api']
        self.assertEqual(job['runs-on'], 'ubuntu-24.04')
        self.assertEqual(job['strategy'], {
            'fail-fast': False, 'matrix': '${{ fromJSON(inputs.matrix) }}',
        })
        self.assertNotIn('continue-on-error', job)
        verify = next(step for step in job['steps']
                      if step.get('name') == 'Verify exact candidate and frozen target')
        self.assertNotIn('continue-on-error', verify)
        self.assertIn('run_ide_verifier.py --snapshot targets/targets.json --archive candidate/plugin.zip', verify['run'])
        self.assertIn('--target "$TARGET"', verify['run'])
        self.assertIn('--historical', verify['run'])
        for forbidden in ('buildPlugin', 'runIdeWithDriver', 'checkIdeIntegration', 'JETBRAINS_LICENSE_SERVER'):
            self.assertNotIn(forbidden, json.dumps(job))

    def test_aggregate_still_requires_complete_reports_for_the_same_bytes(self):
        job = load_workflow('goland-verification.yml')['jobs']['result']
        self.assertEqual(job['needs'], ['api'])
        self.assertEqual(job['if'], '${{ always() }}')
        runs = '\n'.join(step.get('run', '') for step in job['steps'])
        self.assertIn('test "$API_RESULT" = success', runs)
        self.assertIn('--archive candidate/plugin.zip --output api-results --summarize', runs)
        self.assertIn("'scope': 'ci-api'", runs)
        self.assertIn("'localIntegration': {'status': 'not-run'", runs)

    def test_native_build_rendering_and_desktop_checks_remain_on_macos(self):
        for workflow, names in (
            ('ci.yml', ('project-checks', 'macos-package', 'desktop-e2e', 'plugin-build')),
            ('release.yml', ('checks', 'package', 'goland-plugin')),
            ('goland-compose.yml', ('components',)),
        ):
            jobs = load_workflow(workflow)['jobs']
            for name in names:
                with self.subTest(workflow=workflow, job=name):
                    self.assertEqual(jobs[name]['runs-on'], 'macos-15')


if __name__ == '__main__':
    unittest.main()
