import { execFileSync, type ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { appendFileSync, realpathSync } from 'node:fs';
import path from 'node:path';

/** Register only a process just created by this host, never discover by name. */
export function registerOwnedProcess(child: ChildProcess): void {
  const filename = process.env.REQWS_E2E_PROCESS_REGISTRY;
  if (!filename || !child.pid) return;
  const reports = path.join(process.cwd(), 'test-results');
  if (path.dirname(filename) !== reports || realpathSync(reports) !== reports
      || !/^(source|smoke|negative|packaged)-processes\.jsonl$/u.test(path.basename(filename))) {
    throw new Error('Refusing a process registry outside the test reports directory.');
  }
  let signature: string;
  try {
    // PID, owned process-group ID, and kernel creation time survive exec.
    signature = execFileSync('/bin/ps', ['-p', String(child.pid), '-o', 'pid=,pgid=,lstart='], {
      encoding: 'utf8', timeout: 5_000, stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
  } catch (error) {
    if (child.exitCode !== null || child.signalCode !== null) return;
    try { process.kill(child.pid, 0); } catch (probe) {
      if (probe instanceof Error && 'code' in probe && probe.code === 'ESRCH') return;
    }
    throw error;
  }
  const [pid, group] = signature.split(/\s+/u).map(Number);
  if (pid !== child.pid || group !== child.pid) throw new Error('Fixture process does not lead its own process group.');
  const entry = { id: randomUUID(), pid: child.pid, signature };
  appendFileSync(filename, `${JSON.stringify({ event: 'start', ...entry })}\n`, { mode: 0o600 });
  child.once('close', () => appendFileSync(filename, `${JSON.stringify({ event: 'close', ...entry })}\n`));
}
