"""Explicit owner-operated bootstrap. Never called from a release or PR workflow."""

import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time

from plugin_signing import ROOT, CERTIFICATE, java, openssl, preflight, run_checked, verify_signature

PRIVATE_REPOSITORY = 'fredgnr/reqws-secret'
PUBLIC_REPOSITORY = 'fredgnr/reqws-desktop'
IDENTITY_DIRECTORY = 'reqws-desktop/jetbrains-plugin-signing'
IDENTITY_FILES = ('private.pem', 'password', 'chain.crt', 'public.pem', 'metadata.json')


def gh_api(path, method='GET', data=None):
    args = ['gh', 'api', path, '--method', method]
    payload = None
    if data is not None:
        args += ['--input', '-']
        payload = json.dumps(data).encode()
    for attempt in range(3 if method == 'GET' else 1):
        result = subprocess.run(args, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=120, check=False)
        if result.returncode == 0:
            break
        if method == 'GET' and attempt < 2:
            time.sleep(attempt + 1)
    if result.returncode:
        import re
        status = re.search(rb'HTTP ([0-9]{3})', result.stderr)
        reason = status[1].decode() if status else 'transport-or-authentication'
        raise ValueError('GitHub configuration operation failed: ' + method + ' ' + reason)
    return json.loads(result.stdout) if result.stdout else None


