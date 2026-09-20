"""Validate and stage one explicitly selected plugin ZIP without changing its bytes."""

import argparse
import hashlib
from pathlib import Path
import sys

from plugin_release import validate_plugin


def prepare_release(source: Path, output: Path, version: str) -> Path:
    metadata = validate_plugin(source, version)
    output = output.absolute()
    if any(parent.is_symlink() for parent in (output, *output.parents)):
        raise ValueError('Output must not traverse a symlink')
    output.mkdir(parents=True, exist_ok=True)
    target = output / f'ReqWS-{version}-goland-plugin.zip'
    checksum = target.with_suffix('.zip.sha256')
    if target.exists() or checksum.exists():
        raise ValueError('Refusing to replace existing staged release files')
    payload = source.read_bytes()
    if hashlib.sha256(payload).hexdigest() != metadata['sha256']:
        raise ValueError('Plugin changed after validation')
    with target.open('xb') as stream:
        stream.write(payload)
    with checksum.open('x', encoding='utf-8') as stream:
        stream.write(f"{metadata['sha256']}  {target.name}\n")
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('dist/release'))
    args = parser.parse_args()
    try:
        print(prepare_release(args.input, args.output, args.version))
        return 0
    except Exception:
        print('Plugin release validation failed; no release may be published.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
