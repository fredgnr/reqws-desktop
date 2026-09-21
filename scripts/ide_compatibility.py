"""ReqWS compatibility policy and frozen official release catalogs (no Gradle required)."""

import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.request import urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / 'integrations/goland/compatibility.properties'
SOURCE = 'https://data.services.jetbrains.com/products/releases?code=GO&type=release'


def numeric(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', value):
        raise ValueError(f'Expected a numeric version: {value!r}')
    return tuple(int(part) for part in value.split('.'))


def read_policy(path=POLICY):
    values = {}
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in values:
            raise ValueError(f'Duplicate policy key: {key}')
        values[key] = value
    # Changing this guard is an explicit, reviewable policy migration, not an SDK update.
    if values.get('minimumPlatformBranch') != '262':
        raise ValueError('The approved minimum platform branch is 262')
    for role in ('compile', 'uiTest'):
        if values.get(role + 'IdeProduct') != 'GO':
            raise ValueError('Only the GoLand product is approved')
        numeric(values[role + 'IdeVersion'])
        if numeric(values[role + 'IdeBuild'])[0] != 262:
            raise ValueError('Compile and representative IDEs must stay on the approved 262 baseline')
    if values.get('verificationProducts') != 'GO':
        raise ValueError('Only GO is approved for verification')
    for key in ('pluginVerifierVersion', 'starterVersion'):
        numeric(values[key])
    for item in filter(None, values.get('regressionVersions', '').split(',')):
        product, version = item.split(':')
        if product != 'GO':
            raise ValueError('Regression target has an unapproved product')
        numeric(version)
    return values


def check_descriptor(descriptor, policy=None):
    policy = policy or read_policy()
    bounds = descriptor.findall('idea-version')
    if (len(bounds) != 1 or bounds[0].attrib != {'since-build': policy['minimumPlatformBranch']}):
        raise ValueError('Descriptor must have only since-build="262" and no upper bound')
    for required in ('com.intellij.modules.platform', 'com.intellij.modules.goland', 'com.intellij.modules.vcs'):
        nodes = [node for node in descriptor.findall('depends') if (node.text or '').strip() == required]
        if len(nodes) != 1 or nodes[0].get('optional', 'false') != 'false':
            raise ValueError('Required platform/GoLand/VCS dependencies are missing, optional or duplicated')


def resolve_catalog(catalog, policy, mode='pr', fetched_at=None):
    if mode not in {'pr', 'full'}:
        raise ValueError('Unknown matrix mode')
    records = {}
    for product in policy['verificationProducts'].split(','):
        releases = catalog.get(product)
        if not isinstance(releases, list) or not releases:
            raise ValueError(f'No catalog for {product}')
        for release in releases:
            if release.get('type') != 'release':
                continue
            build, version = release['build'], release['version']
            if numeric(build)[0] < int(policy['minimumPlatformBranch']):
                continue
            numeric(version)
            key = (product, build)
            entry = {'id': f'{product}-{build}', 'product': product, 'version': version,
                     'build': build, 'channel': 'release', 'required': True,
                     'releasedAt': release['date'], 'reasons': []}
            if key in records and records[key]['version'] != version:
                raise ValueError('Catalog maps the same product/build to conflicting versions')
            records[key] = entry
    targets = sorted(records.values(), key=lambda item: (item['product'], numeric(item['version']), numeric(item['build'])))
    if not targets:
        raise ValueError('Official catalog produced an empty matrix')
    for role, reason in [('compile', 'minimum-sdk'), ('uiTest', 'fixed-ui')]:
        matches = [item for item in targets if item['product'] == policy[role + 'IdeProduct']
                   and item['version'] == policy[role + 'IdeVersion']]
        if len(matches) != 1 or matches[0]['build'] != policy[role + 'IdeBuild']:
            raise ValueError(f'Missing, ambiguous or changed mandatory {role} release')
        matches[0]['reasons'].append(reason)
    baseline = next(item for item in targets if 'minimum-sdk' in item['reasons'])
    if baseline != targets[0] or numeric(baseline['build'])[0] != 262:
        raise ValueError('Compile SDK is not the earliest official 262 release')
    lines = {}
    for item in targets:
        lines.setdefault((item['product'], numeric(item['version'])[:2]), []).append(item)
    for members in lines.values():
        members[0]['reasons'].append('line-first')
        members[-1]['reasons'].append('line-latest')
    for item in filter(None, policy.get('regressionVersions', '').split(',')):
        product, version = item.split(':')
        matches = [target for target in targets if (target['product'], target['version']) == (product, version)]
        if len(matches) != 1:
            raise ValueError(f'Missing regression release: {item}')
        matches[0]['reasons'].append('known-regression')
    if mode == 'full':
        for item in targets:
            item['reasons'].append('full-stable-scan')
    else:
        targets = [item for item in targets if item['reasons']]
    frozen_catalog = {product: [{key: release[key] for key in ('version', 'build', 'type', 'date')}
                               for release in catalog[product] if release.get('type') == 'release']
                      for product in policy['verificationProducts'].split(',')}
    return {'schemaVersion': 1, 'source': SOURCE, 'catalog': frozen_catalog, 'fetchedAt': fetched_at or datetime.now(timezone.utc).isoformat(),
            'mode': mode, 'policy': policy, 'targets': targets}


def write_json(path, payload):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + '\n')


