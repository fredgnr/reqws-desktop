"""Test cache ownership/budgets without using GitHub or real user directories."""

import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import api_cache as cache
from ide_compatibility import read_policy, resolve_catalog


def snapshot():
    policy = read_policy()
    versions = [('2026.2', '262.8665.270'), ('2026.2.0.1', '262.8665.336'),
                ('2026.2.1', '262.9437.195'), ('2026.2.1.1', '262.9437.286'),
                ('2026.2.2', '262.10315.135'), ('2026.2.2.1', '262.10315.160'),
                ('2026.2.3', '262.10968.67')]
    return resolve_catalog({'GO': [dict(version=v, build=b, type='release', date='2026-09-01')
                                   for v, b in versions]}, policy, 'full')


def trusted():
    return {'REQUEST_CACHE_WRITE': 'true', 'DEFAULT_BRANCH': 'main',
            'GITHUB_REPOSITORY': 'fredgnr/reqws-desktop', 'GITHUB_EVENT_NAME': 'push',
            'GITHUB_REF': 'refs/heads/main', 'GITHUB_JOB': 'api',
            'GITHUB_WORKFLOW_REF': 'fredgnr/reqws-desktop/.github/workflows/ci.yml@refs/heads/main',
            'GITHUB_SHA': 'a' * 40, 'CACHE_SOURCE_REF': '', 'CACHE_HISTORICAL': 'false'}


class CachePolicyTests(unittest.TestCase):
    def test_only_default_ci_push_or_dispatch_with_exact_checkout_can_produce(self):
        for event in ('push', 'workflow_dispatch'):
            self.assertTrue(cache.producer_allowed({**trusted(), 'GITHUB_EVENT_NAME': event}, 'a' * 40))
        self.assertFalse(cache.producer_allowed(trusted(), 'b' * 40))
        self.assertFalse(cache.producer_allowed(trusted(), ''))

    def test_pr_privileged_pr_weekly_and_release_remain_read_only(self):
        for event in ('pull_request', 'pull_request_target', 'workflow_run', 'workflow_call', 'schedule', 'release', ''):
            self.assertFalse(cache.producer_allowed({**trusted(), 'GITHUB_EVENT_NAME': event}, 'a' * 40))
        for key, values in {
            'GITHUB_REF': ['refs/pull/34/merge', 'refs/heads/feature', 'refs/tags/main', 'main', ''],
            'GITHUB_WORKFLOW_REF': ['fredgnr/reqws-desktop/.github/workflows/release.yml@refs/heads/main',
                                    'fredgnr/reqws-desktop/.github/workflows/goland-weekly.yml@refs/heads/main', ''],
            'GITHUB_JOB': ['plugin-build', 'result', ''],
            'REQUEST_CACHE_WRITE': ['false', 'TRUE', '1', ''],
            'DEFAULT_BRANCH': [''], 'GITHUB_REPOSITORY': [''],
            'CACHE_SOURCE_REF': ['a' * 40, 'main'], 'CACHE_HISTORICAL': ['true', 'unknown'],
        }.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    self.assertFalse(cache.producer_allowed({**trusted(), key: value}, 'a' * 40))

    def test_hot_set_is_bounded_and_shared_owner_is_unique(self):
        data = snapshot()
        plans = [cache.cache_plan(data, t['id'], 'fixture', Path('/tmp/home')) for t in data['targets']]
        self.assertEqual(sum(p['shared-owner'] == 'true' for p in plans), 1)
        self.assertEqual({p['target'] for p in plans if p['sdk-hot'] == 'true'},
                         {'GO-262.8665.270', 'GO-262.9437.286', 'GO-262.10968.67'})
        self.assertEqual(len({p['sdk-key'] for p in plans}), 7)
        self.assertEqual(len({p['jbr-key'] for p in plans}), 1)
        self.assertEqual(len(data['targets']), 7)  # Cache selection does not filter verification.

    def test_new_latest_only_rotates_hot_eligibility_not_other_keys(self):
        data = snapshot()
        old = cache.cache_plan(data, 'GO-262.10968.67', 'fixture', Path('/tmp/home'))
        data['targets'].append({'id': 'GO-263.100.1', 'product': 'GO', 'version': '2026.3', 'build': '263.100.1'})
        new = cache.cache_plan(data, 'GO-262.10968.67', 'fixture', Path('/tmp/home'))
        self.assertEqual(old['sdk-key'], new['sdk-key'])
        self.assertEqual(new['sdk-hot'], 'false')
        self.assertEqual(len(new['hot-targets'].split(',')), 3)

    def test_fetched_time_and_order_do_not_duplicate_download_keys(self):
        data = snapshot()
        original = cache.cache_plan(data, 'GO-262.9437.286', 'fixture', Path('/tmp/home'))
        data['fetchedAt'] = 'different'; data['targets'].reverse()
        self.assertEqual(original, cache.cache_plan(data, 'GO-262.9437.286', 'fixture', Path('/tmp/home')))

    def test_target_paths_cannot_be_injected_or_duplicated(self):
        for target in ('missing', '../../escape', 'GO-262.1\nother=value'):
            with self.assertRaises(ValueError):
                cache.cache_plan(snapshot(), target, 'fixture', Path('/tmp/home'))
        data = snapshot(); data['targets'].append(data['targets'][0])
        with self.assertRaises(ValueError):
            cache.cache_plan(data, data['targets'][0]['id'], 'fixture', Path('/tmp/home'))
        data = snapshot(); data['targets'][0]['version'] = '../escape'
        with self.assertRaises(ValueError):
            cache.cache_plan(data, data['targets'][0]['id'], 'fixture', Path('/tmp/home'))

    def test_runtime_is_exact_and_fails_closed_on_policy_or_archive_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); directory = root / 'integrations/goland'; directory.mkdir(parents=True)
            pin = directory / 'verifier-runtime.properties'
            original = (ROOT / 'integrations/goland/verifier-runtime.properties').read_text()
            pin.write_text(original)
            self.assertEqual(cache.runtime_pin(root, read_policy()), 'jbr_jcef-25.0.3-linux-x64-b508.16')
            for text in (original + '\ncompileIdeBuild=262.8665.270\n',
                         original.replace('262.8665.270', '262.9999.1'),
                         original.replace('jbr_jcef-25.0.3-linux-x64-b508.16', '../../escape'),
                         original.replace('linux-x64', 'linux-aarch64')):
                pin.write_text(text)
                with self.assertRaises(ValueError): cache.runtime_pin(root, read_policy())


class DownloadAuditTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name)
        self.directory = self.home / '.gradle/caches/modules-2/files-2.1/example/sdk/1.0'
        (self.directory / 'hash').mkdir(parents=True)
        self.archive = self.directory / 'hash/sdk-1.0.zip'
        self.archive.write_bytes(b'fixture archive')

    def inspect(self, limit=1000):
        return cache.inspect_download(self.directory, 'sdk-1.0.zip', limit, self.home)

    def test_exact_nonempty_download_and_metadata_are_counted(self):
        (self.directory / 'hash/sdk-1.0.pom').write_text('metadata')
        result = self.inspect()
        self.assertTrue(result['cacheable']); self.assertEqual(result['files'], 2)
        self.assertEqual(result['bytes'], len(b'fixture archivemetadata'))

    def test_over_budget_is_uncacheable_not_a_skipped_verification(self):
        result = self.inspect(limit=1)
        self.assertFalse(result['cacheable']); self.assertEqual(result['reason'], 'over-budget')
        self.assertTrue(self.archive.exists())

    def test_missing_directory_is_distinct_from_empty_or_wrong_archive(self):
        absent = cache.inspect_download(self.home / 'absent', 'sdk.zip', 1000, self.home)
        self.assertEqual(absent['reason'], 'missing')
        self.archive.write_bytes(b'')
        with self.assertRaises(ValueError): self.inspect()
        self.archive.rename(self.archive.with_name('other.zip'))
        with self.assertRaises(ValueError): self.inspect()

    def test_extra_archives_and_excessive_files_cannot_be_published(self):
        extra = self.archive.with_name('second.zip'); extra.write_bytes(b'second')
        with self.assertRaises(ValueError): self.inspect()
        extra.unlink()
        for i in range(65): (self.directory / f'file-{i}').write_text('x')
        self.assertEqual(self.inspect()['reason'], 'too-many-files')

    def test_links_and_outside_home_are_rejected(self):
        link = self.archive.with_name('link'); link.symlink_to(self.archive)
        with self.assertRaises(ValueError): self.inspect()
        link.unlink()
        link = self.home / 'alias'; link.symlink_to(self.directory, target_is_directory=True)
        with self.assertRaises(ValueError): cache.inspect_download(link, self.archive.name, 1000, self.home)
        with self.assertRaises(ValueError): cache.inspect_download(Path('/elsewhere'), 'sdk.zip', 1000, self.home)

    def test_outputs_never_append_partial_or_multiline_values(self):
        output = self.home / 'output'; output.write_text('existing=value\n')
        with patch.dict(os.environ, {'GITHUB_OUTPUT': str(output)}), patch('sys.stdout', new=io.StringIO()):
            with self.assertRaises(ValueError): cache.emit({'a': 'valid', 'b': 'bad\ninjected=value'})
        self.assertEqual(output.read_text(), 'existing=value\n')


