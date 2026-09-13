"""Release ZIP regression tests use only disposable, generated fixtures."""

import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from zipfile import BadZipFile, ZipFile

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/prepare-goland-release.py"
SPEC = importlib.util.spec_from_file_location("prepare_goland_release", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PluginReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.distributions = self.root / "distributions"
        self.distributions.mkdir()
        self.output = self.root / "output"

    def fixture(self, version="1.2.3", plugin_id="com.reqws.workspace", descriptor=True):
        jar_bytes = io.BytesIO()
        with ZipFile(jar_bytes, "w") as jar:
            jar.writestr("com/reqws/Example.class", b"fixture")
            if descriptor:
                jar.writestr("META-INF/plugin.xml", f"<idea-plugin><id>{plugin_id}</id><version>{version}</version></idea-plugin>")
        target = self.distributions / "reqws-goland.zip"
        with ZipFile(target, "w") as archive:
            archive.writestr("reqws-goland/lib/reqws-goland.jar", jar_bytes.getvalue())
        return target

    def prepare(self):
        return MODULE.prepare_release(self.distributions, self.output, "1.2.3")

    def test_preserves_verified_bytes_and_writes_matching_checksum(self):
        original = self.fixture().read_bytes()
        target = self.prepare()
        self.assertEqual(target.name, "ReqWS-1.2.3-goland-plugin.zip")
        self.assertEqual(target.read_bytes(), original)
        self.assertEqual(target.with_suffix(".zip.sha256").read_text(), f"{hashlib.sha256(original).hexdigest()}  {target.name}\n")

    def test_rejects_missing_zip(self):
        with self.assertRaises(ValueError):
            self.prepare()

    def test_rejects_multiple_zips(self):
        self.fixture()
        (self.distributions / "stale.zip").write_bytes(b"stale")
        with self.assertRaises(ValueError):
            self.prepare()

    def test_rejects_wrong_id_or_version(self):
        for version, plugin_id in [("1.2.2", "com.reqws.workspace"), ("1.2.3", "other.plugin")]:
            with self.subTest(version=version, plugin_id=plugin_id):
                self.fixture(version=version, plugin_id=plugin_id)
                with self.assertRaises(ValueError):
                    self.prepare()
        self.assertFalse(self.output.exists())

    def test_rejects_missing_descriptor(self):
        self.fixture(descriptor=False)
        with self.assertRaises(ValueError):
            self.prepare()

    def test_rejects_additional_descriptor(self):
        target = self.fixture()
        with ZipFile(target, "a") as archive:
            jar = archive.read("reqws-goland/lib/reqws-goland.jar")
            archive.writestr("reqws-goland/lib/duplicate.jar", jar)
        with self.assertRaises(ValueError):
            self.prepare()

    def test_rejects_empty_or_corrupt_zip(self):
        target = self.distributions / "reqws-goland.zip"
        with ZipFile(target, "w"):
            pass
        with self.assertRaises(ValueError):
            self.prepare()
        target.write_bytes(b"not a ZIP")
        with self.assertRaises(BadZipFile):
            self.prepare()

    def test_rejects_unsafe_path_and_multiple_roots(self):
        for name in ["../escape", "/absolute", "other-root/file", "reqws-goland\\escape"]:
            with self.subTest(name=name):
                target = self.fixture()
                with ZipFile(target, "a") as archive:
                    archive.writestr(name, b"unexpected")
                with self.assertRaises(ValueError):
                    self.prepare()

    def test_rejects_symlink_candidate(self):
        original = self.fixture()
        saved = self.root / "outside.zip"
        original.rename(saved)
        original.symlink_to(saved)
        with self.assertRaises(ValueError):
            self.prepare()

    def test_rejects_invalid_release_version(self):
        self.fixture()
        for version in ["v1.2.3", "01.2.3", "1.2", "1.2.3-beta", "../1.2.3"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                MODULE.prepare_release(self.distributions, self.output, version)

    def test_refuses_to_overwrite_staged_files(self):
        self.fixture()
        target = self.prepare()
        original = target.read_bytes()
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual(target.read_bytes(), original)

    def test_cli_fails_closed(self):
        self.fixture(version="1.2.2")
        result = subprocess.run([sys.executable, str(SCRIPT), "--version", "1.2.3", "--distributions", str(self.distributions), "--output", str(self.output)], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn("embedded version", result.stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
