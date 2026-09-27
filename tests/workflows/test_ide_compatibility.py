"""Compatibility policy, frozen matrices and failure semantics without network or an IDE."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from ide_compatibility import check_descriptor, digest, load_snapshot, read_policy, resolve_catalog, write_json
from ide_ci import aggregate, classify
from run_ide_verifier import classify as verdict, summarize
import run_ide_verifier as verifier_runner
from check_ide_test_reports import check_reports
from test_ci_cache_config import load_yaml


def release(version, build, channel='release'):
    return {'version': version, 'build': build, 'type': channel, 'date': '2026-09-21'}


def catalog():
    return {'GO': [release('2026.2', '262.8665.270'), release('2026.2.1.1', '262.9437.286'),
                   release('2026.2.9', '262.12345.9'), release('2026.2.10', '262.12345.10'),
                   release('2026.3', '263.100.1'), release('2026.3.1', '263.200.1')]}


class TargetTests(unittest.TestCase):
    def test_pr_numeric_first_latest_and_fixed_targets(self):
        values = resolve_catalog(catalog(), read_policy(), 'pr')['targets']
        self.assertEqual([item['version'] for item in values], ['2026.2', '2026.2.1.1', '2026.2.10', '2026.3', '2026.3.1'])

    def test_full_scan_includes_middle_releases_and_deduplicates_product_build(self):
        data = catalog()
        data['GO'] += [data['GO'][0], release('2027.1', '271.100', 'eap'), release('2026.1', '261.100')]
        data['IU'] = [release('2026.3.2', '263.300')]
        targets = resolve_catalog(data, read_policy(), 'full')['targets']
        self.assertEqual(len(targets), 6)
        self.assertTrue(all(item['product'] == 'GO' and item['required'] for item in targets))

    def test_regression_middle_release_is_required_for_pr(self):
        policy = {**read_policy(), 'regressionVersions': 'GO:2026.2.9'}
        self.assertIn('2026.2.9', [item['version'] for item in resolve_catalog(catalog(), policy)['targets']])

    def test_missing_empty_conflicting_and_earlier_baseline_catalogs_fail(self):
        examples = [{}, {'GO': []}, {'GO': catalog()['GO'][1:]},
                    {'GO': catalog()['GO'] + [release('2026.2.0', '262.8665.270')]},
                    {'GO': catalog()['GO'] + [release('2026.2', '262.8000.1')]}]
        for data in examples:
            with self.subTest(data=data), self.assertRaises(ValueError):
                resolve_catalog(data, read_policy())

    def test_snapshot_cannot_omit_baseline_or_use_old_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'targets.json'
            snapshot = resolve_catalog(catalog(), read_policy(), fetched_at='2020-01-01T00:00:00+00:00')
            write_json(path, snapshot)
            with self.assertRaises(ValueError): load_snapshot(path, fresh=True)
            snapshot['targets'] = snapshot['targets'][1:]
            write_json(path, snapshot)
            with self.assertRaises(ValueError): load_snapshot(path)

    def test_descriptor_rejects_all_floor_and_upper_mutations(self):
        deps = ''.join(f'<depends>com.intellij.modules.{module}</depends>' for module in ['platform', 'goland', 'vcs'])
        check_descriptor(ET.fromstring(f'<idea-plugin><idea-version since-build="262"/>{deps}</idea-plugin>'))
        for attributes in ['since-build="262.*"', 'since-build="262.9437.286"', 'since-build="263"',
                           'since-build="262" until-build="999.*"', 'since-build="262" strict-until-build="263"']:
            with self.subTest(attributes=attributes), self.assertRaises(ValueError):
                check_descriptor(ET.fromstring(f'<idea-plugin><idea-version {attributes}/>{deps}</idea-plugin>'))


class VerdictTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.target = resolve_catalog(catalog(), read_policy())['targets'][0]
        self.report = self.root / self.target['id'] / 'plugins/com.reqws.workspace/1.2.3'
        self.report.mkdir(parents=True)

    def test_exit_zero_without_report_is_infrastructure_failure(self):
        self.assertEqual(verdict(self.root, self.target, '1.2.3', 0)[0], 'infrastructure-blocked')

    def test_extracted_plugin_contents_cannot_impersonate_verifier_evidence(self):
        fake = self.root / 'verifier-home/extracted-plugins' / self.target['id'] / 'plugins/com.reqws.workspace/1.2.3'
        fake.mkdir(parents=True)
        (fake / 'verification-verdict.txt').write_text('Compatible')
        self.assertEqual(verdict(self.root, self.target, '1.2.3', 0)[0], 'infrastructure-blocked')
        (self.report / 'verification-verdict.txt').write_text('Compatible')
        self.assertEqual(verdict(self.root, self.target, '1.2.3', 0)[0], 'passed')

    def test_known_terminal_verdict_required(self):
        file = self.report / 'verification-verdict.txt'
        for message, code, status in [('Compatible', 0, 'passed'), ('Compatible', 1, 'infrastructure-blocked'),
                                      ('Unable to download', 0, 'infrastructure-blocked'),
                                      ('2 compatibility problems', 0, 'incompatible'), ('', 0, 'infrastructure-blocked')]:
            file.write_text(message)
            self.assertEqual(verdict(self.root, self.target, '1.2.3', code)[0], status)

    def test_production_internal_or_experimental_usage_blocks_even_with_compatible_verdict(self):
        (self.report / 'verification-verdict.txt').write_text('Compatible')
        for name in ['internal-api-usages.txt', 'experimental-api-usages.txt']:
            with self.subTest(report=name):
                report = self.report / name
                report.write_text('SomeClass.restrictedMethod()')
                self.assertEqual(verdict(self.root, self.target, '1.2.3', 0)[0], 'incompatible')
                report.unlink()

    def test_summary_requires_exact_candidate_snapshot_and_every_target(self):
        snapshot = resolve_catalog(catalog(), read_policy())
        snapshot_path = self.root / 'targets.json'
        write_json(snapshot_path, snapshot)
        archive = self.root / 'plugin.zip'; archive.write_bytes(b'candidate')
        results = self.root / 'results'
        for target in snapshot['targets']:
            write_json(results / target['id'] / 'result.json', {
                'target': target, 'status': 'passed', 'candidateSha256': digest(archive),
                'snapshotSha256': digest(snapshot_path),
            })
        summarize(snapshot_path, archive, results)
        archive.write_bytes(b'replaced after signing')
        with self.assertRaises(ValueError): summarize(snapshot_path, archive, results)
        archive.write_bytes(b'candidate')
        (results / snapshot['targets'][0]['id'] / 'result.json').unlink()
        with self.assertRaisesRegex(ValueError, 'Required API results are missing'):
            summarize(snapshot_path, archive, results)

    def test_full_runner_rejects_archive_replaced_between_successful_targets(self):
        snapshot = resolve_catalog(catalog(), read_policy(), mode='full')
        snapshot_path = self.root / 'targets.json'
        write_json(snapshot_path, snapshot)
        archive = self.root / 'plugin.zip'; archive.write_bytes(b'first candidate')
        results = self.root / 'results'

        def successful_target(snapshot_path, target, archive, version, output, historical):
            if target != snapshot['targets'][0]:
                archive.write_bytes(b'replacement candidate')
            result = {'target': target, 'status': 'passed', 'candidateSha256': digest(archive),
                      'snapshotSha256': digest(snapshot_path), 'version': version}
            write_json(output / 'result.json', result)
            return result

        arguments = ['run_ide_verifier.py', '--snapshot', str(snapshot_path), '--archive', str(archive),
                     '--version', '1.2.3', '--output', str(results)]
        with patch.object(sys, 'argv', arguments), patch.object(verifier_runner, 'run_target', successful_target):
            with self.assertRaisesRegex(ValueError, 'different candidate bytes'):
                verifier_runner.main()


class ImpactTests(unittest.TestCase):
    def test_docs_logic_model_contract_and_unknown_paths(self):
        self.assertEqual((classify(['docs/README.md'])['plugin'], classify(['docs/README.md'])['docsOnly']), (False, True))
        self.assertTrue(classify(['integrations/goland/src/main/kotlin/com/reqws/goland/manifest/ManifestParser.kt'])['plugin'])
        self.assertFalse(classify(['integrations/goland/src/main/kotlin/com/reqws/goland/manifest/ManifestParser.kt'])['localIntegrationRecommended'])
        for path in ['integrations/goland/src/main/kotlin/com/reqws/goland/project/ReqwsProjectService.kt',
                     'src/shared/workspace.ts', 'src/main/goland.ts', '.github/workflows/ci.yml', 'scripts/ide_ci.py',
                     'integrations/goland/compatibility.properties', 'integrations/goland/src/integrationTest/Example.kt']:
            self.assertTrue(classify([path])['localIntegrationRecommended'])
        self.assertTrue(classify([])['localIntegrationRecommended'])
        # Deleted/renamed old paths are passed by git --no-renames along with their destinations.
        self.assertTrue(classify(['docs/new.md', 'integrations/goland/build.gradle.kts'])['localIntegrationRecommended'])

    def test_required_failed_cancelled_skipped_or_missing_child_never_passes(self):
        results = dict.fromkeys(['impact', 'targets', 'build', 'verification'], 'success')
        self.assertEqual(aggregate({'plugin': True}, results), 'passed')
        for key in results:
            for state in ('failure', 'cancelled', 'skipped', None):
                with self.subTest(key=key, state=state), self.assertRaises(ValueError):
                    aggregate({'plugin': True}, {**results, key: state})
        self.assertTrue(aggregate({'plugin': False, 'reason': 'docs'}, {
            'impact': 'success', 'targets': 'skipped', 'build': 'skipped', 'verification': 'skipped'}).startswith('not-applicable'))

    def test_workflows_use_same_candidate_without_api_parallel_cap(self):
        ci = load_yaml('.github/workflows/ci.yml')['jobs']
        self.assertEqual(ci['goland-plugin']['name'], 'GoLand plugin checks')
        self.assertEqual(ci['goland-plugin']['if'], '${{ always() }}')
        reusable = load_yaml('.github/workflows/goland-verification.yml')
        self.assertNotIn('max-parallel', reusable['jobs']['api']['strategy'])
        self.assertFalse(reusable['jobs']['api']['strategy']['fail-fast'])
        self.assertNotIn('secrets.', json.dumps(reusable))
        self.assertNotIn('buildPlugin', json.dumps(reusable))
        self.assertNotIn('integration', reusable['jobs'])
        self.assertEqual(reusable['jobs']['result']['needs'], ['api'])
        self.assertIn("'scope': 'ci-api'", json.dumps(reusable))
        self.assertIn("'status': 'not-run'", json.dumps(reusable))
        for name in ['ci.yml', 'release.yml', 'goland-weekly.yml', 'goland-verification.yml']:
            workflow = json.dumps(load_yaml('.github/workflows/' + name))
            for forbidden in ['checkIdeIntegration', 'prepareLocalIdeAuthorization', 'run_local_ide.py',
                              'runIdeWithDriver', 'JETBRAINS_LICENSE_SERVER', 'REQWS_IDE_LICENSE_SERVER']:
                self.assertNotIn(forbidden, workflow)
        baseline = json.dumps(ci['plugin-build'])
        self.assertIn('compileIntegrationTestKotlin', baseline)
        self.assertIn(' test verifyBaselineTestReports ', baseline)
        self.assertIn('verifyForbiddenProductionSymbols', baseline)
        self.assertNotIn('--exclude-task test', baseline)

        release_jobs = load_yaml('.github/workflows/release.yml')['jobs']
        self.assertIn('compileIntegrationTestKotlin', json.dumps(release_jobs['goland-plugin']))
        self.assertIn('plugin-verification', release_jobs['publish']['needs'])
        self.assertNotIn('integration', release_jobs['plugin-verification']['with'])
        self.assertIn('candidateSha256', json.dumps(release_jobs['publish']))
        weekly = load_yaml('.github/workflows/goland-weekly.yml')
        self.assertIn('compileIntegrationTestKotlin', json.dumps(weekly['jobs']['main-candidate']))
        self.assertIn('schedule', weekly['on'])
        self.assertNotEqual(weekly['jobs']['main-api']['with']['candidate-artifact'], weekly['jobs']['released-api']['with']['candidate-artifact'])
        self.assertIn('main-api', weekly['jobs']['released-api']['needs'])
        self.assertIn('!cancelled()', weekly['jobs']['released-api']['if'])
        self.assertNotIn('needs.main-api.result', weekly['jobs']['released-api']['if'])

    def test_every_baseline_entrypoint_retains_exact_archive_api_gate_and_reports(self):
        for workflow, job_name in [('ci.yml', 'plugin-build'), ('goland-weekly.yml', 'main-candidate'),
                                   ('release.yml', 'goland-plugin')]:
            job = load_yaml('.github/workflows/' + workflow)['jobs'][job_name]
            command = next(step['run'] for step in job['steps']
                           if 'verifyPluginProjectConfiguration' in step.get('run', ''))
            with self.subTest(workflow=workflow):
                self.assertNotRegex(command, r'\bverifyPlugin\b')
                self.assertIn('run_ide_verifier.py --baseline', command)
                self.assertIn('--archive "$(cat integrations/goland/build/release/plugin-archive.txt)"', command)
                for task in ('compileIntegrationTestKotlin', 'test', 'verifyBaselineTestReports',
                             'verifyPluginProjectConfiguration', 'verifyPluginStructure', 'exportPluginArchivePath'):
                    self.assertIn(task, command)
                self.assertTrue(any('always()' in step.get('if', '')
                                    and 'integrations/goland/build/reports' in step.get('with', {}).get('path', '')
                                    for step in job['steps']))
        reusable = json.dumps(load_yaml('.github/workflows/goland-verification.yml'))
        self.assertNotIn('--baseline', reusable)
        self.assertIn('--snapshot targets/targets.json', reusable)
        local = (Path(__file__).resolve().parents[2] / 'scripts/check_goland.py').read_text()
        self.assertIn("'--baseline'", local)
        self.assertIn("'--snapshot'", local)
        self.assertNotIn("'verifyPlugin',", local)

    def test_all_gradle_failure_levels_and_composed_bytecode_gate_remain_enabled(self):
        build = (Path(__file__).resolve().parents[2] / 'integrations/goland/build.gradle.kts').read_text()
        for category in ('COMPATIBILITY_PROBLEMS', 'INVALID_PLUGIN', 'MISSING_DEPENDENCIES', 'INTERNAL_API_USAGES',
                         'EXPERIMENTAL_API_USAGES', 'OVERRIDE_ONLY_API_USAGES', 'NON_EXTENDABLE_API_USAGES'):
            self.assertIn('VerifyPluginTask.FailureLevel.' + category, build)
        self.assertIn('scripts/ide_api_exception.py', build)
        self.assertIn('"--jar", composedJarFile.absolutePath', build)
        self.assertIn('"--sources", sourceRootDirectory.absolutePath', build)


class IntegrationReportsTests(unittest.TestCase):
    def test_missing_skipped_zero_and_incomplete_scenarios_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); marker = root / 'root.txt'; marker.write_text(str(root))
            for xml in ['<testsuite/>', '<testsuite><testcase name="loadingAndProjectTree()"><skipped/></testcase></testsuite>',
                        '<testsuite><testcase name="loadingAndProjectTree()"/></testsuite>']:
                (root / 'TEST-case.xml').write_text(xml)
                with self.assertRaises(ValueError): check_reports(root, marker)


if __name__ == '__main__':
    unittest.main()
