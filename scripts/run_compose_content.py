"""Local S1 Content lifecycle runner; exact ZIP and dedicated profile, without keyboard or mouse input."""
import argparse
import json
import os
from check_compose_artifact import check_compose_artifact
from pathlib import Path
import sys
import tempfile
import xml.etree.ElementTree as ET
from run_local_ide import (ROOT, EnvironmentBlocked, local_only, optional_license_server,
    initialize_profile, checked_path, lock_profile, isolate_project_state,
    validate_plugin, read_policy, write_json, execute, session_closed, digest)

parser = argparse.ArgumentParser()
parser.add_argument('--profile', type=Path, required=True)
parser.add_argument('--archive', type=Path, required=True)
parser.add_argument('--version', required=True)
args = parser.parse_args()
run = Path(tempfile.mkdtemp(prefix='reqws-compose-s1-host-')).resolve()
run.chmod(0o700)
(run/'run-root.txt').write_text(str(run)+'\n')
report = {'scope':'compose-s1-content', 'status':'not-run', 'input':'no-keyboard-or-mouse'}
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
        write_json(active,{'runRoot':str(run),'mode':'compose-s1-content'})
        command = [str(ROOT/'integrations/goland/gradlew'),'-p',str(ROOT/'integrations/goland'),
          'composeContentHostTest','--no-daemon','--no-configuration-cache','--console=plain',
          '-Porg.jetbrains.intellij.platform.useCacheRedirector=false',
          f'-PreqwsLocalIdeProfile={profile}',f'-PreqwsLocalIdeRunRoot={run}',
          f'-PreqwsPluginArchive={archive}',f'-PreleaseVersion={args.version}',
          f"-PreqwsPluginSha256={candidate['sha256']}"]
        print(f'S1 Content report: {run}/report.json',flush=True)
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
        cases = [c for p in (run/'junit').glob('TEST-*.xml') for c in ET.parse(p).getroot().iter('testcase')]
        expected_test = 'contentLifecycleIsIndependentOfProjectSynchronization()'
        if len(cases)!=1 or cases[0].get('name')!=expected_test or any(c.find(t) is not None for c in cases for t in ['failure','error','skipped']):
            raise ValueError('Required non-skipped S1 Content scenario did not pass')
        events = [line.split('\t') for line in (run/'processes.tsv').read_text().splitlines()]
        expected_processes = 1
        pids = list(dict.fromkeys(pid for pid, phase in events))
        if len(pids) != expected_processes or any([phase for seen, phase in events if seen == pid] != ['started', 'passed', 'exited'] for pid in pids):
            raise ValueError('Process cleanup did not pass')
        report.update(status='passed',tests=1,skipped=0,processes=1,
                      actualIde=json.loads((run/'actual-ide.json').read_text()))

except EnvironmentBlocked as e:
    report.update(status='environment-blocked',code=e.code,message=str(e))
except Exception as e:
    report.update(status='failed',message=str(e))
finally:
    write_json(run/'report.json',report)
    print(json.dumps(report,ensure_ascii=False),flush=True)
    print(f'S1 Content report: {run}/report.json',flush=True)
sys.exit(0 if report['status']=='passed' else 2)
