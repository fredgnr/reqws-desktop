"""Private Desktop/Driver coordination, invoked only inside the local profile lock."""

import json
import os
from pathlib import Path
import re
import signal
import stat
import struct
import subprocess
import time
import uuid
from urllib.parse import unquote
import xml.etree.ElementTree as ET
import zlib

from desktop_e2e import ROOT, all_tests, cleanup_owned_processes, identity, read_json, validate_report
from ide_compatibility import read_policy

DESKTOP_TITLE = 'S4 Desktop UI drives the local IDE through an isolated session'
NAMES = {'selection', 'trust', 'invalid-binding', 'invalid-manifest', 'coverage'}
REPOSITORIES = {'repo-a', 'repo-b', 'repo-c'}
ACCEPTANCE_VERSION = 6
CAPTURE_KIND = 'swing-root-pane-print-all'
PROJECTION_PROOFS = 32
SAVED_PROJECTION_PROOFS = 5
ERROR_UI_PROOFS = 4


def assert_desktop_source(expected=None):
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True))
    if dirty or expected is not None and (expected.get('dirty') is not False or expected.get('testedCommit') != commit):
        raise ValueError('Desktop/IDE evidence requires the same clean Desktop commit; commit the candidate before running this suite.')
    return commit


def read_message(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as stream:
        metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 65536:
            raise ValueError('Unsafe or oversized local protocol message')
        return json.load(stream)


def publish(path, value):
    temporary = path.with_suffix('.tmp')
    with temporary.open('x') as stream:
        json.dump(value, stream)
        stream.write('\n')
    try:
        os.link(temporary, path)  # Immutable messages: never replace another step.
    finally:
        temporary.unlink()


def initialize_link(run_root):
    directory = run_root / 'desktop-link'
    directory.mkdir(mode=0o700)
    session = {'schemaVersion': 1, 'purpose': 'reqws-desktop-ide-link', 'sessionId': str(uuid.uuid4())}
    publish(directory / 'session.json', session)
    return session['sessionId']


def validate_transcript(run_root, session_id):
    directory = run_root / 'desktop-link'
    requests = list(directory.glob('request-*.json'))
    responses = list(directory.glob('response-*.json'))
    if not requests or len(requests) != len(responses):
        raise ValueError('Incomplete Desktop/Driver exchange')
    created = {}
    selections = []
    coverage = []
    external_focus = None
    for sequence in range(1, len(requests) + 1):
        request = read_message(directory / f'request-{sequence}.json')
        response = read_message(directory / f'response-{sequence}.json')
        for message in (request, response):
            if (message.get('schemaVersion') != 1 or message.get('sessionId') != session_id
                    or type(message.get('sequence')) is not int or message['sequence'] != sequence):
                raise ValueError('Stale or reordered Desktop/Driver exchange')
        if response.get('status') != 'passed':
            raise ValueError('A Desktop operation failed')
        operation = request.get('operation')
        if operation == 'focus-external-edit':
            before = created.get('selection')
            if (external_focus is not None or before is None or before['revision'] != 1
                    or read_message(directory / f'request-{sequence - 1}.json').get('operation') != 'create'
                    or read_message(directory / f'request-{sequence - 1}.json').get('name') != 'selection'):
                raise ValueError('External edit may focus Desktop only once immediately after selection creation')
            external_focus = validate_focus_response(request, response, before)
            continue
        if operation == 'finish':
            if sequence != len(requests) or set(created) != NAMES:
                raise ValueError('Desktop session finished before all scenarios')
            continue
        name = request.get('name')
        if name not in NAMES or operation not in {'create', 'select'}:
            raise ValueError('Unknown Desktop operation')
        snapshot = response['snapshot']
        repositories = snapshot.get('repositories')
        if (not isinstance(repositories, list) or len(repositories) != 3
                or any(not isinstance(repo, dict) or set(repo) != {'name', 'id'}
                       or not isinstance(repo['id'], str) or not repo['id'] for repo in repositories)
                or {repo['name'] for repo in repositories} != REPOSITORIES
                or len({repo['id'] for repo in repositories}) != 3):
            raise ValueError('Desktop must create three distinct real repository members')
        root = Path(snapshot['root'])
        if (not root.is_absolute() or root.resolve() != root or not root.is_relative_to(run_root)
                or root.parent.name != 'workspaces' or root.parent.parent.parent != run_root
                or not root.parent.parent.name.startswith('reqws-e2e-')
                or snapshot['shell'] != str(root / '.reqws/ide/goland') or snapshot['name'] != name):
            raise ValueError('Desktop workspace escaped its private run')
        expected_launch = {'command': '/usr/bin/open', 'args': ['-a', str(root.parent.parent / 'home/Applications/GoLand.app'), snapshot['shell']],
                           'shell': False, 'boundary': 'os-spawn-only'}
        if snapshot.get('editorLaunch') != expected_launch or snapshot['editorLaunch'].get('shell') is not False:
            raise ValueError('The Starter entry lacks the real Desktop Save-and-open OS target')
        expected = ['repo-a', 'repo-b'] if operation == 'create' else request.get('selected')
        if (not isinstance(expected, list) or len(set(expected)) != len(expected)
                or not set(expected) <= {'repo-a', 'repo-b'} or snapshot['selected'] != expected
                or type(snapshot.get('revision')) is not int):
            raise ValueError('Desktop saved a different selection')
        if operation == 'create':
            if name in created or snapshot['revision'] != 1:
                raise ValueError('Reused workspace or invalid first revision')
            created[name] = snapshot
        else:
            if name == 'selection' and external_focus is None:
                raise ValueError('Selection changed before the required external-edit focus transition')
            before = created[name]
            if (any(snapshot.get(key) != before.get(key) for key in
                    ('root', 'shell', 'workspaceId', 'bindingId', 'repositories', 'editorLaunch'))
                    or snapshot['revision'] != before['revision'] + 1):
                raise ValueError('Desktop identity/revision changed across a selection')
            created[name] = snapshot
        if name == 'selection':
            selections.append(expected)
        if name == 'coverage':
            coverage.append(expected)
    if external_focus is None or len(requests) != 16 or request.get('operation') != 'finish' or selections != [
            ['repo-a', 'repo-b'], ['repo-a'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']]:
        raise ValueError('Missing live 2→1→0→2 or empty/nonempty cold-process selections')
    if coverage != [['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']]:
        raise ValueError('Missing unselected user-root coverage across empty and restored selections')
    if len({value['bindingId'] for value in created.values()}) != len(NAMES):
        raise ValueError('Scenario workspaces must have independent Desktop bindings')
    return {'requests': len(requests), 'workspaces': len(created), 'selections': selections, 'coverageSelections': coverage,
            'editorLaunches': len(created), 'editorLaunchBoundary': 'os-spawn-only', 'externalEditFocuses': 1}


def validate_focus_response(request, response, snapshot):
    envelope = {key: request.get(key) for key in ('schemaVersion', 'sessionId', 'sequence')}
    if (envelope['schemaVersion'] != 1 or type(envelope['schemaVersion']) is not int
            or type(envelope['sequence']) is not int or envelope['sequence'] <= 1
            or not isinstance(envelope['sessionId'], str) or not envelope['sessionId']
            or type(response.get('schemaVersion')) is not int or type(response.get('sequence')) is not int
            or request != {**envelope, 'operation': 'focus-external-edit', 'name': 'selection', 'phase': 'late-files', 'revision': 1}
            or type(request.get('revision')) is not int or response.get('status') != 'passed'
            or any(response.get(key) != value for key, value in envelope.items())
            or set(response) != {*envelope, 'status', 'focus'}):
        raise ValueError('Invalid external-edit focus exchange')
    focus = response['focus']
    if (not isinstance(focus, dict)
            or set(focus) != {'name', 'phase', 'revision', 'workspaceId', 'bindingId', 'desktopPid', 'windowId', 'focused'}
            or any(focus.get(key) != value for key, value in {'name': 'selection', 'phase': 'late-files', 'revision': 1,
                'workspaceId': snapshot['workspaceId'], 'bindingId': snapshot['bindingId']}.items())
            or type(focus.get('revision')) is not int or focus.get('focused') is not True
            or any(type(focus.get(key)) is not int or focus[key] <= 0 for key in ('desktopPid', 'windowId'))):
        raise ValueError('Desktop did not prove the owned window was focused for external editing')
    return focus


def validate_projection_evidence(run_root, process_ids):
    filename = run_root / 'desktop-projections.jsonl'
    if filename.is_symlink():
        raise ValueError('Unsafe IDE projection evidence')
    proofs = [json.loads(line) for line in filename.read_text().splitlines()]
    snapshots = {}
    for path in (run_root / 'desktop-link').glob('response-*.json'):
        response = read_message(path)
        if snapshot := response.get('snapshot'):
            snapshots[snapshot['name'], snapshot['revision']] = snapshot
    ordinary = validate_ordinary_evidence(run_root)
    if len(proofs) != PROJECTION_PROOFS or {str(proof.get('pid')) for proof in proofs} | {str(ordinary['pid'])} != set(process_ids):
        raise ValueError('Missing per-step IDE model/Project-tree evidence')
    if ordinary['pid'] in {proof.get('pid') for proof in proofs}:
        raise ValueError('Unbound shell must use its own independent IDE process')
    screenshots = [proof.get('screenshot') for proof in proofs] + [ordinary.get('screenshot')] + validate_error_ui_evidence(run_root, proofs, snapshots)
    validate_external_edit_evidence(run_root, proofs, snapshots)
    validate_project_reopen_evidence(run_root, proofs, snapshots)
    if any(not isinstance(path, str) for path in screenshots) or len(set(screenshots)) != len(screenshots):
        raise ValueError('Every IDE observation requires its own fresh screenshot')
    for name in NAMES:
        steps = [proof for proof in proofs if proof.get('scenario') == name]
        expected = (['projection', 'excluded-off', 'excluded-on', 'late-files-on', 'late-files-off', 'excluded-restored'] +
                    ['projection'] * 4 + ['cold-empty', 'user-root-selected', 'user-root-empty', 'reopened-empty', 'post-clear-cold-empty', 'projection', 'cold-selected']
                    if name == 'selection' else ['safe-mode-blocked', 'projection'] if name == 'trust'
                    else ['projection'] * 3 if name == 'coverage'
                    else ['projection', 'malformed', 'projection', 'mismatched', 'projection'])
        if [step.get('phase') for step in steps] != expected:
            raise ValueError('A required IDE phase is missing or reordered')
        if name == 'selection':
            if [step.get('revision') for step in steps] != [1] * 6 + [2, 3, 4, 5, 5, 6, 7, 7, 7, 8, 8]:
                raise ValueError('Missing actual empty/nonempty cold-process observations')
            pids = [step['pid'] for step in steps]
            if (len(set(pids)) != 4 or len(set(pids[:10])) != 1 or len(set(pids[10:14])) != 1
                    or len(set(pids[14:16])) != 1):
                raise ValueError('Watcher/cold observations came from the wrong IDE processes')
            if (steps[1].get('showExcludedFiles') is not False or steps[2].get('showExcludedFiles') is not True
                    or steps[3].get('showExcludedFiles') is not True or steps[4].get('showExcludedFiles') is not False
                    or steps[5].get('showExcludedFiles') != steps[0].get('showExcludedFiles')):
                raise ValueError('Excluded Files did not exercise both states and restore the original setting')
        elif len({step.get('pid') for step in steps}) != 1:
            raise ValueError('A live IDE scenario restarted its process')
        if name == 'coverage' and [step.get('revision') for step in steps] != [1, 2, 3]:
            raise ValueError('User-root coverage did not survive actual selection changes')
        for index, step in enumerate(steps):
            snapshot = snapshots[name, step['revision']]
            if any(step.get(key) != snapshot.get(key) for key in ('workspaceId', 'bindingId', 'selected')):
                raise ValueError('IDE proof does not match the Desktop binding/selection')
            root = Path(snapshot['root'])
            roots = step.get('roots')
            modules = step.get('modules')
            pfi = step.get('pfi')
            tree = step.get('tree')
            if not isinstance(roots, list) or not isinstance(modules, dict) or not isinstance(pfi, dict) or not isinstance(tree, list):
                raise ValueError('IDE proof is missing actual roots/modules/PFI/tree')
            if (type(step.get('showExcludedFiles')) is not bool
                    or step.get('managedUserRoot') is not (name == 'selection' and index >= 10)
                    or step.get('lateFiles') is not (name == 'selection' and index >= 3)):
                raise ValueError('The user-root/late-file/Project-view observation is incomplete')
            validate_vcs_observation(step.get('vcsMappings'))
            validate_screenshot(run_root, step.get('screenshot'), snapshot['shell'], step['pid'])
            if step['phase'] == 'safe-mode-blocked':
                if (step.get('trusted') is not False or step.get('lifecycle') != 'SAFE_MODE_BLOCKED'
                        or step.get('lastAppliedDigest') is not None or step.get('validatedProjectionDigest') is not None
                        or any(module.lower().startswith('reqws-') for module in modules)
                        or any(str(root / repo) in roots or pfi.get(str(root / repo), {}).get('inContent') is not False
                               for repo in REPOSITORIES)):
                    raise ValueError('Safe Mode evidence bypassed real trust')
                continue
            if step['phase'] in {'malformed', 'mismatched'}:
                expected_error = 'MANIFEST_INVALID_JSON' if name == 'invalid-manifest' and step['phase'] == 'malformed' else 'BINDING_ERROR'
                shell = snapshot['shell']
                protected_pfi = {path: value for path, value in pfi.items() if path != shell and not path.startswith(shell + '/')}
                previous_pfi = {path: value for path, value in steps[index - 1]['pfi'].items() if path != shell and not path.startswith(shell + '/')}
                if step.get('lifecycle') != 'ERROR' or step.get('error') != expected_error or any(
                        step[key] != steps[index - 1][key] for key in ('roots', 'modules')) or protected_pfi != previous_pfi:
                    raise ValueError('Invalid input did not preserve the prior model')
                if (any(pfi.get(path) != {'inContent': True, 'excluded': False} for path in [shell, shell + '/shell-probe.txt']) or not any(
                        part.split(' [', 1)[0].split(' /', 1)[0] == 'goland' for entry in tree for part in entry)):
                    raise ValueError('Invalid input did not revoke the transient shell hiding capability')
                continue
            loaded = {repo['id'] for repo in snapshot['repositories'] if repo['name'] in snapshot['selected']}
            if (step.get('trusted') is not True or step.get('lifecycle') not in {'SYNCHRONIZED', 'DEGRADED'}
                    or set(step.get('loadedIds', [])) != loaded
                    or not re.fullmatch('[a-f0-9]{64}', step.get('loadingDigest') or '')
                    or any(step.get(key) != step['loadingDigest'] for key in ('validatedProjectionDigest', 'lastAppliedDigest'))):
                raise ValueError('IDE did not confirm the current live projection')
            normalized = [[part.split(' [', 1)[0].split(' /', 1)[0] for part in entry] for entry in tree]
            for repo in REPOSITORIES:
                selected = repo in snapshot['selected'] or name == 'coverage' and repo == 'repo-c'
                if ((str(root / repo) in roots) != selected
                        or pfi.get(str(root / repo), {}).get('inContent') is not selected
                        or pfi.get(str(root / repo / 'docs/probe.txt'), {}).get('inContent') is not selected
                        or any(repo in entry for entry in normalized) != selected
                        or any(entry[-3:] == [repo, 'docs', 'probe.txt'] for entry in normalized) != selected):
                    raise ValueError('Actual Project tree or PFI differs from the saved selection')
            hidden = {'.reqws', 'reqws-project.json', 'goland', 'notes', 'outside.txt', 'shell-probe.txt', 'late-shell.txt'}
            if any(part in hidden for entry in normalized for part in entry):
                raise ValueError('The dedicated entry is visible in the normal Project tree')
            if (pfi.get(str(root / 'notes/outside.txt'), {}).get('inContent') is not False
                    or pfi.get(snapshot['shell']) != {'inContent': False, 'excluded': True}
                    or pfi.get(str(Path(snapshot['shell']) / 'shell-probe.txt')) != {'inContent': False, 'excluded': True}):
                raise ValueError('Real notes/shell files leaked into the project content')
            expected_user = sorted([str(root / 'user-content')] + ([str(root / 'repo-c')] if name == 'coverage' else []))
            if name != 'trust' and (modules.get('user') != expected_user
                    or not any('user-content' in entry and entry[-1] == 'keep.txt' for entry in normalized)):
                raise ValueError('User-owned model/tree content was not preserved')
            expected_managed = {str(root / repo) for repo in snapshot['selected']}
            if step['managedUserRoot']:
                expected_managed.add(str(root / 'user-extra'))
                if (pfi.get(str(root / 'user-extra/keep-extra.txt'), {}).get('inContent') is not True
                        or not any('user-extra' in entry and entry[-1] == 'keep-extra.txt' for entry in normalized)):
                    raise ValueError('The extra unclaimed root inside the managed module was lost')
            if set(modules.get('ReqWS-' + snapshot['bindingId'], [])) != expected_managed:
                raise ValueError('Managed module contains claimed or missing user roots')
            covered = [repo['id'] for repo in snapshot['repositories'] if name == 'coverage' and repo['name'] == 'repo-c']
            if step.get('userCoverage') != covered:
                raise ValueError('ReqWS did not explain unselected user-root coverage')
            if step['lateFiles']:
                expected_late = 'repo-a' in snapshot['selected']
                if (pfi.get(str(root / 'repo-a/docs/late-repo.txt'), {}).get('inContent') is not expected_late
                        or any(entry[-3:] == ['repo-a', 'docs', 'late-repo.txt'] for entry in normalized) != expected_late
                        or pfi.get(str(Path(snapshot['shell']) / 'late-shell.txt')) != {'inContent': False, 'excluded': True}):
                    raise ValueError('Late files were not observed through real VFS/tree boundaries')
        validate_saved_projection(run_root, snapshots[name, steps[-1]['revision']])
    return len(proofs)


def validate_external_edit_evidence(run_root, proofs, snapshots):
    session = read_message(run_root / 'desktop-link/session.json')['sessionId']
    value = read_message(run_root / 'desktop-external-edit.json')
    snapshot = snapshots['selection', 1]
    if (set(value) != {'schemaVersion', 'sessionId', 'scenario', 'phase', 'revision', 'workspaceId', 'bindingId',
                      'project', 'idePid', 'desktopPid', 'windowId', 'requestSequence', 'stages'}
            or type(value.get('schemaVersion')) is not int or value['schemaVersion'] != 1
            or type(value.get('revision')) is not int or value['revision'] != 1
            or value.get('sessionId') != session or value.get('scenario') != 'selection' or value.get('phase') != 'late-files-on'
            or any(value.get(key) != snapshot[key] for key in ('workspaceId', 'bindingId'))
            or value.get('project') != snapshot['shell']
            or any(type(value.get(key)) is not int or value[key] <= 0 for key in ('idePid', 'desktopPid', 'windowId', 'requestSequence'))
            or value['idePid'] == value['desktopPid']):
        raise ValueError('Missing bound external-edit focus evidence')
    sequence = value['requestSequence']
    request = read_message(run_root / f'desktop-link/request-{sequence}.json')
    response = read_message(run_root / f'desktop-link/response-{sequence}.json')
    focus_requests = [read_message(path) for path in (run_root / 'desktop-link').glob('request-*.json')
                      if read_message(path).get('operation') == 'focus-external-edit']
    previous = read_message(run_root / f'desktop-link/request-{sequence - 1}.json')
    if (focus_requests != [request] or request.get('sessionId') != session or request.get('sequence') != sequence
            or previous.get('operation') != 'create' or previous.get('name') != 'selection'):
        raise ValueError('External focus was not the single G3 action after workspace creation')
    focus = validate_focus_response(request, response, snapshot)
    if any(value[key] != focus[key] for key in ('desktopPid', 'windowId')):
        raise ValueError('External edit used a different Desktop process/window')
    selection = [proof for proof in proofs if proof.get('scenario') == 'selection']
    if (len(selection) < 4 or any(proof.get('pid') != value['idePid'] for proof in selection[:4])
            or selection[2].get('phase') != 'excluded-on' or selection[3].get('phase') != 'late-files-on'):
        raise ValueError('Focus transition does not surround the G3 file creation in the same IDE')
    expected = [{'stage': stage, 'frameFocused': active, 'frameActive': active,
                 'repoDiskExists': files, 'shellDiskExists': files} for stage, active, files in [
                    ('desktop-focused', False, False), ('files-created', False, True), ('ide-returned', True, True)]]
    if value.get('stages') != expected or any(type(item.get(key)) is not bool for item in value['stages']
            for key in ('frameFocused', 'frameActive', 'repoDiskExists', 'shellDiskExists')):
        raise ValueError('External files were not created while the IDE was inactive and observed after returning')


def validate_project_reopen_evidence(run_root, proofs, snapshots):
    value = read_message(run_root / 'desktop-project-reopen.json')
    session = read_message(run_root / 'desktop-link/session.json')['sessionId']
    snapshot = snapshots['selection', 7]
    selection = [proof for proof in proofs if proof.get('scenario') == 'selection']
    if (set(value) != {'schemaVersion', 'sessionId', 'scenario', 'phase', 'revision', 'workspaceId', 'bindingId',
                      'project', 'idePid', 'profileConfig', 'previousScreenshot', 'stages'}
            or type(value.get('schemaVersion')) is not int or value['schemaVersion'] != 1
            or type(value.get('revision')) is not int or value['revision'] != 7
            or type(value.get('idePid')) is not int or value['idePid'] <= 0
            or value.get('sessionId') != session or value.get('scenario') != 'selection'
            or value.get('phase') != 'reopened-empty' or value.get('project') != snapshot['shell']
            or any(value.get(key) != snapshot[key] for key in ('workspaceId', 'bindingId'))
            or len(selection) != 17 or selection[12].get('phase') != 'user-root-empty'
            or selection[13].get('phase') != 'reopened-empty'
            or any(proof.get('pid') != value['idePid'] or proof.get('revision') != 7 for proof in selection[12:14])
            or value.get('previousScreenshot') != selection[12].get('screenshot')):
        raise ValueError('Missing bound same-process project close/reopen evidence')
    config = value.get('profileConfig')
    if (not isinstance(config, str) or not Path(config).is_absolute() or Path(config).name != 'config'
            or not Path(config).is_dir() or Path(config).resolve() != Path(config)
            or Path(config).is_relative_to(run_root) or run_root.is_relative_to(Path(config).parent)):
        raise ValueError('Project reopen did not identify the dedicated profile config')
    # report.json is written with profileId before launching either child and is
    # retained for later verify-report. Never derive profile identity from the
    # reopen record itself, or a coordinated config/welcome substitution passes.
    report = read_message(run_root / 'report.json')
    marker = read_message(Path(config).parent / '.reqws-ide-profile.json')
    policy = read_policy()
    ide = {'product': 'GO', 'version': policy['uiTestIdeVersion'], 'build': policy['uiTestIdeBuild']}
    if (not isinstance(report.get('profileId'), str) or not report['profileId']
            or marker.get('id') != report['profileId'] or report.get('ide') != ide
            or type(marker.get('schemaVersion')) is not int or marker['schemaVersion'] != 1
            or marker.get('purpose') != 'reqws-local-ide-authorization'
            or any(marker.get(key) != expected for key, expected in ide.items())):
        raise ValueError('Project reopen profile does not match this run and the fixed IDE')
    welcome = str(Path(config) / 'projects/GoLandWorkspace')
    stages = value.get('stages')
    if (not isinstance(stages, list) or len(stages) != 3
            or any(not isinstance(stage, dict) or type(stage.get('pid')) is not int
                   or stage['pid'] != value['idePid'] for stage in stages)):
        raise ValueError('Project close/reopen must preserve the actual IDE process')
    closed, selected, reopened = stages
    if (set(closed) != {'stage', 'pid', 'originalOpen', 'openProjects'} or closed.get('stage') != 'closed'
            or closed.get('originalOpen') is not False or closed.get('openProjects') not in ([], [welcome])
            or reopened != {'stage': 'reopened', 'pid': value['idePid'], 'originalOpen': False,
                            'projectOpen': True, 'projectInitialized': True, 'openProjects': [snapshot['shell']]}
            or any(type(reopened.get(key)) is not bool for key in ('originalOpen', 'projectOpen', 'projectInitialized'))):
        raise ValueError('The original project did not close and the exact fixture did not reopen')
    if (set(selected) != {'stage', 'pid', 'welcomeProject', 'frameTitle', 'tree', 'selectedRow', 'selectedPath'}
            or selected.get('stage') != 'recent-project-selected' or selected.get('welcomeProject') != welcome
            or selected.get('frameTitle') != 'GoLandWorkspace – Welcome to GoLand'
            or type(selected.get('selectedRow')) is not int or selected['selectedRow'] < 0):
        raise ValueError('Project reopen lacks the actual welcome frame and selected row')
    tree = selected.get('tree')
    if (not isinstance(tree, list) or not tree or any(not isinstance(entry, dict) or set(entry) != {'row', 'path'}
            or type(entry.get('row')) is not int or entry['row'] < 0 or not isinstance(entry.get('path'), list)
            or not entry['path'] or any(not isinstance(text, str) or not text for text in entry['path']) for entry in tree)
            or len({entry['row'] for entry in tree}) != len(tree)):
        raise ValueError('Project reopen lacks complete unambiguous Recent Projects rows')
    exact = re.compile(r'(^|\s)' + re.escape(snapshot['shell']) + r'(?=\s|$)')
    matches = [entry for entry in tree if exact.search(entry['path'][-1])]
    if len(matches) != 1 or matches[0] != {'row': selected['selectedRow'], 'path': selected.get('selectedPath')}:
        raise ValueError('The clicked Recent Projects row is not the unique canonical fixture path')


def validate_error_ui_evidence(run_root, projections, snapshots):
    path = run_root / 'desktop-error-ui.jsonl'
    if not path.is_file() or path.is_symlink() or path.resolve() != path or path.stat().st_size > 64 * 1024:
        raise ValueError('User-visible ReqWS error evidence is missing or unsafe')
    observations = [json.loads(line) for line in path.read_text().splitlines()]
    expected = {(proof['scenario'], proof['phase']): proof for proof in projections
                if proof.get('scenario') in {'invalid-binding', 'invalid-manifest'} and proof.get('phase') in {'malformed', 'mismatched'}}
    if len(observations) != ERROR_UI_PROOFS or {(item.get('scenario'), item.get('phase')) for item in observations} != set(expected):
        raise ValueError('A required user-visible ReqWS error observation is missing or duplicated')
    screenshots = []
    for observation in observations:
        proof = expected[observation['scenario'], observation['phase']]
        code = 'MANIFEST_INVALID_JSON' if observation['scenario'] == 'invalid-manifest' and observation['phase'] == 'malformed' else 'BINDING_ERROR'
        status = observation.get('statusTexts')
        details = observation.get('detailTexts')
        if (observation.get('bindingId') != proof['bindingId'] or observation.get('revision') != proof['revision']
                or not isinstance(status, list) or 'Error' not in status
                or not isinstance(details, list) or not any(isinstance(text, str) and (text == code or text.startswith(code + ' · ')) for text in details)):
            raise ValueError('The ReqWS Tool Window did not visibly report Error and the stable code')
        snapshot = snapshots[proof['scenario'], proof['revision']]
        validate_screenshot(run_root, observation.get('screenshot'), snapshot['shell'], proof['pid'])
        screenshots.append(observation['screenshot'])
    return screenshots


def validate_vcs_observation(mappings):
    if (not isinstance(mappings, list) or any(not isinstance(entry, dict) or set(entry) != {'directory', 'vcs'}
            or any(not isinstance(value, str) for value in entry.values()) for entry in mappings)):
        raise ValueError('Read-only native VCS mapping observation is missing')


def validate_screenshot(run_root, filename, project, pid):
    if not isinstance(filename, str):
        raise ValueError('Native Project-tree screenshot is missing')
    path = Path(filename)
    if (not path.is_absolute() or not path.is_relative_to(run_root) or path.resolve() != path
            or not path.is_file() or path.is_symlink() or not 33 <= path.stat().st_size <= 50 * 1024 * 1024):
        raise ValueError('Native Project-tree screenshot is unsafe or missing')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 33 <= before.st_size <= 50 * 1024 * 1024:
            raise ValueError('Native Project-tree screenshot is unsafe or oversized')
        data = stream.read(50 * 1024 * 1024 + 1)
        after = os.fstat(stream.fileno())
    current = path.lstat()
    if (len(data) != before.st_size or (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns)
            or (current.st_dev, current.st_ino) != (before.st_dev, before.st_ino)):
        raise ValueError('Native Project-tree screenshot changed during verification')
    width, height = validate_png(data)
    metadata_path = path.with_name(path.name + '.json')
    if metadata_path.resolve() != metadata_path:
        raise ValueError('IDE component capture metadata escaped its private location')
    capture = read_message(metadata_path)
    project_path = Path(project)
    if (not isinstance(capture, dict)
            or set(capture) != {'schemaVersion', 'captureKind', 'project', 'pid', 'frameProject', 'frameTitle', 'width', 'height'}
            or type(capture.get('schemaVersion')) is not int or capture['schemaVersion'] != 1
            or capture.get('captureKind') != CAPTURE_KIND
            or type(pid) is not int or pid <= 0 or type(capture.get('pid')) is not int or capture['pid'] != pid
            or not project_path.is_absolute() or not project_path.is_relative_to(run_root)
            or not project_path.is_dir() or project_path.resolve() != project_path
            or capture.get('project') != project or capture.get('frameProject') != project
            or not isinstance(capture.get('frameTitle'), str) or not capture['frameTitle'].strip()
            or len(capture['frameTitle']) > 4096
            or type(capture.get('width')) is not int or type(capture.get('height')) is not int
            or (capture['width'], capture['height']) != (width, height)
            or width < 320 or height < 200 or width * height > 64 * 1024 * 1024):
        raise ValueError('Screenshot does not identify the actual test IDE Swing content, project and process')


def validate_png(data):
    """Check the complete bounded PNG and its single image-data zlib stream, without external decoders."""
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('Native Project-tree screenshot is not a PNG')
    position = 8
    header = palette = ended = False
    idat_started = idat_ended = False
    compressed = bytearray()
    rows = []
    expected_size = 0
    color = depth = 0
    while position < len(data):
        if len(data) - position < 12:
            raise ValueError('Truncated PNG chunk')
        size = struct.unpack_from('>I', data, position)[0]
        kind = data[position + 4:position + 8]
        if (size > len(data) - position - 12 or not re.fullmatch(b'[A-Za-z]{4}', kind)
                or kind[2] & 32):
            raise ValueError('Invalid PNG chunk boundary or type')
        payload = data[position + 8:position + 8 + size]
        crc = struct.unpack_from('>I', data, position + 8 + size)[0]
        if zlib.crc32(payload, zlib.crc32(kind)) != crc:
            raise ValueError('PNG chunk CRC mismatch')
        position += size + 12
        if not header and kind != b'IHDR':
            raise ValueError('PNG does not start with IHDR')
        if idat_started and kind != b'IDAT':
            idat_ended = True
        if kind == b'IHDR':
            if header or size != 13:
                raise ValueError('Duplicate or invalid PNG IHDR')
            width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', payload)
            depths = {0: {1, 2, 4, 8, 16}, 2: {8, 16}, 3: {1, 2, 4, 8}, 4: {8, 16}, 6: {8, 16}}
            if (not 0 < width <= 32768 or not 0 < height <= 32768 or depth not in depths.get(color, set())
                    or compression != 0 or filtering != 0 or interlace not in {0, 1}):
                raise ValueError('Invalid or oversized PNG image header')
            bits = depth * {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
            passes = [(0, 0, 1, 1)] if not interlace else [
                (0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4),
                (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)]
            for x, y, dx, dy in passes:
                columns = max(0, (width - x + dx - 1) // dx)
                count = max(0, (height - y + dy - 1) // dy)
                if columns and count:
                    stride = (columns * bits + 7) // 8 + 1
                    rows.append((stride, count))
                    expected_size += stride * count
            if expected_size > 256 * 1024 * 1024:
                raise ValueError('PNG decompressed image exceeds the screenshot limit')
            header = True
        elif kind == b'PLTE':
            if palette or idat_started or color in {0, 4} or not 0 < size <= 768 or size % 3:
                raise ValueError('Invalid PNG palette')
            if color == 3 and size // 3 > 1 << depth:
                raise ValueError('PNG palette exceeds its bit depth')
            palette = True
        elif kind == b'IDAT':
            if idat_ended or color == 3 and not palette:
                raise ValueError('PNG image chunks are reordered or lack a palette')
            idat_started = True
            compressed.extend(payload)
        elif kind == b'IEND':
            if size or not idat_started or position != len(data):
                raise ValueError('PNG IEND is missing image data or has trailing bytes')
            ended = True
            break
        elif not kind[0] & 32:
            raise ValueError('Unsupported critical PNG chunk')
    if not header or not idat_started or not ended:
        raise ValueError('Incomplete PNG image')
    try:
        decoder = zlib.decompressobj()
        pixels = decoder.decompress(compressed, expected_size + 1)
    except zlib.error as error:
        raise ValueError('Invalid PNG image-data stream') from error
    if (len(pixels) != expected_size or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail):
        raise ValueError('PNG image data is truncated, oversized or contains extra streams')
    offset = 0
    for stride, count in rows:
        if any(pixels[offset + row * stride] > 4 for row in range(count)):
            raise ValueError('Invalid PNG scanline filter')
        offset += stride * count
    return width, height


def validate_ordinary_evidence(run_root):
    value = read_message(run_root / 'desktop-ordinary.json')
    project = Path(value.get('project', ''))
    shell = project / '.reqws/ide/goland'
    if (not project.is_absolute() or not project.is_relative_to(run_root) or project.resolve() != project
            or not project.name.startswith('unbound-') or value.get('lifecycle') != 'INACTIVE'
            or value.get('inContent') is not True or type(value.get('pid')) is not int
            or not isinstance(value.get('tree'), list)
            or not any(entry[-4:] == ['.reqws', 'ide', 'goland', 'shell-probe.txt'] for entry in value['tree'])
            or shell.resolve() != shell or shell.joinpath('reqws-project.json').exists() or shell.joinpath('.idea/reqws-loaded-roots.json').exists()
            or project.joinpath('.reqws/workspace.json').exists()
            or not shell.joinpath('shell-probe.txt').is_file() or shell.joinpath('shell-probe.txt').is_symlink()
            or shell.joinpath('shell-probe.txt').read_text() != 'unbound same-name shell\n'):
        raise ValueError('Missing real unbound same-name shell visibility proof')
    validate_vcs_observation(value.get('vcsMappings'))
    validate_screenshot(run_root, value.get('screenshot'), str(project), value['pid'])
    return value


def native_directory_key(path):
    metadata = path.lstat()
    if not stat.S_ISDIR(metadata.st_mode) or path.resolve() != path:
        raise ValueError('Unsafe native model directory identity')
    # The local representative is macOS; Java's UnixFileKey uses hex device + decimal inode.
    return f'(dev={metadata.st_dev:x},ino={metadata.st_ino})'


def validate_saved_projection(run_root, snapshot):
    """A cache-backed cold process is not proof that native JPS roots were saved."""
    root = Path(snapshot['root'])
    shell = Path(snapshot['shell'])
    if (not root.is_absolute() or not root.is_relative_to(run_root) or root.resolve() != root
            or shell != root / '.reqws/ide/goland'):
        raise ValueError('Saved model belongs outside the private Desktop fixture')
    module_name = 'ReqWS-' + snapshot['bindingId']
    module_file = shell / '.idea/reqws' / (module_name + '.iml')

    def regular(path):
        if (not path.is_relative_to(run_root) or path.resolve() != path
                or not path.is_file() or path.is_symlink()):
            raise ValueError('Saved IDE model escaped its private fixture or is missing')
        return path

    def xml(path):
        descriptor = os.open(regular(path), os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, 'rb') as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise ValueError('Saved IDE model is not a regular file')
            data = stream.read(1048577)
        text = data.decode('utf-8')
        if len(data) > 1048576 or '\x00' in text or '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper():
            raise ValueError('Unsafe saved IDE XML')
        return ET.fromstring(text)

    def native_path(value, url=False, module_dir=module_file.parent):
        if not isinstance(value, str) or url and not value.startswith('file://'):
            raise ValueError('Missing native IDE file path')
        value = unquote(value[7:] if url else value)
        value = value.replace('$MODULE_DIR$', str(module_dir)).replace('$PROJECT_DIR$', str(shell))
        path = Path(os.path.normpath(value))
        if '$' in value or '\x00' in value or not path.is_absolute() or not path.is_relative_to(root):
            raise ValueError('Saved IDE path escaped the fixture')
        return path

    journal = read_message(regular(shell / '.idea/reqws-loaded-roots.json'))
    expected_identity = {'formatVersion': 1, 'workspaceId': snapshot['workspaceId'],
                         'bindingId': snapshot['bindingId'], 'workspaceRoot': str(root),
                         'shell': str(shell), 'moduleName': module_name, 'moduleFile': str(module_file),
                         'shellKey': native_directory_key(shell), 'ideaKey': native_directory_key(shell / '.idea')}
    if (set(journal) != set(expected_identity) | {'claims', 'pendingAdds', 'pendingRemoves'}
            or type(journal.get('formatVersion')) is not int
            or any(journal.get(key) != value for key, value in expected_identity.items())):
        raise ValueError('Saved ownership journal belongs to another Desktop binding')
    claims = journal.get('claims', [])
    expected = {repo['name']: repo['id'] for repo in snapshot['repositories'] if repo['name'] in snapshot['selected']}
    claim_fields = {'relativePath', 'repositoryId', 'nonce', 'rootKey', 'gitKey'}
    for key in ['claims', 'pendingAdds', 'pendingRemoves']:
        entries = journal[key]
        if (not isinstance(entries, list) or len(entries) > len(snapshot['repositories'])
                or any(not isinstance(claim, dict) or set(claim) != claim_fields
                       or any(not isinstance(value, str) or not 0 < len(value) <= 16384 for value in claim.values())
                       or not re.fullmatch('[a-f0-9]{32}', claim['nonce']) for claim in entries)):
            raise ValueError('Invalid saved ownership claim schema')
        if len({claim['relativePath'] for claim in entries}) != len(entries) or len({claim['nonce'] for claim in entries}) != len(entries):
            raise ValueError('Duplicate saved ownership claim')
    if (len(claims) != len(expected) or {claim['relativePath']: claim['repositoryId'] for claim in claims} != expected
            or journal['pendingRemoves'] or any(claim not in claims for claim in journal['pendingAdds'])):
        raise ValueError('Saved ownership claims differ from the final Desktop selection')
    for claim in claims:
        directory = root / claim['relativePath']
        if claim['rootKey'] != native_directory_key(directory) or claim['gitKey'] != native_directory_key(directory / '.git'):
            raise ValueError('Saved ownership directory identity changed')
        namespace = directory / '.reqws-goland-ownership'
        if namespace.exists() or namespace.is_symlink():
            raise ValueError('The virtual ownership namespace exists on disk')
    modules = xml(shell / '.idea/modules.xml').findall('./component[@name="ProjectModuleManager"]/modules/module')
    registrations = [(native_path(module.get('filepath')), native_path(module.get('fileurl'), url=True)) for module in modules]
    if ([entry for entry in registrations if module_file in entry] != [(module_file, module_file)]):
        raise ValueError('The managed module was not registered in native project storage')
    if snapshot['name'] != 'trust':
        user_file = shell / '.idea/user.iml'
        if [entry for entry in registrations if user_file in entry] != [(user_file, user_file)]:
            raise ValueError('The user module registration was not preserved on disk')
        user_roots = xml(user_file).findall('./component[@name="NewModuleRootManager"]/content')
        expected_user = [root / 'user-content'] + ([root / 'repo-c'] if snapshot['name'] == 'coverage' else [])
        if set(native_path(content.get('url'), url=True, module_dir=user_file.parent) for content in user_roots) != set(expected_user) or len(user_roots) != len(expected_user):
            raise ValueError('The user content root was not preserved on disk')
    contents = xml(module_file).findall('./component[@name="NewModuleRootManager"]/content')
    paths = [native_path(content.get('url'), url=True) for content in contents]
    expected_paths = {root / name for name in expected} | ({root / 'user-extra'} if snapshot['name'] == 'selection' else set())
    if len(paths) != len(expected_paths) or set(paths) != expected_paths:
        raise ValueError('Native .iml roots were not saved; IDE cache alone cannot certify cold recovery')
    if snapshot['name'] == 'selection' and list(contents[paths.index(root / 'user-extra')]):
        raise ValueError('The unclaimed user root acquired ownership metadata')
    for claim in claims:
        content = contents[paths.index(root / claim['relativePath'])]
        markers = [native_path(child.get('url'), url=True) for child in content.findall('excludeFolder')]
        if markers != [root / claim['relativePath'] / '.reqws-goland-ownership' / claim['nonce']]:
            raise ValueError('Native .iml ownership marker does not match the saved journal')
    for repo in REPOSITORIES:
        for suffix, content in [('docs/probe.txt', 'ordinary text fixture\n'), ('README.txt', f'ReqWS Git fixture: {repo}\n')]:
            if regular(root / repo / suffix).read_text() != content:
                raise ValueError('A real repository fixture file was not preserved')
    for relative, content in [('notes/outside.txt', 'outside all project roots\n'),
                              ('user-content/keep.txt', 'user owned\n'),
                              ('user-extra/keep-extra.txt', 'unclaimed root in the managed module\n'),
                              ('.reqws/ide/goland/shell-probe.txt', 'dedicated shell stays hidden\n')]:
        if regular(root / relative).read_text() != content:
            raise ValueError('An ordinary outside/user/shell fixture file was not preserved')
    if snapshot['name'] == 'selection':
        for relative, content in [('repo-a/docs/late-repo.txt', 'late repository file\n'),
                                  ('.reqws/ide/goland/late-shell.txt', 'late shell remains hidden\n')]:
            if regular(root / relative).read_text() != content:
                raise ValueError('Late files were removed instead of proving the native visibility boundary')


def stop_child(process):
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)
    except ProcessLookupError:
        pass


def publish_once_or_verify(path, value):
    """A coordinator may repeat an identical terminal marker, never replace it."""
    try:
        publish(path, value)
    except FileExistsError:
        existing = read_message(path)
        if (existing != value or any(type(existing[key]) is not type(expected) for key, expected in value.items())):
            raise ValueError('Existing terminal marker belongs to a different session or status')


def close_linked_session(run_root, session_id, registry, children, passed):
    failures = []
    if not passed:
        try:
            publish_once_or_verify(run_root / 'desktop-link/abort.json',
                                   {'schemaVersion': 1, 'sessionId': session_id, 'status': 'failed'})
        except Exception as error:
            failures.append('Abort publication failed: ' + str(error))
    # Even an invalid/unwritable abort marker must not skip owned child cleanup.
    children_stopped = True
    for process in children:
        try:
            if process is not None and process.poll() is None:
                try: process.wait(timeout=25)
                except subprocess.TimeoutExpired: stop_child(process)
            if process is not None and process.poll() is None:
                raise ValueError('An owned child remains alive')
        except Exception as error:
            children_stopped = False
            failures.append('Child cleanup failed: ' + str(error))
    registry_checked = False
    try:
        killed = cleanup_owned_processes(registry)
        registry_checked = True
        if passed and killed:
            failures.append('Desktop left a live owned process after completion')
    except Exception as error:
        failures.append('Registry cleanup failed: ' + str(error))
    if children_stopped and registry_checked:
        try:
            publish_once_or_verify(run_root / 'desktop-session-closed.json',
                {'schemaVersion': 1, 'sessionId': session_id, 'processesStopped': True, 'ownedRegistryChecked': True})
        except Exception as error:
            failures.append('Session closure publication failed: ' + str(error))
    if failures:
        raise ValueError('Linked session finalization failed: ' + '; '.join(failures))


def execute_linked(command, run_root, timeout, environment):
    directory = run_root / 'desktop-link'
    session_id = read_message(directory / 'session.json')['sessionId']
    registry = run_root / 'desktop-processes.jsonl'
    registry.touch(mode=0o600, exist_ok=False)
    env = {**environment, 'REQWS_DESKTOP_IDE_SESSION': session_id, 'REQWS_E2E_PROCESS_REGISTRY': str(registry)}
    invocation = ['node', str(ROOT / 'node_modules/@playwright/test/cli.js'), 'test', '--config', 'playwright.local-ide.config.ts']
    source = identity('source', invocation)
    source.update(scope='local-desktop-ide', sessionId=session_id)
    publish(run_root / 'desktop-source.json', source)
    desktop = host = None
    passed = False
    with (run_root / 'desktop.log').open('w') as desktop_log, (run_root / 'host.log').open('w') as host_log:
        try:
            assert_desktop_source(source)
            desktop = subprocess.Popen(invocation, cwd=ROOT, env=env, stdout=desktop_log,
                                       stderr=subprocess.STDOUT, start_new_session=True, shell=False)
            deadline = time.monotonic() + timeout
            ready_deadline = time.monotonic() + 120
            while not (directory / 'ready.json').exists():
                if desktop.poll() is not None or time.monotonic() >= ready_deadline:
                    raise ValueError('Desktop did not establish its isolated UI session; see desktop.log')
                time.sleep(0.1)
            ready = read_message(directory / 'ready.json')
            if ready != {'schemaVersion': 1, 'sessionId': session_id}:
                raise ValueError('Desktop readiness belongs to another session')
            host = subprocess.Popen(command, cwd=ROOT, env=env, stdout=host_log,
                                    stderr=subprocess.STDOUT, start_new_session=True, shell=False)
            while host.poll() is None:
                if desktop.poll() is not None:
                    raise ValueError('Desktop exited before the IDE suite completed')
                if time.monotonic() >= deadline:
                    raise subprocess.TimeoutExpired(command, timeout)
                time.sleep(0.25)
            if host.returncode != 0:
                raise ValueError('The IDE host or its strict report gate failed; see host.log')
            sequence = len(list(directory.glob('request-*.json'))) + 1
            publish(directory / f'request-{sequence}.json', {'schemaVersion': 1, 'sessionId': session_id,
                    'sequence': sequence, 'operation': 'finish'})
            code = desktop.wait(timeout=60)
            report = read_json(run_root / 'desktop-report.json')
            tests = validate_report(report, code, 'source', expected_config='playwright.local-ide.config.ts')
            cases = list(all_tests(report['suites']))
            if tests['executed'] != 1 or cases[0][0].get('title') != DESKTOP_TITLE:
                raise ValueError('The complete linked Desktop scenario did not execute')
            exchange = validate_transcript(run_root, session_id)
            if cleanup_owned_processes(registry):
                raise ValueError('Desktop left a live owned process after completion')
            assert_desktop_source(source)
            passed = True
            return {'identity': source, 'tests': tests, 'exchange': exchange, 'exitCode': code}
        finally:
            close_linked_session(run_root, session_id, registry, (host, desktop), passed)
