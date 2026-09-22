"""Marketplace protocol, release identity, and durable retry safety regression tests."""

import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from zipfile import ZipFile
from email.parser import BytesParser
from email.policy import default

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import publish_jetbrains_marketplace as market
from plugin_release import plugin_checksum
import test_prepare_goland_release as fixtures

COMMIT = 'a' * 40
ENV = {'GITHUB_REPOSITORY': market.REPOSITORY, 'GITHUB_REF': 'refs/tags/v1.2.3',
       'GITHUB_RUN_ID': '100', 'GITHUB_RUN_ATTEMPT': '1'}


def candidate():
    return {'schemaVersion': 1, 'repository': market.REPOSITORY, 'tag': 'v1.2.3', 'commit': COMMIT,
            'releaseId': 20, 'assetId': 30, 'checksumAssetId': 31,
            'filename': 'ReqWS-1.2.3-goland-plugin.zip', 'sha256': 'b' * 64,
            'xmlId': market.XML_ID, 'version': '1.2.3', 'channel': '', 'pluginId': 40,
            'runId': 100, 'runAttempt': 1, 'postAttempted': False, 'updateId': None, 'httpStatus': None}


def release():
    return {'id': 20, 'tag_name': 'v1.2.3', 'draft': False, 'prerelease': False, 'assets': [
        {'id': 30, 'name': 'ReqWS-1.2.3-goland-plugin.zip', 'size': 100, 'state': 'uploaded'},
        {'id': 31, 'name': 'SHA256SUMS', 'size': 100, 'state': 'uploaded'},
    ]}


def success():
    # Fields from the pinned official PluginUpdateBean, not a claimed live API sample.
    return {'id': 55, 'pluginId': 40, 'version': '1.2.3', 'channel': '', 'hidden': False}


def receipt_archive(receipt):
    stream = io.BytesIO()
    with ZipFile(stream, 'w') as archive:
        archive.writestr('receipt.json', json.dumps(receipt))
    return stream.getvalue()


def github_response(args, **kwargs):
    """The API media type and the returned archive format are separate contracts."""
    path = args[2]
    accept = args[args.index('--header') + 1] if '--header' in args else 'Accept: application/vnd.github+json'
    expected = ('application/octet-stream' if '/releases/assets/' in path
                else 'application/vnd.github+json')
    if accept != 'Accept: ' + expected:
        return Mock(returncode=1, stdout=b'{"status":"415"}',
                    stderr=b'gh: Unsupported Accept header (HTTP 415)')
    return Mock(returncode=0, stdout=b'', stderr=b'')


class GitHubDownloadTests(unittest.TestCase):
    def test_artifact_zip_readback_uses_json_api_accept(self):
        receipt = candidate()
        payload = receipt_archive(receipt)
        def server(args, **kwargs):
            response = github_response(args, **kwargs)
            if not response.returncode:
                response.stdout = payload
            return response
        with patch.object(market.subprocess, 'run', side_effect=server):
            self.assertEqual(market.GitHub().artifact({'id': 50, 'expired': False}), receipt)

    def test_artifact_download_failure_and_size_limit_still_block_readback(self):
        for code, payload in [(1, b'{"status":"415"}'), (0, b'x' * 100_001)]:
            with self.subTest(code=code), patch.object(market.subprocess, 'run', return_value=Mock(
                    returncode=code, stdout=payload, stderr=b'private server text')):
                with self.assertRaisesRegex(market.PublishingError, '^GitHub API artifact download failed$'):
                    market.GitHub().artifact({'id': 50, 'expired': False})

    def test_artifact_archive_shape_and_receipt_size_remain_restricted(self):
        for names in [['receipt.json', 'extra.txt'], ['nested/receipt.json'], ['receipt.json']]:
            stream = io.BytesIO()
            with ZipFile(stream, 'w') as archive:
                for name in names:
                    archive.writestr(name, b'x' * 16_385 if names == ['receipt.json'] else b'{}')
            with self.subTest(names=names), patch.object(market.subprocess, 'run', return_value=Mock(
                    returncode=0, stdout=stream.getvalue(), stderr=b'')):
                with self.assertRaises(market.UnknownSubmission):
                    market.GitHub().artifact({'id': 50, 'expired': False})


