"""Credential bootstrap ordering and cleanup use only mock private repository data."""

import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import configure_plugin_publishing as config
import plugin_signing as signing
import test_macos_update_release as release_tests


class ConfigurationSafetyTests(unittest.TestCase):
    def test_private_backup_identity_is_mandatory(self):
        for metadata in [{'full_name': config.PRIVATE_REPOSITORY, 'private': False},
                         {'full_name': 'other/private', 'private': True}]:
            with self.assertRaises(ValueError):
                config.PrivateBackup(Mock(return_value=metadata)).check()

    def test_restore_commit_is_exact_and_readback_complete(self):
        api = Mock(return_value={'full_name': config.PRIVATE_REPOSITORY, 'private': True, 'default_branch': 'main'})
        with self.assertRaises(ValueError): config.PrivateBackup(api).read('x', ['key'], 'main')
        with self.assertRaises(ValueError): config.PrivateBackup(api).read('x', ['key'], 'a' * 40)

    def test_verification_precedes_activation_and_all_failure_paths_cleanup(self):
        for failure in [None, 'push', 'read', 'mismatch', 'verify', 'activate']:
            with self.subTest(failure=failure):
                events, directories = [], []
                files = {name: ('test-only-' + name).encode() for name in config.IDENTITY_FILES}
                backup = Mock(); backup.create.return_value = 'a' * 40; backup.read.return_value = files
                if failure == 'push': backup.create.side_effect = ValueError('push failure')
                if failure == 'read': backup.read.side_effect = ValueError('read failure')
                if failure == 'mismatch': backup.read.return_value = {**files, 'password': b'wrong'}
                def generate(directory):
                    directories.append(directory)
                    (directory / 'test-private-material').write_text('disposable')
                    events.append('generate')
                    return files
                def verify(*args):
                    events.append('verify')
                    if failure == 'verify': raise ValueError('wrong password or invalid certificate')
                def activate(*args):
                    events.append('activate')
                    if failure == 'activate': raise ValueError('secret sync failed')
                with patch.object(config, 'generate_identity', side_effect=generate), patch.object(config, 'verify_recovery', side_effect=verify):
                    if failure:
                        with self.assertRaises(ValueError):
                            config.provision_identity(backup, Path('fixture.zip'), Path('signer.jar'), activate=activate)
                    else:
                        config.provision_identity(backup, Path('fixture.zip'), Path('signer.jar'), activate=activate)
                self.assertTrue(all(not directory.exists() for directory in directories))
                if failure in {'push', 'read', 'mismatch', 'verify'}:
                    self.assertNotIn('activate', events)
                if 'activate' in events:
                    self.assertLess(events.index('verify'), events.index('activate'))

    def test_token_backup_failure_never_activates(self):
        backup = Mock(); backup.create.return_value = 'a' * 40; backup.read.return_value = {}
        with patch.object(config, 'set_secret') as secret:
            with self.assertRaises(ValueError): config.provision_token(backup, b'disposable-token')
            secret.assert_not_called()

    def test_cleanup_only_uses_recorded_session_material(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {'RUNNER_TEMP': temporary}):
            root = Path(temporary).resolve()
            directory = root / 'reqws-plugin-signing-fixture'
            directory.mkdir(); (directory / 'private.pem').write_text('disposable')
            marker = root / 'reqws-plugin-signing-state.json'
            marker.write_text(json.dumps({'directory': str(directory)}))
            signing.cleanup_signing_state()
            self.assertFalse(directory.exists()); self.assertFalse(marker.exists())
            marker.write_text(json.dumps({'directory': str(root.parent)}))
            with self.assertRaises(ValueError): signing.cleanup_signing_state()
            self.assertTrue(root.exists())

    def test_cleanup_errors_are_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {'RUNNER_TEMP': temporary}):
            root = Path(temporary).resolve(); directory = root / 'reqws-plugin-signing-fixture'; directory.mkdir()
            marker = root / 'reqws-plugin-signing-state.json'; marker.write_text(json.dumps({'directory': str(directory)}))
            with patch.object(signing.shutil, 'rmtree', side_effect=OSError('cleanup failed')):
                with self.assertRaises(OSError): signing.cleanup_signing_state()
            self.assertTrue(marker.exists())


class MarketplaceWorkflowTests(unittest.TestCase):
    def test_entrypoints_gates_and_permissions(self):
        workflow = release_tests.UpdateWorkflowTests.workflow('marketplace-publish.yml')
        self.assertEqual(set(workflow['on']), {'workflow_call', 'workflow_dispatch'})
        self.assertIsNone(workflow['on']['workflow_dispatch'])
        self.assertEqual(workflow['permissions'], {'contents': 'read', 'actions': 'read'})
        self.assertFalse(workflow['concurrency']['cancel-in-progress'])
        self.assertIn('-stable', workflow['concurrency']['group'])
        job = workflow['jobs']['submit']
        self.assertEqual(job['environment'], 'jetbrains-marketplace')
        self.assertIn("'automatic'", job['if'])
        self.assertNotIn('secrets.', json.dumps(job['env']))
        steps = job['steps']
        intent = next(i for i, step in enumerate(steps) if step.get('id') == 'intent')
        post = next(i for i, step in enumerate(steps) if 'JETBRAINS_MARKETPLACE_TOKEN' in step.get('env', {}))
        self.assertLess(intent, post)
        self.assertIn('--intent-artifact-id', steps[post]['run'])
        self.assertTrue(any('always()' in step.get('if', '') and 'upload-artifact@' in step.get('uses', '') for step in steps))
        text = json.dumps(workflow)
        for forbidden in ['secrets: inherit', 'PRIVATE_KEY', 'publishPlugin', 'buildPlugin', 'release delete', 'contents: write']:
            self.assertNotIn(forbidden, text)
        release = release_tests.UpdateWorkflowTests.workflow('release.yml')
        self.assertEqual(set(release['jobs']['publish-marketplace']['needs']), {'validate', 'publish'})
        plugin = release['jobs']['goland-plugin']
        self.assertEqual(plugin['environment'], 'jetbrains-plugin-signing')
        self.assertEqual(sum('secrets.' in json.dumps(step) for step in plugin['steps']), 1)
        self.assertTrue(any('always()' in step.get('if', '') and 'plugin_signing.py cleanup' in step.get('run', '') for step in plugin['steps']))
        for name in ['ci.yml', 'release.yml']:
            text = json.dumps(release_tests.UpdateWorkflowTests.workflow(name))
            self.assertIn('--input', text)
            self.assertNotIn('--distributions', text)
        self.assertNotIn('reqws-secret', json.dumps(workflow))


if __name__ == '__main__':
    unittest.main()
