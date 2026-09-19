"""Validate Code Signing trust revocation from disposable property lists."""

import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_signing_trust', ROOT / 'scripts/verify-signing-trust.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SigningTrustTests(unittest.TestCase):
    def setUp(self):
        self.identity = 'A' * 40
        self.settings = {'trustVersion': 1, 'trustList': {self.identity: {'trustSettings': [{
            'kSecTrustSettingsPolicy': MODULE.CODE_SIGNING_OID,
            'kSecTrustSettingsPolicyName': 'CodeSigning',
            'kSecTrustSettingsResult': 3,
        }]}}}

    def test_accept_only_code_signing_denial_for_the_managed_identity(self):
        self.settings['trustList']['B' * 40] = {'unrelated': 'preserved'}
        MODULE.verify_code_signing_denial(self.settings, self.identity)
        with self.assertRaises(ValueError):
            MODULE.verify_code_signing_denial(self.settings, 'C' * 40)

    def test_reject_trust_grants_other_policies_exceptions_and_extra_rules(self):
        for change in [
            {'kSecTrustSettingsResult': 1}, {'kSecTrustSettingsResult': 4},
            {'kSecTrustSettingsPolicyName': 'ssl'}, {'kSecTrustSettingsPolicy': b'wrong'},
            {'kSecTrustSettingsAllowedError': -1},
        ]:
            with self.subTest(change=change):
                data = copy.deepcopy(self.settings)
                data['trustList'][self.identity]['trustSettings'][0].update(change)
                with self.assertRaises(ValueError):
                    MODULE.verify_code_signing_denial(data, self.identity)
        for rules in [[], [{}], self.settings['trustList'][self.identity]['trustSettings'] * 2]:
            data = copy.deepcopy(self.settings)
            data['trustList'][self.identity]['trustSettings'] = rules
            with self.assertRaises(ValueError):
                MODULE.verify_code_signing_denial(data, self.identity)

    def test_reject_malformed_readback(self):
        for data in [None, [], {}, {'trustVersion': 2}, {'trustVersion': 1, 'trustList': []}]:
            with self.assertRaises(ValueError):
                MODULE.verify_code_signing_denial(data, self.identity)


if __name__ == '__main__':
    unittest.main()
