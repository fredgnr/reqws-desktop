"""Submit the existing signed Release asset once, with durable and traceable evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request
from zipfile import ZipFile

from plugin_release import MAX_ARCHIVE_BYTES, VERSION_PATTERN, XML_ID, plugin_checksum, validate_plugin
from plugin_signing import CERTIFICATE, verify_signature

REPOSITORY = 'fredgnr/reqws-desktop'
MARKETPLACE = 'https://plugins.jetbrains.com'
UPLOAD_URL = MARKETPLACE + '/api/updates/upload'
MODES = {'bootstrap', 'automatic', 'paused'}
WORKFLOWS = {'.github/workflows/release.yml', '.github/workflows/marketplace-publish.yml'}
IDENTITY_FIELDS = ('repository', 'tag', 'commit', 'releaseId', 'assetId', 'checksumAssetId',
                   'filename', 'sha256', 'xmlId', 'version', 'channel', 'pluginId')


class UnknownSubmission(ValueError):
    pass


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs)


def positive_id(value):
    return type(value) is int and value > 0


def mode(value):
    if value not in MODES:
        raise ValueError('REQWS_MARKETPLACE_MODE must be bootstrap, automatic or paused')
    return value


def version_tuple(value):
    if not re.fullmatch(VERSION_PATTERN, value):
        raise ValueError('Not a stable release version')
    return tuple(map(int, value.split('.')))


def run(args, limit=2_000_000):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180, check=False)
    if result.returncode or len(result.stdout) > limit:
        raise ValueError('GitHub or Git request failed')
    return result.stdout


class GitHub:
    def api(self, path, *, binary=False, limit=2_000_000):
        if not path.startswith(f'repos/{REPOSITORY}/'):
            raise ValueError('Unexpected GitHub API destination')
        args = ['gh', 'api', path]
        if binary:
            args += ['--header', 'Accept: application/octet-stream']
        payload = run(args, limit)
        return payload if binary else strict_json(payload)

    def pages(self, path, field):
        result = []
        for page in range(1, 101):
            separator = '&' if '?' in path else '?'
            response = self.api(f'{path}{separator}per_page=100&page={page}')
            items = response[field]
            if not isinstance(items, list):
                raise ValueError('Invalid GitHub collection')
            result.extend(items)
            if len(items) < 100:
                return result
        raise UnknownSubmission('GitHub history exceeds the bounded audit window')

    def repo(self, suffix):
        return self.api(f'repos/{REPOSITORY}/{suffix}')

    def commit(self, tag):
        reference = self.repo(f'git/ref/tags/{tag}')['object']
        for _ in range(5):
            if reference['type'] == 'commit':
                return reference['sha']
            if reference['type'] != 'tag':
                break
            reference = self.repo(f"git/tags/{reference['sha']}")['object']
        raise ValueError('Tag does not resolve to a commit')

    def artifact(self, artifact):
        if artifact.get('expired') or not positive_id(artifact.get('id')):
            raise UnknownSubmission('Submission evidence has expired')
        data = self.api(f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip", binary=True, limit=100_000)
        with ZipFile(io.BytesIO(data)) as archive:
            if archive.namelist() != ['receipt.json'] or archive.infolist()[0].file_size > 16_384:
                raise UnknownSubmission('Unexpected receipt artifact')
            return strict_json(archive.read('receipt.json'))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise UnknownSubmission('Marketplace redirects are forbidden')


class Marketplace:
    def request(self, path, data=None, token=None, content_type=None):
        if not path.startswith('/api/') or '?' in path or '..' in path:
            raise ValueError('Unexpected Marketplace API path')
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if content_type:
            headers['Content-Type'] = content_type
        request = urllib.request.Request(MARKETPLACE + path, data=data, headers=headers)
        opener = urllib.request.build_opener(NoRedirect())
        try:
            with opener.open(request, timeout=120) as response:
                payload = response.read(1_000_001)
                if len(payload) > 1_000_000:
                    raise UnknownSubmission('Response exceeds limit')
                return response.status, payload
        except urllib.error.HTTPError as error:
            # Do not expose server text or headers to logs/artifacts.
            return error.code, b''
        except (OSError, TimeoutError) as error:
            raise UnknownSubmission('Marketplace outcome could not be confirmed') from error

    def plugin(self):
        status, payload = self.request('/api/plugins/intellij/' + XML_ID)
        if status != 200:
            raise ValueError('The public Marketplace entry is unavailable')
        result = strict_json(payload)
        if (result.get('xmlId') != XML_ID or result.get('family') != 'intellij'
                or not positive_id(result.get('id'))):
            raise ValueError('Marketplace plugin identity mismatch')
        return result

    def reject_newer(self, plugin_id, version):
        status, payload = self.request(f'/api/plugins/{plugin_id}/updateVersions')
        if status != 200:
            raise UnknownSubmission('Could not check newer Marketplace versions')
        versions = strict_json(payload)
        if not isinstance(versions, list):
            raise UnknownSubmission('Unexpected version history')
        for item in versions:
            value = item.get('version') if isinstance(item, dict) else None
            if isinstance(value, str) and re.fullmatch(VERSION_PATTERN, value):
                if version_tuple(value) > version_tuple(version):
                    raise UnknownSubmission('A newer Marketplace version exists')

    def upload(self, archive, token):
        if not token or any(ord(char) < 32 for char in token):
            raise ValueError('Marketplace token is required')
        boundary = 'reqws-' + os.urandom(24).hex()
        fields = {'xmlId': XML_ID, 'family': 'intellij', 'channel': '',
                  'isHidden': 'false', 'containsAds': 'false'}
        parts = []
        for key, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="plugin.zip"\r\n'
                      'Content-Type: application/zip\r\n\r\n').encode())
        parts.extend([archive.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()])
        # There is exactly one POST and no automatic HTTP retry or redirect.
        return self.request('/api/updates/upload', b''.join(parts), token,
                            'multipart/form-data; boundary=' + boundary)


def context(github, env=os.environ):
    if env.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise ValueError('Unexpected repository')
    ref = env.get('GITHUB_REF', '')
    if not re.fullmatch('refs/tags/v' + VERSION_PATTERN, ref):
        raise ValueError('A strict stable tag ref is required')
    tag = ref.removeprefix('refs/tags/')
    commit = github.commit(tag)
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Invalid commit')
    if run(['git', 'rev-parse', 'HEAD']).decode().strip() != commit:
        raise ValueError('Checkout does not match the release tag')
    default_branch = strict_json(run(['gh', 'api', f'repos/{REPOSITORY}']))['default_branch']
    from urllib.parse import quote
    comparison = github.repo(f'compare/{commit}...{quote(default_branch, safe="")}')
    if comparison.get('status') not in {'ahead', 'identical'}:
        raise ValueError('Tag must belong to the default branch')
    return {'repository': REPOSITORY, 'tag': tag, 'commit': commit, 'version': tag[1:],
            'xmlId': XML_ID, 'channel': '', 'schemaVersion': 1,
            'runId': int(env['GITHUB_RUN_ID']), 'runAttempt': int(env['GITHUB_RUN_ATTEMPT'])}


def release_assets(release, tag):
    if (release.get('tag_name') != tag or release.get('draft') is not False
            or release.get('prerelease') is not False or not positive_id(release.get('id'))):
        raise ValueError('Expected an existing public stable GitHub Release')
    selected = []
    for name, limit in [(f'ReqWS-{tag[1:]}-goland-plugin.zip', MAX_ARCHIVE_BYTES), ('SHA256SUMS', 16_384)]:
        matches = [asset for asset in release['assets'] if asset.get('name') == name]
        if (len(matches) != 1 or not positive_id(matches[0].get('id'))
                or type(matches[0].get('size')) is not int or not 0 < matches[0]['size'] <= limit
                or matches[0].get('state') != 'uploaded'):
            raise ValueError('Missing, duplicated or invalid Release asset')
        selected.append({key: matches[0].get(key) for key in ['id', 'name', 'size', 'updated_at', 'digest']})
    return selected


def artifact_name(kind, candidate):
    return f"marketplace-{kind}-{candidate['tag']}-{candidate['runId']}-{candidate['runAttempt']}"


def trusted_receipt(github, artifact):
    workflow_run = github.repo(f"actions/runs/{artifact['workflow_run']['id']}")
    if (workflow_run.get('path') not in WORKFLOWS or workflow_run.get('event') not in {'push', 'workflow_dispatch'}
            or workflow_run.get('repository', {}).get('full_name') != REPOSITORY
            or workflow_run.get('head_repository', {}).get('full_name') != REPOSITORY):
        raise UnknownSubmission('Untrusted receipt source')
    receipt = github.artifact(artifact)
    if (receipt.get('schemaVersion') != 1 or receipt.get('repository') != REPOSITORY
            or receipt.get('commit') != workflow_run['head_sha']
            or receipt.get('runId') != workflow_run['id']
            or not positive_id(receipt.get('runAttempt'))
            or receipt['runAttempt'] > workflow_run['run_attempt']
            or artifact['name'] not in [artifact_name(kind, receipt) for kind in ['intent', 'result']]):
        raise UnknownSubmission('Receipt provenance mismatch')
    if receipt.get('tag') != workflow_run.get('head_branch'):
        raise UnknownSubmission('Receipt tag provenance mismatch')
    return receipt


def history_decision(github, candidate):
    artifacts = github.pages(f'repos/{REPOSITORY}/actions/artifacts', 'artifacts')
    receipts = []
    for artifact in artifacts:
        name = artifact.get('name', '')
        match = re.fullmatch(r'marketplace-(intent|result)-(v' + VERSION_PATTERN + r')-[0-9]+-[0-9]+', name)
        if not match:
            continue
        artifact_version = match[2][1:]
        if version_tuple(artifact_version) < version_tuple(candidate['version']):
            continue
        receipt = trusted_receipt(github, artifact)
        if receipt.get('runId') == candidate['runId'] and receipt.get('runAttempt') == candidate['runAttempt']:
            continue
        if version_tuple(artifact_version) > version_tuple(candidate['version']):
            if receipt.get('postAttempted'):
                raise UnknownSubmission('Newer submission exists or is unresolved')
            continue
        receipts.append(receipt)
    # A successful matching receipt dominates that same attempt's pre-POST intent marker.
    attempts = {}
    for receipt in receipts:
        key = (receipt['runId'], receipt['runAttempt'])
        attempts.setdefault(key, []).append(receipt)
    submitted = False
    for records in attempts.values():
        results = [r for r in records if r.get('outcome') in {'submitted', 'already-submitted'}]
        if results:
            if not all(all(r.get(key) == candidate.get(key) for key in IDENTITY_FIELDS)
                       and positive_id(r.get('updateId')) for r in results):
                raise UnknownSubmission('Existing submission does not match these Release bytes')
            submitted = True
        elif any(r.get('outcome') == 'submission-failed' and r.get('postAttempted') is False for r in records):
            continue
        elif any(r.get('postAttempted') for r in records):
            if not any(r.get('outcome') == 'submission-failed' and r.get('httpStatus') in {401, 403, 404, 413, 422}
                       for r in records):
                raise UnknownSubmission('An earlier POST has no conclusive receipt')
    # Missing/expired evidence is not permission to POST. Audit both calling entry points.
    for workflow in ['release.yml', 'marketplace-publish.yml']:
        runs = github.pages(f'repos/{REPOSITORY}/actions/workflows/{workflow}/runs?head_sha={candidate["commit"]}', 'workflow_runs')
        for prior in runs:
            if prior.get('event') not in {'push', 'workflow_dispatch'} or prior.get('head_branch') != candidate['tag']:
                continue
            for attempt in range(1, prior['run_attempt'] + 1):
                key = (prior['id'], attempt)
                if key == (candidate['runId'], candidate['runAttempt']):
                    continue
                if key not in attempts:
                    raise UnknownSubmission('Prior run has no retained submission evidence')
    return 'already-submitted' if submitted else 'upload'


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def prepare(github, marketplace, directory, signer, certificate):
    candidate = context(github)
    save(directory / 'result/receipt.json', {**candidate, 'outcome': 'submission-failed', 'postAttempted': False})
    release = github.repo(f'releases/tags/{candidate["tag"]}')
    assets = release_assets(release, candidate['tag'])
    archive = directory / assets[0]['name']
    for asset, limit in zip(assets, [MAX_ARCHIVE_BYTES, 16_384]):
        data = github.api(f'repos/{REPOSITORY}/releases/assets/{asset["id"]}', binary=True, limit=limit)
        if len(data) != asset['size']:
            raise ValueError('Downloaded Release asset size changed')
        with (directory / asset['name']).open('xb') as target:
            target.write(data)
    metadata = validate_plugin(archive, candidate['version'])
    if plugin_checksum((directory / 'SHA256SUMS').read_bytes(), archive.name) != metadata['sha256']:
        raise ValueError('Release plugin checksum mismatch')
    verify_signature(archive, certificate, signer)
    plugin = marketplace.plugin()
    candidate.update(releaseId=release['id'], assetId=assets[0]['id'], checksumAssetId=assets[1]['id'],
                     filename=archive.name, sha256=metadata['sha256'], pluginId=plugin['id'], assets=assets,
                     postAttempted=False, updateId=None, httpStatus=None)
    marketplace.reject_newer(plugin['id'], candidate['version'])
    decision = history_decision(github, candidate)
    if decision == 'already-submitted':
        # Preserve the server update ID from a proven previous receipt.
        for artifact in github.pages(f'repos/{REPOSITORY}/actions/artifacts', 'artifacts'):
            if artifact.get('name', '').startswith('marketplace-result-' + candidate['tag'] + '-'):
                receipt = trusted_receipt(github, artifact)
                if receipt.get('outcome') in {'submitted', 'already-submitted'} and all(
                        receipt.get(key) == candidate.get(key) for key in IDENTITY_FIELDS):
                    candidate['updateId'] = receipt['updateId']
                    break
    candidate['outcome'] = decision if decision != 'upload' else 'prepared'
    save(directory / 'prepared.json', candidate)
    if decision == 'upload':
        save(directory / 'intent/receipt.json', {**candidate, 'outcome': 'submission-unknown', 'postAttempted': True})
    else:
        save(directory / 'result/receipt.json', candidate)
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a') as stream:
            stream.write(f'upload={str(decision == "upload").lower()}\n')
    return candidate


def classify_response(status, payload, candidate):
    if status in {401, 403, 404, 413, 422}:
        return 'submission-failed', None
    if not 200 <= status < 300:
        return 'submission-unknown', None
    try:
        response = strict_json(payload)
        if (not positive_id(response.get('id')) or not positive_id(response.get('pluginId'))
                or response.get('pluginId') != candidate['pluginId']
                or response.get('version') != candidate['version'] or response.get('hidden') is not False
                or response.get('channel') not in {None, '', 'default'}):
            raise ValueError('Upload receipt does not match candidate')
        return 'submitted', response['id']
    except (ValueError, TypeError, AttributeError):
        return 'submission-unknown', None


def submit(github, marketplace, directory, signer, certificate, intent_id):
    candidate = strict_json((directory / 'prepared.json').read_bytes())
    fresh = context(github)
    if any(fresh[key] != candidate[key] for key in ['tag', 'commit', 'runId', 'runAttempt']):
        raise ValueError('Prepared candidate context changed')
    artifact = github.repo(f'actions/artifacts/{intent_id}')
    if artifact['name'] != artifact_name('intent', candidate) or artifact['workflow_run']['id'] != candidate['runId']:
        raise ValueError('Durable intent artifact is missing')
    if github.artifact(artifact) != strict_json((directory / 'intent/receipt.json').read_bytes()):
        raise ValueError('Intent readback mismatch')
    release = github.repo(f'releases/tags/{candidate["tag"]}')
    if release['id'] != candidate['releaseId'] or release_assets(release, candidate['tag']) != candidate['assets']:
        raise ValueError('Release assets changed before submission')
    archive = directory / candidate['filename']
    metadata = validate_plugin(archive, candidate['version'])
    if metadata['sha256'] != candidate['sha256']:
        raise ValueError('Prepared plugin bytes changed')
    verify_signature(archive, certificate, signer)
    if marketplace.plugin()['id'] != candidate['pluginId']:
        raise ValueError('Marketplace identity changed')
    marketplace.reject_newer(candidate['pluginId'], candidate['version'])
    token = os.environ.get('JETBRAINS_MARKETPLACE_TOKEN', '')
    if not token:
        raise ValueError('Marketplace token is missing')
    candidate.update(postAttempted=True, outcome='submission-unknown')
    save(directory / 'result/receipt.json', candidate)
    try:
        status, payload = marketplace.upload(archive, token)
        outcome, update_id = classify_response(status, payload, candidate)
        candidate.update(outcome=outcome, updateId=update_id, httpStatus=status)
    except Exception:
        candidate['outcome'] = 'submission-unknown'
    candidate['submittedAt'] = datetime.now(timezone.utc).isoformat()
    save(directory / 'result/receipt.json', candidate)
    return candidate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['mode', 'prepare', 'submit'])
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--signer', type=Path)
    parser.add_argument('--certificate', type=Path, default=CERTIFICATE)
    parser.add_argument('--intent-artifact-id', type=int)
    args = parser.parse_args()
    candidate = None
    try:
        configured_mode = mode(os.environ.get('REQWS_MARKETPLACE_MODE'))
        if args.command == 'mode':
            if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY or not re.fullmatch(
                    'refs/tags/v' + VERSION_PATTERN, os.environ.get('GITHUB_REF', '')):
                raise ValueError('Stable tag in the expected repository is required')
            outcome = {'bootstrap': 'manual-initial-upload-required', 'paused': 'submission-paused',
                       'automatic': 'automatic'}[configured_mode]
        else:
            if configured_mode != 'automatic':
                raise ValueError('Automatic submission is disabled')
            directory = args.directory.resolve()
            directory.mkdir(parents=True, exist_ok=True)
            if args.command == 'prepare':
                candidate = prepare(GitHub(), Marketplace(), directory, args.signer, args.certificate)
            else:
                candidate = submit(GitHub(), Marketplace(), directory, args.signer, args.certificate, args.intent_artifact_id)
            outcome = candidate['outcome']
        print('Marketplace outcome: ' + outcome)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write(f'\nMarketplace: **{outcome}**. GitHub Release remains independent.\n')
        return 0 if outcome not in {'submission-failed', 'submission-unknown'} else 1
    except Exception as error:
        outcome = 'submission-unknown' if isinstance(error, UnknownSubmission) else 'submission-failed'
        if args.command == 'submit':
            # A receipt write/summary error after POST must never be reported as a proven rejection.
            try:
                receipt = strict_json((args.directory / 'result/receipt.json').read_bytes())
                if receipt.get('postAttempted') is not False:
                    outcome = 'submission-unknown'
            except Exception:
                outcome = 'submission-unknown'
        print('Marketplace outcome: ' + outcome + '. Review the original run before retrying.', file=sys.stderr)
        # Keep an existing post-attempt receipt intact. Without enough context, do not manufacture evidence.
        return 1


if __name__ == '__main__':
    sys.exit(main())
