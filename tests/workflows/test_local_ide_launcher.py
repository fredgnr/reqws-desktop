"""Local profile isolation and candidate-bound evidence; no IDE, account or real workspace."""

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from ide_compatibility import digest, read_policy, write_json
from run_local_ide import (EnvironmentBlocked, initialize_profile, isolate_project_state,
                           local_only, lock_profile, optional_license_server, session_closed, verify_report)


class LocalProfileTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.policy = read_policy()

    def test_ci_is_rejected_before_starting_any_ide(self):
        with patch.dict(os.environ, {'CI': 'true'}, clear=True), self.assertRaises(EnvironmentBlocked) as error:
            local_only()
        self.assertEqual(error.exception.code, 'CI_NOT_ALLOWED')

    def test_license_server_is_optional_and_never_accepts_url_credentials(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(optional_license_server())
        with patch.dict(os.environ, {'JETBRAINS_LICENSE_SERVER': 'https://license.example.test'}, clear=True):
            self.assertTrue(optional_license_server())
        for value in ['http://example.test', 'https://user:secret@example.test', 'https://example.test?token=test']:
            with patch.dict(os.environ, {'JETBRAINS_LICENSE_SERVER': value}, clear=True), self.assertRaises(EnvironmentBlocked):
                optional_license_server()

    def test_existing_personal_directory_is_never_adopted_or_copied(self):
        path = self.root / 'existing-config'; path.mkdir()
        (path / 'ordinary.txt').write_text('keep')
        with self.assertRaises(EnvironmentBlocked): initialize_profile(path, self.policy)
        self.assertEqual((path / 'ordinary.txt').read_text(), 'keep')
        self.assertFalse((path / '.reqws-ide-profile.json').exists())

    def test_profile_is_reused_but_business_state_is_moved_to_a_fresh_run(self):
        profile, first = initialize_profile(self.root / 'dedicated', self.policy)
        config = profile / 'config'
        (config / 'options').mkdir()
        (config / 'workspace').mkdir()
        (config / 'workspace/fixture.xml').write_text('previous test project state')
        (config / 'projects/GoLandWorkspace').mkdir(parents=True)
        (config / 'projects/GoLandWorkspace/keep.txt').write_text('IDE-generated welcome workspace')
        (config / 'options/recentProjects.xml').write_text('previous fixture')
        # An inert sentinel proves unrelated persistent files are neither read, moved nor copied.
        (config / 'persistent-sentinel').write_text('fixture-state')
        run = self.root / 'run-one'; run.mkdir()
        with lock_profile(profile): isolate_project_state(profile, run)
        self.assertFalse((config / 'workspace').exists())
        self.assertTrue((run / 'private-previous-project-state/workspace/fixture.xml').exists())
        self.assertFalse((config / 'projects').exists())
        self.assertEqual((run / 'private-previous-project-state/projects/GoLandWorkspace/keep.txt').read_text(),
                         'IDE-generated welcome workspace')
        self.assertEqual((config / 'persistent-sentinel').read_text(), 'fixture-state')
        self.assertFalse(any(path.name == 'persistent-sentinel' for path in run.rglob('*')))
        self.assertEqual(first, initialize_profile(profile, self.policy)[1])

    def test_concurrent_profile_use_fails_closed(self):
        profile, _ = initialize_profile(self.root / 'dedicated', self.policy)
        with lock_profile(profile), self.assertRaises(EnvironmentBlocked):
            with lock_profile(profile): self.fail('Concurrent profile admitted')

    def test_symlink_profile_or_project_state_is_never_followed(self):
        existing = self.root / 'existing'; existing.mkdir()
        link = self.root / 'link'; link.symlink_to(existing)
        with self.assertRaises(EnvironmentBlocked): initialize_profile(link, self.policy)
        profile, _ = initialize_profile(self.root / 'dedicated', self.policy)
        (profile / 'config/workspace').symlink_to(existing)
        run = self.root / 'run'; run.mkdir()
        with self.assertRaises(EnvironmentBlocked): isolate_project_state(profile, run)
        self.assertTrue(existing.is_dir())

    def test_unrecorded_later_launch_does_not_release_profile_as_clean(self):
        self.assertTrue(session_closed(self.root, 'run'))
        (self.root / 'ide-launch-requested').write_text('1\n')
        self.assertFalse(session_closed(self.root, 'run'))
        (self.root / 'processes.tsv').write_text('123\tstarted\n123\texited\n')
        self.assertTrue(session_closed(self.root, 'run'))
        (self.root / 'ide-launch-requested').write_text('2\n')
        self.assertFalse(session_closed(self.root, 'run'))

    def test_unsigned_ui_report_cannot_be_used_for_signed_bytes(self):
        archive = self.root / 'candidate.zip'; archive.write_bytes(b'unsigned fixture')
        ide = {'product': 'GO', 'version': self.policy['uiTestIdeVersion'], 'build': self.policy['uiTestIdeBuild']}
        path = self.root / 'report.json'
        report = {'scope': 'local-ide-integration', 'status': 'passed', 'ide': ide, 'actualIde': ide,
                  'candidate': {'sha256': digest(archive), 'version': '1.2.3'}}
        write_json(path, report)
        verify_report(path, archive, '1.2.3')
        archive.write_bytes(b'signed fixture')
        with self.assertRaises(ValueError): verify_report(path, archive, '1.2.3')
        archive.write_bytes(b'unsigned fixture')
        for status in ['not-run', 'environment-blocked', 'failed', 'preparation-closed', 'skipped']:
            write_json(path, {**report, 'status': status})
            with self.assertRaises(ValueError): verify_report(path, archive, '1.2.3')


if __name__ == '__main__':
    unittest.main()
