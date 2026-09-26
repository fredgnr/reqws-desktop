"""Exact-byte API exception policy; all fixtures are disposable JVM class/JAR bytes."""

from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import ide_api_exception as policy
import run_ide_verifier as runner
from ide_compatibility import digest


def u2(value):
    return struct.pack('>H', value)


def u4(value):
    return struct.pack('>I', value)


def class_fixture(*, wrapper=policy.WRAPPER, caller=policy.CALLER, descriptor=policy.CALLER_DESCRIPTOR,
                  member=policy.MEMBER, member_descriptor=policy.DESCRIPTOR, private=True,
                  casts=1, calls=1, opcode=185, handle=False, field_type=None, other_method=False,
                  extra_string=None, internal=True, bootstrap=False):
    constants = []

    def constant(tag, value):
        constants.append(bytes([tag]) + value)
        return len(constants)

    def text(value):
        encoded = value.encode()
        return constant(1, u2(len(encoded)) + encoded)

    def cls(value):
        return constant(7, u2(text(value)))

    own, superclass = cls(wrapper), cls('java/lang/Object')
    method_name, signature, code_name = text(caller), text(descriptor), text('Code')
    code = b'\x01\xb0'  # aconst_null, areturn
    if internal:
        owner = cls(policy.OWNER)
        name_and_type = constant(12, u2(text(member)) + u2(text(member_descriptor)))
        method = constant(11, u2(owner) + u2(name_and_type))
        message = constant(8, u2(text(policy.CAST_MESSAGE)))
        if handle:
            constant(15, b'\x09' + u2(method))
        code = b'\x2b' + b'\x12' + bytes([message]) + b'\x57'
        code += (b'\xc0' + u2(owner)) * casts
        code += b'\x2c'
        code += (bytes([opcode]) + u2(method) + (b'\x02\x00' if opcode == 185 else b'')) * calls
        code += b'\xb0'
    if extra_string:
        constant(8, u2(text(extra_string)))
    attributes = b''
    if bootstrap:
        attribute_name = text('BootstrapMethods')
        body = u2(1) + u2(1) + u2(1) + u2(owner)
        attributes = u2(attribute_name) + u4(len(body)) + body
    fields = b''
    if field_type:
        fields = u2(2) + u2(text('leaked')) + u2(text(field_type)) + u2(0)
    code_body = u2(4) + u2(3) + u4(len(code)) + code + u2(0) + u2(0)
    method_body = (u2(2 if private else 1) + u2(method_name) + u2(signature) + u2(1)
                   + u2(code_name) + u4(len(code_body)) + code_body)
    methods = method_body
    if other_method:
        methods += (u2(2) + u2(text('other')) + u2(signature) + u2(1)
                    + u2(code_name) + u4(len(code_body)) + code_body)
    return (b'\xca\xfe\xba\xbe' + u2(0) + u2(69) + u2(len(constants) + 1) + b''.join(constants)
            + u2(0x31) + u2(own) + u2(superclass) + u2(0) + u2(bool(fields)) + fields
            + u2(2 if other_method else 1) + methods + u2(bool(attributes)) + attributes)


def jar_bytes(classes):
    payload = io.BytesIO()
    with ZipFile(payload, 'w') as jar:
        for name, data in classes:
            jar.writestr(name + '.class', data)
    return payload.getvalue()


def write_archive(path, classes, extra_jars=()):
    with ZipFile(path, 'w') as archive:
        archive.writestr('reqws/lib/reqws.jar', jar_bytes(classes))
        for name, data in extra_jars:
            archive.writestr('reqws/lib/' + name, data)


