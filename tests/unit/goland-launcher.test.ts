import type { ChildProcess, SpawnOptions } from 'node:child_process';
import { EventEmitter } from 'node:events';
import {
  chmod,
  mkdir,
  mkdtemp,
  realpath,
  rename,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  EditorLauncher,
  type ReadPlistValue,
  type RealpathPath,
  type SpawnOpenProcess,
} from '../../src/main/services/editor-launcher';

interface ProcessCall {
  command: string;
  args: readonly string[];
  options: SpawnOptions;
}

const sandboxes: string[] = [];

async function sandbox(): Promise<string> {
  const created = await mkdtemp(path.join(os.tmpdir(), 'reqws-goland-'));
  const canonical = await realpath(created);
  sandboxes.push(canonical);
  return canonical;
}

async function createGoLandBundle(
  applicationPath: string,
  options: {
    bundleIdentifier?: string;
    executableName?: string;
    version?: string;
  } = {},
): Promise<string> {
  const bundleIdentifier = options.bundleIdentifier ?? 'com.jetbrains.goland';
  const executableName = options.executableName ?? 'goland';
  const version = options.version ?? '2026.1.3';
  const executablePath = path.join(
    applicationPath,
    'Contents',
    'MacOS',
    executableName,
  );
  await mkdir(path.dirname(executablePath), { recursive: true });
  await writeFile(
    path.join(applicationPath, 'Contents', 'Info.plist'),
    `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>${bundleIdentifier}</string>
<key>CFBundleExecutable</key><string>${executableName}</string>
<key>CFBundleShortVersionString</key><string>${version}</string>
</dict></plist>\n`,
    'utf8',
  );
  await writeFile(executablePath, '#!/bin/sh\nexit 0\n', 'utf8');
  await chmod(executablePath, 0o755);
  return executablePath;
}

async function writeToolboxState(
  homeDirectory: string,
  tools: readonly Record<string, string>[],
): Promise<void> {
  const statePath = path.join(
    homeDirectory,
    'Library',
    'Application Support',
    'JetBrains',
    'Toolbox',
    'state.json',
  );
  await mkdir(path.dirname(statePath), { recursive: true });
  await writeFile(
    statePath,
    JSON.stringify({ version: 1, tools }),
    'utf8',
  );
}

function successfulSpawner(calls: ProcessCall[]): SpawnOpenProcess {
  return (command, args, options) => {
    calls.push({ command, args, options });
    const child = new EventEmitter();
    queueMicrotask(() => child.emit('close', 0));
    return child as ChildProcess;
  };
}

afterEach(async () => {
  await Promise.all(
    sandboxes.splice(0).map((directory) =>
      rm(directory, { force: true, recursive: true })),
  );
});

