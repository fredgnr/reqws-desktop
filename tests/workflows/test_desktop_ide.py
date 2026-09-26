"""Fail-closed local coordination/evidence checks without launching a licensed IDE."""

import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from desktop_ide import initialize_link, publish, read_message, validate_transcript, validate_projection_evidence
from check_ide_test_reports import check_reports, DESKTOP_SCENARIOS, SCENARIOS
from run_local_ide import verify_report, session_closed
from ide_compatibility import digest, read_policy


class DesktopIdeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.session = initialize_link(self.root)
        self.directory = self.root / 'desktop-link'

    def transcript(self):
        sequence = 0
        for name in ['selection', 'trust', 'invalid-binding', 'invalid-manifest']:
            root = self.root / name
            root.mkdir()
            values = [['repo-a', 'repo-b'], ['repo-a'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']] if name == 'selection' else [['repo-a', 'repo-b']]
            binding = str(uuid.uuid4())
            for revision, selected in enumerate(values, 1):
                sequence += 1
                envelope = {'schemaVersion': 1, 'sessionId': self.session, 'sequence': sequence}
                request = {**envelope, 'operation': 'create' if revision == 1 else 'select', 'name': name}
                if revision != 1: request['selected'] = selected
                snapshot = {'name': name, 'root': str(root), 'shell': str(root / '.reqws/ide/goland'),
                            'workspaceId': name, 'bindingId': binding, 'revision': revision,
                            'selected': selected, 'repositories': [{'id': 'a', 'name': 'repo-a'}, {'id': 'b', 'name': 'repo-b'}]}
                publish(self.directory / f'request-{sequence}.json', request)
                publish(self.directory / f'response-{sequence}.json', {**envelope, 'status': 'passed', 'snapshot': snapshot})
        sequence += 1
        envelope = {'schemaVersion': 1, 'sessionId': self.session, 'sequence': sequence}
        publish(self.directory / f'request-{sequence}.json', {**envelope, 'operation': 'finish'})
        publish(self.directory / f'response-{sequence}.json', {**envelope, 'status': 'passed'})

    def test_complete_exchange_requires_live_and_cold_selections(self):
        self.transcript()
        self.assertEqual(validate_transcript(self.root, self.session)['requests'], 10)
        path = self.directory / 'request-5.json'
        value = read_message(path)
        value['selected'] = ['repo-a']
        path.write_text(json.dumps(value))
        with self.assertRaises(ValueError): validate_transcript(self.root, self.session)

    def test_missing_failed_stale_or_mismatched_responses_never_pass(self):
        self.transcript()
        path = self.directory / 'response-2.json'
        original = read_message(path)
        variants = [dict(original, status='failed'), dict(original, sessionId=str(uuid.uuid4())), dict(original, sequence=1)]
        for field, value in [('revision', 1), ('bindingId', str(uuid.uuid4())), ('root', '/tmp/foreign')]:
            altered = copy.deepcopy(original); altered['snapshot'][field] = value; variants.append(altered)
        for value in variants:
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError): validate_transcript(self.root, self.session)
        path.unlink()
        with self.assertRaises(ValueError): validate_transcript(self.root, self.session)

    def test_protocol_does_not_overwrite_or_follow_message_symlinks(self):
        filename = self.directory / 'message.json'
        publish(filename, {'keep': True})
        with self.assertRaises(FileExistsError): publish(filename, {'keep': False})
        self.assertEqual(read_message(filename), {'keep': True})
        link = self.directory / 'link.json'; link.symlink_to(filename)
        with self.assertRaises(OSError): read_message(link)

    def test_ide_reports_reject_legacy_skips_duplicates_and_unclean_processes(self):
        self.transcript()
        self.write_proofs()
        marker = self.root / 'run-root.txt'; marker.write_text(str(self.root))
        xml = self.root / 'TEST-suite.xml'
        def write_cases(selectors, extra=''):
            xml.write_text('<testsuite>' + ''.join(f'<testcase name="{name}()">{extra}</testcase>' for name in selectors) + '</testsuite>')
        events = ''.join(f'{pid}\tstarted\n{pid}\tpassed\n{pid}\texited\n' for pid in range(100, 106))
        (self.root / 'processes.tsv').write_text(events)
        write_cases(DESKTOP_SCENARIOS)
        self.assertEqual(check_reports(self.root, marker, 'desktop')['processes'], 6)
        for selectors, extra in [(SCENARIOS, ''), (DESKTOP_SCENARIOS, '<skipped/>'),
                                 (list(DESKTOP_SCENARIOS) + [next(iter(DESKTOP_SCENARIOS))], '')]:
            write_cases(selectors, extra)
            with self.assertRaises(ValueError): check_reports(self.root, marker, 'desktop')
        write_cases(DESKTOP_SCENARIOS)
        (self.root / 'processes.tsv').write_text(events.replace('105\tpassed', '105\tforced-kill'))
        with self.assertRaises(ValueError): check_reports(self.root, marker, 'desktop')

    def write_proofs(self):
        snapshots = {value['snapshot']['name']: value['snapshot'] for path in self.directory.glob('response-*.json')
                     if (value := read_message(path)).get('snapshot')}
        proofs = []
        for name in ['selection', 'trust', 'invalid-binding', 'invalid-manifest']:
            revisions = [1, 2, 3, 4, 5, 5, 6, 6] if name == 'selection' else [1] * (2 if name == 'trust' else 5)
            phases = ['projection'] * 8 if name == 'selection' else ['safe-mode-blocked', 'projection'] if name == 'trust' else ['projection', 'malformed', 'projection', 'mismatched', 'projection']
            for index, (revision, phase) in enumerate(zip(revisions, phases)):
                snapshot = snapshots[name]
                if name == 'selection': snapshot = read_message(self.directory / f'response-{revision}.json')['snapshot']
                selected = snapshot['selected']
                blocked = phase == 'safe-mode-blocked'
                loaded = [] if blocked else selected
                root = Path(snapshot['root'])
                roots = [str(root / repo) for repo in loaded]
                modules = {} if blocked else {'ReqWS-' + snapshot['bindingId']: roots.copy()}
                tree = [[repo, 'docs', 'probe.txt'] for repo in loaded]
                invalid = phase in {'malformed', 'mismatched'}
                if invalid: tree.append(['goland', 'reqws-project.json'])
                if name != 'trust':
                    roots.append(str(root / 'user-content')); modules['user'] = [str(root / 'user-content')]
                    tree.append(['user-content', 'keep.txt'])
                pid = (100 if index < 5 else 101 if index < 7 else 102) if name == 'selection' else {'trust': 103, 'invalid-binding': 104, 'invalid-manifest': 105}[name]
                proof = {'scenario': name, 'phase': phase, 'pid': pid, 'revision': revision, 'selected': selected,
                         'workspaceId': snapshot['workspaceId'], 'bindingId': snapshot['bindingId'], 'roots': roots,
                         'modules': modules, 'pfi': {str(root / repo / 'docs/probe.txt'): {'inContent': repo in loaded} for repo in ['repo-a', 'repo-b']},
                         'tree': tree, 'trusted': not blocked, 'loadedIds': [repo['id'] for repo in snapshot['repositories'] if repo['name'] in loaded],
                         'lifecycle': 'SAFE_MODE_BLOCKED' if blocked else 'ERROR' if phase in {'malformed', 'mismatched'} else 'SYNCHRONIZED',
                         'error': 'MANIFEST_INVALID_JSON' if name == 'invalid-manifest' and phase == 'malformed' else 'BINDING_ERROR'}
                for key in ['loadingDigest', 'validatedProjectionDigest', 'lastAppliedDigest']: proof[key] = None if blocked else '1' * 64
                proof['pfi'][snapshot['shell']] = {'inContent': invalid, 'excluded': not invalid}
                proofs.append(proof)
        (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in proofs))
        return proofs

    def test_missing_or_different_tree_model_trust_and_cold_evidence_fails(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 106)]
        self.assertEqual(validate_projection_evidence(self.root, pids), 20)
        variants = [proofs[:-1]]
        for index, key, value in [(0, 'tree', []), (0, 'loadedIds', []), (8, 'trusted', True), (5, 'pid', 100),
                                  (0, 'validatedProjectionDigest', None)]:
            changed = copy.deepcopy(proofs); changed[index][key] = value; variants.append(changed)
        for changed in variants:
            (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)

    def test_invalid_inputs_may_revoke_shell_hiding_but_must_preserve_repository_pfi(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 106)]
        for key in [str(self.root / 'invalid-binding/repo-a/docs/probe.txt'),
                    str(self.root / 'invalid-binding/.reqws/ide/goland')]:
            changed = copy.deepcopy(proofs)
            changed[11]['pfi'][key] = {'inContent': False, 'excluded': True}
            (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)

    def test_legacy_report_cannot_certify_desktop_linked_suite(self):
        archive = self.root / 'candidate.zip'; archive.write_bytes(b'fixture')
        policy = read_policy()
        ide = {'product': 'GO', 'version': policy['uiTestIdeVersion'], 'build': policy['uiTestIdeBuild']}
        report = {'scope': 'local-ide-integration', 'status': 'passed', 'ide': ide, 'actualIde': ide,
                  'candidate': {'sha256': digest(archive), 'version': '0.1.7'}}
        path = self.root / 'report.json'; path.write_text(json.dumps(report))
        verify_report(path, archive, '0.1.7')
        with self.assertRaises(ValueError): verify_report(path, archive, '0.1.7', 'desktop')
        report['suite'] = 'desktop'; path.write_text(json.dumps(report))
        with self.assertRaises(ValueError): verify_report(path, archive, '0.1.7', 'desktop')
        source = {'testedCommit': 'a' * 40, 'dirty': False}
        report.update(desktop={'tests': {'passed': 1}, 'identity': source}, results={'suite': 'desktop'})
        path.write_text(json.dumps(report))
        with patch('desktop_ide.subprocess.check_output', side_effect=['a' * 40 + '\n', '']):
            verify_report(path, archive, '0.1.7', 'desktop')
        for outputs in [['b' * 40 + '\n', ''], ['a' * 40 + '\n', ' M src/renderer/App.tsx\n']]:
            with patch('desktop_ide.subprocess.check_output', side_effect=outputs), self.assertRaises(ValueError):
                verify_report(path, archive, '0.1.7', 'desktop')
        report['desktop']['identity'] = None; path.write_text(json.dumps(report))
        with self.assertRaises(ValueError): verify_report(path, archive, '0.1.7', 'desktop')

    def test_profile_is_not_released_until_both_desktop_and_ide_cleanup_are_confirmed(self):
        self.assertFalse(session_closed(self.root, 'run', 'desktop'))
        closed = self.root / 'desktop-session-closed.json'
        closed.write_text(json.dumps({'schemaVersion': 1, 'sessionId': self.session,
                          'processesStopped': True, 'ownedRegistryChecked': True}))
        self.assertTrue(session_closed(self.root, 'run', 'desktop'))
        (self.root / 'ide-launch-requested').write_text('1')
        self.assertFalse(session_closed(self.root, 'run', 'desktop'))
        (self.root / 'processes.tsv').write_text('100\tstarted\n100\texited\n')
        self.assertTrue(session_closed(self.root, 'run', 'desktop'))
        closed.write_text('{}')
        self.assertFalse(session_closed(self.root, 'run', 'desktop'))


if __name__ == '__main__':
    unittest.main()
