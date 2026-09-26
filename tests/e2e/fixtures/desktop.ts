import { _electron, test as base, expect, type ElectronApplication, type Page, type TestInfo } from '@playwright/test';
import { appendFile, cp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createIsolation, type Isolation } from './isolation';
import { createGitOrigin, type GitOrigin } from './git-origin';
import type { AppState } from '../../../src/shared/types';
import type { NativeControl } from './test-main';
import { registerOwnedProcess } from './process-registry';

const defaultEntry = path.resolve('.vite/e2e');

async function bounded<T>(operation: Promise<T>, milliseconds: number, label: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      operation,
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error(`${label} timed out.`)), milliseconds); }),
    ]);
  } finally { clearTimeout(timer); }
}

export class Desktop {
  app!: ElectronApplication;
  page!: Page;
  readonly errors: string[] = [];
  readonly console: Array<{ type: string; text: string }> = [];
  private generation = 0;
  private running = false;
  private stopFlight?: Promise<void>;
  private writes: Array<Promise<void>> = [];
  private logErrors: unknown[] = [];
  private child?: ReturnType<ElectronApplication['process']>;

  constructor(
    readonly isolation: Isolation, readonly origin: GitOrigin, readonly info: TestInfo,
    private readonly entry = defaultEntry, private readonly label = 'primary',
  ) {}

  async start(): Promise<void> {
    this.generation += 1;
    this.writes = [];
    this.logErrors = [];
    const output = this.info.outputPath(`${this.label}-process-${this.generation}`);
    await mkdir(output, { recursive: true });
    const configPath = path.join(this.isolation.root, 'launch.json');
    await writeFile(configPath, JSON.stringify(this.isolation));
    await writeFile(path.join(output, 'launch.json'), JSON.stringify({
      entry: this.entry, userData: this.isolation.userData, chromiumSandbox: true,
      electron: process.versions.electron, node: process.version,
      platform: process.platform, arch: process.arch,
    }, null, 2));
    try {
      this.app = await _electron.launch({
        args: [this.entry],
        env: { ...this.isolation.env, REQWS_E2E_CONFIG: configPath },
        chromiumSandbox: true,
        timeout: 20_000,
        artifactsDir: output,
      });
      this.running = true;
      const child = this.app.process();
      this.child = child;
      registerOwnedProcess(child);
      const log = (filename: string, chunk: Buffer) => {
        this.writes.push(appendFile(path.join(output, filename), chunk).catch((error: unknown) => { this.logErrors.push(error); }));
      };
      child.stdout?.on('data', (chunk: Buffer) => log('stdout.log', chunk));
      child.stderr?.on('data', (chunk: Buffer) => log('stderr.log', chunk));
      await this.app.context().tracing.start({ screenshots: true, snapshots: true, sources: true });
      this.page = await this.app.firstWindow();
      const onError = (error: Error) => { if (!this.errors.includes(String(error))) this.errors.push(String(error)); };
      const onConsole = (message: { type(): string; text(): string }) => {
        const entry = { type: message.type(), text: message.text() };
        if (!this.console.some((existing) => existing.type === entry.type && existing.text === entry.text)) this.console.push(entry);
        // Chromium reports this pre-existing, ineffective meta directive as an
        // error. Preserve it in evidence; all other console errors fail tests.
        if (message.type() === 'error' && message.text() !== "The Content Security Policy directive 'frame-ancestors' is ignored when delivered via a <meta> element.") {
          if (!this.errors.includes(message.text())) this.errors.push(message.text());
        }
      };
      this.page.on('pageerror', onError);
      this.page.on('console', onConsole);
      // Events emitted by early renderer/preload scripts precede firstWindow.
      // Read Playwright's startup buffers as well as subscribing to new events.
      for (const error of await this.page.pageErrors()) onError(error);
      for (const message of await this.page.consoleMessages()) onConsole(message);
      const runtime = await this.app.evaluate(({ app, BrowserWindow }) => ({
        userData: app.getPath('userData'), sessionData: app.getPath('sessionData'),
        pid: process.pid, electron: process.versions.electron, argv: process.argv,
        noSandbox: app.commandLine.hasSwitch('no-sandbox'),
        renderer: app.getAppMetrics().find((metric) => metric.pid === BrowserWindow.getAllWindows()[0]?.webContents.getOSProcessId()),
      }));
      await writeFile(path.join(output, 'runtime.json'), JSON.stringify(runtime, null, 2));
      expect(runtime.userData).toBe(this.isolation.userData);
      expect(runtime.sessionData).toBe(this.isolation.sessionData);
      expect(runtime.argv).not.toContain('--no-sandbox');
      expect(runtime.noSandbox).toBe(false);
      expect(runtime.renderer?.sandboxed, 'OS-level renderer sandbox').toBe(true);
      const cdp = await this.app.context().newCDPSession(this.page);
      const contexts: Array<{ id: number; name: string }> = [];
      cdp.on('Runtime.executionContextCreated', ({ context }) => contexts.push(context));
      await cdp.send('Runtime.enable');
      const preload = contexts.find((context) => context.name === 'Electron Isolated Context');
      expect(preload, 'real isolated preload execution context').toBeDefined();
      await cdp.detach();
      await expect(this.page.getByRole('navigation')).toBeVisible();
      expect(this.errors, 'renderer startup errors').toEqual([]);
    } catch (error) {
      await writeFile(path.join(output, 'launch-error.txt'), String(error));
      throw error;
    }
  }

