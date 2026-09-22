"""Full local plugin gate: one build, fresh stable matrix, exact-archive API reports."""

import json
from pathlib import Path
import subprocess
import sys
from tempfile import mkdtemp

ROOT = Path(__file__).resolve().parents[1]


def main():
    version = json.loads((ROOT / 'package.json').read_text())['version']
    output = Path(mkdtemp(prefix='reqws-api-'))
    subprocess.run([sys.executable, str(ROOT / 'scripts/ide_compatibility.py'), 'resolve', '--mode', 'full',
                    '--output', str(output / 'targets.json')], check=True)
    subprocess.run([str(ROOT / 'integrations/goland/gradlew'), '-p', str(ROOT / 'integrations/goland'),
                    '--no-configuration-cache',
                    'compileIntegrationTestKotlin', 'test', 'verifyBaselineTestReports', 'verifyForbiddenProductionSymbols', 'verifyPluginProjectConfiguration',
                    'verifyPluginStructure', 'verifyPlugin', 'exportPluginArchivePath', f'-PreleaseVersion={version}'], check=True)
    archive = (ROOT / 'integrations/goland/build/release/plugin-archive.txt').read_text().strip()
    subprocess.run([sys.executable, str(ROOT / 'scripts/run_ide_verifier.py'), '--snapshot', str(output / 'targets.json'),
                    '--archive', archive, '--version', version, '--output', str(output / 'results')], check=True)
    print(f'Compatibility reports: {output}')


if __name__ == '__main__':
    main()
