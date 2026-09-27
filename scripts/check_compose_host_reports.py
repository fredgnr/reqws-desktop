"""Reject incomplete, skipped or stale-scope Compose host evidence."""
from pathlib import Path
import xml.etree.ElementTree as ET


def check_host_reports(run, allow_input):
    run = Path(run)
    expected = {('com.reqws.goland.ComposeContentLifecycleTest', 'contentLifecycleIsIndependentOfProjectSynchronization()')}
    if allow_input:
        expected |= {('com.reqws.goland.ComposeHostInputTest', name) for name in (
            'productionActionsThemeAndScaleUseRealInput()',
            'settingsDisableAndEnableReleaseAndRecreateProductionContent()')}
    cases = [c for p in (run / 'junit').glob('TEST-*.xml') for c in ET.parse(p).getroot().iter('testcase')]
    if (len(cases) != len(expected) or {(c.get('classname'), c.get('name')) for c in cases} != expected
            or any(c.find(t) is not None for c in cases for t in ('failure', 'error', 'skipped'))):
        raise ValueError('Required non-skipped Compose host scenarios did not pass')
    events = [line.split('\t') for line in (run / 'processes.tsv').read_text().splitlines()]
    expected_processes = 4 if allow_input else 2
    pids = list(dict.fromkeys(pid for pid, _ in events))
    if (len(pids) != expected_processes or any(not pid.isdecimal() for pid in pids)
            or any([phase for seen, phase in events if seen == pid] != ['started', 'passed', 'exited'] for pid in pids)):
        raise ValueError('Every expected cold process must pass and exit without forced cleanup')
    cycles = [line.split('\t') for line in (run / 'compose-content-cycles.tsv').read_text().splitlines()]
    if (len(cycles) != 20 or [row[0] for row in cycles] != [str(n) for n in range(20)]
            or any(len(row) != 5 or row[1:4] != ['1', '0', '1'] or not row[4].isdecimal() for row in cycles)):
        raise ValueError('Twenty exclusive Content cycles were not verified')
    if allow_input:
        inputs = (run / 'compose-input.tsv').read_text().splitlines()
        expected_themes = {f'theme\tdark={dark} scale={scale} focus=copyDiagnostics actions=3'
                           for dark in ('true', 'false') for scale in ('1.0', '1.25')}
        themes = [line for line in inputs if line.startswith('theme\t')]
        if (len(themes) != 4 or set(themes) != expected_themes
                or inputs.count('input\tpointer-sync keyboard-sync keyboard-open pointer-copy keyboard-copy') != 1
                or inputs.count('dynamic-reload\tunloaded content-disposed loaded empty-restored actual-click full-restored') != 1):
            raise ValueError('Real input, both themes/scales and dynamic reload evidence is incomplete')
        screenshots = [Path(line.split('\t', 1)[1]) for line in inputs if line.startswith('screenshot\t')]
        if (len(screenshots) != 4 or len(set(screenshots)) != 4
                or any(not p.is_file() or not p.resolve().is_relative_to(run.resolve()) or p.stat().st_size == 0 for p in screenshots)):
            raise ValueError('Four current-run theme and scale screenshots are required')
    return {'tests': len(cases), 'skipped': 0, 'processes': expected_processes, 'contentCycles': 20}
