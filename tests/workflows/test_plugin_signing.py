"""Real ZIP Signer tests with disposable encrypted identities; no production credentials."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import plugin_signing as signing
from plugin_release import validate_plugin
import test_prepare_goland_release as fixtures


class RealPluginSigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='reqws-signing-test-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name).resolve()
        cls.signer = Path(os.environ.get('REQWS_TEST_ZIP_SIGNER', cls.directory / 'signer.jar'))
        if not cls.signer.exists():
            signing.download_signer(cls.signer)
        cls.password = 'disposable-test-password'
        cls.env = {**os.environ, 'REQWS_TEST_PASSWORD': cls.password}
        cls.key = cls.directory / 'private.pem'
        cls.cert = cls.directory / 'chain.crt'
        cls.crypto(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-aes-256-cbc',
                    '-pass', 'env:REQWS_TEST_PASSWORD', '-out', str(cls.key)])
        cls.crypto(['req', '-new', '-x509', '-sha256', '-days', '2', '-key', str(cls.key),
                    '-passin', 'env:REQWS_TEST_PASSWORD', '-out', str(cls.cert), '-subj', '/CN=ReqWS Disposable Test'])
        builder = fixtures.PluginReleaseTests(); builder.distributions = cls.directory
        cls.archive = builder.fixture()
        cls.signed = cls.directory / 'signed.zip'
        signing.run_checked([signing.java(), '-jar', cls.signer, 'sign', '-in', cls.archive,
                             '-out', cls.signed, '-cert-file', cls.cert, '-key-file', cls.key,
                             '-key-pass', cls.password])

    @classmethod
    def crypto(cls, arguments):
        signing.run_checked([signing.openssl(), *arguments], env=cls.env)

    def test_real_signature_metadata_and_unchanged_staging(self):
        signing.preflight(self.key, self.cert, self.password)
        signing.verify_signature(self.signed, self.cert, self.signer)
        self.assertEqual(validate_plugin(self.signed, '1.2.3')['xmlId'], 'com.reqws.workspace')
        output = self.directory / 'staged'
        target = fixtures.MODULE.prepare_release(self.signed, output, '1.2.3')
        self.assertEqual(target.read_bytes(), self.signed.read_bytes())

    def test_missing_key_wrong_password_and_unsigned_rejected(self):
        for key, password in [(None, self.password), (self.key, ''), (self.key, 'wrong-password')]:
            with self.assertRaises(ValueError):
                signing.preflight(key, self.cert, password)
        with self.assertRaises(ValueError):
            signing.verify_signature(self.archive, self.cert, self.signer)

    def test_mismatched_certificate_and_tampering_rejected(self):
        other_key = self.directory / 'other.pem'; other_cert = self.directory / 'other.crt'
        self.crypto(['req', '-new', '-x509', '-newkey', 'rsa:2048', '-days', '2', '-noenc',
                     '-keyout', str(other_key), '-out', str(other_cert), '-subj', '/CN=Other Test Identity'])
        with self.assertRaises(ValueError): signing.preflight(self.key, other_cert, self.password)
        with self.assertRaises(ValueError): signing.verify_signature(self.signed, other_cert, self.signer)
        tampered = self.directory / 'tampered.zip'
        shutil.copyfile(self.signed, tampered)
        with ZipFile(tampered, 'a') as archive:
            archive.writestr('reqws-goland/tampered.txt', 'changed after signing')
        with self.assertRaises(ValueError): signing.verify_signature(tampered, self.cert, self.signer)

    def test_expired_certificate_rejected(self):
        expired = self.directory / 'expired.crt'
        request = self.directory / 'request.pem'
        self.crypto(['req', '-new', '-key', str(self.key), '-passin', 'env:REQWS_TEST_PASSWORD',
                     '-out', str(request), '-subj', '/CN=ReqWS Expired Test'])
        (self.directory / 'index').write_text('')
        (self.directory / 'serial').write_text('01\n')
        ca_config = self.directory / 'ca.cnf'
        ca_config.write_text('[ca]\ndefault_ca=test\n[test]\n' +
                             f'database={self.directory}/index\nserial={self.directory}/serial\n' +
                             f'new_certs_dir={self.directory}\ncertificate={self.cert}\n' +
                             'default_md=sha256\npolicy=policy\n[policy]\ncommonName=supplied\n')
        self.crypto(['ca', '-batch', '-selfsign', '-config', str(ca_config), '-keyfile', str(self.key),
                     '-passin', 'env:REQWS_TEST_PASSWORD', '-in', str(request), '-startdate', '20000101000000Z',
                     '-enddate', '20000102000000Z', '-notext', '-out', str(expired)])
        with self.assertRaises(ValueError): signing.preflight(self.key, expired, self.password)


if __name__ == '__main__':
    unittest.main()
