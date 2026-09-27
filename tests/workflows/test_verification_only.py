"""Guard the immutable-ZIP fast path without relaxing the retained build gates."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
PLUGIN = ROOT / 'integrations/goland'
LEVELS = {
    'COMPATIBILITY_PROBLEMS', 'INVALID_PLUGIN', 'MISSING_DEPENDENCIES',
    'INTERNAL_API_USAGES', 'EXPERIMENTAL_API_USAGES', 'OVERRIDE_ONLY_API_USAGES',
    'NON_EXTENDABLE_API_USAGES',
}


class VerificationOnlyTests(unittest.TestCase):
    def test_failure_levels_cannot_drift_from_the_retained_build(self):
        for name in ('build.gradle.kts', 'verify.gradle.kts'):
            with self.subTest(script=name):
                text = (PLUGIN / name).read_text()
                levels = re.findall(r'VerifyPluginTask\.FailureLevel\.([A-Z_]+)', text)
                self.assertEqual(set(levels), LEVELS)
                self.assertEqual(len(levels), len(LEVELS))

    def test_selection_is_opt_in_and_cannot_run_other_tasks(self):
        settings = (PLUGIN / 'settings.gradle.kts').read_text()
        self.assertIn('verificationOnly == null || verificationOnly == "true"', settings)
        self.assertIn('if (verificationOnly == "true")', settings)
        self.assertIn('gradle.startParameter.taskNames == listOf("verifyPlugin")', settings)
        self.assertEqual(settings.count('rootProject.buildFileName ='), 1)
        self.assertIn('rootProject.buildFileName = "verify.gradle.kts"', settings)
        for key in ('reqwsPluginArchive', 'reqwsVerificationSnapshot', 'reqwsVerificationTarget',
                    'reqwsVerificationReports', 'releaseVersion'):
            self.assertIn('"' + key + '"', settings)
        self.assertIn('require(!providers.gradleProperty(key).orNull.isNullOrBlank())', settings)

    def test_fast_path_consumes_exact_inputs_and_original_cli(self):
        text = (PLUGIN / 'verify.gradle.kts').read_text()
        for fragment in (
            'id("org.jetbrains.intellij.platform")',
            'pluginVerifier(policy("pluginVerifierVersion"))',
            'snapshot["policy"] == compatibilityPolicy.entries.associate',
            'targets.map { it["id"] }.toSet().size == targets.size',
            'targets.single { it["id"] == requestedTarget }',
            'archiveFile.set(candidateArchive)',
            'verificationReportsDirectory.set(reports)',
            'info["productCode"] == target["product"]',
            'info["buildNumber"] == target["build"]',
            'info["version"] == target["version"]',
            'useInstaller = false', 'offline = false',
        ):
            self.assertIn(fragment, text)
        for forbidden in ('org.jetbrains.kotlin.jvm', 'org.jetbrains.kotlin.plugin.compose',
                          'composeUI()', 'testFramework(', 'ignoreFailures', 'ignoredProblemsFile',
                          'externalPrefixes', 'freeArgs', 'FailureLevel.NONE', 'onlyIf', 'enabled = false'):
            self.assertNotIn(forbidden, text)

    def test_baseline_jbr_and_public_runtime_identity_are_preserved(self):
        text = (PLUGIN / 'verify.gradle.kts').read_text()
        self.assertIn('jetbrainsRuntimeExplicit(runtimePolicy("runtimeArchive"))', text)
        self.assertNotIn('goland(policy("compileIdeVersion"))', text)
        self.assertIn('require(runtimePolicy(key) == policy(key))', text)
        self.assertIn('"compileIdeProduct", "compileIdeVersion", "compileIdeBuild"', text)
        self.assertIn('require(value == runtimePolicy(key))', text)
        self.assertIn('requireNotNull(runtimeRelease.getProperty(it))', text)
        self.assertIn('val runtime = runtimeDirectory.get().asFile', text)
        self.assertIn('"JAVA_VERSION", "JAVA_RUNTIME_VERSION", "IMPLEMENTOR", "OS_ARCH"', text)
        self.assertIn('resolve("runtime.json")', text)
        self.assertNotIn('System.getenv()', text)
        self.assertNotIn('runtimeDirectory.set(', text)

    def test_runtime_is_pinned_and_base_sdk_is_the_same_frozen_target(self):
        text = (PLUGIN / 'verify.gradle.kts').read_text()
        entries = [line.split('=', 1) for line in (PLUGIN / 'verifier-runtime.properties').read_text().splitlines()
                   if line and not line.startswith('#')]
        pin = dict(entries)
        self.assertEqual(len(pin), len(entries))
        self.assertEqual(pin, {
            'compileIdeProduct': 'GO', 'compileIdeVersion': '2026.2', 'compileIdeBuild': '262.8665.270',
            'runtimeArchive': 'jbr_jcef-25.0.3-linux-x64-b508.16',
            'JAVA_VERSION': '25.0.3', 'JAVA_RUNTIME_VERSION': '25.0.3+9-b508.16',
            'IMPLEMENTOR': 'JetBrains s.r.o.', 'OS_ARCH': 'x86_64',
        })
        # Both the task's platform input and the Verifier target use one SDK.
        self.assertEqual(text.count('create(IntelliJPlatformType.GoLand, target["version"] as String)'), 2)
        self.assertEqual(text.count('useInstaller = false'), 2)
        self.assertIn('System.getProperty("os.name") == "Linux"', text)
        self.assertIn('System.getProperty("os.arch") in listOf("amd64", "x86_64")', text)
        for forbidden in ('jetbrainsRuntime()', 'JAVA_HOME', 'runtimeLauncher.set', 'javaLauncher.set'):
            self.assertNotIn(forbidden, text)

    def test_only_api_execution_enables_the_alternative_script(self):
        # Use the existing parser to retain the workflow's real YAML semantics.
        from test_ci_performance import load_workflow
        enabled = []
        for path in sorted((ROOT / '.github/workflows').glob('*.yml')):
            workflow = load_workflow(path.name)
            self.assertNotIn('ORG_GRADLE_PROJECT_reqwsVerificationOnly', workflow.get('env', {}))
            for name, job in workflow['jobs'].items():
                self.assertNotIn('ORG_GRADLE_PROJECT_reqwsVerificationOnly', job.get('env', {}))
                for step in job.get('steps', []):
                    value = step.get('env', {}).get('ORG_GRADLE_PROJECT_reqwsVerificationOnly')
                    if value is not None:
                        enabled.append((path.name, name, step.get('name'), value))
                        self.assertNotIn('continue-on-error', step)
                        self.assertIn('run_ide_verifier.py --snapshot', step['run'])
        self.assertEqual(enabled, [('goland-verification.yml', 'api',
                                  'Verify exact candidate and frozen target', 'true')])


if __name__ == '__main__':
    unittest.main()
