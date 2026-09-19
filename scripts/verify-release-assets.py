#!/usr/bin/env python3
"""Verify the exact public release bytes without credentials or npm dependencies."""

import argparse
import base64
import datetime
import hashlib
import json
from pathlib import Path
import re
import time


def digest(filename, algorithm):
    result = hashlib.new(algorithm)
    with filename.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.digest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate update metadata key.')
        result[key] = value
    return result


def verify_release_assets(directory, version, stage=False):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('Invalid stable release version.')
    desktop = f'ReqWS-{version}-macos-arm64.zip'
    plugin = f'ReqWS-{version}-goland-plugin.zip'
    payloads = [desktop, plugin, 'latest-mac.yml']
    expected = set(payloads + ([name + '.sha256' for name in payloads] if stage else ['SHA256SUMS']))
    entries = list(directory.iterdir())
    if {entry.name for entry in entries} != expected:
        raise ValueError('Incomplete or unexpected release asset set.')
    if any(entry.is_symlink() or not entry.is_file() or entry.stat().st_size == 0 for entry in entries):
        raise ValueError('Release assets must be non-empty regular files.')
    metadata_file = directory / 'latest-mac.yml'
    if metadata_file.stat().st_size > 16384:
        raise ValueError('Update metadata is too large.')
    metadata = json.loads(metadata_file.read_text(), object_pairs_hook=unique_object)
    if not isinstance(metadata, dict) or set(metadata) != {'version', 'files', 'path', 'sha512', 'releaseDate'}:
        raise ValueError('Unexpected update metadata fields.')
    if metadata['version'] != version or metadata['path'] != desktop:
        raise ValueError('Update metadata version/path mismatch.')
    sha512 = base64.b64encode(digest(directory / desktop, 'sha512')).decode('ascii')
    expected_files = [{'url': desktop, 'sha512': sha512, 'size': (directory / desktop).stat().st_size}]
    if metadata['files'] != expected_files or metadata['sha512'] != sha512:
        raise ValueError('Update metadata does not match the final Desktop ZIP.')
    # bool is a subclass of int in Python, but is not a valid updater byte size.
    if type(metadata['files'][0]['size']) is not int or not 0 < metadata['files'][0]['size'] <= 2**53 - 1:
        raise ValueError('Invalid update ZIP size.')
    date = metadata['releaseDate']
    if not isinstance(date, str) or not date.endswith('Z'):
        raise ValueError('Update releaseDate must be UTC ISO-8601.')
    datetime.datetime.fromisoformat(date[:-1] + '+00:00')
    lines = [f'{digest(directory / name, "sha256").hex()}  {name}\n' for name in sorted(payloads)]
    if stage:
        for name, line in zip(sorted(payloads), lines):
            if (directory / (name + '.sha256')).read_text() != line:
                raise ValueError('Intermediate checksum does not match the release bytes.')
        with (directory / 'SHA256SUMS').open('x') as output:
            output.writelines(lines)
    elif (directory / 'SHA256SUMS').read_text() != ''.join(lines):
        raise ValueError('SHA256SUMS does not exactly match all three payloads.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--stage', action='store_true', help='Validate sidecars and exclusively create SHA256SUMS.')
    args = parser.parse_args()
    started = time.monotonic()
    print(f'[release][assets] status=started mode={"stage" if args.stage else "verify"}', flush=True)
    verify_release_assets(args.directory, args.version, args.stage)
    for name in [f'ReqWS-{args.version}-macos-arm64.zip', f'ReqWS-{args.version}-goland-plugin.zip', 'latest-mac.yml', 'SHA256SUMS']:
        print(f'[release][assets] verified={name} bytes={(args.directory / name).stat().st_size}', flush=True)
    print(f'[release][assets] status=success duration_ms={round((time.monotonic() - started) * 1000)}', flush=True)
    print('Exact release asset set, metadata and byte checksums verified.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, TypeError, KeyError, IndexError) as error:
        print('[release][assets] status=failed', flush=True)
        raise SystemExit(f'Release verification failed: {error}') from None
