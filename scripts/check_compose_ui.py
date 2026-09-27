"""Run standalone production Compose tests and prove assertion/empty-suite failures propagate.

Uses the build's SDK JBR and an actual display. Never starts a complete IDE, edits
IDE authorization, or clears CI flags. Each invocation keeps fresh raw evidence.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROBE_CLASS = 'com.reqws.goland.ui.ReqwsComposeFailureProbeTest'
PROBE_NAME = 'deliberateAssertionMustFailTheBuild'
MARKER = 'REQWS_COMPOSE_DELIBERATE_FAILURE'


def cases(directory):
    return [case for path in sorted(Path(directory).glob('TEST-*.xml'))
            for case in ET.parse(path).getroot().iter('testcase')]


def check_reports(directory, negative=False):
    tests = cases(directory)
    failures = [case for case in tests if case.find('failure') is not None or case.find('error') is not None]
    skipped = [case for case in tests if case.find('skipped') is not None]
    if negative:
        if (len(tests) != 1 or len(failures) != 1 or skipped
                or tests[0].get('classname') != PROBE_CLASS or tests[0].get('name') != PROBE_NAME):
            raise ValueError('The deliberate Compose assertion did not execute and fail exactly once')
        failure = tests[0].find('failure')
        if failure is None or failure.get('type') != 'java.lang.AssertionError' or MARKER not in failure.get('message', ''):
            raise ValueError('An infrastructure failure cannot replace the deliberate Compose assertion')
    else:
        classes = {case.get('classname') for case in tests}
        if (not tests or failures or skipped or classes != {
                'com.reqws.goland.ui.ReqwsScreenTest', 'com.reqws.goland.ui.ReqwsComposeEnvironmentTest'}):
            raise ValueError('Production Compose and runtime tests must all execute without failure or skip')
    return {'tests': len(tests), 'failed': len(failures), 'skipped': len(skipped)}


def run(output):
    output.mkdir(parents=True, exist_ok=False)
    report = {'status': 'failed', 'scope': 'standalone-compose', 'completeIde': 'not-started',
              'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'prHead': os.environ.get('REQWS_PR_HEAD_SHA'), 'runs': {}}
    try:
        for task in ('composeUiTest', 'composeUiFailureProbeTest', 'composeUiEmptyProbeTest'):
            command = [str(ROOT / 'integrations/goland/gradlew'), '-p', str(ROOT / 'integrations/goland'), task,
                       '--no-daemon', '--no-configuration-cache', '--console=plain',
                       '-Porg.jetbrains.intellij.platform.useCacheRedirector=false',
                       f'-PreqwsComposeReportRoot={output}']
            log = output / f'{task}.log'
            print(f'{task}: {log}', flush=True)
            with log.open('w') as stream:
                result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
            code = result.returncode
            if task == 'composeUiTest':
                if code != 0:
                    raise ValueError(f'Compose components failed; inspect {log}')
                counts = check_reports(output / task / 'junit')
                if not (output / 'environment.txt').is_file() or not list((output / 'screenshots').glob('*.png')):
                    raise ValueError('Runtime identity and rendered component evidence are required')
            elif task == 'composeUiFailureProbeTest':
                if code == 0:
                    raise ValueError('A deliberate failed assertion did not fail Gradle')
                counts = check_reports(output / task / 'junit', negative=True)
            else:
                if code == 0 or 'No tests found for given includes' not in log.read_text() or cases(output / task / 'junit'):
                    raise ValueError('An empty test selector did not fail discovery as expected')
                counts = {'tests': 0, 'expectedDiscoveryFailure': True}
            report['runs'][task] = {'exitCode': code, **counts}
        report['status'] = 'passed'
    except Exception as failure:
        report['error'] = str(failure)
        raise
    finally:
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    parent = ROOT / 'integrations/goland/build/reports/compose-ui-runs'
    parent.mkdir(parents=True, exist_ok=True)
    output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix='run-', dir=parent)) / 'evidence'
    run(output)


if __name__ == '__main__':
    main()