def load_snapshot(path, fresh=False):
    snapshot = json.loads(Path(path).read_text())
    if snapshot.get('schemaVersion') != 1 or snapshot.get('source') != SOURCE or snapshot.get('policy') != read_policy():
        raise ValueError('Snapshot schema/source/policy mismatch')
    expected = resolve_catalog(snapshot['catalog'], snapshot['policy'], snapshot['mode'], snapshot['fetchedAt'])
    if snapshot != expected:
        raise ValueError('Frozen targets do not match the selection rules and recorded catalog')
    targets = snapshot.get('targets', [])
    if not targets or len({item['id'] for item in targets}) != len(targets):
        raise ValueError('Empty or duplicate frozen targets')
    for item in targets:
        if (item['product'] != 'GO' or item['channel'] != 'release' or item['required'] is not True
                or item['id'] != f"GO-{item['build']}" or numeric(item['build'])[0] < 262 or not item['reasons']):
            raise ValueError('Invalid frozen target')
        numeric(item['version'])
    for role in ('compile', 'uiTest'):
        if not any(item['version'] == snapshot['policy'][role + 'IdeVersion'] for item in targets):
            raise ValueError('Frozen matrix omitted a mandatory target')
    age = datetime.now(timezone.utc) - datetime.fromisoformat(snapshot['fetchedAt'])
    if fresh and not timedelta(0) <= age <= timedelta(hours=24):
        raise ValueError('A fresh official catalog (less than 24h old) is required')
    return snapshot


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    resolve = commands.add_parser('resolve')
    resolve.add_argument('--mode', choices=['pr', 'full'], default='pr')
    resolve.add_argument('--output', type=Path, required=True)
    descriptor = commands.add_parser('descriptor')
    descriptor.add_argument('paths', type=Path, nargs='+')
    args = parser.parse_args()
    if args.command == 'descriptor':
        for path in args.paths:
            check_descriptor(ET.fromstring(path.read_text()))
        return
    # A new run always fetches; downstream jobs reuse this snapshot, never a stale fallback.
    with urlopen(SOURCE, timeout=60) as response:
        catalog = json.load(response)
    snapshot = resolve_catalog(catalog, read_policy(), args.mode)
    write_json(args.output, snapshot)
    matrix = json.dumps({'include': [{'target': item['id']} for item in snapshot['targets']]}, separators=(',', ':'))
    if len(snapshot['targets']) > 256:
        raise ValueError('Matrix exceeds GitHub capacity; shard explicitly, never truncate targets')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a') as stream:
            stream.write(f'matrix={matrix}\n')
    print(f"Frozen {len(snapshot['targets'])} required targets at {snapshot['fetchedAt']}")


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Compatibility infrastructure blocked: {error}', file=sys.stderr)
        sys.exit(2)
