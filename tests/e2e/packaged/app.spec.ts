import { _electron, expect, test, type ElectronApplication, type Page, type TestInfo } from '@playwright/test';
import { execFileSync, type ChildProcess } from 'node:child_process';
import { appendFile, lstat, mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { AppState } from '../../../src/shared/types';
import { registerOwnedProcess } from '../fixtures/process-registry';

interface Candidate {
  app: string;
  executable: string;
  resources: string;
  version: string;
  profile: string;
  signatureVerified: boolean;
}

function authorize(): Candidate {
  const app = process.env.REQWS_PACKAGED_APP;
  if (!app) throw new Error('Packaged smoke requires an explicit app through desktop_e2e.py.');
  // Also enforce the guard when a developer directly invokes this Playwright
  // config. Never set CI/runner variables to enable this on a local account.
  return JSON.parse(execFileSync('python3', [
    path.resolve('scripts/desktop_e2e.py'), '--mode', 'packaged', '--app', app, '--authorize-only',
  ], { encoding: 'utf8', shell: false, timeout: 130_000, stdio: ['ignore', 'pipe', 'pipe'] })) as Candidate;
}

const expectedUserData = '/Users/runner/Library/Application Support/ReqWS';
const knownConsoleError = "The Content Security Policy directive 'frame-ancestors' is ignored when delivered via a <meta> element.";

async function bounded<T>(promise: Promise<T>, milliseconds: number, label: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([promise, new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} timed out.`)), milliseconds);
    })]);
  } finally { if (timer) clearTimeout(timer); }
}

class PackagedApp {
  app?: ElectronApplication;
  page?: Page;
  child?: ChildProcess;
  readonly errors: string[] = [];
  readonly requests: string[] = [];
  readonly writes: Array<Promise<void>> = [];
  readonly logErrors: unknown[] = [];
  private generation = 0;

  constructor(readonly candidate: Candidate, readonly info: TestInfo) {}

  async start(): Promise<number> {
    expect(authorize()).toEqual(this.candidate);
    this.generation += 1;
    const output = this.info.outputPath(`process-${this.generation}`);
    await mkdir(output, { recursive: true });
    await mkdir(this.info.outputPath('logs'), { recursive: true });
    await writeFile(path.join(output, 'launch.json'), JSON.stringify({
      executable: this.candidate.executable, args: [], chromiumSandbox: true,
      profile: this.candidate.profile,
    }, null, 2));
    try {
      this.app = await _electron.launch({
        executablePath: this.candidate.executable,
        args: [],
        chromiumSandbox: true,
        timeout: 20_000,
        artifactsDir: output,
        // Avoid recording GitHub credentials in tracing's launch environment.
        env: {
          PATH: process.env.PATH ?? '/usr/bin:/bin:/usr/sbin:/sbin',
          HOME: '/Users/runner',
          TMPDIR: process.env.RUNNER_TEMP!,
          LANG: 'en_US.UTF-8',
          LC_ALL: 'en_US.UTF-8',
        },
      });
      const child = this.app.process();
      this.child = child;
      registerOwnedProcess(child);
      await writeFile(path.join(output, 'stdout.log'), '');
      await writeFile(path.join(output, 'stderr.log'), '');
      for (const [name, stream] of [['stdout', child.stdout], ['stderr', child.stderr]] as const) {
        stream?.on('data', (chunk: Buffer) => {
          this.writes.push(appendFile(path.join(output, `${name}.log`), chunk).catch((error) => { this.logErrors.push(error); }));
          this.writes.push(appendFile(this.info.outputPath('logs/main.log'), chunk).catch((error) => { this.logErrors.push(error); }));
        });
      }
      await this.app.context().tracing.start({ screenshots: true, snapshots: true, sources: true });
      this.page = await this.app.firstWindow();
      const onError = (error: Error) => { if (!this.errors.includes(String(error))) this.errors.push(String(error)); };
      const onConsole = (message: Awaited<ReturnType<Page['consoleMessages']>>[number]) => {
        if (message.type() === 'error' && message.text() !== knownConsoleError && !this.errors.includes(message.text())) this.errors.push(message.text());
      };
      this.page.on('pageerror', onError);
      this.page.on('console', onConsole);
      this.page.on('requestfailed', (request) => this.requests.push(`${request.url()}: ${request.failure()?.errorText}`));
      for (const error of await this.page.pageErrors()) onError(error);
      for (const message of await this.page.consoleMessages()) onConsole(message);
      const runtime = await this.app.evaluate(({ app, BrowserWindow }) => {
        const window = BrowserWindow.getAllWindows()[0]!;
        return {
          packaged: app.isPackaged, appPath: app.getAppPath(), execPath: process.execPath,
          userData: app.getPath('userData'), version: app.getVersion(),
          pid: process.pid, electron: process.versions.electron, platform: process.platform, arch: process.arch,
          argv: process.argv, noSandbox: app.commandLine.hasSwitch('no-sandbox'),
          renderer: app.getAppMetrics().find((metric) => metric.pid === window.webContents.getOSProcessId()),
        };
      });
      await writeFile(path.join(output, 'runtime.json'), JSON.stringify(runtime, null, 2));
      await appendFile(this.info.outputPath('logs/main.log'), `Main runtime: ${JSON.stringify(runtime)}\n`);
      expect(runtime).toMatchObject({
        packaged: true, appPath: this.candidate.resources, execPath: this.candidate.executable,
        userData: expectedUserData, version: this.candidate.version, platform: 'darwin', arch: 'arm64', noSandbox: false,
        renderer: { sandboxed: true },
      });
      expect(runtime.argv).not.toContain('--no-sandbox');
      const cdp = await this.app.context().newCDPSession(this.page);
      const contexts: Array<{ id: number; name: string }> = [];
      cdp.on('Runtime.executionContextCreated', ({ context }) => contexts.push(context));
      await cdp.send('Runtime.enable');
      expect(contexts.find((context) => context.name === 'Electron Isolated Context'), 'packaged isolated preload context').toBeDefined();
      await cdp.detach();
      await expect(this.page.getByRole('navigation')).toBeVisible();
      expect(decodeURIComponent(this.page.url())).toBe(`file://${this.candidate.resources}/.vite/renderer/main_window/index.html`);
      expect(await this.page.evaluate(() => ({
        bridge: typeof window.reqws.settings.save,
        require: typeof (window as unknown as { require?: unknown }).require,
        process: typeof (window as unknown as { process?: unknown }).process,
        styles: [...document.styleSheets].some((sheet) => sheet.href?.includes('/assets/')),
      }))).toEqual({ bridge: 'function', require: 'undefined', process: 'undefined', styles: true });
      expect(this.errors, 'packaged renderer startup errors').toEqual([]);
      return runtime.pid;
    } catch (error) {
      await writeFile(path.join(output, 'launch-error.txt'), String(error));
      throw error;
    }
  }

  async stop(): Promise<void> {
    if (!this.app) return;
    const application = this.app;
    this.app = undefined;
    const output = this.info.outputPath(`process-${this.generation}`);
    const child = this.child!;
    const failures: unknown[] = [];
    try {
      if (this.page && !this.page.isClosed()) await this.page.screenshot({ path: path.join(output, 'window.png'), timeout: 5_000 });
    } catch (error) { failures.push(error); }
    try { await bounded(application.context().tracing.stop({ path: path.join(output, 'trace.zip') }), 5_000, 'Packaged tracing stop'); } catch (error) { failures.push(error); }
    try { await bounded(application.close(), 3_000, 'Packaged graceful exit'); } catch (error) { failures.push(error); }
    try {
      if (child.exitCode === null && child.signalCode === null && child.pid) process.kill(-child.pid, 'SIGKILL');
      await expect.poll(() => child.exitCode !== null || child.signalCode !== null, { timeout: 5_000 }).toBe(true);
      await Promise.all(this.writes);
      failures.push(...this.logErrors);
      await writeFile(path.join(output, 'exit.json'), JSON.stringify({ pid: child.pid, code: child.exitCode, signal: child.signalCode }));
      expect({ code: child.exitCode, signal: child.signalCode }).toEqual({ code: 0, signal: null });
    } catch (error) { failures.push(error); }
    if (failures.length) throw new AggregateError(failures, 'Packaged shutdown, tracing or logging failed.');
  }

  async disk(): Promise<AppState> {
    return JSON.parse(await readFile(path.join(expectedUserData, 'reqws/state.v1.json'), 'utf8')) as AppState;
  }

  async finish(state: Record<string, AppState>): Promise<void> {
    const failures: unknown[] = [];
    const attempt = async (action: () => Promise<unknown>): Promise<void> => {
      try { await action(); } catch (error) { failures.push(error); }
    };
    await attempt(() => this.stop());
    await attempt(async () => {
      if (this.child?.pid && this.child.exitCode === null && this.child.signalCode === null) {
        process.kill(-this.child.pid, 'SIGKILL');
        await expect.poll(() => this.child!.exitCode !== null || this.child!.signalCode !== null, { timeout: 5_000 }).toBe(true);
        throw new Error('Packaged cleanup required a forced process-group exit.');
      }
    });
    await attempt(() => mkdir(this.info.outputPath(), { recursive: true }));
    await attempt(() => Promise.all(this.writes));
    await attempt(() => writeFile(this.info.outputPath('disk.json'), JSON.stringify(state, null, 2)));
    await attempt(() => writeFile(this.info.outputPath('renderer-errors.json'), JSON.stringify(this.errors)));
    await attempt(() => writeFile(this.info.outputPath('resource-errors.json'), JSON.stringify(this.requests)));
    await attempt(() => writeFile(this.info.outputPath('candidate.json'), JSON.stringify(this.candidate, null, 2)));
    failures.push(...this.logErrors);
    if (failures.length) throw new AggregateError(failures, 'Packaged cleanup or evidence capture failed.');
  }
}