class MarketplaceProtocolTests(unittest.TestCase):
    def test_actual_multipart_contract_and_family_negative_assertions(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'plugin.zip'
            archive.write_bytes(b'EXACT ZIP BYTES')
            client = market.Marketplace()
            client.request = Mock(return_value=(200, json.dumps(success()).encode()))
            client.upload(archive, 'test-token-not-production')
            path, data, token, content_type = client.request.call_args.args
            self.assertEqual(market.MARKETPLACE + path, market.UPLOAD_URL)
            self.assertEqual(token, 'test-token-not-production')
            message = BytesParser(policy=default).parsebytes(
                ('Content-Type: ' + content_type + '\r\n\r\n').encode() + data)
            fields = {part.get_param('name', header='content-disposition'): part.get_payload(decode=True)
                      for part in message.iter_parts()}
            self.assertEqual(fields, {'xmlId': b'com.reqws.workspace', 'family': b'intellij',
                                     'channel': b'', 'isHidden': b'false', 'containsAds': b'false',
                                     'file': b'EXACT ZIP BYTES'})
            for wrong in [None, b'', b'goland', b'GO']:
                mutated = dict(fields)
                if wrong is None:
                    mutated.pop('family')
                else:
                    mutated['family'] = wrong
                self.assertNotEqual(mutated.get('family'), b'intellij')
            client.request.assert_called_once()

    def test_token_only_in_header_and_no_redirect(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.status = 200
        response.read.return_value = b'{}'
        with patch.object(market.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            market.Marketplace().request('/api/updates/upload', b'data', 'private-token', 'multipart/form-data')
            request = opener.return_value.open.call_args.args[0]
            self.assertEqual(request.get_header('Authorization'), 'Bearer private-token')
            self.assertNotIn('private-token', request.full_url)
            self.assertNotIn(b'private-token', request.data)
        with self.assertRaises(market.UnknownSubmission):
            market.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.test/')

    def test_response_classification(self):
        self.assertEqual(market.classify_response(200, json.dumps(success()), candidate()), ('submitted', 55))
        for status in [401, 403, 404, 413, 422]:
            self.assertEqual(market.classify_response(status, b'secret server text', candidate()), ('submission-failed', None))
        for status in [400, 409, 429, 500, 302]:
            self.assertEqual(market.classify_response(status, b'', candidate()), ('submission-unknown', None))
        for payload in [b'<html>', b'{}', b'[]', b'{"id":1,"id":2}', b'null']:
            self.assertEqual(market.classify_response(200, payload, candidate()), ('submission-unknown', None))
        for key, value in [('id', True), ('id', 0), ('pluginId', 41), ('version', '1.2.4'), ('hidden', True), ('channel', 'nightly')]:
            response = success(); response[key] = value
            self.assertEqual(market.classify_response(200, json.dumps(response), candidate()), ('submission-unknown', None))

    def test_modes_fail_closed(self):
        for value in ['bootstrap', 'automatic', 'paused']:
            self.assertEqual(market.mode(value), value)
        for value in [None, '', 'Automatic', 'disabled']:
            with self.assertRaises(ValueError):
                market.mode(value)

    def test_target_checksum_subset_and_negative_records(self):
        filename = candidate()['filename']
        record = 'a' * 64 + '  ' + filename + '\n'
        other = 'b' * 64 + '  desktop.zip\n'
        self.assertEqual(plugin_checksum((record + other).encode(), filename), 'a' * 64)
        for text in [record * 2, other, record.replace(filename, '../' + filename), record.replace('  ', ' '), record.upper()]:
            with self.assertRaises(ValueError):
                plugin_checksum(text.encode(), filename)

    def test_release_identity_and_assets(self):
        self.assertEqual(len(market.release_assets(release(), 'v1.2.3')), 2)
        for key, value in [('draft', True), ('prerelease', True), ('tag_name', 'v1.2.4'), ('id', 0)]:
            mutated = release(); mutated[key] = value
            with self.assertRaises(ValueError):
                market.release_assets(mutated, 'v1.2.3')
        for mutation in ['duplicate', 'missing', 'empty', 'oversize', 'state']:
            mutated = release()
            if mutation == 'duplicate': mutated['assets'].append(mutated['assets'][0])
            if mutation == 'missing': mutated['assets'].pop()
            if mutation == 'empty': mutated['assets'][0]['size'] = 0
            if mutation == 'oversize': mutated['assets'][0]['size'] = market.MAX_ARCHIVE_BYTES + 1
            if mutation == 'state': mutated['assets'][0]['state'] = 'new'
            with self.assertRaises(ValueError):
                market.release_assets(mutated, 'v1.2.3')

    def test_context_rejects_untrusted_refs_and_ancestry(self):
        github = Mock(); github.commit.return_value = COMMIT; github.repo.return_value = {'status': 'ahead'}
        def command(args, limit=0):
            return COMMIT.encode() if args[0] == 'git' else b'{"default_branch":"main"}'
        with patch.object(market, 'run', side_effect=command):
            self.assertEqual(market.context(github, ENV)['commit'], COMMIT)
            for ref in ['refs/heads/main', 'refs/tags/v01.2.3', 'refs/tags/v1.2.3-rc1', 'refs/tags/v1.2.3;echo']:
                with self.assertRaises(ValueError):
                    market.context(github, {**ENV, 'GITHUB_REF': ref})
            with self.assertRaises(ValueError):
                market.context(github, {**ENV, 'GITHUB_REPOSITORY': 'other/repo'})
            github.repo.return_value = {'status': 'diverged'}
            with self.assertRaises(ValueError):
                market.context(github, ENV)


class ReceiptHistoryTests(unittest.TestCase):
    def history(self, records, runs=None):
        github = Mock()
        artifacts = [{'name': market.artifact_name('intent' if r['outcome'] == 'submission-unknown' else 'result', r),
                      'receipt': r} for r in records]
        github.pages.side_effect = lambda path, field: artifacts if field == 'artifacts' else (runs or [])
        with patch.object(market, 'trusted_receipt', side_effect=lambda _gh, artifact: artifact['receipt']):
            return market.history_decision(github, candidate())

    def receipt(self, **kwargs):
        return {**candidate(), 'runId': 90, 'postAttempted': True, 'outcome': 'submitted', 'updateId': 55, **kwargs}

    def test_first_attempt_and_matching_receipt(self):
        self.assertEqual(self.history([]), 'upload')
        self.assertEqual(self.history([self.receipt(outcome='submission-unknown'), self.receipt()]), 'already-submitted')

    def test_missing_expired_mismatched_and_ambiguous_evidence(self):
        for receipt in [self.receipt(sha256='c' * 64), self.receipt(updateId=None), self.receipt(outcome='submission-unknown')]:
            with self.assertRaises(market.UnknownSubmission):
                self.history([receipt])
        with self.assertRaises(market.UnknownSubmission):
            self.history([], [{'id': 90, 'run_attempt': 1, 'head_branch': 'v1.2.3', 'event': 'push'}])
        with self.assertRaises(market.UnknownSubmission):
            market.GitHub().artifact({'id': 7, 'expired': True})

    def test_no_post_failure_and_known_rejection_can_retry(self):
        self.assertEqual(self.history([self.receipt(outcome='submission-failed', postAttempted=False)]), 'upload')
        self.assertEqual(self.history([self.receipt(outcome='submission-unknown'), self.receipt(outcome='submission-failed', httpStatus=403)]), 'upload')

    def test_higher_submission_blocks_older_upload(self):
        with self.assertRaises(market.UnknownSubmission):
            self.history([self.receipt(tag='v1.2.4', version='1.2.4')])

    def test_provenance_rejects_pr_and_other_repository(self):
        github = Mock()
        artifact = {'id': 5, 'workflow_run': {'id': 90}, 'name': market.artifact_name('result', self.receipt())}
        good = {'id': 90, 'head_sha': COMMIT, 'head_branch': 'v1.2.3', 'run_attempt': 1, 'event': 'push',
                'path': '.github/workflows/release.yml', 'repository': {'full_name': market.REPOSITORY},
                'head_repository': {'full_name': market.REPOSITORY}}
        github.repo.return_value = good; github.artifact.return_value = self.receipt()
        self.assertEqual(market.trusted_receipt(github, artifact)['updateId'], 55)
        for key, value in [('event', 'pull_request'), ('path', '.github/workflows/ci.yml'),
                           ('head_sha', 'c' * 40), ('head_repository', {'full_name': 'attacker/fork'})]:
            github.repo.return_value = {**good, key: value}
            with self.assertRaises(market.UnknownSubmission):
                market.trusted_receipt(github, artifact)


class SubmissionIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        builder = fixtures.PluginReleaseTests()
        builder.distributions = self.directory
        self.archive = builder.fixture()
        self.archive.rename(self.directory / candidate()['filename'])
        self.archive = self.directory / candidate()['filename']
        self.candidate = candidate()
        self.candidate['sha256'] = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.candidate['assets'] = market.release_assets(release(), 'v1.2.3')
        self.candidate['outcome'] = 'prepared'
        self.github, self.market = Mock(), Mock()
        self.intent = {**self.candidate, 'outcome': 'submission-unknown', 'postAttempted': True}
        market.save(self.directory / 'prepared.json', self.candidate)
        market.save(self.directory / 'intent/receipt.json', self.intent)
        artifact = {'id': 50, 'name': market.artifact_name('intent', self.candidate), 'workflow_run': {'id': 100}}
        self.github.repo.side_effect = lambda path: artifact if path.startswith('actions/artifacts/') else release()
        self.github.artifact.return_value = self.intent
        self.market.plugin.return_value = {'id': 40}
        self.market.upload.return_value = (200, json.dumps(success()).encode())
        self.addCleanup(patch.stopall)
        patch.object(market, 'context', return_value=candidate()).start()
        patch.object(market, 'verify_signature').start()
        patch.dict(os.environ, {'JETBRAINS_MARKETPLACE_TOKEN': 'mock-private-token'}).start()

    def submit(self):
        return market.submit(self.github, self.market, self.directory, Path('signer.jar'), Path('cert.pem'), 50)

    def test_success_and_timeout_preserve_receipt_without_release_mutation(self):
        result = self.submit()
        self.assertEqual(result['outcome'], 'submitted')
        self.market.upload.assert_called_once()
        self.assertNotIn('mock-private-token', (self.directory / 'result/receipt.json').read_text())
        self.market.upload.side_effect = TimeoutError()
        result = self.submit()
        self.assertEqual(result['outcome'], 'submission-unknown')
        self.assertTrue(result['postAttempted'])
        self.assertTrue(all(call.args[0].startswith(('actions/artifacts/', 'releases/tags/')) for call in self.github.repo.call_args_list))

    def test_receipt_write_failure_after_post_reports_unknown(self):
        arguments = ['publisher', 'submit', '--directory', str(self.directory), '--signer', 'signer.jar', '--intent-artifact-id', '50']
        for post_attempted, expected in [(True, 'submission-unknown'), (False, 'submission-failed')]:
            market.save(self.directory / 'result/receipt.json', {**self.candidate, 'postAttempted': post_attempted})
            with patch.object(sys, 'argv', arguments), patch.dict(os.environ, {'REQWS_MARKETPLACE_MODE': 'automatic'}), \
                    patch.object(market, 'submit', side_effect=OSError('receipt persistence failed')), \
                    patch.object(sys, 'stderr', new_callable=io.StringIO) as stderr:
                self.assertEqual(market.main(), 1)
                self.assertIn(expected, stderr.getvalue())

    def test_intent_readback_and_asset_changes_block_post(self):
        self.github.artifact.return_value = {**self.intent, 'sha256': 'c' * 64}
        with self.assertRaises(ValueError): self.submit()
        self.market.upload.assert_not_called()
        self.github.artifact.return_value = self.intent
        mutated = release(); mutated['assets'][0]['id'] = 99
        previous = self.github.repo.side_effect
        self.github.repo.side_effect = lambda path: mutated if path.startswith('releases/') else previous(path)
        with self.assertRaises(ValueError): self.submit()
        self.market.upload.assert_not_called()

    def test_submit_reads_durable_intent_through_github_transport_before_post(self):
        artifact = {'id': 50, 'name': market.artifact_name('intent', self.candidate),
                    'workflow_run': {'id': 100}, 'expired': False}
        responses = {
            f'repos/{market.REPOSITORY}/actions/artifacts/50': json.dumps(artifact).encode(),
            f'repos/{market.REPOSITORY}/actions/artifacts/50/zip': receipt_archive(self.intent),
            f'repos/{market.REPOSITORY}/releases/tags/v1.2.3': json.dumps(release()).encode(),
        }
        def server(args, **kwargs):
            self.market.upload.assert_not_called()
            response = github_response(args, **kwargs)
            if not response.returncode:
                response.stdout = responses[args[2]]
            return response
        self.github = market.GitHub()
        with patch.object(market.subprocess, 'run', side_effect=server):
            result = self.submit()
        self.assertEqual(result['outcome'], 'submitted')
        self.market.upload.assert_called_once_with(self.archive, 'mock-private-token')

    def test_prepare_downloads_release_asset_bytes_with_octet_stream(self):
        archive = self.archive.read_bytes()
        checksum = (self.candidate['sha256'] + '  ' + self.archive.name + '\n').encode()
        current_release = release()
        for asset, content in zip(current_release['assets'], [archive, checksum]):
            asset['size'] = len(content)
        responses = {
            f'repos/{market.REPOSITORY}/releases/tags/v1.2.3': json.dumps(current_release).encode(),
            f'repos/{market.REPOSITORY}/releases/assets/30': archive,
            f'repos/{market.REPOSITORY}/releases/assets/31': checksum,
        }
        def server(args, **kwargs):
            response = github_response(args, **kwargs)
            if not response.returncode:
                response.stdout = responses[args[2]]
            return response
        download = self.directory / 'download'
        download.mkdir()
        with patch.object(market.subprocess, 'run', side_effect=server), \
                patch.object(market, 'history_decision', return_value='upload'):
            result = market.prepare(market.GitHub(), self.market, download, Path('signer.jar'), Path('cert.pem'))
        self.assertEqual(result['outcome'], 'prepared')
        self.assertEqual((download / self.archive.name).read_bytes(), archive)
        self.assertEqual((download / 'SHA256SUMS').read_bytes(), checksum)
        self.market.upload.assert_not_called()

    def test_artifact_download_failure_reports_safe_diagnostic_without_post(self):
        artifact = {'id': 50, 'name': market.artifact_name('intent', self.candidate),
                    'workflow_run': {'id': 100}, 'expired': False}
        def server(args, **kwargs):
            if args[2].endswith('/zip'):
                return Mock(returncode=1, stdout=b'{"status":"415"}',
                            stderr=b'private-token\n::error::untrusted server text')
            return Mock(returncode=0, stdout=json.dumps(artifact).encode(), stderr=b'')
        arguments = ['publisher', 'submit', '--directory', str(self.directory),
                     '--signer', 'signer.jar', '--intent-artifact-id', '50']
        receipt = {**self.candidate, 'outcome': 'submission-failed', 'postAttempted': False}
        market.save(self.directory / 'result/receipt.json', receipt)
        with patch.object(sys, 'argv', arguments), patch.dict(os.environ, {'REQWS_MARKETPLACE_MODE': 'automatic'}), \
                patch.object(market.subprocess, 'run', side_effect=server), \
                patch.object(market, 'Marketplace', return_value=self.market), \
                patch.object(sys, 'stderr', new_callable=io.StringIO) as stderr:
            self.assertEqual(market.main(), 1)
        self.market.upload.assert_not_called()
        self.assertEqual(market.strict_json((self.directory / 'result/receipt.json').read_bytes()), receipt)
        self.assertIn('submission-failed. GitHub API artifact download failed.', stderr.getvalue())
        for private in ['private-token', '::error::', 'untrusted server text']:
            self.assertNotIn(private, stderr.getvalue())

    def test_missing_token_reports_configuration_error_without_post(self):
        arguments = ['publisher', 'submit', '--directory', str(self.directory),
                     '--signer', 'signer.jar', '--intent-artifact-id', '50']
        receipt = {**self.candidate, 'outcome': 'submission-failed', 'postAttempted': False}
        market.save(self.directory / 'result/receipt.json', receipt)
        with patch.object(sys, 'argv', arguments), \
                patch.dict(os.environ, {'REQWS_MARKETPLACE_MODE': 'automatic', 'JETBRAINS_MARKETPLACE_TOKEN': ''}), \
                patch.object(market, 'GitHub', return_value=self.github), \
                patch.object(market, 'Marketplace', return_value=self.market), \
                patch.object(sys, 'stderr', new_callable=io.StringIO) as stderr:
            self.assertEqual(market.main(), 1)
        self.market.upload.assert_not_called()
        self.assertEqual(market.strict_json((self.directory / 'result/receipt.json').read_bytes()), receipt)
        self.assertIn('submission-failed. JETBRAINS_MARKETPLACE_TOKEN is missing in the jetbrains-marketplace job.',
                      stderr.getvalue())


if __name__ == '__main__':
    unittest.main()
