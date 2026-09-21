"""Self-update asset and workflow boundaries use disposable local bytes only."""

import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_release_assets', ROOT / 'scripts/verify-release-assets.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class UpdateReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.desktop = 'ReqWS-1.2.3-macos-arm64.zip'
        self.plugin = 'ReqWS-1.2.3-goland-plugin.zip'
        (self.directory / self.desktop).write_bytes(b'disposable final desktop zip')
        (self.directory / self.plugin).write_bytes(b'disposable plugin zip')
        sha512 = base64.b64encode(hashlib.sha512((self.directory / self.desktop).read_bytes()).digest()).decode('ascii')
        self.metadata = {
            'version': '1.2.3', 'path': self.desktop, 'sha512': sha512,
            'files': [{'url': self.desktop, 'sha512': sha512, 'size': (self.directory / self.desktop).stat().st_size}],
            'releaseDate': '2026-09-19T00:00:00.000Z',
        }
        self.refresh()

    def refresh(self):
        (self.directory / 'latest-mac.yml').write_text(json.dumps(self.metadata))
        for name in [self.desktop, self.plugin, 'latest-mac.yml']:
            checksum = hashlib.sha256((self.directory / name).read_bytes()).hexdigest()
            (self.directory / (name + '.sha256')).write_text(f'{checksum}  {name}\n')

    def public_assets(self):
        MODULE.verify_release_assets(self.directory, '1.2.3', True)
        for sidecar in self.directory.glob('*.sha256'):
            sidecar.unlink()

    def test_stage_and_verify_exact_four_assets(self):
        self.public_assets()
        MODULE.verify_release_assets(self.directory, '1.2.3')
        self.assertEqual(len((self.directory / 'SHA256SUMS').read_text().splitlines()), 3)

    def test_reject_changed_draft_bytes_and_manifest(self):
        self.public_assets()
        for name in [self.desktop, self.plugin, 'latest-mac.yml', 'SHA256SUMS']:
            with self.subTest(name=name):
                target = self.directory / name
                before = target.read_bytes()
                target.write_bytes(before + b'changed')
                with self.assertRaises((ValueError, TypeError)):
                    MODULE.verify_release_assets(self.directory, '1.2.3')
                target.write_bytes(before)

    def test_reject_extra_missing_symlink_and_secret_assets(self):
        self.public_assets()
        for name in ['extra.zip', 'private.p12', 'source.key', 'latest-mac.yml.sha256']:
            target = self.directory / name
            target.write_text('unexpected')
            with self.assertRaises(ValueError):
                MODULE.verify_release_assets(self.directory, '1.2.3')
            target.unlink()
        target = self.directory / self.plugin
        target.unlink()
        with self.assertRaises(ValueError):
            MODULE.verify_release_assets(self.directory, '1.2.3')
        target.symlink_to(self.directory / self.desktop)
        with self.assertRaises(ValueError):
            MODULE.verify_release_assets(self.directory, '1.2.3')

    def test_reject_wrong_files_version_size_and_digest_even_with_matching_sidecars(self):
        original = json.loads(json.dumps(self.metadata))
        changes = [
            {'version': '1.2.4'}, {'path': self.plugin}, {'path': '../outside.zip'},
            {'sha512': '0' * 128}, {'files': []}, {'files': original['files'] * 2},
            {'files': [{**original['files'][0], 'url': self.plugin}]},
            {'files': [{**original['files'][0], 'url': 'https://example.invalid/file.zip'}]},
            {'files': [{**original['files'][0], 'size': True}]}, {'token': 'forbidden'},
            {'releaseDate': 'not-a-date'},
        ]
        for change in changes:
            with self.subTest(change=change):
                self.metadata = {**original, **change}
                self.refresh()
                with self.assertRaises(ValueError):
                    MODULE.verify_release_assets(self.directory, '1.2.3', True)
                self.assertFalse((self.directory / 'SHA256SUMS').exists())

    def test_reject_duplicate_json_keys_and_invalid_versions(self):
        target = self.directory / 'latest-mac.yml'
        target.write_text(target.read_text().replace('"version": "1.2.3"', '"version": "1.2.3", "version": "1.2.3"'))
        with self.assertRaises(ValueError):
            MODULE.verify_release_assets(self.directory, '1.2.3', True)
        for version in ['../1.2.3', '1.2.3-beta', 'v1.2.3', '01.2.3']:
            with self.assertRaises(ValueError):
                MODULE.verify_release_assets(self.directory, version, True)

    def test_preserve_existing_checksum_manifest(self):
        (self.directory / 'SHA256SUMS').write_text('previous release')
        with self.assertRaises(ValueError):
            MODULE.verify_release_assets(self.directory, '1.2.3', True)
        self.assertEqual((self.directory / 'SHA256SUMS').read_text(), 'previous release')


