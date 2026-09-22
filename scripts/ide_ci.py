"""Conservative path classification and non-skippable GoLand CI aggregation."""

import argparse
import json
import os
from pathlib import Path
import subprocess


def classify(paths):
    paths = sorted(set(paths))
    if not paths:
        return {'docsOnly': False, 'plugin': True, 'localIntegrationRecommended': True, 'reason': 'Unknown or empty diff'}
    docs_only = all(path.endswith('.md') and not path.startswith('.agents/') for path in paths)
    if docs_only:
        return {'docsOnly': True, 'plugin': False, 'localIntegrationRecommended': False, 'reason': 'Only Markdown documentation changed'}
    plugin = local_integration = False
    for path in paths:
        if path.endswith('.md') and not path.startswith('.agents/'):
            continue
        if path.startswith('integrations/goland/src/main/kotlin/'):
            plugin = True
            # This is a local follow-up recommendation, never a CI IDE launch decision.
            # Sync, persistence, VFS, startup and any new package are conservative full-impact.
            local_integration |= not any(f'/{part}/' in path for part in ('manifest', 'diagnostics'))
        elif path.startswith('integrations/goland/src/test/kotlin/'):
            plugin = True
        elif path.startswith(('src/renderer/', 'src/preload/', 'tests/renderer/')):
            continue
        else:
            # Includes shared contracts, Desktop entry generation, workflow/cache/release
            # tooling, dependencies, descriptors, new/unknown paths and this classifier.
            plugin = local_integration = True
    return {'docsOnly': False, 'plugin': plugin, 'localIntegrationRecommended': local_integration,
            'reason': 'Conservative source-path classification', 'paths': paths}


def aggregate(impact, results):
    if results.get('impact') != 'success':
        raise ValueError('Impact classification failed or was cancelled')
    expected = {'targets', 'build', 'verification'} if impact['plugin'] else set()
    for job in ('targets', 'build', 'verification'):
        if results.get(job) != ('success' if job in expected else 'skipped'):
            raise ValueError(f'{job} was missing, cancelled, failed or unexpectedly skipped')
    return 'passed' if expected else 'not-applicable: ' + impact['reason']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base')
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        if not args.base or set(args.base) == {'0'}:
            raise ValueError('Base unavailable')
        data = subprocess.check_output(['git', 'diff', '--name-only', '-z', '--no-renames',
                                        args.base, args.head, '--'])
        # --no-renames exposes both the removed and added path, including directory moves.
        result = classify([part.decode('utf-8') for part in data.split(b'\0') if part])
    except (ValueError, UnicodeError, subprocess.CalledProcessError):
        result = classify([])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a') as stream:
            for key in ('docsOnly', 'plugin', 'localIntegrationRecommended'):
                stream.write(f'{key}={str(result[key]).lower()}\n')
    if summary := os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(summary, 'a') as stream:
            stream.write('Minimum version assessment: 262 retained; baseline/API evidence is required for plugin changes.\n\n')
            stream.write(json.dumps(result, indent=2) + '\n')
            stream.write('Local IDE integration: not run by CI; record separately for the exact candidate ZIP.\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
