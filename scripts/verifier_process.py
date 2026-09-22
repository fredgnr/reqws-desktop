"""Live Gradle output and bounded diagnostics for this verifier's process group only."""

import codecs
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def announce(target, message):
    print(f'[{timestamp()}] [{target}] {message}', flush=True)


def process_group_rows(output, group):
    rows = []
    for line in output.splitlines():
        fields = line.strip().split(None, 7)
        if len(fields) != 8 or not all(value.isdigit() for value in fields[:3]):
            continue
        if int(fields[2]) == group:
            rows.append({'pid': int(fields[0]), 'ppid': int(fields[1]), 'pgid': int(fields[2]),
                         'elapsed': fields[3], 'cpuPercent': fields[4], 'rssKiB': fields[5],
                         'state': fields[6], 'executable': Path(fields[7]).name})
    return rows


def collect_diagnostics(process, directory, reason, target):
    """Best effort, read-only samples; no command arguments or environment dump."""
    directory.mkdir(parents=True, exist_ok=True)
    announce(target, f'diagnostics reason={reason} pid={process.pid} directory={directory}')
    try:
        sample = subprocess.run(['ps', '-axo', 'pid=,ppid=,pgid=,etime=,pcpu=,rss=,stat=,comm='],
                                capture_output=True, text=True, timeout=10, check=True)
        rows = process_group_rows(sample.stdout, process.pid)
        (directory / 'processes.json').write_text(json.dumps({'at': timestamp(), 'reason': reason,
                                                            'processes': rows}, indent=2) + '\n')
        for row in rows:
            announce(target, f"process pid={row['pid']} parent={row['ppid']} elapsed={row['elapsed']} "
                     f"cpu={row['cpuPercent']}% rss={row['rssKiB']}KiB state={row['state']} executable={row['executable']}")
        java_home = os.environ.get('JAVA_HOME')
        jcmd = str(Path(java_home) / 'bin/jcmd') if java_home else shutil.which('jcmd')
        if not jcmd or not Path(jcmd).is_file():
            (directory / 'jcmd-unavailable.txt').write_text('jcmd is unavailable; process sample retained.\n')
            return
        # Recheck group ownership immediately before each attach. Never enumerate all JVMs with jcmd -l.
        for row in [row for row in rows if row['executable'] == 'java'][-4:]:
            try:
                if os.getpgid(row['pid']) != process.pid:
                    continue
                announce(target, f"capturing Java thread dump pid={row['pid']} (15s attach limit)")
                with (directory / f"java-{row['pid']}-threads.txt").open('w') as stream:
                    subprocess.run([jcmd, str(row['pid']), 'Thread.print', '-l'], stdout=stream,
                                   stderr=subprocess.STDOUT, timeout=15, check=False)
            except (OSError, subprocess.TimeoutExpired) as error:
                (directory / f"java-{row['pid']}-diagnostic-error.txt").write_text(f'{type(error).__name__}: {error}\n')
    except (OSError, subprocess.SubprocessError) as error:
        (directory / 'diagnostic-error.txt').write_text(f'{type(error).__name__}: {error}\n')
        announce(target, f'diagnostic unavailable: {type(error).__name__}; verification remains authoritative')


def stop_process_group(process):
    # Popen(start_new_session=True) makes the command PID its own group leader.
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=20)
    except ProcessLookupError:
        pass


def run_logged_process(command, log_path, target, timeout=5400, heartbeat=30, silence=300,
                       diagnose=collect_diagnostics):
    started = last_output = time.monotonic()
    last_heartbeat = started
    last_diagnostic = started
    diagnostic_count = 0
    phase = 'Gradle startup/configuration (no task output yet)'
    carry = ''
    decoder = codecs.getincrementaldecoder('utf-8')(errors='replace')
    progress_path = log_path.parent / 'progress.json'
    process = None

    def progress(state, exit_code=None):
        now = time.monotonic()
        value = {'at': timestamp(), 'target': target, 'state': state, 'phase': phase,
                 'pid': process.pid if process else None, 'elapsedSeconds': round(now - started, 1),
                 'silentSeconds': round(now - last_output, 1), 'logBytes': log_path.stat().st_size,
                 'exitCode': exit_code}
        progress_path.write_text(json.dumps(value, indent=2) + '\n')
        if carry:
            # Keep liveness metadata separate from a child's partial output line.
            # The complete, unmodified child byte stream remains in gradle.log.
            sys.stdout.write('\n')
        announce(target, f"{state}: phase={phase}; pid={value['pid']}; elapsed={value['elapsedSeconds']}s; "
                 f"silent={value['silentSeconds']}s; logBytes={value['logBytes']}; exitCode={exit_code}")

    def forward(data, final=False):
        nonlocal last_output, phase, carry
        if data:
            last_output = time.monotonic()
        text = decoder.decode(data, final=final)
        if text:
            sys.stdout.write(text)
            sys.stdout.flush()
            lines = carry + text
            tasks = re.findall(r'(?:^|\n)> Task ([^\r\n]+)', lines)
            if tasks:
                phase = tasks[-1][:200]
            carry = lines.rsplit('\n', 1)[-1][-1024:]

    def diagnostic(reason):
        # Tool absence, attach failures and filesystem errors must not change the verifier verdict.
        try:
            diagnose(process, log_path.parent / 'diagnostics' / f'{reason}-{diagnostic_count}', reason, target)
        except Exception as error:
            announce(target, f'diagnostic failed: {type(error).__name__}; continuing verdict/cleanup handling')

    with log_path.open('wb') as log, log_path.open('rb') as reader:
        try:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            progress('started')
            while True:
                data = reader.read(64 * 1024)
                forward(data)
                code = process.poll()
                if code is not None:
                    while data := reader.read(64 * 1024):
                        forward(data)
                    forward(b'', final=True)
                    progress('exited', code)
                    return code
                now = time.monotonic()
                if now - started >= timeout:
                    progress('timeout')
                    diagnostic('timeout')
                    raise subprocess.TimeoutExpired(command, timeout)
                if now - last_heartbeat >= heartbeat:
                    progress('heartbeat')
                    last_heartbeat = now
                if diagnostic_count < 3 and now - last_output >= silence and now - last_diagnostic >= silence:
                    diagnostic_count += 1
                    diagnostic('silent')
                    last_diagnostic = time.monotonic()
                if not data:
                    time.sleep(min(0.1, heartbeat, silence))
        except BaseException:
            if process is not None:
                stop_process_group(process)
                while data := reader.read(64 * 1024):
                    forward(data)
                forward(b'', final=True)
                progress('stopped', process.returncode)
            raise