class CacheWorkflowTests(unittest.TestCase):
    def test_only_linux_api_uses_the_new_action_after_frozen_inputs(self):
        from test_ci_performance import load_workflow
        workflow = load_workflow('goland-verification.yml')
        job = workflow['jobs']['api']; steps = job['steps']
        setup = next(s for s in steps if s.get('id') == 'api-cache')
        self.assertEqual(setup['uses'], './.github/actions/setup-goland-api')
        self.assertEqual(job['runs-on'], 'ubuntu-24.04')
        before = steps[:steps.index(setup)]
        self.assertEqual([s['with']['path'] for s in before if 'download-artifact@' in s.get('uses', '')],
                         ['candidate', 'targets'])
        self.assertEqual(setup['with']['snapshot'], 'targets/targets.json')
        self.assertEqual(setup['with']['target'], '${{ matrix.target }}')
        request = setup['with']['cache-write']
        for guard in ("github.event_name == 'push'", "github.event_name == 'workflow_dispatch'",
                      'github.event.repository.default_branch', "github.workflow == 'CI'",
                      "inputs.source-ref == ''", '!inputs.historical'):
            self.assertIn(guard, request)
        self.assertEqual(job['strategy'], {'fail-fast': False, 'matrix': '${{ fromJSON(inputs.matrix) }}'})

    def test_hits_never_skip_verification_and_saves_follow_verdict_and_budget(self):
        from test_ci_performance import load_workflow
        steps = load_workflow('goland-verification.yml')['jobs']['api']['steps']
        verify = next(s for s in steps if s.get('name') == 'Verify exact candidate and frozen target')
        audit = next(s for s in steps if s.get('id') == 'cache-audit')
        self.assertNotIn('if', verify); self.assertNotIn('continue-on-error', verify)
        self.assertIn('run_ide_verifier.py --snapshot', verify['run'])
        self.assertLess(steps.index(verify), steps.index(audit))
        saves = [s for s in steps if 'actions/cache/save@' in s.get('uses', '')]
        self.assertEqual(len(saves), 2)
        for step in saves:
            self.assertLess(steps.index(audit), steps.index(step))
            self.assertIn('success()', step['if'])
            self.assertIn("-write == 'true'", step['if'])
            self.assertIn("-hit != 'true'", step['if'])
            self.assertIn("-store == 'true'", step['if'])
            self.assertNotIn('restore-keys', step['with'])
            self.assertNotIn('continue-on-error', step)
        self.assertTrue(any('shared-write' in s['if'] for s in saves))

    def test_shared_state_is_small_and_raw_restores_are_read_only(self):
        from test_ci_cache_config import load_yaml
        action = load_yaml('.github/actions/setup-goland-api/action.yml')
        self.assertEqual(action['inputs']['cache-write']['default'], 'false')
        steps = action['runs']['steps']
        gradle = next(s for s in steps if 'setup-gradle@' in s.get('uses', ''))
        self.assertEqual(gradle['with']['cache-read-only'], "${{ steps.plan.outputs.shared-write != 'true' }}")
        self.assertNotIn('cache-write-only', gradle['with'])
        self.assertNotIn('validate-wrappers', gradle['with'])
        includes = gradle['with']['gradle-home-cache-includes']
        self.assertIn('kotlin-dsl', includes); self.assertIn('generated-gradle-jars', includes)
        for forbidden in ('transforms', 'build-cache', '.intellijPlatform', 'candidate', 'reports', 'init.d'):
            self.assertNotIn(forbidden, includes)
        excludes = gradle['with']['gradle-home-cache-excludes']
        for coordinate in ('go/goland', 'com.jetbrains.intellij.goland/goland', 'com.jetbrains/jbr'):
            self.assertIn(coordinate, excludes)
        raw = [s for s in steps if 'actions/cache/' in s.get('uses', '')]
        self.assertEqual(len(raw), 2)
        for step in raw:
            self.assertLess(steps.index(gradle), steps.index(step))
            self.assertIn('actions/cache/restore@', step['uses'])
            self.assertNotIn('restore-keys', step['with'])
        self.assertNotIn('secrets.', json.dumps(action))


if __name__ == '__main__':
    unittest.main()
