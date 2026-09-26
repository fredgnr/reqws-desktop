"""Fail-closed local coordination/evidence checks without launching a licensed IDE."""

import copy
import struct
import zlib
import json
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from desktop_ide import CAPTURE_KIND, close_linked_session, execute_linked, validate_png, ACCEPTANCE_VERSION, PROJECTION_PROOFS, SAVED_PROJECTION_PROOFS, initialize_link, publish, read_message, validate_transcript, validate_projection_evidence, validate_saved_projection, validate_screenshot, native_directory_key
from check_ide_test_reports import check_reports, DESKTOP_SCENARIOS, SCENARIOS
from run_local_ide import verify_report, session_closed
from ide_compatibility import digest, read_policy


def png_chunk(kind, payload=b''):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))


PNG_SIGNATURE = b'\x89PNG\r\n\x1a\n'
PNG_IHDR = png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
PNG_IDAT = png_chunk(b'IDAT', zlib.compress(b'\0\x11\x22\x33'))
PNG_IEND = png_chunk(b'IEND')
VALID_PNG = PNG_SIGNATURE + PNG_IHDR + PNG_IDAT + PNG_IEND
CAPTURE_PNG = (PNG_SIGNATURE + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 400, 300, 8, 2, 0, 0, 0)) +
               png_chunk(b'IDAT', zlib.compress((b'\0' + b'\x11\x22\x33' * 400) * 300)) + PNG_IEND)


class DesktopIdeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.workspaces = self.root / 'reqws-e2e-fixture/workspaces'
        self.workspaces.mkdir(parents=True)
        self.session = initialize_link(self.root)
        self.directory = self.root / 'desktop-link'
        profile = tempfile.TemporaryDirectory(prefix='reqws-profile-fixture-')
        self.addCleanup(profile.cleanup)
        self.profile = Path(profile.name).resolve()
        (self.profile / 'config').mkdir()
        self.profile_id = str(uuid.uuid4())
        policy = read_policy()
        ide = {'product': 'GO', 'version': policy['uiTestIdeVersion'], 'build': policy['uiTestIdeBuild']}
        (self.profile / '.reqws-ide-profile.json').write_text(json.dumps({
            'schemaVersion': 1, 'purpose': 'reqws-local-ide-authorization', 'id': self.profile_id, **ide}))
        (self.root / 'report.json').write_text(json.dumps({'profileId': self.profile_id, 'ide': ide}))

    def transcript(self):
        sequence = 0
        for name in ['selection', 'trust', 'invalid-binding', 'invalid-manifest', 'coverage']:
            root = self.workspaces / name
            root.mkdir()
            values = ([['repo-a', 'repo-b'], ['repo-a'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']]
                      if name == 'selection' else [['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']]
                      if name == 'coverage' else [['repo-a', 'repo-b']])
            binding = str(uuid.uuid4())
            for revision, selected in enumerate(values, 1):
                sequence += 1
                envelope = {'schemaVersion': 1, 'sessionId': self.session, 'sequence': sequence}
                request = {**envelope, 'operation': 'create' if revision == 1 else 'select', 'name': name}
                if revision != 1: request['selected'] = selected
                snapshot = {'name': name, 'root': str(root), 'shell': str(root / '.reqws/ide/goland'),
                            'workspaceId': name, 'bindingId': binding, 'revision': revision,
                            'selected': selected, 'repositories': [{'id': 'a', 'name': 'repo-a'}, {'id': 'b', 'name': 'repo-b'}, {'id': 'c', 'name': 'repo-c'}]}
                snapshot['editorLaunch'] = {'command': '/usr/bin/open',
                    'args': ['-a', str(root.parent.parent / 'home/Applications/GoLand.app'), snapshot['shell']],
                    'shell': False, 'boundary': 'os-spawn-only'}
                publish(self.directory / f'request-{sequence}.json', request)
                publish(self.directory / f'response-{sequence}.json', {**envelope, 'status': 'passed', 'snapshot': snapshot})
                if name == 'selection' and revision == 1:
                    sequence += 1
                    envelope = {'schemaVersion': 1, 'sessionId': self.session, 'sequence': sequence}
                    publish(self.directory / f'request-{sequence}.json', {**envelope,
                        'operation': 'focus-external-edit', 'name': 'selection', 'phase': 'late-files', 'revision': 1})
                    publish(self.directory / f'response-{sequence}.json', {**envelope, 'status': 'passed', 'focus': {
                        'name': 'selection', 'phase': 'late-files', 'revision': 1, 'workspaceId': name,
                        'bindingId': binding, 'desktopPid': 999, 'windowId': 1, 'focused': True}})
            self.write_saved_model(snapshot)
        sequence += 1
        envelope = {'schemaVersion': 1, 'sessionId': self.session, 'sequence': sequence}
        publish(self.directory / f'request-{sequence}.json', {**envelope, 'operation': 'finish'})
        publish(self.directory / f'response-{sequence}.json', {**envelope, 'status': 'passed'})

    def write_saved_model(self, snapshot):
        shell = Path(snapshot['shell'])
        module_name = 'ReqWS-' + snapshot['bindingId']
        module_file = shell / '.idea/reqws' / (module_name + '.iml')
        module_file.parent.mkdir(parents=True)
        for repo in snapshot['repositories']:
            directory = Path(snapshot['root']) / repo['name']
            (directory / '.git').mkdir(parents=True)
            (directory / 'docs').mkdir()
            (directory / 'docs/probe.txt').write_text('ordinary text fixture\n')
            (directory / 'README.txt').write_text(f"ReqWS Git fixture: {repo['name']}\n")
        for relative, content in [('notes/outside.txt', 'outside all project roots\n'), ('user-content/keep.txt', 'user owned\n'),
                                  ('user-extra/keep-extra.txt', 'unclaimed root in the managed module\n'),
                                  ('.reqws/ide/goland/shell-probe.txt', 'dedicated shell stays hidden\n')]:
            file = Path(snapshot['root']) / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content)
        if snapshot['name'] == 'selection':
            (Path(snapshot['root']) / 'repo-a/docs/late-repo.txt').write_text('late repository file\n')
            (shell / 'late-shell.txt').write_text('late shell remains hidden\n')
        claims = [{'relativePath': repo['name'], 'repositoryId': repo['id'], 'nonce': uuid.uuid4().hex,
                   'rootKey': native_directory_key(Path(snapshot['root']) / repo['name']),
                   'gitKey': native_directory_key(Path(snapshot['root']) / repo['name'] / '.git')}
                  for repo in snapshot['repositories'] if repo['name'] in snapshot['selected']]
        journal = {'formatVersion': 1, 'workspaceId': snapshot['workspaceId'], 'bindingId': snapshot['bindingId'],
                   'workspaceRoot': snapshot['root'], 'shell': str(shell), 'moduleName': module_name,
                   'moduleFile': str(module_file), 'claims': claims, 'pendingAdds': claims.copy(), 'pendingRemoves': [],
                   'shellKey': native_directory_key(shell), 'ideaKey': native_directory_key(shell / '.idea')}
        (shell / '.idea/reqws-loaded-roots.json').write_text(json.dumps(journal))
        model = ET.Element('module')
        component = ET.SubElement(model, 'component', name='NewModuleRootManager')
        for claim in claims:
            url = 'file://$MODULE_DIR$/../../../../../' + claim['relativePath']
            content = ET.SubElement(component, 'content', url=url)
            ET.SubElement(content, 'excludeFolder', url=url + '/.reqws-goland-ownership/' + claim['nonce'])
        if snapshot['name'] == 'selection':
            ET.SubElement(component, 'content', url='file://$MODULE_DIR$/../../../../../user-extra')
        module_file.write_bytes(ET.tostring(model))
        project = ET.Element('project')
        modules = ET.SubElement(ET.SubElement(project, 'component', name='ProjectModuleManager'), 'modules')
        path = '$PROJECT_DIR$/.idea/reqws/' + module_file.name
        ET.SubElement(modules, 'module', filepath=path, fileurl='file://' + path)
        if snapshot['name'] != 'trust':
            path = '$PROJECT_DIR$/.idea/user.iml'
            ET.SubElement(modules, 'module', filepath=path, fileurl='file://' + path)
            user = ET.Element('module')
            component = ET.SubElement(user, 'component', name='NewModuleRootManager')
            ET.SubElement(component, 'content', url='file://$MODULE_DIR$/../../../../user-content')
            if snapshot['name'] == 'coverage':
                ET.SubElement(component, 'content', url='file://$MODULE_DIR$/../../../../repo-c')
            (shell / '.idea/user.iml').write_bytes(ET.tostring(user))
        (shell / '.idea/modules.xml').write_bytes(ET.tostring(project))

    def test_saved_model_rejects_cache_only_roots_markers_registration_and_foreign_binding(self):
        self.transcript()
        snapshot = read_message(self.directory / 'response-9.json')['snapshot']
        shell = Path(snapshot['shell'])
        module = next((shell / '.idea/reqws').glob('*.iml'))
        modules = shell / '.idea/modules.xml'
        journal = shell / '.idea/reqws-loaded-roots.json'
        validate_saved_projection(self.root, snapshot)
        variants = [(module, b'<module><component name="NewModuleRootManager"/></module>'),
                    (module, module.read_bytes().replace(b'excludeFolder', b'sourceFolder')),
                    (module, module.read_bytes().replace(b'.reqws-goland-ownership/', b'.reqws-goland-ownership/foreign')),
                    (modules, b'<project/>'),
                    (modules, modules.read_bytes().replace(b'file://$PROJECT_DIR$', b'file://$PROJECT_DIR$/foreign')),
                    (journal, journal.read_bytes().replace(snapshot['bindingId'].encode(), b'foreign')),
                    (module, b'<!DOCTYPE module><module/>'),
                    (module, '<!DOCTYPE module><module/>'.encode('utf-16')),
                    (shell / '.idea/user.iml', b'<module/>'),
                    (modules, modules.read_bytes().replace(b'user.iml', b'foreign.iml'))]
        baseline = json.loads(journal.read_text())
        for field in ['shellKey', 'ideaKey', 'pendingAdds', 'pendingRemoves']:
            changed = copy.deepcopy(baseline); del changed[field]
            variants.append((journal, json.dumps(changed).encode()))
        for field in ['rootKey', 'gitKey', 'nonce']:
            changed = copy.deepcopy(baseline); changed['claims'][0][field] = 'foreign'
            variants.append((journal, json.dumps(changed).encode()))
        changed = copy.deepcopy(baseline); changed['pendingRemoves'] = changed['claims'][:1]
        variants.append((journal, json.dumps(changed).encode()))
        for path, data in variants:
            with self.subTest(path=path.name, data=data[:60]):
                original = path.read_bytes()
                try:
                    path.write_bytes(data)
                    with self.assertRaises(ValueError): validate_saved_projection(self.root, snapshot)
                finally:
                    path.write_bytes(original)
        original = module.read_bytes()
        module.unlink()
        module.symlink_to(journal)
        with self.assertRaises(ValueError): validate_saved_projection(self.root, snapshot)
        module.unlink(); module.write_bytes(original)

    def test_complete_exchange_requires_live_and_cold_selections(self):
        self.transcript()
        self.assertEqual(validate_transcript(self.root, self.session)['requests'], 16)
        path = self.directory / 'request-6.json'
        value = read_message(path)
        value['selected'] = ['repo-a']
        path.write_text(json.dumps(value))
        with self.assertRaises(ValueError): validate_transcript(self.root, self.session)

    def test_external_edit_focus_requires_one_bound_native_window_and_actual_inactive_to_active_stages(self):
        self.transcript()
        self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        self.assertEqual(validate_projection_evidence(self.root, pids), PROJECTION_PROOFS)
        path = self.root / 'desktop-external-edit.json'
        original = read_message(path)
        variants = []
        for key, value in [('desktopPid', 998), ('idePid', 101), ('windowId', 2), ('requestSequence', 3),
                           ('bindingId', 'foreign'), ('sessionId', str(uuid.uuid4())), ('phase', 'projection')]:
            variants.append({**original, key: value})
        changed = copy.deepcopy(original); changed['stages'] = list(reversed(changed['stages'])); variants.append(changed)
        for index, key, value in [(0, 'frameActive', True), (1, 'frameFocused', True),
                                   (1, 'repoDiskExists', False), (2, 'frameActive', False), (0, 'frameFocused', 0)]:
            changed = copy.deepcopy(original); changed['stages'][index][key] = value; variants.append(changed)
        for changed in variants:
            path.write_text(json.dumps(changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        path.write_text(json.dumps(original))
        request = self.directory / 'request-2.json'
        before = read_message(request)
        for key, value in [('name', 'coverage'), ('phase', 'projection'), ('revision', 2)]:
            request.write_text(json.dumps({**before, key: value}))
            with self.assertRaises(ValueError): validate_transcript(self.root, self.session)
        request.write_text(json.dumps(before))
        response = self.directory / 'response-2.json'
        before = read_message(response)
        for key, value in [('schemaVersion', True), ('sequence', 2.0)]:
            response.write_text(json.dumps({**before, key: value}))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        changed = copy.deepcopy(before); changed['focus']['focused'] = False
        response.write_text(json.dumps(changed))
        with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        with self.assertRaises(ValueError): validate_transcript(self.root, self.session)

    def test_host_focus_gate_completes_before_coordinator_publishes_finish(self):
        self.transcript()
        self.write_proofs()
        (self.directory / 'request-16.json').unlink()
        (self.directory / 'response-16.json').unlink()
        self.assertEqual(validate_projection_evidence(self.root, [str(pid) for pid in range(100, 109)]), PROJECTION_PROOFS)
        with self.assertRaises(ValueError): validate_transcript(self.root, self.session)

    def test_missing_failed_stale_or_mismatched_responses_never_pass(self):
        self.transcript()
        path = self.directory / 'response-3.json'
        original = read_message(path)
        variants = [dict(original, status='failed'), dict(original, sessionId=str(uuid.uuid4())), dict(original, sequence=1)]
        for field, value in [('revision', 1), ('bindingId', str(uuid.uuid4())), ('root', '/tmp/foreign')]:
            altered = copy.deepcopy(original); altered['snapshot'][field] = value; variants.append(altered)
        for value in variants:
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError): validate_transcript(self.root, self.session)
        path.unlink()
        with self.assertRaises(ValueError): validate_transcript(self.root, self.session)

    def test_starter_target_requires_the_exact_observed_desktop_goland_os_launch(self):
        self.transcript()
        self.assertEqual(validate_transcript(self.root, self.session)['editorLaunches'], 5)
        path = self.directory / 'response-1.json'
        original = read_message(path)
        expected = original['snapshot']['editorLaunch']
        variants = [None, {**expected, 'shell': True}, {**expected, 'shell': 0},
                    {**expected, 'command': '/bin/sh'}, {**expected, 'boundary': 'native-open'},
                    {**expected, 'args': ['-a', expected['args'][1], original['snapshot']['root']]},
                    {**expected, 'args': ['-a', '/Applications/GoLand.app', expected['args'][2]]}]
        for launch in variants:
            changed = copy.deepcopy(original)
            if launch is None: del changed['snapshot']['editorLaunch']
            else: changed['snapshot']['editorLaunch'] = launch
            path.write_text(json.dumps(changed))
            with self.assertRaises(ValueError): validate_transcript(self.root, self.session)
        path.write_text(json.dumps(original))

    def test_service_error_alone_cannot_replace_visible_tool_window_error_and_code(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        self.assertEqual(validate_projection_evidence(self.root, pids), PROJECTION_PROOFS)
        path = self.root / 'desktop-error-ui.jsonl'
        original = [json.loads(line) for line in path.read_text().splitlines()]
        variants = [[], original[:-1], original + [original[0]]]
        for key, value in [('statusTexts', ['Synced']), ('detailTexts', ['OTHER_ERROR']),
                           ('detailTexts', ['BINDING_ERROR_SUFFIX']), ('bindingId', 'foreign'),
                           ('revision', 99), ('screenshot', proofs[0]['screenshot'])]:
            changed = copy.deepcopy(original); changed[0][key] = value; variants.append(changed)
        for entries in variants:
            path.write_text(''.join(json.dumps(value) + '\n' for value in entries))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        path.unlink()
        with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)

    def test_protocol_does_not_overwrite_or_follow_message_symlinks(self):
        filename = self.directory / 'message.json'
        publish(filename, {'keep': True})
        with self.assertRaises(FileExistsError): publish(filename, {'keep': False})
        self.assertEqual(read_message(filename), {'keep': True})
        link = self.directory / 'link.json'; link.symlink_to(filename)
        with self.assertRaises(OSError): read_message(link)

    def test_existing_same_session_abort_is_idempotent_and_preserves_original_failure(self):
        abort = self.directory / 'abort.json'
        publish(abort, {'schemaVersion': 1, 'sessionId': self.session, 'status': 'failed'})
        original = abort.read_bytes()
        with patch('desktop_ide.identity', return_value={}), patch('desktop_ide.assert_desktop_source',
                side_effect=ValueError('original host failure')), patch('desktop_ide.cleanup_owned_processes', return_value=[]) as cleanup:
            with self.assertRaisesRegex(ValueError, 'original host failure'):
                execute_linked(['unused'], self.root, 1, {})
        cleanup.assert_called_once_with(self.root / 'desktop-processes.jsonl')
        self.assertEqual(abort.read_bytes(), original)
        self.assertTrue(session_closed(self.root, 'run', 'desktop'))

    def test_abort_write_failure_still_stops_both_children_and_checks_registry(self):
        registry = self.root / 'desktop-processes.jsonl'
        registry.touch()
        host = Mock()
        host.poll.side_effect = [None, 0]
        host.wait.side_effect = subprocess.TimeoutExpired('host', 25)
        desktop = Mock()
        desktop.poll.side_effect = [None, 0]
        original_publish = publish
        def fail_abort(path, value):
            if path.name == 'abort.json': raise PermissionError('abort denied')
            original_publish(path, value)
        with patch('desktop_ide.publish', side_effect=fail_abort), patch('desktop_ide.stop_child') as stop, \
                patch('desktop_ide.cleanup_owned_processes', return_value=[]) as cleanup:
            with self.assertRaisesRegex(ValueError, 'Abort publication failed: abort denied'):
                close_linked_session(self.root, self.session, registry, (host, desktop), False)
        stop.assert_called_once_with(host)
        desktop.wait.assert_called_once_with(timeout=25)
        cleanup.assert_called_once_with(registry)
        self.assertTrue(session_closed(self.root, 'run', 'desktop'))

    def test_foreign_abort_is_preserved_but_does_not_bypass_cleanup(self):
        registry = self.root / 'desktop-processes.jsonl'
        registry.touch()
        abort = self.directory / 'abort.json'
        publish(abort, {'schemaVersion': 1, 'sessionId': 'foreign', 'status': 'failed'})
        original = abort.read_bytes()
        with patch('desktop_ide.cleanup_owned_processes', return_value=[]) as cleanup:
            with self.assertRaisesRegex(ValueError, 'different session or status'):
                close_linked_session(self.root, self.session, registry, (None, None), False)
        cleanup.assert_called_once_with(registry)
        self.assertEqual(abort.read_bytes(), original)

    def test_unconfirmed_child_or_registry_cleanup_never_publishes_closed_marker(self):
        registry = self.root / 'desktop-processes.jsonl'
        registry.touch()
        host = Mock()
        host.poll.return_value = None
        host.wait.side_effect = OSError('owned child unavailable')
        desktop = Mock()
        desktop.poll.side_effect = [None, 0]
        with patch('desktop_ide.cleanup_owned_processes', side_effect=ValueError('reused PID')) as cleanup:
            with self.assertRaisesRegex(ValueError, 'owned child unavailable.*reused PID'):
                close_linked_session(self.root, self.session, registry, (host, desktop), False)
        desktop.wait.assert_called_once_with(timeout=25)
        cleanup.assert_called_once_with(registry)
        self.assertFalse((self.root / 'desktop-session-closed.json').exists())

    def test_successful_test_body_cannot_hide_a_process_leak_during_finalization(self):
        registry = self.root / 'desktop-processes.jsonl'
        registry.touch()
        with patch('desktop_ide.cleanup_owned_processes', return_value=[123]):
            with self.assertRaisesRegex(ValueError, 'live owned process'):
                close_linked_session(self.root, self.session, registry, (None, None), True)

    def test_ide_reports_reject_legacy_skips_duplicates_and_unclean_processes(self):
        self.transcript()
        self.write_proofs()
        marker = self.root / 'run-root.txt'; marker.write_text(str(self.root))
        xml = self.root / 'TEST-suite.xml'
        def write_cases(selectors, extra=''):
            xml.write_text('<testsuite>' + ''.join(f'<testcase name="{name}()">{extra}</testcase>' for name in selectors) + '</testsuite>')
        events = ''.join(f'{pid}\tstarted\n{pid}\tpassed\n{pid}\texited\n' for pid in range(100, 109))
        (self.root / 'processes.tsv').write_text(events)
        write_cases(DESKTOP_SCENARIOS)
        self.assertEqual(check_reports(self.root, marker, 'desktop')['processes'], 9)
        for selectors, extra in [(SCENARIOS, ''), (DESKTOP_SCENARIOS, '<skipped/>'),
                                 (list(DESKTOP_SCENARIOS) + [next(iter(DESKTOP_SCENARIOS))], '')]:
            write_cases(selectors, extra)
            with self.assertRaises(ValueError): check_reports(self.root, marker, 'desktop')
        write_cases(DESKTOP_SCENARIOS)
        (self.root / 'processes.tsv').write_text(events.replace('105\tpassed', '105\tforced-kill'))
        with self.assertRaises(ValueError): check_reports(self.root, marker, 'desktop')

    def write_proofs(self):
        snapshots = {(value['snapshot']['name'], value['snapshot']['revision']): value['snapshot'] for path in self.directory.glob('response-*.json')
                     if (value := read_message(path)).get('snapshot')}
        proofs = []
        for name in ['selection', 'trust', 'invalid-binding', 'invalid-manifest', 'coverage']:
            revisions = ([1] * 6 + [2, 3, 4, 5, 5, 6, 7, 7, 7, 8, 8] if name == 'selection' else [1, 2, 3]
                         if name == 'coverage' else [1] * (2 if name == 'trust' else 5))
            phases = (['projection', 'excluded-off', 'excluded-on', 'late-files-on', 'late-files-off', 'excluded-restored'] +
                      ['projection'] * 4 + ['cold-empty', 'user-root-selected', 'user-root-empty', 'reopened-empty', 'post-clear-cold-empty', 'projection', 'cold-selected']
                      if name == 'selection' else ['projection'] * 3 if name == 'coverage' else ['safe-mode-blocked', 'projection']
                      if name == 'trust' else ['projection', 'malformed', 'projection', 'mismatched', 'projection'])
            for index, (revision, phase) in enumerate(zip(revisions, phases)):
                snapshot = snapshots[name, revision]
                selected = snapshot['selected']
                blocked = phase == 'safe-mode-blocked'
                loaded = [] if blocked else selected
                included = loaded + (['repo-c'] if name == 'coverage' else [])
                root = Path(snapshot['root'])
                roots = [str(root / repo) for repo in included]
                modules = {} if blocked else {'ReqWS-' + snapshot['bindingId']: [str(root / repo) for repo in loaded]}
                tree = [[repo, 'docs', 'probe.txt'] for repo in included]
                invalid = phase in {'malformed', 'mismatched'}
                if invalid: tree.append(['goland', 'reqws-project.json'])
                if name != 'trust':
                    roots.append(str(root / 'user-content'))
                    modules['user'] = sorted([str(root / 'user-content')] + ([str(root / 'repo-c')] if name == 'coverage' else []))
                    tree.append(['user-content', 'keep.txt'])
                managed_user = name == 'selection' and index >= 10
                late = name == 'selection' and index >= 3
                if managed_user:
                    roots.append(str(root / 'user-extra'))
                    modules['ReqWS-' + snapshot['bindingId']].append(str(root / 'user-extra'))
                    tree.append(['user-extra', 'keep-extra.txt'])
                if late and 'repo-a' in loaded: tree.append(['repo-a', 'docs', 'late-repo.txt'])
                pid = (100 if index < 10 else 101 if index < 14 else 102 if index < 16 else 103) if name == 'selection' else {'trust': 104, 'invalid-binding': 105, 'invalid-manifest': 106, 'coverage': 107}[name]
                pfi = {str(root / repo / suffix): {'inContent': repo in included, 'excluded': False}
                       for repo in ['repo-a', 'repo-b', 'repo-c'] for suffix in ['', 'docs/probe.txt']}
                pfi[str(root / 'notes/outside.txt')] = {'inContent': False, 'excluded': False}
                pfi[str(root / 'user-extra/keep-extra.txt')] = {'inContent': managed_user, 'excluded': False}
                for path in [snapshot['shell'], str(Path(snapshot['shell']) / 'shell-probe.txt')]:
                    pfi[path] = {'inContent': invalid, 'excluded': not invalid}
                if late:
                    pfi[str(root / 'repo-a/docs/late-repo.txt')] = {'inContent': 'repo-a' in loaded, 'excluded': False}
                    pfi[str(Path(snapshot['shell']) / 'late-shell.txt')] = {'inContent': False, 'excluded': True}
                proof = {'scenario': name, 'phase': phase, 'pid': pid, 'revision': revision, 'selected': selected,
                         'workspaceId': snapshot['workspaceId'], 'bindingId': snapshot['bindingId'], 'roots': roots,
                         'modules': modules, 'pfi': pfi, 'managedUserRoot': managed_user, 'lateFiles': late,
                         'showExcludedFiles': not (name == 'selection' and index in [1, 4]), 'vcsMappings': [],
                         'userCoverage': ['c'] if name == 'coverage' else [],
                         'tree': tree, 'trusted': not blocked, 'loadedIds': [repo['id'] for repo in snapshot['repositories'] if repo['name'] in loaded],
                         'lifecycle': 'SAFE_MODE_BLOCKED' if blocked else 'ERROR' if phase in {'malformed', 'mismatched'} else 'SYNCHRONIZED',
                         'error': 'MANIFEST_INVALID_JSON' if name == 'invalid-manifest' and phase == 'malformed' else 'BINDING_ERROR'}
                proof['screenshot'] = self.screenshot(f'{name}-{index}', snapshot['shell'], pid)
                for key in ['loadingDigest', 'validatedProjectionDigest', 'lastAppliedDigest']: proof[key] = None if blocked else '1' * 64
                proofs.append(proof)
        (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in proofs))
        ordinary = self.root / 'unbound-fixture'
        shell = ordinary / '.reqws/ide/goland'
        shell.mkdir(parents=True, exist_ok=True)
        (shell / 'shell-probe.txt').write_text('unbound same-name shell\n')
        (self.root / 'desktop-ordinary.json').write_text(json.dumps({'pid': 108, 'project': str(ordinary),
            'tree': [['unbound-fixture', '.reqws', 'ide', 'goland', 'shell-probe.txt']], 'inContent': True,
            'lifecycle': 'INACTIVE', 'vcsMappings': [], 'screenshot': self.screenshot('ordinary', str(ordinary), 108)}))
        errors = []
        for proof in proofs:
            if proof['phase'] not in {'malformed', 'mismatched'}: continue
            errors.append({'scenario': proof['scenario'], 'phase': proof['phase'], 'bindingId': proof['bindingId'],
                'revision': proof['revision'], 'statusTexts': ['Error'], 'detailTexts': [proof['error']],
                'screenshot': self.screenshot(f"error-{proof['scenario']}-{proof['phase']}",
                    snapshots[proof['scenario'], proof['revision']]['shell'], proof['pid'])})
        (self.root / 'desktop-error-ui.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in errors))
        snapshot = snapshots['selection', 1]
        (self.root / 'desktop-external-edit.json').write_text(json.dumps({
            'schemaVersion': 1, 'sessionId': self.session, 'scenario': 'selection', 'phase': 'late-files-on',
            'revision': 1, 'workspaceId': snapshot['workspaceId'], 'bindingId': snapshot['bindingId'],
            'project': snapshot['shell'], 'idePid': 100, 'desktopPid': 999, 'windowId': 1, 'requestSequence': 2,
            'stages': [{'stage': stage, 'frameFocused': active, 'frameActive': active,
                'repoDiskExists': exists, 'shellDiskExists': exists} for stage, active, exists in [
                    ('desktop-focused', False, False), ('files-created', False, True), ('ide-returned', True, True)]]}))
        snapshot = snapshots['selection', 7]
        config = self.profile / 'config'
        welcome = str(config / 'projects/GoLandWorkspace')
        path = ['Recent Projects', 'goland ' + snapshot['shell']]
        (self.root / 'desktop-project-reopen.json').write_text(json.dumps({
            'schemaVersion': 1, 'sessionId': self.session, 'scenario': 'selection', 'phase': 'reopened-empty',
            'revision': 7, 'workspaceId': snapshot['workspaceId'], 'bindingId': snapshot['bindingId'],
            'project': snapshot['shell'], 'idePid': 101, 'profileConfig': str(config),
            'previousScreenshot': proofs[12]['screenshot'], 'stages': [
                {'stage': 'closed', 'pid': 101, 'originalOpen': False, 'openProjects': [welcome]},
                {'stage': 'recent-project-selected', 'pid': 101, 'welcomeProject': welcome,
                 'frameTitle': 'GoLandWorkspace – Welcome to GoLand', 'tree': [{'row': 0, 'path': path}],
                 'selectedRow': 0, 'selectedPath': path},
                {'stage': 'reopened', 'pid': 101, 'originalOpen': False, 'projectOpen': True,
                 'projectInitialized': True, 'openProjects': [snapshot['shell']]}]}))
        return proofs

    def screenshot(self, name, project=None, pid=100):
        file = self.root / f'{name}.png'
        file.write_bytes(CAPTURE_PNG)
        project = project or str(self.root)
        file.with_name(file.name + '.json').write_text(json.dumps({
            'schemaVersion': 1, 'captureKind': CAPTURE_KIND, 'project': project, 'frameProject': project,
            'frameTitle': 'Fixture - GoLand', 'pid': pid, 'width': 400, 'height': 300}))
        return str(file)

    def test_every_capture_requires_component_provenance_matching_project_pid_and_pixels(self):
        filename = self.screenshot('component')
        validate_screenshot(self.root, filename, str(self.root), 100)
        sidecar = Path(filename + '.json')
        original = read_message(sidecar)
        for key, value in [('schemaVersion', 2), ('captureKind', 'full-screen'), ('pid', 101), ('pid', True),
                           ('project', '/Applications/GoLand.app'), ('frameProject', '/foreign'),
                           ('frameTitle', ''), ('width', 1), ('height', 1)]:
            with self.subTest(field=key, value=value):
                sidecar.write_text(json.dumps({**original, key: value}))
                with self.assertRaises(ValueError): validate_screenshot(self.root, filename, str(self.root), 100)
        sidecar.unlink()
        with self.assertRaises(OSError): validate_screenshot(self.root, filename, str(self.root), 100)

    def test_raw_capture_metadata_cannot_be_replaced_by_v4_summary_or_a_renamed_full_screen(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        self.assertEqual(validate_projection_evidence(self.root, pids), PROJECTION_PROOFS)
        error_ui = [json.loads(line) for line in (self.root / 'desktop-error-ui.jsonl').read_text().splitlines()]
        ordinary = read_message(self.root / 'desktop-ordinary.json')
        for observation in [proofs[0], error_ui[0], ordinary]:
            sidecar = Path(observation['screenshot'] + '.json')
            original = sidecar.read_bytes()
            metadata = read_message(sidecar)
            metadata['captureKind'] = 'full-screen'
            sidecar.write_text(json.dumps(metadata))
            try:
                with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
            finally:
                sidecar.write_bytes(original)

    def test_screenshots_require_complete_crc_checked_png_and_one_bounded_zlib_stream(self):
        path = self.root / 'integrity.png'
        path.write_bytes(VALID_PNG)
        self.assertEqual(validate_png(path.read_bytes()), (1, 1))
        compressed = zlib.compress(b'\0\x11\x22\x33')
        # Multiple consecutive IDAT chunks are one legal PNG image stream.
        prefix = PNG_SIGNATURE + PNG_IHDR
        path.write_bytes(prefix + png_chunk(b'IDAT', compressed[:3]) + png_chunk(b'IDAT', compressed[3:]) + PNG_IEND)
        validate_png(path.read_bytes())
        cases = {
            'header-only': prefix,
            'truncated-chunk': VALID_PNG[:-2],
            'wrong-crc': prefix + PNG_IDAT[:-1] + bytes([PNG_IDAT[-1] ^ 1]) + PNG_IEND,
            'duplicate-header': prefix + PNG_IHDR + PNG_IDAT + PNG_IEND,
            'no-image-data': prefix + PNG_IEND,
            'no-end': prefix + PNG_IDAT,
            'duplicate-end': VALID_PNG + PNG_IEND,
            'trailing-bytes': VALID_PNG + b'junk',
            'truncated-zlib-with-valid-crc': prefix + png_chunk(b'IDAT', compressed[:-1]) + PNG_IEND,
            'bad-zlib-with-valid-crc': prefix + png_chunk(b'IDAT', b'not zlib') + PNG_IEND,
            'extra-zlib-stream': prefix + png_chunk(b'IDAT', compressed + compressed) + PNG_IEND,
            'oversized-decoded-data': prefix + png_chunk(b'IDAT', zlib.compress(b'\0' * 1024)) + PNG_IEND,
            'short-decoded-data': prefix + png_chunk(b'IDAT', zlib.compress(b'\0')) + PNG_IEND,
            'invalid-filter': prefix + png_chunk(b'IDAT', zlib.compress(b'\x05\x11\x22\x33')) + PNG_IEND,
            'nonconsecutive-idat': prefix + png_chunk(b'IDAT', compressed[:3]) + png_chunk(b'tEXt', b'key\0value') + png_chunk(b'IDAT', compressed[3:]) + PNG_IEND,
            'unbounded-dimensions': PNG_SIGNATURE + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 32769, 1, 8, 2, 0, 0, 0)) + PNG_IDAT + PNG_IEND,
        }
        for name, image in cases.items():
            with self.subTest(name=name):
                path.write_bytes(image)
                with self.assertRaises(ValueError): validate_png(path.read_bytes())

    def test_reopen_requires_closed_original_unique_canonical_recent_row_and_same_pid(self):
        self.transcript()
        self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        self.assertEqual(validate_projection_evidence(self.root, pids), PROJECTION_PROOFS)
        filename = self.root / 'desktop-project-reopen.json'
        original = read_message(filename)
        variants = []
        for key, value in [('schemaVersion', True), ('sessionId', str(uuid.uuid4())), ('revision', 7.0),
                           ('idePid', 102), ('bindingId', 'foreign'), ('project', '/wrong'),
                           ('previousScreenshot', str(self.root / 'selection-13.png'))]:
            changed = copy.deepcopy(original); changed[key] = value; variants.append(changed)
        for index, key, value in [
            (0, 'originalOpen', True), (0, 'openProjects', [original['project']]),
            (0, 'openProjects', ['/unrelated/project']), (0, 'pid', 101.0),
            (1, 'selectedRow', True), (1, 'selectedRow', 1), (1, 'selectedPath', ['goland']),
            (1, 'welcomeProject', '/foreign/config/projects/GoLandWorkspace'),
            (1, 'frameTitle', 'Other IDE'), (2, 'pid', 102), (2, 'originalOpen', True),
            (2, 'projectOpen', False), (2, 'projectInitialized', 1), (2, 'openProjects', []),
        ]:
            changed = copy.deepcopy(original); changed['stages'][index][key] = value; variants.append(changed)
        for path in [['goland /private/.../goland'], ['goland ' + original['project'] + '-other'],
                     [original['project'], 'child-without-path']]:
            changed = copy.deepcopy(original)
            changed['stages'][1]['tree'][0]['path'] = path
            changed['stages'][1]['selectedPath'] = path
            variants.append(changed)
        changed = copy.deepcopy(original)
        changed['stages'][1]['tree'].append({**changed['stages'][1]['tree'][0], 'row': 1})
        variants.append(changed)
        # Even a second correctly marked profile for the same fixed IDE is not
        # this run's profile. Replacing all mutually consistent paths must fail.
        foreign = tempfile.TemporaryDirectory(prefix='reqws-foreign-profile-fixture-')
        self.addCleanup(foreign.cleanup)
        foreign_profile = Path(foreign.name).resolve()
        (foreign_profile / 'config').mkdir()
        foreign_marker = read_message(self.profile / '.reqws-ide-profile.json')
        foreign_marker['id'] = str(uuid.uuid4())
        (foreign_profile / '.reqws-ide-profile.json').write_text(json.dumps(foreign_marker))
        changed = copy.deepcopy(original)
        changed['profileConfig'] = str(foreign_profile / 'config')
        foreign_welcome = str(foreign_profile / 'config/projects/GoLandWorkspace')
        changed['stages'][0]['openProjects'] = [foreign_welcome]
        changed['stages'][1]['welcomeProject'] = foreign_welcome
        variants.append(changed)
        changed = copy.deepcopy(changed)
        changed['profileConfig'] = str(foreign_profile / 'missing/config')
        changed['stages'][0]['openProjects'] = [str(foreign_profile / 'missing/config/projects/GoLandWorkspace')]
        changed['stages'][1]['welcomeProject'] = changed['stages'][0]['openProjects'][0]
        variants.append(changed)
        changed = copy.deepcopy(original); changed['stages'] = changed['stages'][1:]; variants.append(changed)
        changed = copy.deepcopy(original); changed['stages'].reverse(); variants.append(changed)
        for changed in variants:
            filename.write_text(json.dumps(changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        filename.unlink()
        with self.assertRaises(FileNotFoundError): validate_projection_evidence(self.root, pids)

    def test_g4_user_root_survives_two_to_empty_before_project_reopen_and_cold_recovery(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        self.assertEqual([(proofs[i]['phase'], proofs[i]['revision'], proofs[i]['selected']) for i in range(10, 15)], [
            ('cold-empty', 5, []), ('user-root-selected', 6, ['repo-a', 'repo-b']),
            ('user-root-empty', 7, []), ('reopened-empty', 7, []), ('post-clear-cold-empty', 7, [])])
        self.assertNotEqual(proofs[13]['pid'], proofs[14]['pid'])
        self.assertEqual(validate_projection_evidence(self.root, pids), 32)
        variants = [proofs[:11] + proofs[13:], proofs[:14] + proofs[15:]]
        changed = copy.deepcopy(proofs); changed[14]['pid'] = changed[13]['pid']; variants.append(changed)
        changed = copy.deepcopy(proofs); changed[14]['revision'] = 8; variants.append(changed)
        for field, value in [('managedUserRoot', False), ('revision', 6), ('pid', 102)]:
            changed = copy.deepcopy(proofs); changed[12][field] = value; variants.append(changed)
        changed = copy.deepcopy(proofs)
        changed[12]['modules']['ReqWS-' + changed[12]['bindingId']] = []
        variants.append(changed)
        changed = copy.deepcopy(proofs)
        changed[12]['pfi'][str(self.workspaces / 'selection/user-extra/keep-extra.txt')]['inContent'] = False
        variants.append(changed)
        changed = copy.deepcopy(proofs)
        changed[12]['tree'] = [entry for entry in changed[12]['tree'] if 'user-extra' not in entry]
        variants.append(changed)
        for changed in variants:
            (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        request = self.directory / 'request-8.json'
        message = read_message(request); message['selected'] = ['repo-a', 'repo-b']
        request.write_text(json.dumps(message))
        with self.assertRaises(ValueError): validate_transcript(self.root, self.session)

    def test_missing_or_different_tree_model_trust_and_cold_evidence_fails(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        self.assertEqual(validate_projection_evidence(self.root, pids), PROJECTION_PROOFS)
        variants = [proofs[:-1]]
        for index, key, value in [(0, 'tree', []), (0, 'loadedIds', []), (17, 'trusted', True), (10, 'pid', 100),
                                  (0, 'validatedProjectionDigest', None)]:
            changed = copy.deepcopy(proofs); changed[index][key] = value; variants.append(changed)
        for changed in variants:
            (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)

    def test_invalid_inputs_may_revoke_shell_hiding_but_must_preserve_repository_pfi(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        for key in [str(self.workspaces / 'invalid-binding/repo-a/docs/probe.txt'),
                    str(self.workspaces / 'invalid-binding/.reqws/ide/goland')]:
            changed = copy.deepcopy(proofs)
            changed[20]['pfi'][key] = {'inContent': False, 'excluded': True}
            (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)

    def test_v_visibility_extra_root_coverage_and_vcs_evidence_cannot_be_omitted(self):
        self.transcript()
        proofs = self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        variants = []
        for index, key, value in [(1, 'showExcludedFiles', True), (2, 'showExcludedFiles', False),
                                  (5, 'showExcludedFiles', False), (3, 'lateFiles', False),
                                  (10, 'managedUserRoot', False), (0, 'vcsMappings', None),
                                  (1, 'screenshot', None), (1, 'screenshot', proofs[0]['screenshot']),
                                  (29, 'userCoverage', [])]:
            changed = copy.deepcopy(proofs); changed[index][key] = value; variants.append(changed)
        for index, file in [(0, 'notes'), (0, 'repo-c'), (3, 'late-shell.txt')]:
            changed = copy.deepcopy(proofs); changed[index]['tree'].append([file]); variants.append(changed)
        changed = copy.deepcopy(proofs)
        changed[10]['modules']['ReqWS-' + changed[10]['bindingId']] = []
        variants.append(changed)
        changed = copy.deepcopy(proofs)
        changed[29]['modules']['user'] = [str(self.workspaces / 'coverage/user-content')]
        variants.append(changed)
        changed = copy.deepcopy(proofs)
        changed[3]['tree'] = [entry for entry in changed[3]['tree'] if entry[-1] != 'late-repo.txt']
        variants.append(changed)
        for changed in variants:
            (self.root / 'desktop-projections.jsonl').write_text(''.join(json.dumps(value) + '\n' for value in changed))
            with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)

    def test_v_cannot_hide_missing_real_files_or_adopt_the_unbound_shell(self):
        self.transcript()
        self.write_proofs()
        pids = [str(pid) for pid in range(100, 109)]
        late = self.workspaces / 'selection/.reqws/ide/goland/late-shell.txt'
        original = late.read_text()
        late.unlink()
        with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        late.write_text(original)
        ordinary = self.root / 'unbound-fixture/.reqws/ide/goland/reqws-project.json'
        ordinary.write_text('{}')
        with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)
        ordinary.unlink()
        proof = self.root / 'desktop-ordinary.json'
        value = json.loads(proof.read_text()); value['tree'] = []
        proof.write_text(json.dumps(value))
        with self.assertRaises(ValueError): validate_projection_evidence(self.root, pids)

    def test_legacy_report_cannot_certify_desktop_linked_suite(self):
        archive = self.root / 'candidate.zip'; archive.write_bytes(b'fixture')
        policy = read_policy()
        ide = {'product': 'GO', 'version': policy['uiTestIdeVersion'], 'build': policy['uiTestIdeBuild']}
        report = {'scope': 'local-ide-integration', 'status': 'passed', 'ide': ide, 'actualIde': ide, 'profileId': self.profile_id,
                  'candidate': {'sha256': digest(archive), 'version': '0.1.7'}}
        path = self.root / 'report.json'; path.write_text(json.dumps(report))
        verify_report(path, archive, '0.1.7')
        with self.assertRaises(ValueError): verify_report(path, archive, '0.1.7', 'desktop')
        report['suite'] = 'desktop'; path.write_text(json.dumps(report))
        with self.assertRaises(ValueError): verify_report(path, archive, '0.1.7', 'desktop')
        source = {'testedCommit': 'a' * 40, 'dirty': False}
        report.update(desktop={'tests': {'passed': 1}, 'identity': source}, results={'suite': 'desktop',
            'savedProjectionProofs': SAVED_PROJECTION_PROOFS, 'projectionProofs': PROJECTION_PROOFS,
            'acceptanceVersion': ACCEPTANCE_VERSION, 'tests': 4, 'processes': 9})
        self.transcript()
        self.write_proofs()
        marker = self.root / 'run-root.txt'; marker.write_text(str(self.root))
        junit = self.root / 'junit'; junit.mkdir()
        (junit / 'TEST-suite.xml').write_text('<testsuite>' + ''.join(f'<testcase name="{name}()"/>' for name in DESKTOP_SCENARIOS) + '</testsuite>')
        (self.root / 'processes.tsv').write_text(''.join(f'{pid}\tstarted\n{pid}\tpassed\n{pid}\texited\n' for pid in range(100, 109)))
        report['results'] = check_reports(junit, marker, 'desktop')
        report['desktop']['exchange'] = validate_transcript(self.root, self.session)
        path.write_text(json.dumps(report))
        with patch('desktop_ide.subprocess.check_output', side_effect=['a' * 40 + '\n', '']):
            verify_report(path, archive, '0.1.7', 'desktop')
        for field, value in [('acceptanceVersion', 1), ('acceptanceVersion', 2), ('acceptanceVersion', 3), ('acceptanceVersion', 4), ('acceptanceVersion', 5), ('savedProjectionProofs', 4), ('projectionProofs', 20),
                             ('projectionProofs', 31), ('tests', 3), ('processes', 6), ('processes', 8)]:
            stale = copy.deepcopy(report); stale['results'][field] = value
            path.write_text(json.dumps(stale))
            with self.assertRaises(ValueError): verify_report(path, archive, '0.1.7', 'desktop')
        path.write_text(json.dumps(report))
        raw = self.root / 'desktop-projections.jsonl'; baseline = raw.read_text()
        raw.write_text('')
        with patch('desktop_ide.subprocess.check_output', side_effect=['a' * 40 + '\n', '']), self.assertRaises(ValueError):
            verify_report(path, archive, '0.1.7', 'desktop')
        raw.write_text(baseline)
        for outputs in [['b' * 40 + '\n', ''], ['a' * 40 + '\n', ' M src/renderer/App.tsx\n']]:
            with patch('desktop_ide.subprocess.check_output', side_effect=outputs), self.assertRaises(ValueError):
                verify_report(path, archive, '0.1.7', 'desktop')
        report['desktop']['identity'] = None; path.write_text(json.dumps(report))
        with self.assertRaises(ValueError): verify_report(path, archive, '0.1.7', 'desktop')
        report['desktop']['identity'] = source
        del report['results']['savedProjectionProofs']
        path.write_text(json.dumps(report))
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
