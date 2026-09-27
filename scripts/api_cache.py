"""Bounded Linux API download caches; a cache is never compatibility evidence."""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

from ide_compatibility import load_snapshot, numeric, read_policy

ROOT = Path(__file__).resolve().parents[1]
MIB = 1024 ** 2
SDK_LIMIT = 1536 * MIB
JBR_LIMIT = 512 * MIB


def runtime_pin(root, policy):
    values = {}
    for line in (root / 'integrations/goland/verifier-runtime.properties').read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in values:
            raise ValueError('Duplicate runtime pin')
        values[key] = value
    for key in ('compileIdeProduct', 'compileIdeVersion', 'compileIdeBuild'):
        if values.get(key) != policy[key]:
            raise ValueError('Runtime pin needs review after compile SDK change')
    archive = values.get('runtimeArchive', '')
    if not re.fullmatch(r'jbr_jcef-[0-9]+(?:\.[0-9]+)*-linux-x64-b[0-9]+(?:\.[0-9]+)*', archive):
        raise ValueError('Expected a fixed Linux x64 JBR archive')
    return archive


def producer_allowed(env, checkout_sha):
    """Even explicit requests cannot turn PRs, tags or other callers into writers."""
    branch = env.get('DEFAULT_BRANCH', '')
    repository = env.get('GITHUB_REPOSITORY', '')
    ref = f'refs/heads/{branch}'
    return bool(
        env.get('REQUEST_CACHE_WRITE') == 'true'
        and branch and repository
        and env.get('GITHUB_EVENT_NAME') in {'push', 'workflow_dispatch'}
        and env.get('GITHUB_REF') == ref
        and env.get('GITHUB_WORKFLOW_REF') == f'{repository}/.github/workflows/ci.yml@{ref}'
        and env.get('GITHUB_JOB') == 'api'
        and env.get('CACHE_SOURCE_REF', '') == ''
        and env.get('CACHE_HISTORICAL', 'false') == 'false'
        and re.fullmatch(r'[0-9a-f]{40}', checkout_sha or '')
        and env.get('GITHUB_SHA') == checkout_sha
    )


def cache_plan(snapshot, target_id, archive, home):
    policy = snapshot['policy']
    matches = [target for target in snapshot['targets'] if target['id'] == target_id]
    if len(matches) != 1:
        raise ValueError('Target must occur exactly once in the frozen snapshot')
    target = matches[0]
    numeric(target['version']); numeric(target['build'])
    if target['product'] != 'GO' or target_id != 'GO-' + target['build']:
        raise ValueError('Invalid API target')
    # Keep the hot set bounded as the full verification matrix grows. All other
    # targets still run, using shared dependencies/JBR but downloading their SDK.
    baseline = 'GO-' + policy['compileIdeBuild']
    fixed = 'GO-' + policy['uiTestIdeBuild']
    latest = max(snapshot['targets'], key=lambda item: (numeric(item['version']), numeric(item['build'])))['id']
    hot = sorted({baseline, fixed, latest})
    base = home / '.gradle/caches/modules-2/files-2.1'
    return {
        'target': target_id, 'sdk-hot': str(target_id in hot).lower(),
        'shared-owner': str(target_id == baseline).lower(),
        'sdk-key': f"api-sdk-v1-Linux-X64-GO-{target['version']}-{target['build']}",
        'sdk-path': str(base / 'com.jetbrains.intellij.goland/goland' / target['version']),
        'sdk-file': f"goland-{target['version']}.zip",
        'jbr-key': f'api-jbr-v1-Linux-X64-{archive}',
        'jbr-path': str(base / 'com.jetbrains/jbr' / archive),
        'jbr-file': f'jbr-{archive}.tar.gz', 'hot-targets': ','.join(hot),
    }


