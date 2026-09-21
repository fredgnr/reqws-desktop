"""Small, dependency-free readers for CI download keys and cache write ownership."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys


def may_write(requested, event, ref, default_branch):
    """Only an opted-in default-branch push/manual job can publish shared caches."""
    return (
        requested == 'true'
        and bool(default_branch)
        and event in {'push', 'workflow_dispatch'}
        and ref == f'refs/heads/{default_branch}'
    )


def desktop_keys(root):
    major = (root / '.nvmrc').read_text().strip()
    if not re.fullmatch(r'[1-9][0-9]*', major):
        raise ValueError('.nvmrc must contain a pinned Node major for the CI cache key')
    lock = json.loads((root / 'package-lock.json').read_text())
    packages = lock.get('packages')
    if not isinstance(packages, dict) or not packages:
        raise ValueError('package-lock.json must contain resolved packages')
    electron = packages.get('node_modules/electron', {}).get('version', '')
    if not isinstance(electron, str) or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', electron):
        raise ValueError('The locked Electron version must be an exact stable version')
    # Root app version/name changes do not change downloaded npm packages. npm ci
    # still validates the complete lockfile; this digest is not an install gate.
    downloads = {name: value for name, value in packages.items() if name}
    payload = json.dumps([lock.get('lockfileVersion'), downloads], sort_keys=True, separators=(',', ':'))
    digest = hashlib.sha256(payload.encode()).hexdigest()
    return {'npm-suffix': f'node{major}-{digest}', 'npm-prefix': f'node{major}-', 'electron-version': electron}


def goland_keys(root):
    # Read the two authoritative literal declarations without running Gradle
    # before its caches are restored. A DSL refactor must update this reader.
    source = (root / 'integrations/goland/build.gradle.kts').read_text()
    # Ignore comments so an old version in an explanation cannot become a key.
    source = re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S)
    build = re.findall(r'\bgoland\(\s*"([0-9.]+)"\s*\)', source)
    verify = re.findall(r'create\(\s*IntelliJPlatformType\.GoLand\s*,\s*"([0-9.]+)"\s*\)', source)
    if len(build) != 1 or len(verify) != 1 or build != verify:
        raise ValueError('Expected one matching literal GoLand build/verifier version; update the cache reader with the Gradle DSL')
    version = build[0]
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}', version):
        raise ValueError('Unsupported GoLand version in cache key')
    return {'goland-version': version}


def goland_downloads(root, home):
    """Verify the directories being cached contain the resolved IDE installer."""
    version = goland_keys(root)['goland-version']
    base = home / '.gradle/caches/modules-2/files-2.1'
    downloads = []
    for group in ['go', 'com.jetbrains.intellij.goland']:
        directory = base / group / 'goland' / version
        for candidate in directory.glob('**/*'):
            if candidate.is_file() and candidate.name.endswith(('.dmg', '.zip', '.tar.gz')):
                downloads.append(candidate)
    if not downloads:
        raise ValueError('No GoLand installer found in the versioned cache paths; check the resolved dependency coordinates')
    return {str(candidate): candidate.stat().st_size for candidate in downloads}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=['policy', 'desktop', 'goland', 'goland-downloads'])
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    if args.kind == 'goland-downloads':
        print(json.dumps(goland_downloads(args.root, Path.home()), indent=2))
        return
    if args.kind == 'policy':
        values = {'write': str(may_write(
            os.environ.get('REQUEST_CACHE_WRITE', ''), os.environ.get('GITHUB_EVENT_NAME', ''),
            os.environ.get('GITHUB_REF', ''), os.environ.get('DEFAULT_BRANCH', ''),
        )).lower()}
    else:
        values = desktop_keys(args.root) if args.kind == 'desktop' else goland_keys(args.root)
    output = ''.join(f'{name}={value}\n' for name, value in values.items())
    print(output, end='')
    if target := os.environ.get('GITHUB_OUTPUT'):
        with open(target, 'a', encoding='utf-8') as stream:
            stream.write(output)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, TypeError, AttributeError) as error:
        print(f'CI cache configuration: {error}', file=sys.stderr)
        sys.exit(1)
