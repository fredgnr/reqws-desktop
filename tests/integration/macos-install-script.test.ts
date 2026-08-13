import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import {
  chmod,
  cp,
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import {
  acquireExclusiveLock,
  machArchitecture,
  parseCodeSigningDetails,
  parseCliOptions,
  type InstallOperations,
  replaceAppBundle,
  sanitizeElectronLaunchEnvironment,
  validateExistingReqwsBundle,
} from '../../scripts/install-macos.mjs';

const temporaryDirectories: string[] = [];
const repositoryRoot = path.resolve(import.meta.dirname, '../..');
const launcherPath = path.join(repositoryRoot, 'scripts/run-install-macos.mjs');

function installedPre24Node(): string | undefined {
  const nvmDirectory = process.env.NVM_DIR ?? path.join(os.homedir(), '.nvm');
  const versionsDirectory = path.join(nvmDirectory, 'versions', 'node');
  try {
    for (const entry of readdirSync(versionsDirectory, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const candidate = path.join(versionsDirectory, entry.name, 'bin', 'node');
      if (!existsSync(candidate)) continue;
      const version = spawnSync(candidate, ['-p', 'process.versions.node'], {
        encoding: 'utf8',
      }).stdout.trim();
      if (/^\d+\./u.test(version) && Number(version.split('.')[0]) < 24) return candidate;
    }
  } catch {
    return undefined;
  }
  return undefined;
}

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-install-test-'));
  temporaryDirectories.push(directory);
  return directory;
}

async function fakeApp(parent: string, marker: string): Promise<string> {
  const app = path.join(parent, 'Source ReqWS.app');
  await mkdir(path.join(app, 'Contents'), { recursive: true });
  await writeFile(path.join(app, 'Contents', 'marker'), marker, 'utf8');
  return app;
}

async function identityApp(
  parent: string,
  bundleId: string,
  executable = 'ReqWS',
): Promise<string> {
  const app = path.join(parent, `${bundleId}.app`);
  await mkdir(path.join(app, 'Contents'), { recursive: true });
  await writeFile(path.join(app, 'Contents', 'Info.plist'), `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>${bundleId}</string>
<key>CFBundleExecutable</key><string>${executable}</string>
</dict></plist>`, 'utf8');
  return app;
}

function filesystemOperations(): InstallOperations {
  return {
    copyBundle: async (source, destination) => {
      await cp(source, destination, { recursive: true });
    },
    move: async (source, destination) => {
      const { rename } = await import('node:fs/promises');
      await rename(source, destination);
    },
    remove: async (target) => {
      await rm(target, { force: true, recursive: true });
    },
  };
}

async function marker(app: string): Promise<string> {
  return await readFile(path.join(app, 'Contents', 'marker'), 'utf8');
}

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map(async (directory) => {
    await rm(directory, { force: true, recursive: true });
  }));
});