class UpdateWorkflowTests(unittest.TestCase):
    @staticmethod
    def workflow(name):
        source = ROOT / '.github/workflows' / name
        output = subprocess.check_output([
            'node', '-e',
            'const fs=require("fs");const yaml=require("js-yaml");process.stdout.write(JSON.stringify(yaml.load(fs.readFileSync(process.argv[1],"utf8"))))',
            str(source),
        ], cwd=ROOT, text=True)
        return json.loads(output)

    def test_secrets_only_reach_the_protected_signing_step(self):
        workflow = self.workflow('release.yml')
        self.assertEqual(workflow['permissions'], {'contents': 'read'})
        self.assertNotIn('pull_request_target', workflow['on'])
        package = workflow['jobs']['package']
        self.assertEqual(package['environment'], 'macos-release')
        self.assertEqual(package['env']['REQWS_BUILD_PROFILE'], 'personal-release')
        self.assertNotIn('MAC_SIGNING_P12_BASE64', package['env'])
        steps = package['steps']
        signing = next(step for step in steps if step.get('id') == 'signing')
        self.assertIn('with-macos-signing.mjs', signing['run'])
        self.assertIn('build-macos-release.mts', signing['run'])
        self.assertLess(next(index for index, step in enumerate(steps) if step.get('run') == 'npm ci'), steps.index(signing))
        exposed = [(job, step) for job, config in workflow['jobs'].items() for step in config.get('steps', []) if 'MAC_SIGNING_P12' in json.dumps(step)]
        self.assertEqual(len(exposed), 1)
        self.assertEqual(exposed[0][0], 'package')
        self.assertTrue(any('always()' in step.get('if', '') and '--cleanup' in step.get('run', '') for step in steps))
        ci = json.dumps(self.workflow('ci.yml'))
        self.assertNotIn('secrets.', ci)
        self.assertNotIn('macos-release', ci)

    def test_publish_joins_all_gates_and_verifies_downloaded_draft_before_publication(self):
        workflow = self.workflow('release.yml')
        publish = workflow['jobs']['publish']
        self.assertEqual(set(publish['needs']), {'validate', 'checks', 'package', 'goland-plugin', 'plugin-verification'})
        self.assertEqual(publish['permissions'], {'contents': 'write'})
        self.assertNotIn('MAC_SIGNING_P12', json.dumps(publish))
        run = next(step['run'] for step in publish['steps'] if 'gh release create' in step.get('run', ''))
        self.assertIn('latest-mac.yml', run)
        self.assertIn('reqws-release-workflow:', run)
        self.assertIn('release_is_draft', run)
        self.assertNotIn('--clobber', run)
        self.assertLess(run.index('gh release download'), run.index('gh release edit'))
        self.assertLess(run.index('cmp '), run.index('gh release edit'))
        self.assertLess(run.index('verify-release-assets.py'), run.index('gh release edit'))
        self.assertIn('verifyPlugin', json.dumps(workflow['jobs']['goland-plugin']))


if __name__ == '__main__':
    unittest.main()
