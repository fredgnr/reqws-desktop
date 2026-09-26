import { test, expect } from '../fixtures/desktop';
import { Desktop } from '../fixtures/desktop';
import { createIsolation } from '../fixtures/isolation';
import { createGitOrigin } from '../fixtures/git-origin';
import { spawn } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

test('D01 fresh built Electron starts with the real preload @smoke', async ({ desktop }) => {
  const { page } = desktop;
  await expect(page.getByRole('heading', { level: 1, name: 'Workspaces' })).toBeVisible();
  await expect(page.getByText('No workspaces yet', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => ({
    bridge: typeof window.reqws.workspaces.create,
    require: typeof (window as unknown as { require?: unknown }).require,
    process: typeof (window as unknown as { process?: unknown }).process,
    ipc: typeof (window as unknown as { ipcRenderer?: unknown }).ipcRenderer,
  }))).toEqual({ bridge: 'function', require: 'undefined', process: 'undefined', ipc: 'undefined' });
});

test('S1 isolated instances coexist and a shared userData contender exits', async ({ desktop }, info) => {
  const isolated = await createIsolation();
  const origin = await createGitOrigin(isolated);
  const second = new Desktop(isolated, origin, info, undefined, 'secondary');
  try {
    await second.start();
    expect(second.app.process().pid).not.toBe(desktop.app.process().pid);
    const executable = await desktop.app.evaluate(() => process.execPath);
    const child = spawn(executable, [path.resolve('.vite/e2e')], {
      env: { ...desktop.isolation.env, REQWS_E2E_CONFIG: path.join(desktop.isolation.root, 'launch.json') },
      shell: false, stdio: 'pipe',
    });
    let logs = '';
    child.stdout.on('data', (chunk: Buffer) => { logs += chunk.toString(); });
    child.stderr.on('data', (chunk: Buffer) => { logs += chunk.toString(); });
    try {
      const result = await new Promise<number | null>((resolve, reject) => {
        const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('Single-instance contender did not exit.')); }, 10_000);
        child.once('error', (error) => { clearTimeout(timer); reject(error); });
        child.once('close', (code) => { clearTimeout(timer); resolve(code); });
      });
      expect(result).toBe(0);
      expect(desktop.app.windows()).toHaveLength(1);
      expect(await desktop.app.evaluate(({ app }) => app.hasSingleInstanceLock())).toBe(true);
    } finally {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      await writeFile(info.outputPath('contender.log'), logs);
    }
  } finally {
    await second.finish();
  }
});

test('S1 a blocked graceful quit fails within a deadline and terminates the owned process', async ({ desktop }) => {
  const child = desktop.app.process();
  await desktop.app.evaluate(({ app }) => app.on('before-quit', (event) => event.preventDefault()));
  await expect(desktop.stop()).rejects.toThrow('Electron shutdown or tracing failed.');
  expect(child.signalCode).toBe('SIGKILL');
});