  async stop(): Promise<void> {
    if (this.stopFlight) return this.stopFlight;
    if (!this.running) return;
    this.stopFlight = this.stopRunning();
    try { await this.stopFlight; } finally {
      this.running = !!this.child && this.child.exitCode === null && this.child.signalCode === null;
      this.stopFlight = undefined;
    }
  }

  private async stopRunning(): Promise<void> {
    const output = this.info.outputPath(`${this.label}-process-${this.generation}`);
    const failures: unknown[] = [];
    try {
      if (this.page && !this.page.isClosed()) {
        await this.page.screenshot({ path: path.join(output, 'window.png'), timeout: 5_000 });
      }
      await bounded(this.app.context().tracing.stop({ path: path.join(output, 'trace.zip') }), 5_000, 'Electron tracing stop');
    } catch (error) { failures.push(error); }
    const child = this.app.process();
    try { await bounded(this.app.close(), 3_000, 'Electron graceful exit'); } catch (error) { failures.push(error); }
    try {
      if (child.exitCode === null && child.signalCode === null && child.pid) {
        // Playwright's locked POSIX launcher creates a detached group led by
        // this exact owned PID; include its Chromium/Git children in cleanup.
        process.kill(-child.pid, 'SIGKILL');
      }
      await expect.poll(() => child.exitCode !== null || child.signalCode !== null).toBe(true);
      await Promise.all(this.writes);
      failures.push(...this.logErrors);
      await writeFile(path.join(output, 'exit.json'), JSON.stringify({ pid: child.pid, code: child.exitCode, signal: child.signalCode }));
      expect(child.exitCode, 'Electron exited cleanly').toBe(0);
      expect(child.signalCode).toBeNull();
    } catch (error) { failures.push(error); }
    if (failures.length) throw new AggregateError(failures, 'Electron shutdown or tracing failed.');
  }

  async restart(): Promise<void> { await this.stop(); await this.start(); }

  async control(update: Partial<NativeControl>): Promise<void> {
    await this.app.evaluate((_electron, value) => Object.assign(globalThis.__reqwsE2E, value), update);
  }

  async state(): Promise<AppState> {
    return JSON.parse(await readFile(path.join(this.isolation.userData, 'reqws/state.v1.json'), 'utf8')) as AppState;
  }

  async captureDisk(): Promise<void> {
    const files: Record<string, unknown> = {};
    const walk = async (directory: string): Promise<void> => {
      for (const item of await readdir(directory, { withFileTypes: true })) {
        if (item.name === '.git' || item.isSymbolicLink()) continue;
        const filename = path.join(directory, item.name);
        if (item.isDirectory()) await walk(filename);
        else {
          const relative = path.relative(this.isolation.root, filename);
          files[relative] = /(?:\.json|\.code-workspace|\.txt)$/u.test(filename)
            ? (await readFile(filename, 'utf8')).slice(0, 32_000) : 'present';
        }
      }
    };
    await walk(this.isolation.workspaceRoot);
    await walk(this.isolation.outputRoot);
    const state = await this.state().catch(() => null);
    await writeFile(this.info.outputPath(this.label === 'primary' ? 'disk.json' : `${this.label}-disk.json`), JSON.stringify({ state, files }, null, 2));
  }

  async finish(): Promise<void> {
    const failures: unknown[] = [];
    let serverClosed = true;
    const attempt = async (action: () => Promise<unknown>): Promise<void> => {
      try { await action(); } catch (error) { failures.push(error); }
    };
    await mkdir(this.info.outputPath(), { recursive: true });
    await attempt(() => this.stop());
    // This is an observed process state, not the fact that stop() was requested.
    const stopped = !this.child || this.child.exitCode !== null || this.child.signalCode !== null;
    if (!stopped) failures.push(new Error('Owned Electron process is still alive; preserving its fixture.'));
    await attempt(() => this.captureDisk());
    await attempt(async () => { try { await this.origin.close(); } catch (error) { serverClosed = false; throw error; } });
    await attempt(() => cp(this.isolation.logs, this.info.outputPath(this.label === 'primary' ? 'logs' : `${this.label}-logs`), { recursive: true }));
    await attempt(() => writeFile(this.info.outputPath(`${this.label}-renderer-console.json`), JSON.stringify(this.console)));
    await attempt(() => writeFile(this.info.outputPath(this.label === 'primary' ? 'renderer-errors.json' : `${this.label}-renderer-errors.json`), JSON.stringify(this.errors)));
    // Preserve diagnostics and the fixture if a process could still be alive.
    if (stopped && serverClosed) await attempt(() => this.isolation.dispose());
    if (failures.length) throw new AggregateError(failures, `E2E cleanup or evidence failed: ${this.isolation.root}`);
  }
}

export const test = base.extend<{ desktop: Desktop }>({
  // Playwright requires a destructured fixture argument, even with no dependencies.
  // eslint-disable-next-line no-empty-pattern
  desktop: async ({}, runTest, info) => {
    const isolation = await createIsolation();
    let origin: GitOrigin | undefined;
    let desktop: Desktop | undefined;
    try {
      origin = await createGitOrigin(isolation);
      desktop = new Desktop(isolation, origin, info);
      await desktop.start();
      await runTest(desktop);
      expect(desktop.errors).toEqual([]);
    } finally {
      if (desktop) await desktop.finish();
      else {
        await mkdir(info.outputPath(), { recursive: true });
        try {
          await origin?.close();
          await cp(isolation.logs, info.outputPath('logs'), { recursive: true });
        } finally { await isolation.dispose(); }
      }
    }
  },
});

export { expect };