class BytecodePolicyTests(unittest.TestCase):
    def test_exact_private_wrapper_and_historical_candidate_without_api(self):
        self.assertEqual(policy.audit_class(class_fixture(), policy.WRAPPER + '.class'), (policy.WRAPPER, True))
        self.assertEqual(policy.audit_class(class_fixture(internal=False), policy.WRAPPER + '.class'), (policy.WRAPPER, False))

    def test_same_api_in_other_class_method_or_signature_is_not_approved(self):
        cases = [{'wrapper': policy.WRAPPER + 'Other'}, {'caller': 'await'}, {'private': False},
                 {'descriptor': '(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'}, {'other_method': True}]
        for kwargs in cases:
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                policy.audit_class(class_fixture(**kwargs), kwargs.get('wrapper', policy.WRAPPER) + '.class')

    def test_extra_cast_call_wrong_opcode_member_descriptor_and_handles_fail(self):
        cases = [{'casts': 2}, {'calls': 2}, {'casts': 0}, {'calls': 0}, {'opcode': 182},
                 {'member': policy.MEMBER + 'Other'}, {'member_descriptor': '()V'}, {'handle': True}, {'bootstrap': True}]
        for kwargs in cases:
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                policy.audit_class(class_fixture(**kwargs), policy.WRAPPER + '.class')

    def test_field_and_unreviewed_string_cannot_hide_a_type_usage(self):
        cases = [{'field_type': 'L' + policy.OWNER + ';'}, {'extra_string': policy.OWNER},
                 {'extra_string': 'unknown ' + policy.OWNER}, {'extra_string': policy.MEMBER}]
        for kwargs in cases:
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                policy.audit_class(class_fixture(**kwargs), policy.WRAPPER + '.class')

    def test_corrupt_class_path_and_truncation_fail_closed(self):
        with self.assertRaises(ValueError):
            policy.audit_class(class_fixture(), 'wrong/Name.class')
        data = class_fixture()
        for length in (0, 3, 10, len(data) - 1):
            with self.subTest(length=length), self.assertRaises(ValueError):
                policy.audit_class(data[:length], policy.WRAPPER + '.class')
        with self.assertRaises(ValueError):
            policy.audit_class(data + b'junk', policy.WRAPPER + '.class')

    def test_real_nested_zip_bytes_are_checked_and_duplicate_or_unknown_classes_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'plugin.zip'
            classes = [(policy.WRAPPER, class_fixture())]
            write_archive(path, classes)
            self.assertTrue(policy.audit_archive(path)['present'])
            write_archive(path, classes, [('other.jar', jar_bytes(classes))])
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                policy.audit_archive(path)
            write_archive(path, classes, [('other.jar', jar_bytes([
                ('other/Caller', class_fixture(wrapper='other/Caller'))]))])
            with self.assertRaisesRegex(ValueError, 'outside'):
                policy.audit_archive(path)

    def test_source_reference_is_restricted_to_one_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrapper = root / 'kotlin' / (policy.WRAPPER + '.kt')
            wrapper.parent.mkdir(parents=True)
            wrapper.write_text(policy.OWNER)
            policy.audit_sources(root)
            (root / 'Other.kt').write_text(policy.MEMBER)
            with self.assertRaisesRegex(ValueError, 'outside'):
                policy.audit_sources(root)

    def test_runner_audits_real_candidate_before_starting_gradle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive, snapshot = root / 'plugin.zip', root / 'snapshot.json'
            write_archive(archive, [('other/Caller', class_fixture(wrapper='other/Caller'))])
            snapshot.write_text('{}')
            target = {'id': 'GO-262.8665.270', 'version': '2026.2', 'build': '262.8665.270'}
            # Structural metadata is outside this test; the candidate JAR bytes
            # and policy audit are real and are deliberately not mocked.
            with patch.object(runner, 'validate_plugin', return_value={'sha256': digest(archive)}), \
                    patch.object(runner, 'run_logged_process') as process, redirect_stdout(io.StringIO()):
                result = runner.run_target(snapshot, target, archive, '1.2.3', root / 'results')
            self.assertEqual(result['status'], 'incompatible')
            process.assert_not_called()


class GradleFailureTests(unittest.TestCase):
    CATEGORIES = {'INTERNAL_API_USAGES', 'EXPERIMENTAL_API_USAGES'}
    LOG = """> Task :verifyPlugin FAILED
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':verifyPlugin'.
> Verification failed with [INTERNAL_API_USAGES, EXPERIMENTAL_API_USAGES] problems. See the report at: file:///fixture/report.html
BUILD FAILED in 10s
"""

    def test_exact_known_failure_accepts_only_its_observed_categories(self):
        self.assertTrue(policy.approved_gradle_failure(self.LOG, 1, self.CATEGORIES))
        self.assertFalse(policy.approved_gradle_failure(self.LOG, 0, self.CATEGORIES))
        self.assertFalse(policy.approved_gradle_failure(self.LOG, 2, self.CATEGORIES))
        self.assertFalse(policy.approved_gradle_failure(self.LOG, -15, self.CATEGORIES))
        self.assertFalse(policy.approved_gradle_failure(self.LOG, 1, {'INTERNAL_API_USAGES'}))

    def test_additional_or_missing_failure_information_cannot_pass(self):
        for log in [self.LOG + "Execution failed for task ':other'.\n", self.LOG + '> Task :other FAILED\n',
                    self.LOG.replace('INTERNAL_API_USAGES,', 'MISSING_DEPENDENCIES,'),
                    self.LOG.replace('BUILD FAILED in 10s', ''), self.LOG.replace('> Task :verifyPlugin FAILED', ''),
                    self.LOG.replace('with an exception.', 'with 2 failures.'), self.LOG + self.LOG]:
            with self.subTest(log=log):
                self.assertFalse(policy.approved_gradle_failure(log, 1, self.CATEGORIES))


