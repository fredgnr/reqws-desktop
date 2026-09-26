"""Private Desktop/Driver coordination, invoked only inside the local profile lock."""

import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import time
import uuid
from urllib.parse import unquote
import xml.etree.ElementTree as ET

from desktop_e2e import ROOT, all_tests, cleanup_owned_processes, identity, read_json, validate_report

DESKTOP_TITLE = 'S4 Desktop UI drives the local IDE through an isolated session'
NAMES = {'selection', 'trust', 'invalid-binding', 'invalid-manifest'}


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
        if operation == 'finish':
            if sequence != len(requests) or set(created) != NAMES:
                raise ValueError('Desktop session finished before all scenarios')
            continue
        name = request.get('name')
        if name not in NAMES or operation not in {'create', 'select'}:
            raise ValueError('Unknown Desktop operation')
        snapshot = response['snapshot']
        root = Path(snapshot['root'])
        if (not root.is_absolute() or root.resolve() != root or not root.is_relative_to(run_root)
                or snapshot['shell'] != str(root / '.reqws/ide/goland') or snapshot['name'] != name):
            raise ValueError('Desktop workspace escaped its private run')
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
            before = created[name]
            if (any(snapshot.get(key) != before.get(key) for key in
                    ('root', 'shell', 'workspaceId', 'bindingId', 'repositories'))
                    or snapshot['revision'] != before['revision'] + 1):
                raise ValueError('Desktop identity/revision changed across a selection')
            created[name] = snapshot
        if name == 'selection':
            selections.append(expected)
    if request.get('operation') != 'finish' or selections != [
            ['repo-a', 'repo-b'], ['repo-a'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']]:
        raise ValueError('Missing live 2→1→0→2 or empty/nonempty cold-process selections')
    if len({value['bindingId'] for value in created.values()}) != 4:
        raise ValueError('Scenario workspaces must have independent Desktop bindings')
    return {'requests': len(requests), 'workspaces': len(created), 'selections': selections}


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
    if len(proofs) != 20 or {str(proof.get('pid')) for proof in proofs} != set(process_ids):
        raise ValueError('Missing per-step IDE model/Project-tree evidence')
    for name in NAMES:
        steps = [proof for proof in proofs if proof.get('scenario') == name]
        expected = (['projection'] * 8 if name == 'selection' else ['safe-mode-blocked', 'projection']
                    if name == 'trust' else ['projection', 'malformed', 'projection', 'mismatched', 'projection'])
        if [step.get('phase') for step in steps] != expected:
            raise ValueError('A required IDE phase is missing or reordered')
        if name == 'selection':
            if [step.get('revision') for step in steps] != [1, 2, 3, 4, 5, 5, 6, 6]:
                raise ValueError('Missing actual empty/nonempty cold-process observations')
            pids = [step['pid'] for step in steps]
            if len(set(pids)) != 3 or len(set(pids[:5])) != 1 or pids[5] != pids[6]:
                raise ValueError('Watcher/cold observations came from the wrong IDE processes')
        elif len({step.get('pid') for step in steps}) != 1:
            raise ValueError('A live IDE scenario restarted its process')
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
            if step['phase'] == 'safe-mode-blocked':
                if (step.get('trusted') is not False or step.get('lifecycle') != 'SAFE_MODE_BLOCKED'
                        or step.get('lastAppliedDigest') is not None or step.get('validatedProjectionDigest') is not None
                        or any(module.lower().startswith('reqws-') for module in modules)):
                    raise ValueError('Safe Mode evidence bypassed real trust')
                continue
            if step['phase'] in {'malformed', 'mismatched'}:
                expected_error = 'MANIFEST_INVALID_JSON' if name == 'invalid-manifest' and step['phase'] == 'malformed' else 'BINDING_ERROR'
                shell = snapshot['shell']
                protected_pfi = {path: value for path, value in pfi.items() if path != shell}
                previous_pfi = {path: value for path, value in steps[index - 1]['pfi'].items() if path != shell}
                if step.get('lifecycle') != 'ERROR' or step.get('error') != expected_error or any(
                        step[key] != steps[index - 1][key] for key in ('roots', 'modules')) or protected_pfi != previous_pfi:
                    raise ValueError('Invalid input did not preserve the prior model')
                if pfi.get(shell) != {'inContent': True, 'excluded': False} or not any(
                        part.split(' [', 1)[0].split(' /', 1)[0] == 'goland' for entry in tree for part in entry):
                    raise ValueError('Invalid input did not revoke the transient shell hiding capability')
                continue
            loaded = {repo['id'] for repo in snapshot['repositories'] if repo['name'] in snapshot['selected']}
            if (step.get('trusted') is not True or step.get('lifecycle') not in {'SYNCHRONIZED', 'DEGRADED'}
                    or set(step.get('loadedIds', [])) != loaded
                    or not re.fullmatch('[a-f0-9]{64}', step.get('loadingDigest') or '')
                    or any(step.get(key) != step['loadingDigest'] for key in ('validatedProjectionDigest', 'lastAppliedDigest'))):
                raise ValueError('IDE did not confirm the current live projection')
            normalized = [[part.split(' [', 1)[0].split(' /', 1)[0] for part in entry] for entry in tree]
            for repo in ['repo-a', 'repo-b']:
                selected = repo in snapshot['selected']
                if ((str(root / repo) in roots) != selected
                        or pfi.get(str(root / repo / 'docs/probe.txt'), {}).get('inContent') is not selected
                        or any(entry[-3:] == [repo, 'docs', 'probe.txt'] for entry in normalized) != selected):
                    raise ValueError('Actual Project tree or PFI differs from the saved selection')
            if any(part in {'.reqws', 'reqws-project.json', 'goland'} for entry in normalized for part in entry):
                raise ValueError('The dedicated entry is visible in the normal Project tree')
            if name != 'trust' and (modules.get('user') != [str(root / 'user-content')]
                    or not any('user-content' in entry and entry[-1] == 'keep.txt' for entry in normalized)):
                raise ValueError('User-owned model/tree content was not preserved')
        validate_saved_projection(run_root, snapshots[name, steps[-1]['revision']])
    return len(proofs)


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
        if [native_path(content.get('url'), url=True, module_dir=user_file.parent) for content in user_roots] != [root / 'user-content']:
            raise ValueError('The user content root was not preserved on disk')
    contents = xml(module_file).findall('./component[@name="NewModuleRootManager"]/content')
    paths = [native_path(content.get('url'), url=True) for content in contents]
    if len(paths) != len(expected) or set(paths) != {root / name for name in expected}:
        raise ValueError('Native .iml roots were not saved; IDE cache alone cannot certify cold recovery')
    for claim in claims:
        content = contents[paths.index(root / claim['relativePath'])]
        markers = [native_path(child.get('url'), url=True) for child in content.findall('excludeFolder')]
        if markers != [root / claim['relativePath'] / '.reqws-goland-ownership' / claim['nonce']]:
            raise ValueError('Native .iml ownership marker does not match the saved journal')


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
            if not passed:
                publish(directory / 'abort.json', {'schemaVersion': 1, 'sessionId': session_id, 'status': 'failed'})
                # Let the Driver and Playwright unwind their own handles first.
                failures = []
                for process in (host, desktop):
                    if process is not None and process.poll() is None:
                        try:
                            try: process.wait(timeout=25)
                            except subprocess.TimeoutExpired: stop_child(process)
                        except (OSError, subprocess.SubprocessError) as error:
                            failures.append(str(error))
                try:
                    if registry.stat().st_size: cleanup_owned_processes(registry)
                except (OSError, ValueError, subprocess.SubprocessError) as error:
                    failures.append(str(error))
                if failures:
                    raise ValueError('Linked process cleanup is unconfirmed: ' + '; '.join(failures))
            if all(process is None or process.poll() is not None for process in (desktop, host)):
                publish(run_root / 'desktop-session-closed.json', {'schemaVersion': 1, 'sessionId': session_id,
                        'processesStopped': True, 'ownedRegistryChecked': True})