describe('macOS install scaffold', () => {
  it('rejects the ad-hoc plus Hardened Runtime combination that macOS cannot launch', () => {
    expect(parseCodeSigningDetails(`
flags=0x10002(adhoc,runtime) hashes=3+7 location=embedded
Signature=adhoc
TeamIdentifier=not set
`)).toEqual({ adHoc: true, hardenedRuntime: true });
    expect(parseCodeSigningDetails(`
flags=0x2(adhoc) hashes=3+7 location=embedded
Signature=adhoc
TeamIdentifier=not set
`)).toEqual({ adHoc: true, hardenedRuntime: false });
  });

  it('removes Electron Node mode before launching the installed app', () => {
    expect(sanitizeElectronLaunchEnvironment({
      ELECTRON_RUN_AS_NODE: '1',
      HOME: '/Users/tester',
      SSH_AUTH_SOCK: '/tmp/agent.sock',
    })).toEqual({
      HOME: '/Users/tester',
      SSH_AUTH_SOCK: '/tmp/agent.sock',
    });
  });

  it('make install relaunches with Node 24 when PATH starts with an older nvm Node', () => {
    const oldNode = installedPre24Node();
    if (!oldNode) return;

    const environment = { ...process.env };
    delete environment.REQWS_NODE24;
    environment.PATH = [path.dirname(oldNode), '/usr/bin', '/bin'].join(path.delimiter);
    const result = spawnSync(
      '/usr/bin/make',
      ['install', 'INSTALL_ARGS=--dry-run'],
      { cwd: repositoryRoot, encoding: 'utf8', env: environment },
    );
    const output = `${result.stdout}${result.stderr}`;

    expect(result.status, output).toBe(0);
    expect(output).toContain('using Node v24');
    expect(output).toContain('ReqWS macOS install plan');
    expect(output).not.toContain('ERR_UNKNOWN_FILE_EXTENSION');
  });

  it('accepts an explicit Node 24 executable', () => {
    const result = spawnSync(
      process.execPath,
      [launcherPath, '--dry-run'],
      {
        cwd: repositoryRoot,
        encoding: 'utf8',
        env: { ...process.env, REQWS_NODE24: process.execPath },
      },
    );
    const output = `${result.stdout}${result.stderr}`;

    expect(result.status, output).toBe(0);
    expect(output).toContain('ReqWS macOS install plan');
  });

  it('fails clearly for invalid explicit Node 24 overrides', async () => {
    const root = await temporaryDirectory();
    const oldNode = path.join(root, 'node');
    await writeFile(
      oldNode,
      '#!/bin/sh\nif [ "$1" = "-p" ]; then printf "22.14.0\\n"; else exit 2; fi\n',
      'utf8',
    );
    await chmod(oldNode, 0o755);

    const cases = [
      { expected: 'absolute path', value: 'relative/node' },
      { expected: 'does not exist', value: path.join(root, 'missing-node') },
      { expected: 'must point to Node.js 24', value: oldNode },
    ];
    for (const testCase of cases) {
      const result = spawnSync(
        process.execPath,
        [launcherPath, '--dry-run'],
        {
          cwd: repositoryRoot,
          encoding: 'utf8',
          env: { ...process.env, REQWS_NODE24: testCase.value },
        },
      );
      const output = `${result.stdout}${result.stderr}`;
      expect(result.status, output).toBe(1);
      expect(output).toContain(testCase.expected);
      expect(output).not.toContain('ERR_UNKNOWN_FILE_EXTENSION');
    }
  });

  it('parses safe defaults and explicit overrides', () => {
    expect(parseCliOptions([], {}, 'arm64')).toMatchObject({
      arch: 'arm64',
      installDirectory: '/Applications',
      noLaunch: false,
      packageOnly: false,
      skipCheck: false,
      skipCi: false,
    });
    expect(parseCliOptions([
      '--arch', 'x64',
      '--install-dir', '/tmp/Applications With Spaces',
      '--no-launch',
      '--skip-ci',
      '--skip-check',
    ], {}, 'arm64')).toMatchObject({
      arch: 'x64',
      installDirectory: '/tmp/Applications With Spaces',
      noLaunch: true,
      skipCheck: true,
      skipCi: true,
    });
  });

  it('rejects relative install paths and unsupported architectures', () => {
    expect(() => parseCliOptions(['--install-dir', 'Applications'], {}, 'arm64'))
      .toThrow('absolute path');
    expect(() => parseCliOptions(['--arch', 'universal'], {}, 'arm64'))
      .toThrow('Unsupported architecture');
    expect(() => parseCliOptions(['--install-dir', '/Applications/ReqWS.app'], {}, 'arm64'))
      .toThrow('parent directory');
  });

  it('maps Forge architecture names to Mach-O architecture names', () => {
    expect(machArchitecture('arm64')).toBe('arm64');
    expect(machArchitecture('x64')).toBe('x86_64');
  });

  it('performs a first install without touching adjacent user state', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications With Spaces');
    const state = path.join(root, 'Library/Application Support/ReqWS/state.json');
    await mkdir(applications, { recursive: true });
    await mkdir(path.dirname(state), { recursive: true });
    await writeFile(state, 'KEEP', 'utf8');
    const source = await fakeApp(root, 'version-1');

    const installed = await replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations: filesystemOperations(),
      sourceApp: source,
      transientId: 'first-install',
      validate: async (app) => { expect(await marker(app)).toBe('version-1'); },
      validateExisting: async () => undefined,
    });

    expect(await marker(installed)).toBe('version-1');
    expect(await readFile(state, 'utf8')).toBe('KEEP');
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('updates by replacing the whole bundle instead of merging it', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    const installed = path.join(applications, 'ReqWS.app');
    await mkdir(path.join(installed, 'Contents'), { recursive: true });
    await writeFile(path.join(installed, 'Contents', 'marker'), 'old', 'utf8');
    await writeFile(path.join(installed, 'Contents', 'stale-file'), 'remove me', 'utf8');
    const source = await fakeApp(root, 'new');

    await replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations: filesystemOperations(),
      sourceApp: source,
      transientId: 'whole-replace',
      validate: async () => undefined,
      validateExisting: async () => undefined,
    });

    expect(await marker(installed)).toBe('new');
    await expect(accessFile(path.join(installed, 'Contents', 'stale-file'))).resolves.toBe(false);
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('restores the previous app when post-install validation fails', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    const installed = path.join(applications, 'ReqWS.app');
    await mkdir(path.join(installed, 'Contents'), { recursive: true });
    await writeFile(path.join(installed, 'Contents', 'marker'), 'known-good', 'utf8');
    const source = await fakeApp(root, 'broken-update');

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations: filesystemOperations(),
      sourceApp: source,
      transientId: 'rollback',
      validate: async (app) => {
        if (path.basename(app) === 'ReqWS.app') throw new Error('post-install validation failed');
      },
      validateExisting: async () => undefined,
    })).rejects.toThrow('post-install validation failed');

    expect(await marker(installed)).toBe('known-good');
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('cleans a partial staging copy and leaves the old app untouched', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    const installed = path.join(applications, 'ReqWS.app');
    await mkdir(path.join(installed, 'Contents'), { recursive: true });
    await writeFile(path.join(installed, 'Contents', 'marker'), 'known-good', 'utf8');
    const source = await fakeApp(root, 'new');
    const operations = filesystemOperations();
    operations.copyBundle = async (_source, destination) => {
      await mkdir(destination);
      throw new Error('copy interrupted');
    };

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations,
      sourceApp: source,
      transientId: 'copy-failure',
      validate: async () => undefined,
      validateExisting: async () => undefined,
    })).rejects.toThrow('copy interrupted');

    expect(await marker(installed)).toBe('known-good');
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('restores the old app if publishing the staged app fails', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    const installed = path.join(applications, 'ReqWS.app');
    await mkdir(path.join(installed, 'Contents'), { recursive: true });
    await writeFile(path.join(installed, 'Contents', 'marker'), 'known-good', 'utf8');
    const source = await fakeApp(root, 'new');
    const baseOperations = filesystemOperations();
    let moves = 0;
    const operations: InstallOperations = {
      ...baseOperations,
      move: async (from, to) => {
        moves += 1;
        if (moves === 2) throw new Error('publish failed');
        await baseOperations.move(from, to);
      },
    };

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations,
      sourceApp: source,
      transientId: 'publish-failure',
      validate: async () => undefined,
      validateExisting: async () => undefined,
    })).rejects.toThrow('publish failed');

    expect(await marker(installed)).toBe('known-good');
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('keeps a successful install when launch fails', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    await mkdir(applications);
    const source = await fakeApp(root, 'new');

    await expect(replaceAppBundle({
      installDirectory: applications,
      launch: async () => { throw new Error('Launch Services unavailable'); },
      operations: filesystemOperations(),
      sourceApp: source,
      transientId: 'launch-failure',
      validate: async () => undefined,
      validateExisting: async () => undefined,
    })).resolves.toBe(path.join(applications, 'ReqWS.app'));

    expect(await marker(path.join(applications, 'ReqWS.app'))).toBe('new');
  });

  it('rejects a symlink installed-app target', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    const elsewhere = path.join(root, 'Elsewhere.app');
    await mkdir(applications, { recursive: true });
    await mkdir(elsewhere);
    await symlink(elsewhere, path.join(applications, 'ReqWS.app'));
    const source = await fakeApp(root, 'new');

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations: filesystemOperations(),
      sourceApp: source,
      validate: async () => undefined,
      validateExisting: async () => undefined,
    })).rejects.toThrow('must be a real directory');
  });

  it('rejects source/target overlap before copying', async () => {
    const root = await temporaryDirectory();
    const source = await fakeApp(root, 'new');
    const operations = filesystemOperations();

    await expect(replaceAppBundle({
      installDirectory: source,
      noLaunch: true,
      operations,
      sourceApp: source,
      validate: async () => undefined,
      validateExisting: async () => undefined,
    })).rejects.toThrow('separate parent directory');

    await expect(replaceAppBundle({
      installDirectory: path.join(source, 'Contents'),
      noLaunch: true,
      operations,
      sourceApp: source,
      validate: async () => undefined,
      validateExisting: async () => undefined,
    })).rejects.toThrow('separate parent directory');
  });

  it('refuses to replace an unrelated same-name app', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    const installed = path.join(applications, 'ReqWS.app');
    await mkdir(path.join(installed, 'Contents'), { recursive: true });
    await writeFile(path.join(installed, 'Contents', 'marker'), 'unrelated', 'utf8');
    const source = await fakeApp(root, 'new');

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations: filesystemOperations(),
      sourceApp: source,
      validate: async () => undefined,
      validateExisting: async () => { throw new Error('not an installed ReqWS bundle'); },
    })).rejects.toThrow('not an installed ReqWS bundle');

    expect(await marker(installed)).toBe('unrelated');
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('revalidates an app that appears while the staging copy is created', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    await mkdir(applications);
    const installed = path.join(applications, 'ReqWS.app');
    const source = await fakeApp(root, 'new');
    const operations = filesystemOperations();
    operations.copyBundle = async (from, to) => {
      await cp(from, to, { recursive: true });
      await mkdir(path.join(installed, 'Contents'), { recursive: true });
      await writeFile(path.join(installed, 'Contents', 'marker'), 'unrelated', 'utf8');
    };

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations,
      sourceApp: source,
      transientId: 'appeared-target',
      validate: async () => undefined,
      validateExisting: async () => { throw new Error('not an installed ReqWS bundle'); },
    })).rejects.toThrow('not an installed ReqWS bundle');

    expect(await marker(installed)).toBe('unrelated');
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('revalidates an existing app after the staging copy is created', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    const installed = path.join(applications, 'ReqWS.app');
    await mkdir(path.join(installed, 'Contents'), { recursive: true });
    await writeFile(path.join(installed, 'Contents', 'marker'), 'known-good', 'utf8');
    const source = await fakeApp(root, 'new');
    const operations = filesystemOperations();
    operations.copyBundle = async (from, to) => {
      await cp(from, to, { recursive: true });
      await rm(installed, { recursive: true });
      await mkdir(path.join(installed, 'Contents'), { recursive: true });
      await writeFile(path.join(installed, 'Contents', 'marker'), 'unrelated', 'utf8');
    };

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations,
      sourceApp: source,
      transientId: 'replaced-target',
      validate: async () => undefined,
      validateExisting: async (app) => {
        if (await marker(app) !== 'known-good') {
          throw new Error('not an installed ReqWS bundle');
        }
      },
    })).rejects.toThrow('not an installed ReqWS bundle');

    expect(await marker(installed)).toBe('unrelated');
    expect(await readdir(applications)).toEqual(['ReqWS.app']);
  });

  it('recognizes only current and legacy ReqWS bundle identities', async () => {
    const root = await temporaryDirectory();
    const current = await identityApp(root, 'com.reqws.desktop');
    const legacy = await identityApp(root, 'com.electron.reqws');
    const unrelated = await identityApp(root, 'com.example.unrelated');

    await expect(validateExistingReqwsBundle(current)).resolves.toBeUndefined();
    await expect(validateExistingReqwsBundle(legacy)).resolves.toBeUndefined();
    await expect(validateExistingReqwsBundle(unrelated)).rejects.toThrow(
      'not an installed ReqWS bundle',
    );
  });

  it('refuses to proceed past artifacts from an interrupted transaction', async () => {
    const root = await temporaryDirectory();
    const applications = path.join(root, 'Applications');
    await mkdir(path.join(applications, '.reqws-backup-old-run.app'), { recursive: true });
    const source = await fakeApp(root, 'new');

    await expect(replaceAppBundle({
      installDirectory: applications,
      noLaunch: true,
      operations: filesystemOperations(),
      sourceApp: source,
      validate: async () => undefined,
      validateExisting: async () => undefined,
    })).rejects.toThrow('previous interrupted ReqWS install');
  });

  it('serializes concurrent build/install runs and releases its lock', async () => {
    const root = await temporaryDirectory();
    const lockPath = path.join(root, 'install.lock');
    const releaseFirst = await acquireExclusiveLock(lockPath);

    await expect(acquireExclusiveLock(lockPath)).rejects.toThrow(
      'Another ReqWS build/install lock is running',
    );
    await releaseFirst();

    const releaseSecond = await acquireExclusiveLock(lockPath);
    await releaseSecond();
    await expect(accessFile(lockPath)).resolves.toBe(false);
  });
});

async function accessFile(target: string): Promise<boolean> {
  try {
    await readFile(target);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false;
    throw error;
  }
}
