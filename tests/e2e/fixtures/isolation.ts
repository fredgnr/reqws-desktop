import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { assertOwnedProcessesExited, registerOwnedProcess } from './process-registry';

import {
  sanitizeGitEnvironment,
  type SpawnGitProcess,
} from '../../../src/main/services/git-runner';

const OWNER_FILE = '.reqws-e2e-owner';
const FIXTURE_PATH = '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin';
const GIT_ENVIRONMENT_KEYS = ['PATH', 'XDG_CONFIG_HOME', 'TMPDIR', 'LANG', 'LC_ALL'] as const;

export interface Isolation {
  root: string;
  home: string;
  userData: string;
  sessionData: string;
  logs: string;
  workspaceRoot: string;
  outputRoot: string;
  originRoot: string;
  hooks: string;
  env: Record<string, string>;
  dispose(): Promise<void>;
}

export async function createIsolation(): Promise<Isolation> {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'reqws-e2e-')));
  const identity = await lstat(root);
  const owner = randomUUID();
  const ownerPath = path.join(root, OWNER_FILE);
  await writeFile(ownerPath, `${owner}\n`, { flag: 'wx', mode: 0o600 });
  let disposed = false;

  const dispose = async (): Promise<void> => {
    if (disposed) return;
    assertOwnedProcessesExited(path.join(root, 'home'));
    const current = await lstat(root);
    if (
      !current.isDirectory()
      || current.isSymbolicLink()
      || current.dev !== identity.dev
      || current.ino !== identity.ino
      || await realpath(root) !== root
    ) {
      throw new Error('Refusing to remove an E2E root whose directory identity changed.');
    }
    const marker = await lstat(ownerPath);
    if (
      !marker.isFile()
      || marker.isSymbolicLink()
      || await readFile(ownerPath, 'utf8') !== `${owner}\n`
    ) {
      throw new Error('Refusing to remove an E2E root without its ownership marker.');
    }
    await rm(root, { recursive: true });
    disposed = true;
  };

  try {
    const directories = {
      home: path.join(root, 'home'),
      userData: path.join(root, 'user-data'),
      sessionData: path.join(root, 'session-data'),
      logs: path.join(root, 'logs'),
      workspaceRoot: path.join(root, 'workspaces'),
      outputRoot: path.join(root, 'output'),
      originRoot: path.join(root, 'origins'),
      hooks: path.join(root, 'empty-hooks'),
    };
    for (const directory of Object.values(directories)) {
      await mkdir(directory, { mode: 0o700 });
      if (await realpath(directory) !== directory) {
        throw new Error('An E2E directory escaped the canonical fixture root.');
      }
    }
    const xdgConfig = path.join(directories.home, '.config');
    const temporary = path.join(directories.sessionData, 'tmp');
    await mkdir(xdgConfig, { mode: 0o700 });
    await mkdir(temporary, { mode: 0o700 });

    return {
      root,
      ...directories,
      env: {
        PATH: FIXTURE_PATH,
        HOME: directories.home,
        XDG_CONFIG_HOME: xdgConfig,
        TMPDIR: temporary,
        LANG: 'en_US.UTF-8',
        LC_ALL: 'en_US.UTF-8',
      },
      dispose,
    };
  } catch (error) {
    await dispose();
    throw error;
  }
}

/** Trusted test-host transport; production Git sanitation remains unchanged. */
export function isolatedGitSpawn(
  isolation: Pick<Isolation, 'home' | 'hooks' | 'env'>,
): SpawnGitProcess {
  return (command, args, options) => {
    const environment: NodeJS.ProcessEnv = {};
    for (const key of GIT_ENVIRONMENT_KEYS) {
      if (isolation.env[key] !== undefined) environment[key] = isolation.env[key];
    }
    environment.HOME = isolation.home;
    const sanitized = sanitizeGitEnvironment(environment);
    // GitRunner intentionally strips inherited GIT_* values. Only this fixed
    // test-host value is added afterwards, excluding the machine's Git config.
    sanitized.GIT_CONFIG_NOSYSTEM = '1';
    const child = spawn(command, [
      '-c', 'credential.helper=',
      '-c', 'core.askPass=',
      '-c', `core.hooksPath=${isolation.hooks}`,
      '-c', `init.templateDir=${isolation.hooks}`,
      '-c', 'commit.gpgSign=false',
      '-c', 'tag.gpgSign=false',
      '-c', 'http.sslVerify=true',
      ...args,
    ], {
      ...options,
      // Connection tests and clone have no cwd. Do not let source launches
      // discover the developer checkout's local Git configuration.
      cwd: options.cwd ?? isolation.home,
      env: sanitized,
      shell: false,
      stdio: 'pipe',
    });
    if (options.detached) registerOwnedProcess(child, isolation.home);
    return child;
  };
}
