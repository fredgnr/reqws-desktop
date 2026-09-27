"""The S0 source stager must never apply its patch in a user's existing checkout."""

from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import stage_compose_probe as probe
from test_ci_cache_config import load_yaml


class ComposeProbeStagingTests(unittest.TestCase):
    def test_stages_the_required_runtime_dependency_only_for_compose(self):
        with tempfile.TemporaryDirectory() as temporary:
            for variant in ['compose', 'swing']:
                with self.subTest(variant=variant):
                    root = probe.stage(Path(temporary) / variant, variant)
                    descriptor = ET.parse(root / 'integrations/goland/src/main/resources/META-INF/plugin.xml')
                    dependencies = [d for d in descriptor.getroot().findall('depends')
                                    if d.text == 'com.intellij.modules.compose']
                    self.assertEqual(len(dependencies), 1 if variant == 'compose' else 0)
                    if dependencies:
                        self.assertNotEqual(dependencies[0].get('optional'), 'true')

    def test_workflow_regressions_fetch_the_pinned_probe_history(self):
        for workflow, job_id in [('ci.yml', 'project-checks'), ('release.yml', 'checks')]:
            with self.subTest(workflow=workflow, job=job_id):
                steps = load_yaml('.github/workflows/' + workflow)['jobs'][job_id]['steps']
                self.assertTrue(any('unittest discover -s tests/workflows' in step.get('run', '')
                                    for step in steps))
                checkout = next(step for step in steps
                                if step.get('uses', '').startswith('actions/checkout@'))
                self.assertEqual(checkout['with'].get('fetch-depth'), 0,
                                 'The source-staging tests need their pinned historical Git tree')
                self.assertIs(checkout['with'].get('persist-credentials'), False)

    def test_refuses_output_inside_the_existing_checkout_before_creating_it(self):
        output = probe.ROOT / 'reqws-s0-must-not-be-created'
        self.assertFalse(output.exists())
        with self.assertRaisesRegex(ValueError, 'outside a Git working tree'):
            probe.stage(output)
        self.assertFalse(output.exists())

    def test_preserves_existing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            marker = output / 'keep.txt'
            marker.write_text('existing work')
            with self.assertRaises(FileExistsError):
                probe.stage(output)
            self.assertEqual(marker.read_text(), 'existing work')

    def test_refuses_user_symlink_parent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            target = root / 'target'
            target.mkdir()
            link = root / 'linked'
            link.symlink_to(target, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'user symlink'):
                probe.stage(link / 'candidate')
            self.assertEqual(list(target.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
