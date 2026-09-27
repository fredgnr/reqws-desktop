"""Compose CI gates must reject zero/skipped tests and arbitrary negative failures."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from check_compose_ui import check_reports, PROBE_CLASS, PROBE_NAME, MARKER
from check_compose_host_reports import check_host_reports, check_input_probe_reports
from test_ide_compatibility import load_yaml
from test_workflow_telemetry import steps_after_telemetry


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
        weekly = load_yaml('.github/workflows/goland-weekly.yml')['jobs']['plugin-compose']
        self.assertEqual(weekly['needs'], 'targets')
        self.assertEqual(weekly['with']['source-ref'], '${{ needs.targets.outputs.revision }}')
        self.assertEqual(steps_after_telemetry(self, workflow)[0]['with']['ref'], '${{ inputs.source-ref }}')


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


    def input_fixture(self, root, full=False):
        if full:
            self.fixture(root)
            suite = ET.parse(root / 'junit/TEST-host.xml').getroot()
        else:
            (root / 'junit').mkdir()
            suite = ET.Element('testsuite')
        ET.SubElement(suite, 'testcase', classname='com.reqws.goland.ComposeHostInputTest',
                      name='productionActionsThemeAndScaleUseRealInput()')
        if full:
            ET.SubElement(suite, 'testcase', classname='com.reqws.goland.ComposeHostInputTest',
                          name='settingsDisableAndEnableReleaseAndRecreateProductionContent()')
        ET.ElementTree(suite).write(root / 'junit/TEST-host.xml')
        pids = (101, 102, 103, 104) if full else (101,)
        (root / 'processes.tsv').write_text(''.join(f'{pid}\t{phase}\n' for pid in pids
                                                  for phase in ('started', 'passed', 'exited')))
        inputs = [f'theme\tdark={dark} scale={scale} focus=copyDiagnostics actions=3'
                  for dark in ('true', 'false') for scale in ('1.0', '1.25')]
        inputs.append('input\tpointer-sync keyboard-sync keyboard-open pointer-copy keyboard-copy')
        if full:
            inputs.append('dynamic-reload\tunloaded content-disposed loaded empty-restored actual-click full-restored')
        for number in range(4):
            image = root / f'theme-{number}.png'
            image.write_bytes(b'fixture')
            inputs.append(f'screenshot\t{image}')
        (root / 'compose-input.tsv').write_text('\n'.join(inputs) + '\n')

    def test_probe_and_full_acceptance_are_not_interchangeable(self):
        for full in (False, True):
            with self.subTest(full=full), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.input_fixture(root, full)
                if full:
                    self.assertEqual(check_host_reports(root, True),
                                     {'tests': 3, 'skipped': 0, 'processes': 4, 'contentCycles': 20})
                    with self.assertRaises(ValueError):
                        check_input_probe_reports(root)
                    source = root / 'compose-input.tsv'
                    source.write_text('\n'.join(line for line in source.read_text().splitlines()
                                                if not line.startswith('dynamic-reload\t')))
                    with self.assertRaises(ValueError):
                        check_host_reports(root, True)
                else:
                    self.assertEqual(check_input_probe_reports(root),
                                     {'tests': 1, 'skipped': 0, 'processes': 1, 'contentCycles': 0})
                    for allow_input in (False, True):
                        with self.assertRaises(ValueError):
                            check_host_reports(root, allow_input)

    def test_probe_rejects_missing_extra_wrong_or_unsuccessful_tests(self):
        for bad in ('empty', 'duplicate', 'wrong', 'failure', 'error', 'skipped'):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.input_fixture(root)
                path = root / 'junit/TEST-host.xml'
                suite = ET.parse(path).getroot()
                case = suite.find('testcase')
                if bad == 'empty':
                    suite.remove(case)
                elif bad == 'duplicate':
                    ET.SubElement(suite, 'testcase', **case.attrib)
                elif bad == 'wrong':
                    case.set('name', 'settingsDisableAndEnableReleaseAndRecreateProductionContent()')
                else:
                    ET.SubElement(case, bad)
                ET.ElementTree(suite).write(path)
                with self.assertRaises(ValueError):
                    check_input_probe_reports(root)

    def test_probe_rejects_incomplete_or_forced_processes(self):
        for events in ('', '101\tstarted\n101\tpassed\n',
                       '101\tstarted\n101\tforced-kill\n101\texited\n',
                       '101\tstarted\n102\tpassed\n101\texited\n',
                       '101\tstarted\textra\n101\tpassed\n101\texited\n'):
            with self.subTest(events=events), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.input_fixture(root)
                (root / 'processes.tsv').write_text(events)
                with self.assertRaises(ValueError):
                    check_input_probe_reports(root)

    def test_probe_rejects_missing_input_and_other_scope_evidence(self):
        for bad in ('missing-theme', 'missing-input', 'missing-image', 'reload', 'cycles'):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.input_fixture(root)
                path = root / 'compose-input.tsv'
                if bad == 'missing-theme':
                    path.write_text(path.read_text().replace('scale=1.25', 'scale=1.0'))
                elif bad == 'missing-input':
                    path.write_text('\n'.join(line for line in path.read_text().splitlines()
                                             if not line.startswith('input\t')))
                elif bad == 'missing-image':
                    (root / 'theme-0.png').unlink()
                elif bad == 'reload':
                    path.write_text(path.read_text() + 'dynamic-reload\tunrelated\n')
                else:
                    (root / 'compose-content-cycles.tsv').write_text('')
                with self.assertRaises(ValueError):
                    check_input_probe_reports(root)


if __name__ == '__main__':
    unittest.main()
