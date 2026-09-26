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

DESKTOP_TITLE = 'S4 Desktop UI drives the local IDE through an isolated session'
NAMES = {'selection', 'trust', 'invalid-binding', 'invalid-manifest', 'coverage'}
REPOSITORIES = {'repo-a', 'repo-b', 'repo-c'}
ACCEPTANCE_VERSION = 2
PROJECTION_PROOFS = 32
SAVED_PROJECTION_PROOFS = 5


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
        repositories = snapshot.get('repositories')
        if (not isinstance(repositories, list) or len(repositories) != 3
                or any(not isinstance(repo, dict) or set(repo) != {'name', 'id'}
                       or not isinstance(repo['id'], str) or not repo['id'] for repo in repositories)
                or {repo['name'] for repo in repositories} != REPOSITORIES
                or len({repo['id'] for repo in repositories}) != 3):
            raise ValueError('Desktop must create three distinct real repository members')
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
        if name == 'coverage':
            coverage.append(expected)
    if request.get('operation') != 'finish' or selections != [
            ['repo-a', 'repo-b'], ['repo-a'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']]:
        raise ValueError('Missing live 2→1→0→2 or empty/nonempty cold-process selections')
    if coverage != [['repo-a', 'repo-b'], [], ['repo-a', 'repo-b']]:
        raise ValueError('Missing unselected user-root coverage across empty and restored selections')
    if len({value['bindingId'] for value in created.values()}) != len(NAMES):
        raise ValueError('Scenario workspaces must have independent Desktop bindings')
    return {'requests': len(requests), 'workspaces': len(created), 'selections': selections, 'coverageSelections': coverage}


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
    screenshots = [proof.get('screenshot') for proof in proofs] + [ordinary.get('screenshot')]
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
            validate_screenshot(run_root, step.get('screenshot'))
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


def validate_vcs_observation(mappings):
    if (not isinstance(mappings, list) or any(not isinstance(entry, dict) or set(entry) != {'directory', 'vcs'}
            or any(not isinstance(value, str) for value in entry.values()) for entry in mappings)):
        raise ValueError('Read-only native VCS mapping observation is missing')


def validate_screenshot(run_root, filename):
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
    validate_png(data)


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
    validate_screenshot(run_root, value.get('screenshot'))
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
