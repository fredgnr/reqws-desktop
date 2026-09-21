"""Local-only Starter entry: dedicated interactive authorization and exact-ZIP integration."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import tempfile
import uuid
from urllib.parse import urlsplit

from check_ide_test_reports import check_reports
from ide_compatibility import ROOT, digest, read_policy, write_json
from plugin_release import validate_plugin

PROFILE_MARKER = '.reqws-ide-profile.json'
PROJECT_STATE = ('workspace', 'options/recentProjects.xml', 'options/recentProjectDirectories.xml',
                 'options/trusted-paths.xml')


class EnvironmentBlocked(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def local_only():
    if any(os.environ.get(key, '').lower() not in {'', '0', 'false'} for key in
           ('CI', 'GITHUB_ACTIONS', 'TEAMCITY_VERSION', 'JENKINS_URL', 'BUILD_BUILDID')):
        raise EnvironmentBlocked('CI_NOT_ALLOWED', 'Complete IDE startup is local-only; CI retains platform tests and API verification.')
    if platform.system() != 'Darwin':
        raise EnvironmentBlocked('UNSUPPORTED_HOST', 'The fixed local representative requires a macOS graphical session.')


def checked_path(path):
    path = Path(path).expanduser().absolute()
    for item in (path, *path.parents):
        # macOS's system aliases are canonicalized; user-created links are rejected.
        if item.is_symlink() and str(item) not in {'/tmp', '/var'}:
            raise EnvironmentBlocked('UNSAFE_PATH', 'Local run/profile paths must not traverse user symlinks.')
    return path.resolve()


def initialize_profile(path, policy):
    path = checked_path(path)
    marker = path / PROFILE_MARKER
    identity = {'schemaVersion': 1, 'purpose': 'reqws-local-ide-authorization',
                'product': 'GO', 'version': policy['uiTestIdeVersion'], 'build': policy['uiTestIdeBuild']}
    if not marker.exists():
        if path.exists() and (not path.is_dir() or any(path.iterdir())):
            raise EnvironmentBlocked('PROFILE_NOT_OWNED', 'Initialize an empty dedicated directory; daily IDE configuration cannot be used.')
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        write_json(marker, {**identity, 'id': str(uuid.uuid4())})
        marker.chmod(0o600)
    checked_path(marker)
    metadata = json.loads(marker.read_text())
    if any(metadata.get(key) != value for key, value in identity.items()):
        raise EnvironmentBlocked('PROFILE_MISMATCH', 'The profile belongs to another purpose or IDE version.')
    path.chmod(0o700)
    config = checked_path(path / 'config')
    config.mkdir(exist_ok=True, mode=0o700)
    for name in ('dependencies', 'installers'):
        checked_path(path / name).mkdir(exist_ok=True, mode=0o700)
    return path, metadata


@contextmanager
def lock_profile(profile):
    descriptor = os.open(profile / '.local-ide.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise EnvironmentBlocked('PROFILE_BUSY', 'Another local authorization/integration session owns this profile.') from error
        yield
    finally:
        os.close(descriptor)


def isolate_project_state(profile, run_root):
    # Move only known project metadata in our dedicated config, never licensing/account files.
    # Preserve it privately instead of deleting it or including it in a report bundle.
    for relative in PROJECT_STATE:
        source = profile / 'config' / relative
        checked_path(source)
        if source.exists():
            target = run_root / 'private-previous-project-state' / relative
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            source.rename(target)
    config = profile / 'config'
    options = checked_path(config / 'options')
    options.mkdir(exist_ok=True, mode=0o700)
    # These are harness-owned settings, not a copy of any user's IDE configuration.
    general = checked_path(options / 'ide.general.xml')
    general.write_text('<application><component name="GeneralSettings">'
                       '<option name="reopenLastProject" value="false"/>'
                       '<option name="confirmExit" value="false"/>'
                       '<option name="showTipsOnStartup" value="false"/>'
                       '</component></application>\n')
    checked_path(config / 'migrate.config').write_text('properties intellij.first.ide.session\n')


def session_closed(run_root, mode):
    if not (run_root / 'ide-launch-requested').exists():
        return True  # e.g. dependency/compilation failure before any IDE launch
    if mode == 'prepare':
        return (run_root / 'authorization-session.json').is_file()
    events_path = run_root / 'processes.tsv'
    if not events_path.is_file():
        return False
    events = {}
    for line in events_path.read_text().splitlines():
        pid, phase = line.split('\t')
        events.setdefault(pid, []).append(phase)
    requested = int((run_root / 'ide-launch-requested').read_text().strip())
    return len(events) == requested and requested > 0 and all(
        phases.count('started') == 1 and phases[-1] == 'exited' for phases in events.values())


def optional_license_server():
    value = os.environ.get('JETBRAINS_LICENSE_SERVER', '')
    if value:
        try:
            parsed = urlsplit(value)
        except ValueError as error:
            raise EnvironmentBlocked('INVALID_LICENSE_SERVER', 'Optional License Server URL is invalid.') from error
        if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment):
            raise EnvironmentBlocked('INVALID_LICENSE_SERVER', 'Optional License Server must be credential-free HTTPS; no URL tokens.')
    return bool(value)


def execute(command, log_path, timeout, environment):
    with log_path.open('w') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True, env=environment)
        try:
            return process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            # Only this command's process group; never locate or kill a daily IDE.
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=20)
            except ProcessLookupError:
                pass
            raise


def verify_report(path, archive, version):
    report = json.loads(Path(path).read_text())
    policy = read_policy()
    if (report.get('scope') != 'local-ide-integration' or report.get('status') != 'passed'
            or report.get('candidate', {}).get('sha256') != digest(archive)
            or report['candidate'].get('version') != version
            or report.get('actualIde') != report.get('ide')
            or report.get('ide') != {'product': 'GO', 'version': policy['uiTestIdeVersion'], 'build': policy['uiTestIdeBuild']}):
        raise ValueError('No passing local UI evidence for these exact bytes, plugin version and representative IDE.')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'run', 'verify-report'])
    parser.add_argument('--profile', type=Path)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--version')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if args.command in {'run', 'verify-report'} and (not args.archive or not args.version):
        parser.error('--archive and --version are required for the exact candidate')
    if args.command == 'verify-report':
        if not args.report:
            parser.error('--report is required')
        verify_report(args.report, args.archive, args.version)
        print('Local integration report matches the exact candidate; no IDE was started.')
        return 0
    if not args.profile:
        parser.error('--profile is required; use an empty dedicated directory initially')
    policy = read_policy()
    run_root = Path(tempfile.mkdtemp(prefix='reqws-local-ide-')).resolve()
    run_root.chmod(0o700)
    marker = run_root / 'run-root.txt'
    marker.write_text(str(run_root) + '\n')
    report = {'schemaVersion': 1, 'scope': 'local-ide-integration' if args.command == 'run' else 'local-ide-authorization',
              'status': 'not-run', 'startedAt': datetime.now(timezone.utc).isoformat(),
              'ide': {'product': 'GO', 'version': policy['uiTestIdeVersion'], 'build': policy['uiTestIdeBuild']},
              'starterVersion': policy['starterVersion'], 'execution': 'local-only',
              'authorization': 'unverified', 'actualIde': None, 'results': None}
    report_path = run_root / 'report.json'
    write_json(report_path, report)
    try:
        local_only()
        server = optional_license_server()
        report['licenseServerConfigured'] = server
        profile, metadata = initialize_profile(args.profile, policy)
        report['profileId'] = metadata['id']
        if profile == run_root or profile in run_root.parents or run_root in profile.parents:
            raise EnvironmentBlocked('OVERLAPPING_PATHS', 'The persistent profile and disposable run directory must be disjoint.')
        with lock_profile(profile):
            active = checked_path(profile / 'active-session.json')
            if active.exists():
                raise EnvironmentBlocked('PREVIOUS_SESSION_UNCONFIRMED', 'A previous local IDE session did not confirm shutdown. Inspect its private run directory, close that dedicated IDE, then explicitly clear active-session.json; never stop a daily IDE.')
            if args.command == 'run':
                archive = checked_path(args.archive)
                candidate = validate_plugin(archive, args.version)
                report['candidate'] = {'path': str(archive), 'sha256': candidate['sha256'], 'version': args.version}
                if not server and not (profile / 'preparation-session.json').is_file():
                    raise EnvironmentBlocked('AUTHORIZATION_PREPARATION_REQUIRED', 'Run prepare with this profile and sign in in the dedicated IDE, or use an optional authorized License Server.')
            isolate_project_state(profile, run_root)
            write_json(report_path, report)
            command = [str(ROOT / 'integrations/goland/gradlew'), '-p', str(ROOT / 'integrations/goland'),
                       'prepareLocalIdeAuthorization' if args.command == 'prepare' else 'checkIdeIntegration',
                       '--no-daemon', '--no-configuration-cache', '--console=plain',
                       f'-PreqwsLocalIdeProfile={profile}', f'-PreqwsLocalIdeRunRoot={run_root}']
            if args.command == 'run':
                command += [f'-PreqwsPluginArchive={archive}', f'-PreleaseVersion={args.version}',
                            f"-PreqwsPluginSha256={candidate['sha256']}"]
            print(f'Local {args.command}; private report: {report_path}', flush=True)
            if args.command == 'prepare':
                print('Use JetBrains Account in the dedicated GoLand window, then quit that test IDE normally. Do not import/sync personal settings or open real projects.', flush=True)
            write_json(active, {'runRoot': str(run_root), 'mode': args.command})
            try:
                code = execute(command, run_root / 'host.log', 3900, {**os.environ,
                    'REQWS_LOCAL_IDE_RUN_ROOT': str(run_root), 'REQWS_LOCAL_IDE_PROFILE': str(profile)})
            finally:
                if session_closed(run_root, args.command):
                    active.unlink()
            report['exitCode'] = code
            if args.command == 'run' and digest(archive) != candidate['sha256']:
                raise ValueError('Candidate bytes changed; signed and unsigned artifacts cannot share evidence.')
            blocked = run_root / 'environment-blocked.json'
            if blocked.is_file():
                detail = json.loads(blocked.read_text())
                raise EnvironmentBlocked(detail['code'], detail['message'])
            if code != 0:
                raise ValueError('Local host failed; inspect the private host log and XML. No UI pass is recorded.')
            if args.command == 'prepare':
                session = json.loads((run_root / 'authorization-session.json').read_text())
                if session.get('status') != 'closed' or session.get('ide') != report['ide']:
                    raise ValueError('Authorization preparation did not close normally on the fixed IDE.')
                write_json(profile / 'preparation-session.json', {'status': 'session-closed-not-verified', 'ide': report['ide']})
                report['status'] = 'preparation-closed'
            else:
                report['results'] = check_reports(run_root / 'junit', marker)
                actual = json.loads((run_root / 'actual-ide.json').read_text())
                if actual != report['ide']:
                    raise ValueError('Actual IDE differs from the fixed representative.')
                report['actualIde'] = actual
                report['status'] = 'passed'
                report['authorization'] = 'ide-started-without-authorization-dialog'
    except EnvironmentBlocked as error:
        report.update(status='environment-blocked', code=error.code, message=str(error))
    except subprocess.TimeoutExpired:
        report.update(status='environment-blocked', code='HOST_TIMEOUT', message='The local host exceeded its deadline; inspect private process diagnostics before reusing the profile.')
    except KeyboardInterrupt:
        report.update(status='failed', code='INTERRUPTED', message='Local session interrupted; no UI pass.')
    except PermissionError:
        report.update(status='environment-blocked', code='FILE_PERMISSION_DENIED', message='The dedicated local profile or run files are not accessible with current filesystem permissions.')
    except Exception as error:
        report.update(status='failed', code=type(error).__name__, message='Local preparation, execution or evidence validation failed; inspect the private host log.')
    finally:
        report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        write_json(report_path, report)
        print(f"{report['status']}: {report.get('code', '')} {report.get('message', '')}\nReport: {report_path}", flush=True)
    return 0 if report['status'] in {'passed', 'preparation-closed'} else 2


if __name__ == '__main__':
    sys.exit(main())
