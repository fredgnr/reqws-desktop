"""Validate runtime and test isolation of an explicit Compose candidate; never builds or launches it."""

import argparse
import io
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from plugin_release import validate_plugin


HOST_OR_TEST_PACKAGES = (
    'androidx/compose/', 'org/jetbrains/compose/', 'org/jetbrains/jewel/',
    'org/jetbrains/skiko/', 'org/jetbrains/skia/', 'kotlin/', 'kotlinx/coroutines/',
    'org/junit/', 'junit/', 'org/hamcrest/', 'com/intellij/driver/',
    'com/intellij/ide/starter/', 'com/jetbrains/performancePlugin/remotedriver/',
)
NATIVE_SUFFIXES = ('.dylib', '.so', '.dll')
S0_TEST_CLASSES = (
    'com/reqws/goland/ComposeHostProbeTest', 'com/reqws/goland/S0',
    'com/reqws/goland/ui/ReqwsComposeProbeLabelTest',
)

COMPOSE_TEST_CLASSES = S0_TEST_CLASSES + (
    'com/reqws/goland/ComposeContentLifecycleTest', 'com/reqws/goland/ComposeLifecycle',
    'com/reqws/goland/ui/ReqwsScreenTest', 'com/reqws/goland/ui/ReqwsTestTheme',
)


def check_compose_artifact(path, version):
    # Preserve the existing identity, descriptor, ZIP containment and size checks.
    result = validate_plugin(path, version)
    classes = 0
    with ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.filename.endswith(NATIVE_SUFFIXES):
                raise ValueError('Compose candidate must use the host native runtime')
            if not entry.filename.endswith('.jar'):
                continue
            with ZipFile(io.BytesIO(archive.read(entry))) as jar:
                for name in jar.namelist():
                    if name.endswith(NATIVE_SUFFIXES):
                        raise ValueError('Compose candidate must use the host native runtime')
                    # Inspect class paths rather than JAR names, including shaded JARs
                    # whose entries retain their original package names.
                    logical_name = name
                    if name.startswith('META-INF/versions/'):
                        logical_name = name.split('/', 3)[-1]
                    if logical_name.endswith('.class'):
                        classes += 1
                        if logical_name.startswith(HOST_OR_TEST_PACKAGES + COMPOSE_TEST_CLASSES):
                            raise ValueError(f'Bundled host/test class: {logical_name}')
                    if name == 'META-INF/plugin.xml':
                        descriptor = ET.fromstring(jar.read(name))
                        compose = [node for node in descriptor.findall('depends')
                                   if (node.text or '').strip() == 'com.intellij.modules.compose']
                        if len(compose) != 1 or compose[0].get('optional', 'false') != 'false':
                            raise ValueError('Exactly one required Compose module dependency is needed')
    if not classes:
        raise ValueError('Compose candidate contains no classes')
    return {'version': result['version'], 'classes': classes}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    print(check_compose_artifact(args.archive, args.version))
