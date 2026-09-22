"""Read the latest published ZIP for a separate weekly API scan; never publish or rebuild."""

import json
from pathlib import Path
import subprocess
import sys

from ide_compatibility import digest
from plugin_release import plugin_checksum, validate_version

metadata = json.loads(subprocess.check_output(['gh', 'release', 'view', '--json', 'tagName,isDraft,isPrerelease,assets']))
if metadata['isDraft'] or metadata['isPrerelease'] or not metadata['tagName'].startswith('v'):
    raise ValueError('Latest release must be a published stable semantic version')
version = validate_version(metadata['tagName'][1:])
filename = f'ReqWS-{version}-goland-plugin.zip'
for expected in (filename, 'SHA256SUMS'):
    if sum(item['name'] == expected and item['size'] > 0 for item in metadata['assets']) != 1:
        raise ValueError('Required release asset missing or ambiguous')
directory = Path('released-download')
directory.mkdir(exist_ok=False)
subprocess.run(['gh', 'release', 'download', metadata['tagName'], '--dir', str(directory),
                '--pattern', filename, '--pattern', 'SHA256SUMS'], check=True)
if digest(directory / filename) != plugin_checksum((directory / 'SHA256SUMS').read_bytes(), filename):
    raise ValueError('Published plugin checksum mismatch')
subprocess.run([sys.executable, 'scripts/stage_ide_candidate.py', '--input', str(directory / filename),
                '--version', version, '--historical'], check=True)
