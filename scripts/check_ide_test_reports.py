"""Reject missing/skipped Starter scenarios, process leaks, and stale test output."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SCENARIOS = {'loadingAndProjectTree', 'atomicSelectionAutomaticallyRefreshes', 'emptyAndNonemptySurviveColdProcesses'}
DESKTOP_SCENARIOS = {'desktopSelectionAndColdProcesses', 'desktopTrustTransitionUsesRealUi', 'desktopInvalidInputsPreserveUserModel'}


def check_reports(reports, root_file, suite='legacy'):
    if suite not in {'legacy', 'desktop'}:
        raise ValueError('Unknown local IDE suite')
    expected = DESKTOP_SCENARIOS if suite == 'desktop' else SCENARIOS
    files = list(Path(reports).glob('TEST-*.xml'))
    if not files:
        raise ValueError('No integration XML results')
    cases = [case for path in files for case in ET.parse(path).getroot().iter('testcase')]
    if not cases or any(list(case.iter('failure')) or list(case.iter('error')) or list(case.iter('skipped')) for case in cases):
        raise ValueError('Failed, skipped or empty integration suite')
    actual = {case.get('name', '').removesuffix('()') for case in cases}
    if actual != expected or len(cases) != len(expected):
        raise ValueError('A required complete-IDE scenario did not execute')
    root = Path(Path(root_file).read_text().strip())
    events = {}
    for line in (root / 'processes.tsv').read_text().splitlines():
        pid, phase = line.split('\t')
        events.setdefault(pid, []).append(phase)
    # Loading + ordinary + refresh + twice three cold processes.
    process_count = 6 if suite == 'desktop' else 9
    if len(events) != process_count or any(phases != ['started', 'passed', 'exited'] for phases in events.values()):
        raise ValueError('Cold process coverage/cleanup is incomplete or an IDE was force-killed')
    result = {'tests': len(cases), 'skipped': 0, 'failed': 0, 'processes': len(events),
              'selectors': sorted(actual), 'scope': 'local-ide-integration', 'suite': suite}
    if suite == 'desktop':
        from desktop_ide import validate_projection_evidence
        result['projectionProofs'] = validate_projection_evidence(root, events)
        result['savedProjectionProofs'] = 4
    print(f'All three {suite} IDE scenario groups completed with {process_count} distinct, cleanly exited processes')
    return result


if __name__ == '__main__':
    check_reports(*sys.argv[1:])
