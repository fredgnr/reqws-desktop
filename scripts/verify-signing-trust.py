"""Verify read-back of a single certificate's Code Signing denial, without logging trust data."""

import argparse
import plistlib
from pathlib import Path
import re

# Apple's Code Signing policy OID: 1.2.840.113635.100.1.16.
CODE_SIGNING_OID = bytes.fromhex('2a864886f763640110')


def verify_code_signing_denial(settings, identity):
    if not re.fullmatch('[A-F0-9]{40}', identity):
        raise ValueError('Invalid managed signing identity.')
    if not isinstance(settings, dict) or settings.get('trustVersion') != 1:
        raise ValueError('Unexpected trust settings version.')
    entries = settings.get('trustList')
    entry = entries.get(identity) if isinstance(entries, dict) else None
    expected = [{
        'kSecTrustSettingsPolicy': CODE_SIGNING_OID,
        'kSecTrustSettingsPolicyName': 'CodeSigning',
        'kSecTrustSettingsResult': 3,  # kSecTrustSettingsResultDeny
    }]
    if not isinstance(entry, dict) or entry.get('trustSettings') != expected:
        raise ValueError('Managed certificate does not have exactly the required Code Signing denial.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--settings', type=Path, required=True)
    parser.add_argument('--identity', required=True)
    args = parser.parse_args()
    if args.settings.is_symlink() or not args.settings.is_file() or args.settings.stat().st_size > 4 * 1024 * 1024:
        raise ValueError('Unsafe trust settings read-back file.')
    verify_code_signing_denial(plistlib.loads(args.settings.read_bytes()), args.identity)
    print('Managed Code Signing trust revocation verified.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, TypeError, plistlib.InvalidFileException):
        raise SystemExit('Signing trust revocation verification failed.') from None
