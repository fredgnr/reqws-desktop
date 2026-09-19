"""Exercise release diagnostics with disposable assets and a local gh substitute."""

import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseLoggingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = json.loads(subprocess.check_output([
            'node', '-e', 'const fs=require("fs"),yaml=require("js-yaml");process.stdout.write(JSON.stringify(yaml.load(fs.readFileSync(process.argv[1],"utf8"))))',
            str(ROOT / '.github/workflows/release.yml'),
        ], cwd=ROOT, text=True))

    def test_context_uses_only_public_allowlist_and_escapes_log_controls(self):
        environment = {
            **os.environ, 'GITHUB_REF': 'refs/tags/v1.2.3\n::error::injected',
            'MAC_SIGNING_P12_PASSWORD': 'private-password', 'GH_TOKEN': 'private-token',
        }
        result = subprocess.run(['python3', str(ROOT / 'scripts/log-release-context.py')], env=environment, capture_output=True, text=True, check=True)
        self.assertEqual(len(result.stdout.splitlines()), 1)
        data = json.loads(result.stdout.removeprefix('[release][context] '))
        self.assertEqual(data['GITHUB_REF'], environment['GITHUB_REF'])
        self.assertNotIn('private-password', result.stdout)
        self.assertNotIn('private-token', result.stdout)
        for job in self.workflow['jobs'].values():
            self.assertTrue(any(step.get('run') == 'python3 scripts/log-release-context.py' for step in job['steps']))

    def test_publish_logs_preserve_verification_cleanup_and_exit_codes(self):
        script = next(step['run'] for step in self.workflow['jobs']['publish']['steps'] if 'gh release create' in step.get('run', ''))
        for scenario, exit_code in [('success', 0), ('upload-failure', 23), ('cleanup-failure', 23), ('corrupt-download', 1), ('existing-release', 1)]:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                assets = root / 'dist/release'
                assets.mkdir(parents=True)
                (root / 'bin').mkdir()
                (root / 'scripts').mkdir()
                shutil.copy(ROOT / 'scripts/verify-release-assets.py', root / 'scripts')
                desktop = 'ReqWS-1.2.3-macos-arm64.zip'
                (assets / desktop).write_bytes(b'disposable desktop bytes')
                (assets / 'ReqWS-1.2.3-goland-plugin.zip').write_bytes(b'disposable plugin bytes')
                digest = base64.b64encode(hashlib.sha512((assets / desktop).read_bytes()).digest()).decode()
                (assets / 'latest-mac.yml').write_text(json.dumps({
                    'version': '1.2.3', 'path': desktop, 'sha512': digest,
                    'files': [{'url': desktop, 'sha512': digest, 'size': (assets / desktop).stat().st_size}],
                    'releaseDate': '2026-09-19T00:00:00.000Z',
                }))
                (assets / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(assets.iterdir())))
                fake_gh = root / 'bin/gh'
                fake_gh.write_text('''#!/usr/bin/env python3
import os, pathlib, shutil, sys
args=sys.argv[1:]
action=args[1]
scenario=os.environ['FIXTURE_SCENARIO']
root=pathlib.Path.cwd()
assets=root/'dist/release'
created=root/'created'
with (root/'trace').open('a') as trace: trace.write(action+'\\n')
if action=='view':
    if '--json' not in args: sys.exit(0 if scenario=='existing-release' else 1)
    field=args[args.index('--json')+1]
    if field=='isDraft': print('true' if created.exists() else 'false')
    elif field=='body': print('<!-- reqws-release-workflow:123:1 -->' if created.exists() else '')
    elif field=='assets':
        for p in sorted(assets.iterdir()): print(p.stat().st_size if args[-1]=='.assets[].size' else p.name)
elif action=='create': created.touch()
elif action=='upload' and scenario in ['upload-failure','cleanup-failure']: sys.exit(23)
elif action=='download':
    destination=pathlib.Path(args[args.index('--dir')+1])
    for p in assets.iterdir(): shutil.copy(p,destination)
    if scenario=='corrupt-download': (destination/'ReqWS-1.2.3-macos-arm64.zip').write_bytes(b'corrupt')
elif action=='delete':
    if scenario=='cleanup-failure': sys.exit(47)
    created.unlink()
''')
                fake_gh.chmod(0o700)
                environment = {
                    **os.environ, 'PATH': str(root / 'bin') + os.pathsep + os.environ['PATH'],
                    'TAG': 'v1.2.3', 'VERSION': '1.2.3', 'RUNNER_TEMP': str(root),
                    'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
                    'FIXTURE_SCENARIO': scenario, 'GH_TOKEN': 'private-token-sentinel',
                }
                result = subprocess.run(['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script], cwd=root, env=environment, capture_output=True, text=True)
                self.assertEqual(result.returncode, exit_code, result.stdout + result.stderr)
                output = result.stdout + result.stderr
                self.assertNotIn('private-token-sentinel', output)
                actions = (root / 'trace').read_text().splitlines()
                if scenario == 'success':
                    self.assertIn('stage=publish-release status=success duration_s=', output)
                    self.assertIn('stage=verify-downloaded-bytes status=success', output)
                    self.assertIn('verified=latest-mac.yml bytes=', output)
                    self.assertIn('edit', actions)
                    self.assertNotIn('delete', actions)
                else:
                    self.assertNotIn('edit', actions)
                    self.assertNotIn('stage=publish-release status=success', output)
                    self.assertIn(f'status=failed exit_code={exit_code}', output)
                    if scenario == 'existing-release':
                        self.assertNotIn('create', actions)
                        self.assertNotIn('delete', actions)
                    else:
                        self.assertIn('delete', actions)
                        self.assertIn('stage=cleanup-draft status=' + ('failed' if scenario == 'cleanup-failure' else 'success'), output)
                    if scenario == 'corrupt-download':
                        self.assertIn('stage=verify-downloaded-bytes status=failed', output)


if __name__ == '__main__':
    unittest.main()
