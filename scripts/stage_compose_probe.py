"""Recreate the disposable S0 candidate from its fixed Git baseline and source patch."""

import argparse
import io
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
# This mainline commit has the same archived paths as the original S0 merge
# baseline and remains reachable when the Compose branch is rebased.
BASE = 'e90cd310b2250594a22148b877b9a6882e752943'
PATCH = ROOT / 'tests/fixtures/goland-compose-s0/probe.patch'


def stage(output=None, variant='compose'):
    if variant not in {'compose', 'swing'}:
        raise ValueError('Unknown S0 variant')
    patch = PATCH if variant == 'compose' else PATCH.with_name('swing.patch')
    if output is not None:
        destination = Path(output).absolute()
        if any(path.is_symlink() for path in (destination, *destination.parents)
               if str(path) not in {'/tmp', '/var'}):
            raise ValueError('Probe output must not traverse a user symlink')
        if destination.exists():
            raise FileExistsError(destination)
        parent = destination.parent
    else:
        parent = Path(tempfile.gettempdir())
    # Git apply interprets paths differently inside a checkout. Refuse that case
    # before creating files so an explicit output cannot reach the parent tree.
    inside_git = subprocess.run(['git', 'rev-parse', '--is-inside-work-tree'], cwd=parent,
                                capture_output=True, text=True, check=False)
    if inside_git.returncode == 0 and inside_git.stdout.strip() == 'true':
        raise ValueError('Probe output must be outside a Git working tree')
    if output is None:
        destination = Path(tempfile.mkdtemp(prefix='reqws-compose-s0-')).resolve()
    else:
        destination.mkdir(mode=0o700, parents=False, exist_ok=False)
        destination = destination.resolve()
    payload = subprocess.check_output([
        'git', 'archive', BASE, 'integrations/goland', 'scripts', 'package.json',
    ], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
        for entry in archive:
            name = PurePosixPath(entry.name)
            if name.is_absolute() or '..' in name.parts or not (entry.isdir() or entry.isfile()):
                raise ValueError('Unexpected Git archive member')
            target = destination / entry.name
            if entry.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(entry).read())
                target.chmod(entry.mode & 0o777)
    # git apply works outside a checkout. It neither creates a worktree nor changes
    # the user's branch/index; only the newly allocated fixture directory is edited.
    subprocess.run(['git', 'apply', '--check', str(patch)], cwd=destination, check=True)
    subprocess.run(['git', 'apply', str(patch)], cwd=destination, check=True)
    return destination


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='New directory outside Git; existing paths are refused')
    parser.add_argument('--variant', choices=['compose', 'swing'], default='compose')
    args = parser.parse_args()
    print(stage(args.output, args.variant))
