"""A skipped POST job is evidence of non-submission, not a missing upload receipt."""

import io
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import publish_jetbrains_marketplace as market

COMMIT = 'a' * 40
RELEASE = '.github/workflows/release.yml'
MANUAL = '.github/workflows/marketplace-publish.yml'
NAMES = {RELEASE: 'Submit signed plugin to Marketplace / submit', MANUAL: 'submit'}


def candidate(**changes):
    return {'schemaVersion': 1, 'repository': market.REPOSITORY, 'tag': 'v1.2.3', 'commit': COMMIT,
            'releaseId': 20, 'assetId': 30, 'checksumAssetId': 31,
            'filename': 'ReqWS-1.2.3-goland-plugin.zip', 'sha256': 'b' * 64,
            'xmlId': market.XML_ID, 'version': '1.2.3', 'channel': '', 'pluginId': 40,
            'runId': 100, 'runAttempt': 1, **changes}


def prior_run(workflow=RELEASE, **changes):
    return {'id': 90, 'run_attempt': 1, 'head_sha': COMMIT, 'head_branch': 'v1.2.3',
            'path': workflow, 'event': 'push' if workflow == RELEASE else 'workflow_dispatch',
            'repository': {'full_name': market.REPOSITORY},
            'head_repository': {'full_name': market.REPOSITORY}, **changes}


def skipped_job(workflow=RELEASE, **changes):
    return {'id': 900, 'run_id': 90, 'run_attempt': 1, 'head_sha': COMMIT,
            'name': NAMES[workflow], 'status': 'completed', 'conclusion': 'skipped',
            'steps': [], **changes}


