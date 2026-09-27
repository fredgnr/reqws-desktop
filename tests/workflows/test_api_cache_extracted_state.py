"""Guard separately extracted Gradle entries, not just the main archive includes.

These are workflow contracts, not a substitute for a real producer post-save
log and a subsequent cross-ref restore. The action's pinned extractor defines
common entries independently of gradle-home-cache-includes.
"""

from copy import deepcopy
import unittest


class ExtractedStateBoundaryTests(unittest.TestCase):
    def setUp(self):
        from test_ci_cache_config import load_yaml
        self.action = load_yaml('.github/actions/setup-goland-api/action.yml')
        self.gradle = next(step for step in self.action['runs']['steps']
                           if 'setup-gradle@' in step.get('uses', ''))

    def require_boundary(self, step):
        self.assertEqual(step['uses'],
                         'gradle/actions/setup-gradle@0723195856401067f7a2779048b490ace7a47d7c')
        # An includes-only allowlist does not constrain the pinned extractor.
        excludes = set(step['with']['gradle-home-cache-excludes'].splitlines())
        self.assertTrue({'caches/transforms-*', 'caches/*/transforms', 'jdks'} <= excludes)
        # Excludes run after restore: do not download old extracted JBR entries
        # just to delete them. This internal switch is tied to the pin above.
        self.assertEqual(step.get('env', {}).get('GRADLE_BUILD_ACTION_SKIP_RESTORE'),
                         'transforms,java-toolchains')

    def test_extracted_state_is_excluded_from_new_saves_and_old_restores(self):
        self.require_boundary(self.gradle)
        self.assertEqual(self.gradle['with']['cache-read-only'],
                         "${{ steps.plan.outputs.shared-write != 'true' }}")
        self.assertNotIn('validate-wrappers', self.gradle['with'])
        self.assertNotIn('cache-disabled', self.gradle['with'])
        self.assertEqual(self.action['inputs']['cache-write']['default'], 'false')

    def test_includes_only_or_any_missing_exclusion_is_rejected(self):
        for missing in ('caches/transforms-*', 'caches/*/transforms', 'jdks'):
            with self.subTest(missing=missing):
                mutated = deepcopy(self.gradle)
                mutated['with']['gradle-home-cache-excludes'] = '\n'.join(
                    value for value in mutated['with']['gradle-home-cache-excludes'].splitlines()
                    if value != missing)
                with self.assertRaises(AssertionError):
                    self.require_boundary(mutated)

    def test_legacy_restore_guard_cannot_disappear_or_skip_shared_dependencies(self):
        for skip in ('', 'transforms', 'java-toolchains',
                     'transforms,java-toolchains,dependencies',
                     'transforms,java-toolchains,wrapper-zips'):
            with self.subTest(skip=skip):
                mutated = deepcopy(self.gradle)
                mutated['env'] = {'GRADLE_BUILD_ACTION_SKIP_RESTORE': skip}
                with self.assertRaises(AssertionError):
                    self.require_boundary(mutated)
        mutated = deepcopy(self.gradle)
        mutated['uses'] = 'gradle/actions/setup-gradle@main'
        with self.assertRaises(AssertionError):
            self.require_boundary(mutated)


if __name__ == '__main__':
    unittest.main()
