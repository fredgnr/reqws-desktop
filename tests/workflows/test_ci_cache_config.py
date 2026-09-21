"""Download key stability and cache ownership, using disposable fixtures only."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ci_cache_config', ROOT / 'scripts/ci-cache-config.py')
CACHE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CACHE)


class CacheConfigTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / '.nvmrc').write_text('24\n')
        self.lock = {'version': '1.0.0', 'lockfileVersion': 3, 'packages': {
            '': {'version': '1.0.0', 'devDependencies': {'electron': '43.4.0'}},
            'node_modules/electron': {'version': '43.4.0', 'integrity': 'fixture-electron'},
            'node_modules/example': {'version': '1.0.0', 'integrity': 'fixture-example'},
        }}
        self.write_lock()
        self.gradle = self.root / 'integrations/goland/build.gradle.kts'
        self.gradle.parent.mkdir(parents=True)
        self.gradle.write_text('goland("2026.2.1.1")\ncreate(IntelliJPlatformType.GoLand, "2026.2.1.1")\n')

    def write_lock(self):
        (self.root / 'package-lock.json').write_text(json.dumps(self.lock))

    def test_only_opted_in_default_branch_push_and_dispatch_write(self):
        for event in ['push', 'workflow_dispatch']:
            self.assertTrue(CACHE.may_write('true', event, 'refs/heads/main', 'main'))
            self.assertTrue(CACHE.may_write('true', event, 'refs/heads/trunk', 'trunk'))

    def test_pr_and_privileged_pr_events_never_write(self):
        for event in ['pull_request', 'pull_request_target', 'workflow_run', 'workflow_call', 'schedule', '']:
            for ref in ['refs/heads/main', 'refs/pull/42/merge', 'refs/heads/feature']:
                self.assertFalse(CACHE.may_write('true', event, ref, 'main'))

    def test_sibling_branch_and_tag_named_main_never_write(self):
        for ref in ['refs/heads/feature', 'refs/tags/main', 'refs/tags/v1.0.0', 'refs/pull/42/merge', 'main', '']:
            self.assertFalse(CACHE.may_write('true', 'push', ref, 'main'))

    def test_missing_metadata_and_unrequested_writes_fail_closed(self):
        for request in ['false', '', 'TRUE', '1']:
            self.assertFalse(CACHE.may_write(request, 'push', 'refs/heads/main', 'main'))
        self.assertFalse(CACHE.may_write('true', 'push', 'refs/heads/', ''))

    def test_app_version_bump_does_not_duplicate_downloads(self):
        before = CACHE.desktop_keys(self.root)
        self.lock['version'] = self.lock['packages']['']['version'] = '1.0.1'
        self.write_lock()
        self.assertEqual(before, CACHE.desktop_keys(self.root))

    def test_unrelated_npm_change_does_not_duplicate_electron(self):
        before = CACHE.desktop_keys(self.root)
        self.lock['packages']['node_modules/example']['version'] = '2.0.0'
        self.write_lock()
        after = CACHE.desktop_keys(self.root)
        self.assertNotEqual(before['npm-suffix'], after['npm-suffix'])
        self.assertEqual(before['electron-version'], after['electron-version'])
        self.assertEqual(before['npm-prefix'], after['npm-prefix'])

    def test_dependency_integrity_changes_invalidate_npm_cache(self):
        before = CACHE.desktop_keys(self.root)
        self.lock['packages']['node_modules/example']['integrity'] = 'new-fixture'
        self.write_lock()
        self.assertNotEqual(before['npm-suffix'], CACHE.desktop_keys(self.root)['npm-suffix'])

    def test_node_major_partitions_npm_cache(self):
        before = CACHE.desktop_keys(self.root)
        (self.root / '.nvmrc').write_text('25\n')
        after = CACHE.desktop_keys(self.root)
        self.assertNotEqual(before['npm-prefix'], after['npm-prefix'])
        self.assertEqual(before['electron-version'], after['electron-version'])

    def test_electron_upgrade_invalidates_its_download_key(self):
        self.lock['packages']['node_modules/electron']['version'] = '44.0.0'
        self.write_lock()
        self.assertEqual(CACHE.desktop_keys(self.root)['electron-version'], '44.0.0')

    def test_json_order_and_whitespace_do_not_change_npm_key(self):
        before = CACHE.desktop_keys(self.root)
        self.lock['packages'] = dict(reversed(list(self.lock['packages'].items())))
        (self.root / 'package-lock.json').write_text(json.dumps(self.lock, indent=4))
        self.assertEqual(before, CACHE.desktop_keys(self.root))

    def test_invalid_tool_versions_and_missing_lock_fail(self):
        for major in ['lts/*', '24\nextra=value', '24.0.0', '']:
            (self.root / '.nvmrc').write_text(major)
            with self.assertRaises(ValueError): CACHE.desktop_keys(self.root)
        (self.root / '.nvmrc').write_text('24')
        for version in ['latest', '../43.4.0', '43.4.0\nother=value', None]:
            self.lock['packages']['node_modules/electron']['version'] = version
            self.write_lock()
            with self.assertRaises(ValueError): CACHE.desktop_keys(self.root)
        (self.root / 'package-lock.json').unlink()
        with self.assertRaises(OSError): CACHE.desktop_keys(self.root)

    def test_missing_packages_fails_instead_of_creating_an_empty_key(self):
        (self.root / 'package-lock.json').write_text('{}')
        with self.assertRaises(ValueError): CACHE.desktop_keys(self.root)

    def test_source_and_task_changes_do_not_invalidate_ide_download(self):
        before = CACHE.goland_keys(self.root)
        with self.gradle.open('a') as stream:
            stream.write('\ntasks.named("test") { maxParallelForks = 2 }\n// goland("2025.1")\n')
        self.assertEqual(before, CACHE.goland_keys(self.root))

    def test_matching_ide_upgrade_changes_download_key(self):
        self.gradle.write_text(self.gradle.read_text().replace('2026.2.1.1', '2026.2.2'))
        self.assertEqual(CACHE.goland_keys(self.root), {'goland-version': '2026.2.2'})

    def test_mismatching_build_and_verifier_fail_closed(self):
        self.gradle.write_text(self.gradle.read_text().replace('2026.2.1.1', '2026.2.2', 1))
        with self.assertRaises(ValueError): CACHE.goland_keys(self.root)

    def test_dynamic_or_multiple_ide_declarations_require_reader_update(self):
        original = self.gradle.read_text()
        for source in [original.replace('goland("2026.2.1.1")', 'goland(versionProvider)'),
                       original + '\ngoland("2026.2.1.1")', '']:
            self.gradle.write_text(source)
            with self.assertRaises(ValueError): CACHE.goland_keys(self.root)

    def test_whitespace_and_block_comments_do_not_change_ide_version(self):
        self.gradle.write_text('/* goland("2025.1") */\ngoland( "2026.2.1.1" )\ncreate(\n IntelliJPlatformType.GoLand,\n "2026.2.1.1" )')
        self.assertEqual(CACHE.goland_keys(self.root)['goland-version'], '2026.2.1.1')

    def test_cache_path_audit_rejects_metadata_without_installer(self):
        directory = self.root / '.gradle/caches/modules-2/files-2.1/go/goland/2026.2.1.1/hash'
        directory.mkdir(parents=True)
        (directory / 'goland.pom').write_text('fixture')
        with self.assertRaises(ValueError): CACHE.goland_downloads(self.root, self.root)
        archive = directory / 'goland-aarch64.dmg'
        archive.write_bytes(b'disposable installer fixture')
        self.assertEqual(CACHE.goland_downloads(self.root, self.root), {str(archive): archive.stat().st_size})

    def test_cache_path_audit_accepts_maven_archive_but_not_other_versions(self):
        base = self.root / '.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.goland/goland'
        directory = base / '2025.1/hash'
        directory.mkdir(parents=True)
        (directory / 'goland.zip').write_bytes(b'old fixture')
        with self.assertRaises(ValueError): CACHE.goland_downloads(self.root, self.root)
        directory = base / '2026.2.1.1/hash'
        directory.mkdir(parents=True)
        archive = directory / 'goland.zip'
        archive.write_bytes(b'current fixture')
        self.assertEqual(list(CACHE.goland_downloads(self.root, self.root)), [str(archive)])

    def test_cli_appends_only_valid_action_outputs(self):
        output = self.root / 'outputs'
        output.write_text('existing=value\n')
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/ci-cache-config.py'), 'desktop', '--root', str(self.root)],
                                env={**os.environ, 'GITHUB_OUTPUT': str(output)}, text=True, capture_output=True, check=True)
        self.assertEqual(output.read_text(), 'existing=value\n' + result.stdout)
        self.assertIn('electron-version=43.4.0\n', result.stdout)

    def test_cli_invalid_input_does_not_publish_partial_outputs(self):
        output = self.root / 'outputs'
        (self.root / '.nvmrc').write_text('invalid')
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/ci-cache-config.py'), 'desktop', '--root', str(self.root)],
                                env={**os.environ, 'GITHUB_OUTPUT': str(output)}, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())


def load_yaml(path):
    # Use the same parser already installed for the workflow regression suite.
    output = subprocess.check_output(['node', '-e',
        'const fs=require("fs");const yaml=require("js-yaml");process.stdout.write(JSON.stringify(yaml.load(fs.readFileSync(process.argv[1],"utf8"))))',
        str(ROOT / path)], cwd=ROOT, text=True)
    return json.loads(output)


class CacheWorkflowTests(unittest.TestCase):
    def test_reader_has_no_post_save_action(self):
        action = load_yaml('.github/actions/cache-dependency/action.yml')
        self.assertEqual(action['inputs']['write']['default'], 'false')
        steps = action['runs']['steps']
        writer = next(step for step in steps if step.get('id') == 'writer')
        reader = next(step for step in steps if step.get('id') == 'reader')
        self.assertEqual(writer['if'], "steps.policy.outputs.write == 'true'")
        self.assertEqual(reader['if'], "steps.policy.outputs.write != 'true'")
        self.assertIn('actions/cache/restore@', reader['uses'])
        self.assertEqual(reader['with'], writer['with'])

    def test_gradle_cache_ownership_and_reverse_post_order(self):
        action = load_yaml('.github/actions/setup-goland/action.yml')
        self.assertEqual(action['inputs']['cache-write']['default'], 'false')
        steps = action['runs']['steps']
        gradle = next(s for s in steps if 'setup-gradle@' in s.get('uses', ''))
        installer = next(s for s in steps if s.get('uses') == './.github/actions/cache-dependency')
        self.assertLess(steps.index(gradle), steps.index(installer))
        self.assertEqual(gradle['with']['cache-read-only'], "${{ steps.policy.outputs.write != 'true' }}")
        for coordinate in ['go/goland', 'com.jetbrains.intellij.goland/goland']:
            self.assertIn(coordinate, gradle['with']['gradle-home-cache-excludes'])
            self.assertIn(coordinate, installer['with']['path'])
        self.assertNotIn('caches/build-cache', gradle['with']['gradle-home-cache-excludes'])
        self.assertNotIn('hashFiles', installer['with']['key'])
        self.assertNotIn('restore-keys', installer['with'])
        self.assertNotIn('.intellijPlatform/ides', json.dumps(steps))
        self.assertFalse(any('wrapper-validation@' in s.get('uses', '') for s in steps))

    def test_desktop_download_layers_do_not_cache_installed_or_built_outputs(self):
        action = load_yaml('.github/actions/setup-desktop/action.yml')
        steps = action['runs']['steps']
        node = next(s for s in steps if 'setup-node@' in s.get('uses', ''))
        self.assertFalse(node['with']['package-manager-cache'])
        self.assertNotIn('cache', node['with'])
        caches = [s for s in steps if s.get('uses') == './.github/actions/cache-dependency']
        self.assertEqual({s['with']['path'] for s in caches}, {'~/.npm/_cacache', '~/Library/Caches/electron'})
        electron = next(s for s in caches if 'electron' in s['with']['path'])
        self.assertEqual(electron['if'], "inputs.electron == 'true'")
        self.assertIn('electron-version', electron['with']['key'])
        self.assertNotIn('restore-keys', electron['with'])

    def test_ci_deduplicates_pr_events_and_keeps_required_gate(self):
        ci = load_yaml('.github/workflows/ci.yml')
        self.assertEqual(ci['on']['push']['branches'], ['main'])
        self.assertIn('pull_request', ci['on'])
        self.assertIn('workflow_dispatch', ci['on'])
        gate = ci['jobs']['checks']
        self.assertEqual(gate['name'], 'Checks and macOS package smoke')
        self.assertEqual(gate['if'], '${{ always() }}')
        self.assertEqual(set(gate['needs']), {'project-checks', 'macos-package'})
        run = gate['steps'][-1]['run']
        for left in ['success', 'failure', 'cancelled', 'skipped']:
            for right in ['success', 'failure', 'cancelled', 'skipped']:
                result = subprocess.run(['bash', '-e', '-c', run], env={**os.environ, 'CHECK_RESULT': left, 'PACKAGE_RESULT': right})
                self.assertEqual(result.returncode == 0, left == right == 'success')
        self.assertNotIn('secrets.', json.dumps(ci))

    def test_check_jobs_skip_electron_but_package_jobs_install_it(self):
        ci = load_yaml('.github/workflows/ci.yml')
        release = load_yaml('.github/workflows/release.yml')
        for job in [ci['jobs']['project-checks'], release['jobs']['checks']]:
            install = next(s for s in job['steps'] if s.get('run') == 'npm ci')
            self.assertEqual(install['env']['ELECTRON_SKIP_BINARY_DOWNLOAD'], '1')
            self.assertIn('npm run check', json.dumps(job))
        for job in [ci['jobs']['macos-package'], release['jobs']['package']]:
            setup = next(s for s in job['steps'] if s.get('uses') == './.github/actions/setup-desktop')
            self.assertEqual(setup['with']['electron'], 'true')
            install = next(s for s in job['steps'] if s.get('run') == 'npm ci')
            self.assertNotIn('ELECTRON_SKIP_BINARY_DOWNLOAD', install.get('env', {}))

    def test_release_consumers_never_request_cache_writes(self):
        release = load_yaml('.github/workflows/release.yml')
        for job in release['jobs'].values():
            for step in job.get('steps', []):
                self.assertFalse(step.get('uses', '').startswith('actions/cache@'))
                for name, value in step.get('with', {}).items():
                    if name.endswith('cache-write'):
                        self.assertEqual(value, 'false')
        for workflow in ['ci.yml', 'release.yml']:
            job = load_yaml('.github/workflows/' + workflow)['jobs']['goland-plugin']
            command = next(s['run'] for s in job['steps'] if './gradlew test ' in s.get('run', ''))
            self.assertNotIn('--no-build-cache', command)
            self.assertIn('verifyPlugin', command)


if __name__ == '__main__':
    unittest.main()
