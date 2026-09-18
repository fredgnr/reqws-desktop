"""Regression checks for the privileged merged-PR cache cleanup workflow.

Uses only the Python standard library plus Bash/Git already required by CI.
No GitHub requests or real cache mutations are performed.
"""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/cleanup-pr-cache.yml').read_text(encoding='utf-8')


class CleanupPrCacheTests(unittest.TestCase):
    def test_only_merged_same_repository_non_base_branches_are_eligible(self):
        self.assertRegex(WORKFLOW, r'on:\n  pull_request_target:\n    types:\n      - closed\n')
        condition = re.search(r'    if: >-\n((?:      .+\n)+)', WORKFLOW)
        self.assertIsNotNone(condition)
        self.assertEqual(' '.join(condition.group(1).split()), (
            'github.event.pull_request.merged == true && '
            'github.event.pull_request.head.repo.full_name == github.repository && '
            'github.event.pull_request.head.ref != github.event.repository.default_branch && '
            'github.event.pull_request.head.ref != github.event.pull_request.base.ref'
        ))
        self.assertNotIn('workflow_dispatch:', WORKFLOW)
        self.assertNotIn('schedule:', WORKFLOW)

    def test_minimal_permissions_and_no_pr_checkout(self):
        self.assertIn('\npermissions: {}\n', WORKFLOW)
        self.assertIn('    permissions:\n      actions: write\n', WORKFLOW)
        actions = re.findall(r'^\s+uses: (.+)$', WORKFLOW, re.MULTILINE)
        self.assertEqual(actions, [
            'toshimaru/delete-action-cache@a12c3ca71338e3312989302c48edeb5ce66293b7',
        ])
        self.assertNotIn('secrets.', WORKFLOW)
        self.assertIn('cancel-in-progress: false', WORKFLOW)

    def test_both_cache_scopes_are_preserved(self):
        self.assertIn('repo: ${{ github.repository }}', WORKFLOW)
        self.assertIn('pr-number: ${{ github.event.pull_request.number }}', WORKFLOW)
        self.assertIn('head-ref: ${{ github.event.pull_request.head.ref }}', WORKFLOW)
        self.assertIn("limit: '1000'", WORKFLOW)
        self.assertNotRegex(WORKFLOW, r'(?m)^\s+branch:', msg='branch input skips PR caches')

    def validation_script(self):
        block = re.search(r'        run: \|\n((?:          [^\n]*\n)+)', WORKFLOW)
        self.assertIsNotNone(block)
        script = textwrap.dedent(block.group(1))
        self.assertNotIn('${{', script, 'Never interpolate event fields into shell source')
        self.assertIn('HEAD_REF: ${{ github.event.pull_request.head.ref }}', WORKFLOW)
        self.assertLess(WORKFLOW.index('name: Validate source branch name'),
                        WORKFLOW.index('uses: toshimaru/delete-action-cache@'))
        return script

    def run_validation(self, branch):
        with tempfile.TemporaryDirectory(prefix='reqws-cache-cleanup-test-') as directory:
            result = subprocess.run(
                ['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', self.validation_script()],
                env={**os.environ, 'HEAD_REF': branch},
                cwd=directory,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertFalse((Path(directory) / 'injected').exists())
            return result

    def test_normal_temporary_branch_names_are_accepted(self):
        for branch in ['feature/example', 'ci/cache-cleanup-20260914', 'agent/pr_7.fix', 'fix-42']:
            with self.subTest(branch=branch):
                result = self.run_validation(branch)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_shell_metacharacters_are_rejected_without_execution(self):
        for branch in ['$(touch injected)', '`touch injected`', 'fix";touch injected;#',
                       'fix\ntouch injected', 'fix;touch injected', "fix'quote", 'fix\\escape']:
            with self.subTest(branch=branch):
                result = self.run_validation(branch)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('no caches were deleted', result.stdout)

    def test_empty_non_ascii_and_malformed_refs_are_rejected(self):
        for branch in ['', '修复/cache', 'feature..bad', 'feature/.hidden', 'feature.lock',
                       'feature//bad', 'feature/', 'feature with space']:
            with self.subTest(branch=branch):
                result = self.run_validation(branch)
                self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