def inspect_download(directory, expected_archive, limit, home):
    """Do not upload links, other archive versions, unbounded trees or empty caches."""
    directory = Path(directory)
    relative = directory.relative_to(home)
    current = home
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('Download cache path contains a symlink')
    if not directory.exists():
        return {'cacheable': False, 'reason': 'missing', 'bytes': 0, 'files': 0}
    size = 0
    files = 0
    archives = []
    for entry in directory.rglob('*'):
        mode = entry.lstat().st_mode
        if stat.S_ISLNK(mode) or not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise ValueError('Download cache contains a non-regular entry')
        if stat.S_ISDIR(mode):
            continue
        files += 1
        size += entry.stat().st_size
        if files > 64:
            return {'cacheable': False, 'reason': 'too-many-files', 'bytes': size, 'files': files}
        if entry.name.endswith(('.zip', '.tar.gz', '.dmg')):
            archives.append(entry)
    if len(archives) != 1 or archives[0].name != expected_archive or archives[0].stat().st_size == 0:
        raise ValueError('Expected exactly the resolved nonempty download archive')
    return {'cacheable': size <= limit, 'reason': 'within-budget' if size <= limit else 'over-budget',
            'bytes': size, 'files': files}


def emit(values):
    text = ''.join(f'{key}={value}\n' for key, value in values.items())
    if any('\n' in str(value) or '\r' in str(value) for value in values.values()):
        raise ValueError('Unsafe action output')
    print(text, end='')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['plan', 'audit'])
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--output', type=Path, default=Path('api-results'))
    args = parser.parse_args()
    if os.environ.get('RUNNER_OS') != 'Linux' or os.environ.get('RUNNER_ARCH') != 'X64':
        raise ValueError('API caches are scoped to Linux X64')
    home = Path.home()
    if Path(os.environ.get('GRADLE_USER_HOME', home / '.gradle')) != home / '.gradle':
        raise ValueError('Unexpected Gradle User Home for the reviewed cache paths')
    snapshot = load_snapshot(args.snapshot)
    plan = cache_plan(snapshot, args.target, runtime_pin(ROOT, read_policy()), home)
    if args.command == 'plan':
        # Does not create ~/.gradle before setup-gradle attempts its restoration.
        sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        allowed = producer_allowed(os.environ, sha)
        emit({**plan, 'shared-write': str(allowed and plan['shared-owner'] == 'true').lower(),
              'sdk-write': str(allowed and plan['sdk-hot'] == 'true').lower()})
        return
    sdk = inspect_download(plan['sdk-path'], plan['sdk-file'], SDK_LIMIT, home)
    jbr = inspect_download(plan['jbr-path'], plan['jbr-file'], JBR_LIMIT, home)
    report = {'schemaVersion': 1, 'target': args.target, 'sdk': sdk, 'jbr': jbr,
              'sdkKey': plan['sdk-key'], 'jbrKey': plan['jbr-key'],
              'hotTargets': plan['hot-targets'].split(','),
              'restore': {name: os.environ.get('CACHE_' + name.upper() + '_HIT', '')
                          for name in ('sdk', 'jbr')},
              'scope': 'download-cache-only-not-verification-evidence'}
    path = args.output / args.target / 'cache.json'
    # The verifier has already created this target directory and retained its verdict.
    if not path.parent.is_dir() or path.is_symlink():
        raise ValueError('Missing verifier output directory or unsafe cache report path')
    path.write_text(json.dumps(report, indent=2) + '\n')
    emit({'sdk-store': str(sdk['cacheable'] and plan['sdk-hot'] == 'true').lower(),
          'jbr-store': str(jbr['cacheable']).lower()})
    if summary := os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(summary, 'a', encoding='utf-8') as stream:
            stream.write(f"\n### API download cache: {args.target}\n\n")
            stream.write('| Layer | Exact hit | Stored MiB | Cache eligibility |\n|---|---|---:|---|\n')
            for layer, result in (('sdk', sdk), ('jbr', jbr)):
                hit = report['restore'][layer] == 'true'
                reason = result['reason'] if layer != 'sdk' or plan['sdk-hot'] == 'true' else 'outside-hot-set'
                stream.write(f"| {layer} | {str(hit).lower()} | {result['bytes'] / MIB:.1f} | {reason} |\n")


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f'API cache configuration blocked: {type(error).__name__}', file=sys.stderr)
        sys.exit(2)
