"""Prepare the immutable ZIP/version interface shared by API and Starter jobs."""

import argparse
from pathlib import Path
from plugin_release import validate_plugin
from ide_compatibility import digest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input', type=Path, required=True)
parser.add_argument('--version', required=True)
parser.add_argument('--output', type=Path, default=Path('candidate'))
parser.add_argument('--historical', action='store_true')
args = parser.parse_args()
metadata = validate_plugin(args.input, args.version, historical=args.historical)
args.output.mkdir(parents=True, exist_ok=False)
(args.output / 'plugin.zip').write_bytes(args.input.read_bytes())
if digest(args.output / 'plugin.zip') != metadata['sha256']:
    raise ValueError('Candidate changed during staging')
(args.output / 'version.txt').write_text(args.version + '\n')
