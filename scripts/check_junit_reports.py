"""Require an executed baseline suite and report real JUnit counts."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check_baseline(directory):
    reports = list(Path(directory).glob('TEST-*.xml'))
    cases = [case for path in reports for case in ET.parse(path).getroot().iter('testcase')]
    skipped = sum(case.find('skipped') is not None for case in cases)
    failed = sum(case.find('failure') is not None or case.find('error') is not None for case in cases)
    if not reports or len(cases) == skipped or failed:
        raise ValueError('Baseline XML missing, no executed tests, all skipped or failures present')
    print(f'Baseline tests: executed={len(cases) - skipped}, skipped={skipped}, failed={failed}')


if __name__ == '__main__':
    check_baseline(sys.argv[1])