class SkippedSubmissionHistoryTests(unittest.TestCase):
    def github(self, runs=(), jobs=None, receipts=()):
        github = Mock()
        artifacts = [{'id': index + 1, 'name': market.artifact_name(
            'intent' if receipt['outcome'] == 'submission-unknown' else 'result', receipt),
            'workflow_run': {'id': receipt['runId']}, 'receipt': receipt}
            for index, receipt in enumerate(receipts)]
        jobs = jobs or {}

        def pages(path, field):
            if field == 'artifacts':
                return artifacts
            if field == 'workflow_runs':
                return [run for run in runs if f'/{Path(run["path"]).name}/runs?' in path]
            if field == 'jobs':
                parts = path.split('/')
                return jobs.get((int(parts[-4]), int(parts[-2])), [])
            raise AssertionError('Unexpected GitHub collection: ' + path)

        github.pages.side_effect = pages
        github.repo.side_effect = lambda path: next(run for run in runs if run['id'] == int(path.split('/')[-1]))
        github.artifact.side_effect = lambda artifact: artifact['receipt']
        return github

    def decision(self, github, current=None):
        with patch.object(sys, 'stdout', new_callable=io.StringIO):
            return market.history_decision(github, current or candidate())

    def test_bootstrap_release_without_receipt_can_retry(self):
        github = self.github([prior_run()], {(90, 1): [skipped_job()]})
        self.assertEqual(self.decision(github), 'upload')
        github.pages.assert_any_call(
            f'repos/{market.REPOSITORY}/actions/runs/90/attempts/1/jobs', 'jobs')

    def test_paused_standalone_workflow_without_receipt_can_retry(self):
        github = self.github([prior_run(MANUAL)], {(90, 1): [skipped_job(MANUAL)]})
        self.assertEqual(self.decision(github), 'upload')

    def test_both_entry_points_are_audited(self):
        runs = [prior_run(), prior_run(MANUAL, id=91)]
        jobs = {(90, 1): [skipped_job()], (91, 1): [skipped_job(MANUAL, run_id=91)]}
        github = self.github(runs, jobs)
        self.assertEqual(self.decision(github), 'upload')
        self.assertEqual(sum(call.args[1] == 'jobs' for call in github.pages.call_args_list), 2)

    def test_first_run_does_not_need_skip_evidence(self):
        github = self.github()
        self.assertEqual(self.decision(github), 'upload')
        self.assertFalse(any(call.args[1] == 'jobs' for call in github.pages.call_args_list))

    def test_current_attempt_is_ignored_but_earlier_attempt_is_checked(self):
        github = self.github([prior_run(id=100, run_attempt=2)],
                             {(100, 1): [skipped_job(run_id=100)]})
        self.assertEqual(self.decision(github, candidate(runAttempt=2)), 'upload')
        calls = [call.args[0] for call in github.pages.call_args_list if call.args[1] == 'jobs']
        self.assertEqual(calls, [f'repos/{market.REPOSITORY}/actions/runs/100/attempts/1/jobs'])

    def test_every_prior_attempt_requires_its_own_skip(self):
        github = self.github([prior_run(run_attempt=2)], {
            (90, 1): [skipped_job()], (90, 2): [skipped_job(run_attempt=2)],
        })
        self.assertEqual(self.decision(github), 'upload')
        self.assertEqual(sum(call.args[1] == 'jobs' for call in github.pages.call_args_list), 2)

    def test_later_skip_cannot_hide_an_earlier_possible_post(self):
        for conclusion in ['success', 'failure', 'cancelled', 'timed_out', None]:
            with self.subTest(conclusion=conclusion):
                github = self.github([prior_run(run_attempt=2)], {
                    (90, 1): [skipped_job(conclusion=conclusion)],
                    (90, 2): [skipped_job(run_attempt=2)],
                })
                with self.assertRaises(market.UnknownSubmission):
                    self.decision(github)

    def test_missing_ambiguous_or_malformed_jobs_are_not_skip_evidence(self):
        bad_lists = [[], [skipped_job(), skipped_job(id=901)],
                     [skipped_job(name='validate')], [skipped_job(name='other / submit')],
                     [skipped_job(name='submit')], [None], {'jobs': [skipped_job()]}]
        for jobs in bad_lists:
            with self.subTest(jobs=jobs):
                github = self.github([prior_run()], {(90, 1): jobs})
                with self.assertRaises(market.UnknownSubmission):
                    self.decision(github)

    def test_job_identity_and_no_steps_are_required(self):
        mutations = [('id', 0), ('id', True), ('run_id', 91), ('run_id', '90'),
                     ('run_attempt', 2), ('run_attempt', True), ('head_sha', 'c' * 40),
                     ('status', 'queued'), ('status', 'in_progress'), ('conclusion', 'success'),
                     ('steps', None), ('steps', {}), ('steps', [{'name': 'Read back intent and submit once'}])]
        for key, value in mutations:
            with self.subTest(key=key, value=value):
                github = self.github([prior_run()], {(90, 1): [skipped_job(**{key: value})]})
                with self.assertRaises(market.UnknownSubmission):
                    self.decision(github)
        for key in skipped_job():
            with self.subTest(missing=key):
                job = skipped_job()
                del job[key]
                github = self.github([prior_run()], {(90, 1): [job]})
                with self.assertRaises(market.UnknownSubmission):
                    self.decision(github)

    def test_run_provenance_is_required_before_accepting_a_skip(self):
        mutations = [('id', True), ('run_attempt', 0), ('path', '.github/workflows/ci.yml'),
                     ('event', 'pull_request'), ('head_sha', 'c' * 40), ('head_branch', 'main'),
                     ('repository', {'full_name': 'other/repo'}),
                     ('head_repository', {'full_name': 'other/fork'}),
                     ('repository', None), ('head_repository', None)]
        for key, value in mutations:
            with self.subTest(key=key):
                github = Mock()
                github.pages.return_value = [skipped_job()]
                self.assertFalse(market.skipped_submission(github, prior_run(**{key: value}), 1, candidate()))
                github.pages.assert_not_called()
        for key in prior_run():
            with self.subTest(missing=key):
                prior = prior_run()
                del prior[key]
                self.assertFalse(market.skipped_submission(Mock(), prior, 1, candidate()))

    def test_wrong_attempt_number_never_selects_the_latest_attempt(self):
        for attempt in [0, -1, True, '1', 2]:
            with self.subTest(attempt=attempt):
                github = Mock()
                self.assertFalse(market.skipped_submission(github, prior_run(), attempt, candidate()))
                github.pages.assert_not_called()

    def test_unavailable_job_history_remains_unknown(self):
        for error in [ValueError('403'), KeyError('jobs'), OSError('offline'),
                      subprocess.TimeoutExpired('gh', 180), market.UnknownSubmission('audit limit')]:
            with self.subTest(error=type(error)):
                github = Mock()
                github.pages.side_effect = error
                with self.assertRaises(market.UnknownSubmission):
                    market.skipped_submission(github, prior_run(), 1, candidate())

    def test_skip_job_on_second_page_is_not_missed(self):
        github = market.GitHub()
        filler = [{'name': f'API target {index}'} for index in range(100)]
        with patch.object(github, 'api', side_effect=[{'jobs': filler}, {'jobs': [skipped_job()]}]) as api:
            self.assertTrue(market.skipped_submission(github, prior_run(), 1, candidate()))
        self.assertEqual(api.call_count, 2)
        self.assertTrue(api.call_args_list[0].args[0].endswith('/attempts/1/jobs?per_page=100&page=1'))
        self.assertTrue(api.call_args_list[1].args[0].endswith('/attempts/1/jobs?per_page=100&page=2'))

    def test_duplicate_skip_on_later_page_is_ambiguous(self):
        github = market.GitHub()
        first_page = [skipped_job()] + [{'name': f'API target {index}'} for index in range(99)]
        with patch.object(github, 'api', side_effect=[{'jobs': first_page}, {'jobs': [skipped_job(id=901)]}]):
            self.assertFalse(market.skipped_submission(github, prior_run(), 1, candidate()))

    def test_matching_submission_receipt_still_prevents_another_upload(self):
        receipt = candidate(runId=91, outcome='submitted', postAttempted=True, updateId=55)
        github = self.github([prior_run(), prior_run(MANUAL, id=91)],
                             {(90, 1): [skipped_job()]}, [receipt])
        self.assertEqual(self.decision(github), 'already-submitted')
        self.assertEqual(sum(call.args[1] == 'jobs' for call in github.pages.call_args_list), 1)

    def test_skip_cannot_override_an_unresolved_post_or_mismatched_receipt(self):
        records = [candidate(runId=90, outcome='submission-unknown', postAttempted=True),
                   candidate(runId=90, outcome='submitted', postAttempted=True, updateId=55, sha256='c' * 64)]
        for receipt in records:
            with self.subTest(outcome=receipt['outcome']):
                github = self.github([prior_run()], {(90, 1): [skipped_job()]}, [receipt])
                with self.assertRaises(market.UnknownSubmission):
                    self.decision(github)
                self.assertFalse(any(call.args[1] == 'jobs' for call in github.pages.call_args_list))

    def test_expired_receipt_cannot_be_replaced_by_a_skip(self):
        receipt = candidate(runId=90, outcome='submitted', postAttempted=True, updateId=55)
        github = self.github([prior_run()], {(90, 1): [skipped_job()]}, [receipt])
        github.artifact.side_effect = market.UnknownSubmission('Submission evidence has expired')
        with self.assertRaises(market.UnknownSubmission):
            self.decision(github)
        self.assertFalse(any(call.args[1] == 'jobs' for call in github.pages.call_args_list))

    def test_known_rejections_and_pre_post_failures_keep_their_retry_policy(self):
        for extra in [{'postAttempted': False}, {'postAttempted': True, 'httpStatus': 403}]:
            with self.subTest(extra=extra):
                receipt = candidate(runId=90, outcome='submission-failed', **extra)
                github = self.github([prior_run()], receipts=[receipt])
                self.assertEqual(self.decision(github), 'upload')
                self.assertFalse(any(call.args[1] == 'jobs' for call in github.pages.call_args_list))

    def test_skip_proof_is_logged_with_its_run_and_attempt(self):
        github = self.github([prior_run()], {(90, 1): [skipped_job()]})
        with patch.object(sys, 'stdout', new_callable=io.StringIO) as output:
            market.history_decision(github, candidate())
        self.assertIn('run 90 attempt 1: submit skipped; no POST.', output.getvalue())


if __name__ == '__main__':
    unittest.main()