class PrivateBackup:
    def __init__(self, api=gh_api):
        self.api = api

    def check(self):
        metadata = self.api(f'repos/{PRIVATE_REPOSITORY}')
        if metadata.get('private') is not True or metadata.get('full_name') != PRIVATE_REPOSITORY:
            raise ValueError('Backup must be the designated private repository')
        return metadata['default_branch']

    def create(self, directory, files):
        branch = self.check()
        prefix = f'repos/{PRIVATE_REPOSITORY}'
        head = self.api(f'{prefix}/git/ref/heads/{branch}')['object']['sha']
        commit = self.api(f'{prefix}/git/commits/{head}')
        existing = self.api(f'{prefix}/git/trees/{commit["tree"]["sha"]}?recursive=1')
        if existing.get('truncated') or any(item['path'] == directory or item['path'].startswith(directory + '/')
                                            for item in existing['tree']):
            raise ValueError('Backup namespace already exists or cannot be safely inspected; restore it explicitly')
        tree = []
        for name, data in files.items():
            if '/' in name or name in {'.', '..'}:
                raise ValueError('Invalid backup filename')
            blob = self.api(f'{prefix}/git/blobs', 'POST', {'encoding': 'base64', 'content': base64.b64encode(data).decode()})
            tree.append({'path': directory + '/' + name, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
        created_tree = self.api(f'{prefix}/git/trees', 'POST', {'base_tree': commit['tree']['sha'], 'tree': tree})
        created = self.api(f'{prefix}/git/commits', 'POST', {
            'message': 'Back up independent ReqWS plugin publishing identity',
            'tree': created_tree['sha'], 'parents': [head],
        })
        self.api(f'{prefix}/git/refs/heads/{branch}', 'PATCH', {'sha': created['sha'], 'force': False})
        # Activation uses the exact remote commit, never an unpushed local state.
        remote = self.api(f'{prefix}/git/ref/heads/{branch}')['object']['sha']
        if remote != created['sha']:
            raise ValueError('Backup branch changed; verify the exact remote commit before activation')
        return remote

    def read(self, directory, names, commit):
        import re
        self.check()
        if not re.fullmatch('[0-9a-f]{40}', commit):
            raise ValueError('Recovery requires an exact commit')
        result = {}
        for name in names:
            data = self.api(f'repos/{PRIVATE_REPOSITORY}/contents/{directory}/{name}?ref={commit}')
            if data.get('encoding') != 'base64' or data.get('type') != 'file':
                raise ValueError('Incomplete backup recovery')
            result[name] = base64.b64decode(data['content'])
        return result


def generate_identity(directory):
    password = secrets.token_urlsafe(48)
    env = {**os.environ, 'REQWS_NEW_PLUGIN_PASSWORD': password}
    key, certificate = directory / 'private.pem', directory / 'chain.crt'
    run_checked([openssl(), 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:4096',
                 '-aes-256-cbc', '-pass', 'env:REQWS_NEW_PLUGIN_PASSWORD', '-out', key], env=env)
    key.chmod(0o600)
    run_checked([openssl(), 'req', '-new', '-x509', '-sha256', '-days', '365', '-key', key,
                 '-passin', 'env:REQWS_NEW_PLUGIN_PASSWORD', '-out', certificate,
                 '-subj', '/CN=ReqWS Plugin Signing'], env=env)
    public = run_checked([openssl(), 'pkey', '-in', key, '-passin', 'env:REQWS_NEW_PLUGIN_PASSWORD', '-pubout'], env=env)
    return {'private.pem': key.read_bytes(), 'password': password.encode(), 'chain.crt': certificate.read_bytes(),
            'public.pem': public, 'metadata.json': json.dumps({
                'purpose': 'ReqWS plugin author signing; separate from macOS identity',
                'createdAt': datetime.now(timezone.utc).isoformat(), 'format': 'encrypted PKCS8 PEM, RSA 4096',
                'environment': 'jetbrains-plugin-signing', 'validityDays': 365,
            }, indent=2).encode()}


def verify_recovery(files, directory, fixture, signer):
    if set(files) != set(IDENTITY_FILES):
        raise ValueError('Recovery is missing identity materials')
    for name, data in files.items():
        path = directory / name
        path.write_bytes(data)
        path.chmod(0o600)
    key, certificate = directory / 'private.pem', directory / 'chain.crt'
    password = files['password'].decode()
    preflight(key, certificate, password)
    if run_checked([openssl(), 'x509', '-in', certificate, '-pubkey', '-noout']) != files['public.pem']:
        raise ValueError('Recovered public key does not match certificate')
    signed = directory / 'restoration-test.zip'
    run_checked([java(), '-jar', signer, 'sign', '-in', fixture, '-out', signed,
                 '-key-file', key, '-key-pass', password, '-cert-file', certificate])
    verify_signature(signed, certificate, signer)


def ensure_environment(name, api=gh_api):
    if name not in {'jetbrains-plugin-signing', 'jetbrains-marketplace'}:
        raise ValueError('Unexpected publishing environment')
    prefix = f'repos/{PUBLIC_REPOSITORY}'
    existing = api(f'{prefix}/environments')['environments']
    if any(environment['name'] == name for environment in existing):
        environment = api(f'{prefix}/environments/{name}')
        if environment.get('deployment_branch_policy') != {'protected_branches': False, 'custom_branch_policies': True}:
            raise ValueError('Existing environment has unexpected protections; preserve it for owner review')
        policies = api(f'{prefix}/environments/{name}/deployment-branch-policies')['branch_policies']
        if len(policies) != 1 or policies[0]['name'] != 'v*' or policies[0].get('type') != 'tag':
            raise ValueError('Existing environment tag policy needs owner review')
        return
    api(f'{prefix}/environments/{name}', 'PUT', {
        'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True},
    })
    api(f'{prefix}/environments/{name}/deployment-branch-policies', 'POST', {'name': 'v*', 'type': 'tag'})


def set_secret(environment, name, value):
    result = subprocess.run(['gh', 'secret', 'set', name, '--repo', PUBLIC_REPOSITORY, '--env', environment],
                            input=value, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120, check=False)
    if result.returncode:
        raise ValueError('Secret synchronization failed; restore the recorded remote backup before retrying')


def activate_identity(files):
    if CERTIFICATE.exists() and CERTIFICATE.read_bytes() != files['chain.crt']:
        raise ValueError('Refusing to replace an existing signing identity')
    ensure_environment('jetbrains-plugin-signing')
    CERTIFICATE.parent.mkdir(parents=True, exist_ok=True)
    CERTIFICATE.write_bytes(files['chain.crt'])
    set_secret('jetbrains-plugin-signing', 'JETBRAINS_PLUGIN_PRIVATE_KEY', files['private.pem'])
    set_secret('jetbrains-plugin-signing', 'JETBRAINS_PLUGIN_PRIVATE_KEY_PASSWORD', files['password'])
    ensure_environment('jetbrains-marketplace')


def provision_identity(backup, fixture, signer, commit=None, activate=activate_identity):
    # Cleanup covers successful, failed and interrupted generation/recovery; no private clone is created.
    with tempfile.TemporaryDirectory(prefix='reqws-plugin-provision-') as temporary:
        directory = Path(temporary).resolve()
        generated = None
        if commit is None:
            backup.check()
            generated = generate_identity(directory)
            commit = backup.create(IDENTITY_DIRECTORY, generated)
            print('Private plugin backup commit: ' + commit, flush=True)
        restored = backup.read(IDENTITY_DIRECTORY, IDENTITY_FILES, commit)
        if generated is not None and restored != generated:
            raise ValueError('Remote backup readback differs; identity was not activated')
        recovery = directory / 'recovered'
        recovery.mkdir(mode=0o700)
        verify_recovery(restored, recovery, fixture, signer)
        activate(restored)
        return commit


def provision_token(backup, token):
    if not token or b'\n' in token or b'\r' in token:
        raise ValueError('A single nonempty token is required')
    directory = 'reqws-desktop/jetbrains-marketplace'
    files = {'token': token, 'metadata.json': json.dumps({
        'purpose': 'ReqWS Marketplace upload', 'environment': 'jetbrains-marketplace',
        'createdAt': datetime.now(timezone.utc).isoformat(),
    }).encode()}
    commit = backup.create(directory, files)
    print('Private Marketplace token backup commit: ' + commit, flush=True)
    if backup.read(directory, files.keys(), commit) != files:
        raise ValueError('Token backup readback failed; runtime was not activated')
    ensure_environment('jetbrains-marketplace')
    set_secret('jetbrains-marketplace', 'JETBRAINS_MARKETPLACE_TOKEN', token)
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['initialize', 'restore', 'token'])
    parser.add_argument('--commit')
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--signer', type=Path)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda _signum, _frame: sys.exit(130))
    os.umask(0o077)
    try:
        backup = PrivateBackup()
        if args.command == 'token':
            # Read from a controlled terminal or pipe; never from a command argument/chat message.
            import getpass
            token = getpass.getpass('Marketplace token (hidden): ').encode() if sys.stdin.isatty() else sys.stdin.buffer.read().strip()
            provision_token(backup, token)
        else:
            if args.command == 'restore' and not args.commit:
                raise ValueError('Restore requires an exact backup commit')
            provision_identity(backup, args.fixture.resolve(), args.signer.resolve(), args.commit)
        print('Verified private backup synchronized; temporary materials cleaned.')
        return 0
    except Exception:
        print('Publishing configuration failed. Preserve any printed remote commit; activation may be partial. No secret values were logged.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