describe('EditorLauncher GoLand support', () => {
  it('canonicalizes and validates a GoLand bundle in user Applications', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const installedApplication = path.join(
      directory,
      'toolbox-apps',
      'GoLand-2026.1.app',
    );
    await createGoLandBundle(installedApplication);
    const userApplication = path.join(
      homeDirectory,
      'Applications',
      'GoLand.app',
    );
    await mkdir(path.dirname(userApplication), { recursive: true });
    await symlink(installedApplication, userApplication);

    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      systemApplicationsDirectory: path.join(directory, 'Applications'),
      resolveGitPath: async () => '/usr/bin/git',
    });

    const availability = await launcher.getAvailability();

    expect(availability.goland).toEqual({
      available: true,
      path: installedApplication,
    });
  });

  it('uses validated Toolbox state and deterministically prefers stable GoLand', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const stableApplication = path.join(
      directory,
      'toolbox-apps',
      'GoLand-2026.1.app',
    );
    const previewApplication = path.join(
      directory,
      'toolbox-apps',
      'GoLand-2026.2-EAP.app',
    );
    const stableLauncher = await createGoLandBundle(stableApplication, {
      version: '2026.1.3',
    });
    const previewLauncher = await createGoLandBundle(previewApplication, {
      version: '2026.2 EAP',
    });
    await writeToolboxState(homeDirectory, [
      {
        productCode: 'GO',
        toolId: 'Goland',
        displayVersion: '2026.2 EAP',
        channelId: 'Goland-EAP',
        launchCommand: previewLauncher,
      },
      {
        productCode: 'GO',
        toolId: 'Goland',
        displayVersion: '2026.1.3',
        channelId: 'Goland-Release',
        launchCommand: stableLauncher,
      },
    ]);
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      systemApplicationsDirectory: path.join(directory, 'Applications'),
      processEnvironment: { PATH: '/tmp/untrusted-bin' },
      resolveGitPath: async () => '/usr/bin/git',
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: true, path: stableApplication },
    });
  });

  it('keeps a valid standard install when Toolbox advertises a bad launcher for the same app', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const systemApplicationsDirectory = path.join(directory, 'Applications');
    const applicationPath = path.join(systemApplicationsDirectory, 'GoLand.app');
    await createGoLandBundle(applicationPath);
    const staleLauncher = path.join(
      applicationPath,
      'Contents',
      'MacOS',
      'stale-goland',
    );
    await writeFile(staleLauncher, '#!/bin/sh\nexit 0\n', 'utf8');
    await chmod(staleLauncher, 0o755);
    await writeToolboxState(homeDirectory, [{
      productCode: 'GO',
      toolId: 'Goland',
      displayVersion: '2026.1.3',
      channelId: 'Goland-Release',
      launchCommand: staleLauncher,
    }]);
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      systemApplicationsDirectory,
      resolveGitPath: async () => '/usr/bin/git',
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: true, path: applicationPath },
    });
  });

  it('ignores Toolbox state whose record count exceeds the parsing budget', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const toolboxApplication = path.join(
      directory,
      'toolbox-apps',
      'GoLand-2026.1.app',
    );
    const toolboxLauncher = await createGoLandBundle(toolboxApplication);
    await writeToolboxState(homeDirectory, [
      {
        productCode: 'GO',
        toolId: 'Goland',
        launchCommand: toolboxLauncher,
      },
      ...Array.from({ length: 1_024 }, () => ({ productCode: 'OTHER' })),
    ]);
    const readPlistValue = vi.fn<ReadPlistValue>();
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      readPlistValue,
      resolveGitPath: async () => '/usr/bin/git',
      systemApplicationsDirectory: path.join(directory, 'Applications'),
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: false, reasonCode: 'NOT_FOUND' },
    });
    expect(readPlistValue).not.toHaveBeenCalled();
  });

  it('ignores excessive unique Toolbox candidates without hiding a standard install', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const systemApplicationsDirectory = path.join(directory, 'Applications');
    const standardApplication = path.join(
      systemApplicationsDirectory,
      'GoLand.app',
    );
    await createGoLandBundle(standardApplication);
    await writeToolboxState(
      homeDirectory,
      Array.from({ length: 65 }, (_, index) => ({
        productCode: 'GO',
        toolId: 'Goland',
        launchCommand: path.join(
          directory,
          'toolbox-apps',
          `GoLand-${String(index)}.app`,
          'Contents',
          'MacOS',
          'goland',
        ),
      })),
    );
    const realpathPath = vi.fn<RealpathPath>((filePath) => realpath(filePath));
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      realpathPath,
      resolveGitPath: async () => '/usr/bin/git',
      systemApplicationsDirectory,
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: true, path: standardApplication },
    });
    expect(realpathPath.mock.calls.some(([filePath]) =>
      filePath.includes(`${path.sep}toolbox-apps${path.sep}`)))
      .toBe(false);
  });

  it('deduplicates repeated Toolbox source, path, and launcher records before validation', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const applicationPath = path.join(
      directory,
      'toolbox-apps',
      'GoLand-2026.1.app',
    );
    const applicationLauncher = await createGoLandBundle(applicationPath);
    const toolboxRecord = {
      productCode: 'GO',
      toolId: 'Goland',
      displayVersion: '2026.1.3',
      channelId: 'Goland-Release',
      launchCommand: applicationLauncher,
    };
    await writeToolboxState(
      homeDirectory,
      Array.from({ length: 512 }, () => toolboxRecord),
    );
    const readPlistValue = vi.fn<ReadPlistValue>(async (_plistPath, key) => {
      if (key === 'CFBundleIdentifier') return 'com.jetbrains.goland';
      if (key === 'CFBundleExecutable') return 'goland';
      return '2026.1.3';
    });
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      readPlistValue,
      resolveGitPath: async () => '/usr/bin/git',
      systemApplicationsDirectory: path.join(directory, 'Applications'),
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: true, path: applicationPath },
    });
    expect(readPlistValue).toHaveBeenCalledTimes(3);
  });

  it('bounds concurrent Toolbox candidate validation', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const launchers = await Promise.all(
      Array.from({ length: 12 }, (_, index) => createGoLandBundle(path.join(
        directory,
        'toolbox-apps',
        `GoLand-${String(index)}.app`,
      ))),
    );
    await writeToolboxState(
      homeDirectory,
      launchers.map((launchCommand) => ({
        productCode: 'GO',
        toolId: 'Goland',
        displayVersion: '2026.1.3',
        channelId: 'Goland-Release',
        launchCommand,
      })),
    );
    let activePlistReads = 0;
    let maximumActivePlistReads = 0;
    const readPlistValue = vi.fn<ReadPlistValue>(async (_plistPath, key) => {
      activePlistReads += 1;
      maximumActivePlistReads = Math.max(
        maximumActivePlistReads,
        activePlistReads,
      );
      await new Promise<void>((resolve) => setTimeout(resolve, 5));
      activePlistReads -= 1;
      if (key === 'CFBundleIdentifier') return 'com.jetbrains.goland';
      if (key === 'CFBundleExecutable') return 'goland';
      return '2026.1.3';
    });
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      readPlistValue,
      resolveGitPath: async () => '/usr/bin/git',
      systemApplicationsDirectory: path.join(directory, 'Applications'),
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: true },
    });
    expect(readPlistValue).toHaveBeenCalledTimes(36);
    expect(maximumActivePlistReads).toBeLessThanOrEqual(8);
  });

  it('rejects candidates with an unexpected bundle identifier', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const maliciousApplication = path.join(
      homeDirectory,
      'Applications',
      'GoLand.app',
    );
    await createGoLandBundle(maliciousApplication, {
      bundleIdentifier: 'example.attacker.goland',
    });
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      systemApplicationsDirectory: path.join(directory, 'Applications'),
      processEnvironment: { PATH: '/tmp/untrusted-bin' },
      resolveGitPath: async () => '/usr/bin/git',
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: false, reasonCode: 'NOT_FOUND' },
    });
  });

  it.each(['Info.plist', 'executable'] as const)(
    'rejects a bundle whose internal %s is a symlink',
    async (symlinkTarget) => {
      const directory = await sandbox();
      const homeDirectory = path.join(directory, 'home');
      const applicationPath = path.join(
        homeDirectory,
        'Applications',
        'GoLand.app',
      );
      const executablePath = await createGoLandBundle(applicationPath);
      const targetPath = symlinkTarget === 'Info.plist'
        ? path.join(applicationPath, 'Contents', 'Info.plist')
        : executablePath;
      const relocatedPath = path.join(
        directory,
        symlinkTarget === 'Info.plist' ? 'external.plist' : 'external-goland',
      );
      await rename(targetPath, relocatedPath);
      await symlink(relocatedPath, targetPath);
      const launcher = new EditorLauncher(async () => ({
        rootPath: '/unused',
        workspaceFilePath: '/unused.code-workspace',
      }), {
        homeDirectory,
        systemApplicationsDirectory: path.join(directory, 'Applications'),
        resolveGitPath: async () => '/usr/bin/git',
      });

      await expect(launcher.getAvailability()).resolves.toMatchObject({
        goland: { available: false, reasonCode: 'NOT_FOUND' },
      });
    },
  );

  it('rejects a Toolbox launchCommand that does not name the bundle executable', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const applicationPath = path.join(
      directory,
      'toolbox-apps',
      'GoLand-2026.1.app',
    );
    await createGoLandBundle(applicationPath);
    const maliciousLauncher = path.join(
      applicationPath,
      'Contents',
      'MacOS',
      'attacker',
    );
    await writeFile(maliciousLauncher, '#!/bin/sh\nexit 0\n', 'utf8');
    await chmod(maliciousLauncher, 0o755);
    await writeToolboxState(homeDirectory, [{
      productCode: 'GO',
      toolId: 'Goland',
      displayVersion: '2026.1.3',
      channelId: 'Goland-Release',
      launchCommand: maliciousLauncher,
    }]);
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/unused',
      workspaceFilePath: '/unused.code-workspace',
    }), {
      homeDirectory,
      systemApplicationsDirectory: path.join(directory, 'Applications'),
      resolveGitPath: async () => '/usr/bin/git',
    });

    await expect(launcher.getAvailability()).resolves.toMatchObject({
      goland: { available: false, reasonCode: 'NOT_FOUND' },
    });
  });

  it('opens the validated workspace root with fixed arguments and shell disabled', async () => {
    const directory = await sandbox();
    const homeDirectory = path.join(directory, 'home');
    const applicationPath = path.join(
      homeDirectory,
      'Applications',
      'GoLand.app',
    );
    await createGoLandBundle(applicationPath);
    const rootPath = path.join(directory, 'workspace with space');
    await mkdir(path.join(rootPath, '.reqws'), { recursive: true });
    await writeFile(
      path.join(rootPath, '.reqws', 'workspace.json'),
      '{}\n',
      'utf8',
    );
    const calls: ProcessCall[] = [];
    const regularResolver = vi.fn(async () => {
      throw new Error('The regular editor resolver must not be used.');
    });
    const goLandResolver = vi.fn(async () => ({
      rootPath,
      workspaceFilePath: path.join(directory, 'workspace.code-workspace'),
    }));
    const launcher = new EditorLauncher(regularResolver, {
      homeDirectory,
      resolveGoLandWorkspacePaths: goLandResolver,
      spawnProcess: successfulSpawner(calls),
      systemApplicationsDirectory: path.join(directory, 'Applications'),
    });

    await launcher.openGoLand('ws_1');

    expect(regularResolver).not.toHaveBeenCalled();
    expect(goLandResolver).toHaveBeenCalledWith('ws_1');
    expect(calls).toEqual([
      {
        command: '/usr/bin/open',
        args: ['-a', applicationPath, rootPath],
        options: { shell: false, stdio: 'ignore', windowsHide: true },
      },
    ]);
  });

  it('accepts an immutable macOS top-level alias for the workspace and manifest', async () => {
    const directory = await sandbox();
    const aliasRoot = await mkdtemp('/tmp/reqws-goland-alias-');
    sandboxes.push(await realpath(aliasRoot));
    const homeDirectory = path.join(directory, 'home');
    const applicationPath = path.join(
      homeDirectory,
      'Applications',
      'GoLand.app',
    );
    await createGoLandBundle(applicationPath);
    await mkdir(path.join(aliasRoot, '.reqws'), { recursive: true });
    await writeFile(
      path.join(aliasRoot, '.reqws', 'workspace.json'),
      '{}\n',
      'utf8',
    );
    const calls: ProcessCall[] = [];
    const launcher = new EditorLauncher(async () => ({
      rootPath: aliasRoot,
      workspaceFilePath: path.join(directory, 'workspace.code-workspace'),
    }), {
      homeDirectory,
      spawnProcess: successfulSpawner(calls),
      systemApplicationsDirectory: path.join(directory, 'Applications'),
    });

    await launcher.openGoLand('ws_alias');

    expect(calls).toEqual([
      {
        command: '/usr/bin/open',
        args: ['-a', applicationPath, aliasRoot],
        options: { shell: false, stdio: 'ignore', windowsHide: true },
      },
    ]);
  });

  it('rejects a missing manifest before resolving or starting GoLand', async () => {
    const directory = await sandbox();
    const rootPath = path.join(directory, 'workspace');
    await mkdir(rootPath, { recursive: true });
    const spawnProcess = vi.fn<SpawnOpenProcess>();
    const launcher = new EditorLauncher(async () => ({
      rootPath,
      workspaceFilePath: path.join(directory, 'workspace.code-workspace'),
    }), {
      homeDirectory: path.join(directory, 'home'),
      spawnProcess,
      systemApplicationsDirectory: path.join(directory, 'Applications'),
    });

    await expect(launcher.openGoLand('ws_1')).rejects.toMatchObject({
      code: 'WORKSPACE_PATH_MISSING',
      stage: 'launching',
    });
    expect(spawnProcess).not.toHaveBeenCalled();
  });
});
