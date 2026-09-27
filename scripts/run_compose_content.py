"""Local Compose host runner; exact ZIP and dedicated profile, with explicitly selected input coverage."""
import argparse
import json
import os
from check_compose_artifact import check_compose_artifact
from pathlib import Path
import sys
import tempfile
from check_compose_host_reports import check_host_reports
from run_local_ide import (ROOT, EnvironmentBlocked, local_only, optional_license_server,
    initialize_profile, checked_path, lock_profile, isolate_project_state,
    validate_plugin, read_policy, write_json, execute, session_closed, digest)

parser = argparse.ArgumentParser()
parser.add_argument('--profile', type=Path, required=True)
parser.add_argument('--archive', type=Path, required=True)
parser.add_argument('--version', required=True)
parser.add_argument('--allow-input', action='store_true', help='Run real pointer/keyboard/theme/reload scenarios during an authorized foreground window')
args = parser.parse_args()
run = Path(tempfile.mkdtemp(prefix='reqws-compose-host-')).resolve()
run.chmod(0o700)
(run/'run-root.txt').write_text(str(run)+'\n')
report = {'scope':'compose-host', 'status':'not-run', 'input':'pointer-and-keyboard' if args.allow_input else 'no-keyboard-or-mouse'}
try:
    local_only()
    server = optional_license_server()
    policy = read_policy()
    profile, metadata = initialize_profile(args.profile, policy)
    with lock_profile(profile):
        active = checked_path(profile/'active-session.json')
        if active.exists():
            raise EnvironmentBlocked('PREVIOUS_SESSION_UNCONFIRMED', 'Dedicated session remains unconfirmed')
        archive = checked_path(args.archive)
        candidate = validate_plugin(archive,args.version)
        check_compose_artifact(archive, args.version)
        report['candidate'] = {'path':str(archive),'sha256':candidate['sha256'],'version':args.version}
        if not server and not (profile/'preparation-session.json').is_file():
            raise EnvironmentBlocked('AUTHORIZATION_PREPARATION_REQUIRED','Dedicated authorization preparation required')
        isolate_project_state(profile,run)
        write_json(active,{'runRoot':str(run),'mode':'compose-host'})
        command = [str(ROOT/'integrations/goland/gradlew'),'-p',str(ROOT/'integrations/goland'),
          'composeContentHostTest','--no-daemon','--no-configuration-cache','--console=plain',
          '-Porg.jetbrains.intellij.platform.useCacheRedirector=false',
          f'-PreqwsLocalIdeProfile={profile}',f'-PreqwsLocalIdeRunRoot={run}',
          f'-PreqwsPluginArchive={archive}',f'-PreleaseVersion={args.version}',
          f"-PreqwsPluginSha256={candidate['sha256']}"]
        if args.allow_input:
            command += ['-PreqwsComposeHostInput=true']
        print(f'Compose host report: {run}/report.json',flush=True)
        try:
            code = execute(command,run/'host.log',1800,{**os.environ,
              'REQWS_LOCAL_IDE_RUN_ROOT':str(run),'REQWS_LOCAL_IDE_PROFILE':str(profile)})
        finally:
            if session_closed(run,'run'):
                active.unlink()
        report['exitCode'] = code
        if digest(archive) != candidate['sha256']:
            raise ValueError('Candidate changed')
        if (run/'environment-blocked.json').exists():
            detail=json.loads((run/'environment-blocked.json').read_text())
            raise EnvironmentBlocked(detail['code'],detail['message'])
        if code != 0:
            raise ValueError('Host failed; inspect host.log')
        report.update(status='passed', **check_host_reports(run, args.allow_input),
                      actualIde=json.loads((run/'actual-ide.json').read_text()))

except EnvironmentBlocked as e:
    report.update(status='environment-blocked',code=e.code,message=str(e))
except Exception as e:
    report.update(status='failed',message=str(e))
finally:
    write_json(run/'report.json',report)
    print(json.dumps(report,ensure_ascii=False),flush=True)
    print(f'Compose host report: {run}/report.json',flush=True)
sys.exit(0 if report['status']=='passed' else 2)
