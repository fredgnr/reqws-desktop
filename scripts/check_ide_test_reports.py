"""Reject missing/skipped Starter scenarios, process leaks, and stale test output."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SCENARIOS = {'loadingAndProjectTree', 'atomicSelectionAutomaticallyRefreshes', 'emptyAndNonemptySurviveColdProcesses'}


def check_reports(reports, root_file):
    files = list(Path(reports).glob('TEST-*.xml'))
    if not files:
        raise ValueError('No integration XML results')
    cases = [case for path in files for case in ET.parse(path).getroot().iter('testcase')]
    if not cases or any(list(case.iter('failure')) or list(case.iter('error')) or list(case.iter('skipped')) for case in cases):
        raise ValueError('Failed, skipped or empty integration suite')
    actual = {case.get('name', '').removesuffix('()') for case in cases}
    if not SCENARIOS <= actual:
        raise ValueError('A required complete-IDE scenario did not execute')
    root = Path(Path(root_file).read_text().strip())
    events = {}
    for line in (root / 'processes.tsv').read_text().splitlines():
        pid, phase = line.split('\t')
        events.setdefault(pid, []).append(phase)
    # Loading + ordinary + refresh + twice three cold processes.
    if len(events) != 9 or any(phases != ['started', 'passed', 'exited'] for phases in events.values()):
        raise ValueError('Cold process coverage/cleanup is incomplete or an IDE was force-killed')
    result = {'tests': len(cases), 'skipped': 0, 'failed': 0, 'processes': len(events),
              'selectors': sorted(actual), 'scope': 'local-ide-integration'}
    print('All three local IDE scenario groups completed with nine distinct, cleanly exited processes')
    return result


if __name__ == '__main__':
    check_reports(*sys.argv[1:])
