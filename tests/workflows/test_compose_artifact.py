"""S0 negative fixtures for the opt-in Compose archive check."""

import io
from pathlib import Path
import sys
import tempfile
import unittest
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from check_compose_artifact import HOST_OR_TEST_PACKAGES, COMPOSE_TEST_CLASSES, check_compose_artifact
import test_prepare_goland_release as fixtures


class ComposeArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.distributions = self.root / 'distributions'
        self.distributions.mkdir()

    def fixture(self, dependency='<depends>com.intellij.modules.compose</depends>', extra=None):
        target = fixtures.PluginReleaseTests.fixture(self)
        with ZipFile(target) as archive:
            with ZipFile(io.BytesIO(archive.read('reqws-goland/lib/reqws-goland.jar'))) as jar:
                entries = {name: jar.read(name) for name in jar.namelist()}
        entries['META-INF/plugin.xml'] = entries['META-INF/plugin.xml'].replace(
            b'</idea-plugin>', dependency.encode() + b'</idea-plugin>')
        if extra:
            entries[extra] = b'controlled negative fixture'
        stream = io.BytesIO()
        with ZipFile(stream, 'w') as jar:
            for name, content in entries.items():
                jar.writestr(name, content)
        with ZipFile(target, 'w') as archive:
            archive.writestr('reqws-goland/lib/renamed.jar', stream.getvalue())
        return target

    def test_accepts_own_classes_and_required_compose_module(self):
        self.assertEqual(check_compose_artifact(self.fixture(), '1.2.3')['classes'], 1)

    def test_rejects_missing_optional_and_duplicate_compose_module(self):
        for dependency in ['', '<depends optional="true">com.intellij.modules.compose</depends>',
                           '<depends>com.intellij.modules.compose</depends>' * 2]:
            with self.subTest(dependency=dependency), self.assertRaisesRegex(ValueError, 'Compose module'):
                check_compose_artifact(self.fixture(dependency), '1.2.3')

    def test_rejects_host_and_test_classes_even_in_renamed_jars(self):
        for package in HOST_OR_TEST_PACKAGES:
            with self.subTest(package=package), self.assertRaisesRegex(ValueError, 'Bundled host/test class'):
                check_compose_artifact(self.fixture(extra=package + 'Runtime.class'), '1.2.3')

    def test_rejects_multi_release_runtime_classes(self):
        with self.assertRaisesRegex(ValueError, 'Bundled host/test class'):
            check_compose_artifact(self.fixture(extra='META-INF/versions/25/kotlin/Runtime.class'), '1.2.3')

    def test_rejects_the_known_compose_control_and_component_test_classes(self):
        for name in COMPOSE_TEST_CLASSES:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Bundled host/test class'):
                check_compose_artifact(self.fixture(extra=name + '.class'), '1.2.3')

    def test_rejects_embedded_native_runtime(self):
        with self.assertRaisesRegex(ValueError, 'host native runtime'):
            check_compose_artifact(self.fixture(extra='libskiko-macos-arm64.dylib'), '1.2.3')

    def test_preserves_existing_version_gate(self):
        with self.assertRaisesRegex(ValueError, 'identity'):
            check_compose_artifact(self.fixture(), '9.9.9')


if __name__ == '__main__':
    unittest.main()
