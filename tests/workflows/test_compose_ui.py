"""Compose CI gates must reject zero/skipped tests and arbitrary negative failures."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from check_compose_ui import check_reports, PROBE_CLASS, PROBE_NAME, MARKER
from check_compose_host_reports import check_host_reports
from test_ide_compatibility import load_yaml


class ComposeReportsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, rows):
        suite = ET.Element('testsuite')
        for name, method, result in rows:
            test = ET.SubElement(suite, 'testcase', classname=name, name=method)
            if result:
                tag, kind, message = result
                ET.SubElement(test, tag, type=kind, message=message)
        ET.ElementTree(suite).write(self.root / 'TEST-components.xml')
        return self.root

    def test_requires_both_real_component_and_runtime_tests_without_skips(self):
        rows = [('com.reqws.goland.ui.ReqwsScreenTest', 'production', None),
                ('com.reqws.goland.ui.ReqwsComposeEnvironmentTest', 'graphics', None)]
        self.assertEqual(check_reports(self.fixture(rows))['tests'], 2)
        for bad in [[], rows[:1], [(c, m, ('skipped', '', '')) for c, m, _ in rows],
                    rows + [('unexpected', 'test', None)],
                    [(rows[0][0], 'production', ('failure', 'Error', 'broken')), rows[1]]]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                check_reports(self.fixture(bad))

    def test_negative_requires_exact_failed_assertion_and_rejects_infrastructure(self):
        row = (PROBE_CLASS, PROBE_NAME, ('failure', 'java.lang.AssertionError', MARKER))
        self.assertEqual(check_reports(self.fixture([row]), negative=True)['failed'], 1)
        for bad in [[], [row, row], [(PROBE_CLASS, PROBE_NAME, None)],
                    [(PROBE_CLASS, PROBE_NAME, ('failure', 'java.lang.UnsatisfiedLinkError', MARKER))],
                    [(PROBE_CLASS, PROBE_NAME, ('failure', 'java.lang.AssertionError', 'other failure'))],
                    [('Wrong', PROBE_NAME, row[2])], [(PROBE_CLASS, 'wrong', row[2])]]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                check_reports(self.fixture(bad), negative=True)

    def test_ci_has_an_independent_required_graphics_job(self):
        ci = load_yaml('.github/workflows/ci.yml')['jobs']
        self.assertIn('plugin-compose', ci['goland-plugin']['needs'])
        self.assertEqual(ci['plugin-compose']['uses'], './.github/workflows/goland-compose.yml')
        self.assertIn('COMPOSE_RESULT', ci['goland-plugin']['steps'][-1]['env'])
        workflow = load_yaml('.github/workflows/goland-compose.yml')['jobs']['components']
        self.assertEqual(workflow['env']['CI'], 'true')
        self.assertIn('check_compose_ui.py', json.dumps(workflow))
        self.assertTrue(any(step.get('if') == '${{ always() }}' and 'upload-artifact@' in step.get('uses', '')
                            for step in workflow['steps']))
        for forbidden in ('runIdeWithDriver', 'checkIdeIntegration', 'JETBRAINS_LICENSE_SERVER', 'unset CI'):
            self.assertNotIn(forbidden, json.dumps(workflow))
        release = load_yaml('.github/workflows/release.yml')['jobs']
        self.assertIn('plugin-compose', release['publish']['needs'])
        self.assertIn('plugin-compose', load_yaml('.github/workflows/goland-weekly.yml')['jobs'])


class ComposeHostReportsTests(unittest.TestCase):
    def fixture(self, root):
        (root / 'junit').mkdir()
        suite = ET.Element('testsuite')
        ET.SubElement(suite, 'testcase', classname='com.reqws.goland.ComposeContentLifecycleTest',
                      name='contentLifecycleIsIndependentOfProjectSynchronization()')
        ET.ElementTree(suite).write(root / 'junit/TEST-host.xml')
        (root / 'processes.tsv').write_text(''.join(f'{pid}\t{phase}\n' for pid in (101, 102)
                                                  for phase in ('started', 'passed', 'exited')))
        (root / 'compose-content-cycles.tsv').write_text(''.join(f'{n}\t1\t0\t1\t2\n' for n in range(20)))

    def test_requires_all_cycles_processes_and_the_selected_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            self.assertEqual(check_host_reports(root, False)['contentCycles'], 20)
            with self.assertRaises(ValueError):
                check_host_reports(root, True)
            source = root / 'compose-content-cycles.tsv'
            source.write_text(source.read_text().replace('19\t', '18\t'))
            with self.assertRaisesRegex(ValueError, 'Twenty'):
                check_host_reports(root, False)

    def test_a_forced_exit_is_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            source = root / 'processes.tsv'
            source.write_text(source.read_text().replace('passed', 'forced-kill'))
            with self.assertRaisesRegex(ValueError, 'process'):
                check_host_reports(root, False)


if __name__ == '__main__':
    unittest.main()
