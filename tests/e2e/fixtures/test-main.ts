// External source-E2E entry. Forge never imports this module.
import { app } from 'electron';
import { EventEmitter } from 'node:events';
import fs from 'node:fs/promises';
import { appendFileSync, readFileSync, realpathSync, type PathLike } from 'node:fs';
import path from 'node:path';
import type { ChildProcess } from 'node:child_process';
import { bootstrapApplication } from '../../../src/main/bootstrap';
import { isolatedGitSpawn, type Isolation } from './isolation';
import type { UpdateAdapterEvents } from '../../../src/main/services/update-service';
import { redactGitOutput } from '../../../src/main/services/git-runner';

export interface NativeControl {
  directories: Array<string | null>;
  dialogCalls: unknown[];
  launches: Array<{ command: string; args: readonly string[]; shell: boolean | string | undefined }>;
  launcherFails: boolean;
  failWrite: 'manifest' | 'workspace' | 'state' | 'selection' | null;
  failedWrites: string[];
  holdWrite: 'state' | 'selection' | null;
  writeHeld: boolean;
  finishWrite?: () => void;
  updateFails: boolean;
  holdDownload: boolean;
  finishDownload?: () => void;
  installed: boolean;
}

declare global {
  var __reqwsE2E: NativeControl;
}

const configPath = process.env.REQWS_E2E_CONFIG;
if (!configPath || !path.isAbsolute(configPath)) throw new Error('Missing isolated E2E configuration.');
const isolated = JSON.parse(readFileSync(configPath, 'utf8')) as Isolation;
for (const directory of [isolated.home, isolated.userData, isolated.sessionData, isolated.logs]) {
  if (!directory.startsWith(`${isolated.root}${path.sep}`) || realpathSync(directory) !== directory) {
    throw new Error('E2E path escaped its fixture.');
  }
}
const logPath = path.join(isolated.logs, 'main.log');
for (const level of ['log', 'warn', 'error'] as const) {
  const original = console[level];
  console[level] = (...values: unknown[]) => {
    appendFileSync(logPath, `${level}: ${values.map(String).join(' ')}\n`);
    original(...values);
  };
}
console.log('REQWS_E2E_EXTERNAL_MAIN', process.pid);

const control: NativeControl = globalThis.__reqwsE2E = {
  directories: [], dialogCalls: [], launches: [], launcherFails: false,
  failWrite: null, failedWrites: [], holdWrite: null, writeHeld: false,
  updateFails: false, holdDownload: false, installed: false,
};
// Fault injection is at the final OS atomic-publication call, not a service fake.
const matchesWrite = (destination: PathLike, kind: NativeControl['failWrite']): boolean => {
  const target = String(destination);
  if (!target.startsWith(`${isolated.root}${path.sep}`)) throw new Error('E2E write escaped fixture.');
  const names = { manifest: 'workspace.json', state: 'state.v1.json', selection: 'reqws-project.json' };
  return kind === 'workspace' ? target.endsWith('.code-workspace')
    : kind !== null && path.basename(target) === names[kind];
};
for (const operation of ['rename', 'link'] as const) {
  const original = fs[operation];
  fs[operation] = async (source, destination) => {
    if (matchesWrite(destination, control.holdWrite)) {
      control.holdWrite = null;
      control.writeHeld = true;
      await new Promise<void>((resolve) => { control.finishWrite = resolve; });
      control.writeHeld = false;
      control.finishWrite = undefined;
    }
    if (matchesWrite(destination, control.failWrite)) {
      control.failWrite = null;
      control.failedWrites.push(String(destination));
      throw Object.assign(new Error('Isolated E2E atomic publication failure.'), { code: 'EIO' });
    }
    return original(source, destination);
  };
}

const spawnGit = isolatedGitSpawn(isolated);
void bootstrapApplication({
  userDataPath: isolated.userData,
  sessionDataPath: isolated.sessionData,
  logsPath: isolated.logs,
  serviceOptions: {
    getPreferredSystemLanguages: () => ['en-US'],
    spawnGitProcess: (command, args, options) => {
      const child = spawnGit(command, args, options);
      const filename = path.join(isolated.logs, 'git-client.log');
      appendFileSync(filename, `${JSON.stringify({ command, args, cwd: options.cwd })}\n`);
      let remaining = 64_000;
      for (const stream of [child.stdout, child.stderr]) {
        stream.on('data', (chunk: Buffer) => {
          const output = redactGitOutput(String(chunk)).slice(0, remaining);
          remaining -= output.length;
          if (output) appendFileSync(filename, output);
        });
      }
      child.once('close', (code) => appendFileSync(filename, `\nexit=${code}\n`));
      return child;
    },
    dialog: {
      showOpenDialog: (async (...args: unknown[]) => {
        control.dialogCalls.push(args.at(-1));
        const selected = control.directories.shift() ?? null;
        return { canceled: selected === null, filePaths: selected ? [selected] : [] };
      }) as Electron.Dialog['showOpenDialog'],
    },
    editor: {
      homeDirectory: isolated.home,
      systemApplicationsDirectory: path.join(isolated.home, 'Applications'),
      processEnvironment: isolated.env,
      spawnProcess: (command, args, options) => {
        control.launches.push({ command, args, shell: options.shell });
        const child = new EventEmitter() as ChildProcess;
        child.unref = () => undefined;
        queueMicrotask(() => child.emit('close', control.launcherFails ? 1 : 0));
        return child;
      },
    },
    update: {
      currentVersion: app.getVersion(),
      validateInstallLocation: async () => undefined,
      createAdapter: (events: UpdateAdapterEvents) => ({
        check: async () => {
          if (control.updateFails) throw new Error('Isolated update network failure.');
          return { version: '99.0.0', releaseNotes: 'Disposable update adapter.' };
        },
        download: async () => {
          events.progress(50);
          if (control.holdDownload) await new Promise<void>((resolve) => { control.finishDownload = resolve; });
          events.progress(100);
        },
        install: () => { control.installed = true; },
        disarmInstallation: () => undefined,
        dispose: () => undefined,
      }),
    },
  },
});