// eslint-disable-next-line no-empty-pattern
test('exact CI ad-hoc app loads its bridge/resources and persists settings across exit/restart', async ({}, info) => {
  const candidate = authorize();
  expect(candidate).toMatchObject({ profile: 'ci-adhoc', signatureVerified: true });
  // The disposable CI account must have no preexisting ReqWS data. Preserve and
  // fail if it does; never clean a profile to make this gate pass.
  await expect(lstat(expectedUserData)).rejects.toMatchObject({ code: 'ENOENT' });
  const before = await stat(candidate.resources);
  const app = new PackagedApp(candidate, info);
  const state: Record<string, AppState> = {};
  try {
    const firstPid = await app.start();
    await expect(app.page!.getByRole('heading', { level: 1, name: 'Workspaces' })).toBeVisible();
    await app.page!.getByRole('navigation').getByRole('button', { name: 'Settings', exact: true }).click();
    await app.page!.getByLabel('Interface language', { exact: true }).selectOption('zh-CN');
    await app.page!.getByRole('button', { name: 'Save settings', exact: true }).click();
    await expect(app.page!.getByRole('heading', { level: 1, name: '设置' })).toBeVisible();
    await expect.poll(async () => (await app.disk()).settings.localePreference).toBe('zh-CN');
    state.saved = await app.disk();
    await app.stop();
    const secondPid = await app.start();
    expect(secondPid).not.toBe(firstPid);
    await app.page!.getByRole('navigation').getByRole('button', { name: '设置', exact: true }).click();
    await expect(app.page!.getByRole('combobox')).toHaveValue('zh-CN');
    state.restarted = await app.disk();
    expect(state.restarted.settings.localePreference).toBe('zh-CN');
    await app.stop();
    expect(app.errors).toEqual([]);
    expect(app.requests).toEqual([]);
    const after = await stat(candidate.resources);
    expect([after.dev, after.ino, after.size, after.mtimeMs]).toEqual([before.dev, before.ino, before.size, before.mtimeMs]);
  } finally { await app.finish(state); }
});