class BaselineTests(unittest.TestCase):
    def test_baseline_is_exactly_the_compile_and_fixed_representative_targets(self):
        snapshot = runner.baseline_snapshot()
        self.assertEqual(snapshot['mode'], 'baseline')
        self.assertNotIn('catalog', snapshot)
        self.assertEqual({target['version'] for target in snapshot['targets']}, {'2026.2', '2026.2.1.1'})
        self.assertEqual({target['build'] for target in snapshot['targets']}, {'262.8665.270', '262.9437.286'})
        self.assertTrue(all(target['required'] for target in snapshot['targets']))

    def test_baseline_snapshot_omission_extra_target_or_changed_policy_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'baseline.json'
            original = runner.baseline_snapshot()
            path.write_text(json.dumps(original))
            self.assertEqual(runner.load_verification_snapshot(path), original)
            for change in ('omit', 'extra', 'policy', 'catalog'):
                snapshot = json.loads(json.dumps(original))
                if change == 'omit': snapshot['targets'].pop()
                if change == 'extra': snapshot['targets'].append(snapshot['targets'][0])
                if change == 'policy': snapshot['policy']['minimumPlatformBranch'] = '263'
                if change == 'catalog': snapshot['catalog'] = {'GO': []}
                path.write_text(json.dumps(snapshot))
                with self.subTest(change=change), self.assertRaises(ValueError):
                    runner.load_verification_snapshot(path)

    def test_baseline_cli_runs_both_targets_then_requires_bound_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / 'candidate.zip'
            archive.write_bytes(b'candidate identity fixture')
            output = root / 'evidence'
            seen = []

            def run(snapshot, target, archive, version, directory, historical):
                seen.append(target['id'])
                result = {'target': target, 'candidateSha256': digest(archive), 'snapshotSha256': digest(snapshot),
                          'status': 'passed'}
                runner.write_json(directory / 'result.json', result)
                return result

            argv = ['run_ide_verifier.py', '--baseline', '--archive', str(archive), '--version', '1.2.3',
                    '--output', str(output)]
            with patch.object(sys, 'argv', argv), patch.object(runner, 'run_target', side_effect=run), \
                    redirect_stdout(io.StringIO()):
                runner.main()
            self.assertEqual(seen, [target['id'] for target in runner.baseline_snapshot()['targets']])
            self.assertTrue((output / 'baseline-targets.json').is_file())

    def test_failed_baseline_target_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            argv = ['run_ide_verifier.py', '--baseline', '--archive', str(root / 'candidate.zip'),
                    '--version', '1.2.3', '--output', str(root / 'evidence')]
            with patch.object(sys, 'argv', argv), \
                    patch.object(runner, 'run_target', return_value={'status': 'incompatible'}), \
                    self.assertRaises(SystemExit) as failure:
                runner.main()
            self.assertEqual(failure.exception.code, 1)


# Literal report lines observed in the 2026-09-26 GO 262.8665.270 probe with
# Verifier 1.410. These are diagnostic fixtures, not candidate pass evidence.
OBSERVED_INTERNAL = '''Internal method com.intellij.platform.backend.workspace.impl.WorkspaceModelInternal.awaitSynchronizationWithJpsModel(kotlin.coroutines.Continuation arg0) : java.lang.Object is invoked in com.reqws.goland.loading.model.InitialJpsSynchronization.awaitPlatform(Project, Continuation) : Object. This method is marked with @org.jetbrains.annotations.ApiStatus.Internal annotation or @com.intellij.openapi.util.IntellijInternalApi annotation and indicates that the method is not supposed to be used in client code.
Internal interface com.intellij.platform.backend.workspace.impl.WorkspaceModelInternal is referenced in com.reqws.goland.loading.model.InitialJpsSynchronization.awaitPlatform(Project, Continuation) : Object. This interface is marked with @org.jetbrains.annotations.ApiStatus.Internal annotation or @com.intellij.openapi.util.IntellijInternalApi annotation and indicates that the class is not supposed to be used in client code.
'''
OBSERVED_EXPERIMENTAL = 'Experimental API method com.intellij.platform.backend.workspace.impl.WorkspaceModelInternal.awaitSynchronizationWithJpsModel(kotlin.coroutines.Continuation arg0) : java.lang.Object is invoked in com.reqws.goland.loading.model.InitialJpsSynchronization.awaitPlatform(Project, Continuation) : Object. This method can be changed in a future release leading to incompatibilities\n'
OBSERVED_VERDICT = 'Compatible. 1 usage of experimental API. 2 usages of internal API\n'


class ReportPolicyTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.target = runner.baseline_snapshot()['targets'][0]
        self.directory = self.root / self.target['id'] / 'plugins/com.reqws.workspace/1.2.3'
        self.directory.mkdir(parents=True)
        self.internal = self.directory / 'internal-api-usages.txt'
        self.experimental = self.directory / 'experimental-api-usages.txt'
        self.verdict = self.directory / 'verification-verdict.txt'
        self.internal.write_text(OBSERVED_INTERNAL)
        self.experimental.write_text(OBSERVED_EXPERIMENTAL)
        self.verdict.write_text(OBSERVED_VERDICT)
        self.audit = {'present': True, 'id': policy.EXCEPTION_ID}

    def classify(self, code=1, log=GradleFailureTests.LOG, audit=True):
        return runner.classify(self.root, self.target, '1.2.3', code, log, self.audit if audit else None)[0]

    def test_observed_reports_allow_exact_exception_without_rewriting_evidence(self):
        before = {path.name: path.read_bytes() for path in self.directory.iterdir()}
        self.assertEqual(self.classify(), 'passed')
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.directory.iterdir()})
        self.assertEqual(self.classify(audit=False), 'incompatible')

    def test_unknown_internal_caller_member_type_signature_or_duplicate_is_rejected(self):
        for content in [OBSERVED_INTERNAL.replace('.awaitPlatform(', '.other('),
                        OBSERVED_INTERNAL.replace('WorkspaceModelInternal', 'WorkspaceModelInternalOther'),
                        OBSERVED_INTERNAL.replace('awaitSynchronizationWithJpsModel(', 'otherMethod('),
                        OBSERVED_INTERNAL.replace('Continuation arg0', 'Continuation arg0, int arg1'),
                        OBSERVED_INTERNAL + OBSERVED_INTERNAL.splitlines()[0] + '\n',
                        OBSERVED_INTERNAL + 'unknown usage\n']:
            with self.subTest(content=content):
                self.internal.write_text(content)
                self.assertEqual(self.classify(), 'incompatible')

    def test_other_experimental_and_every_other_restricted_category_remain_blocking(self):
        self.experimental.write_text(OBSERVED_EXPERIMENTAL.replace('awaitSynchronizationWithJpsModel(', 'otherMethod('))
        self.assertEqual(self.classify(), 'incompatible')
        self.experimental.write_text(OBSERVED_EXPERIMENTAL)
        for name in runner.BLOCKING_REPORTS:
            if name in policy.APPROVED_REPORTS:
                continue
            with self.subTest(report=name):
                path = self.directory / name
                path.write_text('Unknown prohibited usage')
                self.assertEqual(self.classify(), 'incompatible')
                path.unlink()

    def test_missing_reports_and_mismatched_verdict_counts_never_pass(self):
        self.experimental.unlink()
        self.assertEqual(self.classify(), 'incompatible')
        self.internal.unlink()
        self.assertEqual(self.classify(code=0, log=''), 'infrastructure-blocked')
        self.internal.write_text(OBSERVED_INTERNAL)
        self.experimental.write_text(OBSERVED_EXPERIMENTAL)
        self.verdict.write_text(OBSERVED_VERDICT.replace('2 usages', '3 usages'))
        self.assertEqual(self.classify(), 'infrastructure-blocked')

    def test_extra_gradle_category_and_infrastructure_exit_remain_failures(self):
        for code in (0, 2, 137, -15):
            with self.subTest(code=code):
                self.assertEqual(self.classify(code=code), 'infrastructure-blocked')
        self.assertEqual(self.classify(log=GradleFailureTests.LOG.replace(
            'INTERNAL_API_USAGES,', 'MISSING_DEPENDENCIES, INTERNAL_API_USAGES,')), 'incompatible')
        self.assertEqual(self.classify(log=GradleFailureTests.LOG + "Execution failed for task ':other'.\n"),
                         'infrastructure-blocked')

    def test_clean_historical_reports_do_not_require_the_new_wrapper(self):
        self.internal.unlink()
        self.experimental.unlink()
        self.verdict.write_text('Compatible')
        self.assertEqual(self.classify(code=0, log='', audit=False), 'passed')


if __name__ == '__main__':
    unittest.main()
