import type { ChildProcess, SpawnOptions } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { constants as fsConstants } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';

import {
  EditorLauncher,
  type AccessPath,
  type EditorLauncherDependencies,
  type SpawnOpenProcess,
} from '../../src/main/services/editor-launcher';
import { ReqwsError } from '../../src/shared/errors';

const paths = {
  workspaceFilePath: '/workspaces/feature.code-workspace',
  rootPath: '/features/feature',
};

const systemCursorAppPath = '/Applications/Cursor.app';
const systemCursorCliPath =
  '/Applications/Cursor.app/Contents/Resources/app/bin/cursor';
const systemCodeCliPath =
  '/Applications/Cursor.app/Contents/Resources/app/bin/code';
const userCursorAppPath = '/Users/rose/Applications/Cursor.app';
const userCursorCliPath =
  '/Users/rose/Applications/Cursor.app/Contents/Resources/app/bin/cursor';

interface ProcessCall {
  command: string;
  args: readonly string[];
  options: SpawnOptions;
}

function successfulSpawner(calls: ProcessCall[]): SpawnOpenProcess {
  return (command, args, options) => {
    calls.push({ command, args, options });
    const child = new EventEmitter();
    queueMicrotask(() => child.emit('close', 0));
    return child as ChildProcess;
  };
}

function accessOnly(...availablePaths: string[]): AccessPath {
  const available = new Set(availablePaths);
  return async (filePath) => {
    if (available.has(filePath)) return;
    throw Object.assign(new Error('not found'), { code: 'ENOENT' });
  };
}

const availableAccess = accessOnly(
  paths.workspaceFilePath,
  paths.rootPath,
  '/Applications/Visual Studio Code.app',
  systemCursorAppPath,
  systemCursorCliPath,
);

const noGoLand: Pick<
  EditorLauncherDependencies,
  'inspectPath' | 'realpathPath'
> = {
  inspectPath: async () => {
    throw Object.assign(new Error('not found'), { code: 'ENOENT' });
  },
  realpathPath: async () => {
    throw Object.assign(new Error('not found'), { code: 'ENOENT' });
  },
};

describe('EditorLauncher', () => {
  it('detects Git and editor applications in system locations', async () => {
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: availableAccess,
      homeDirectory: '/Users/rose',
      resolveGitPath: async () => '/usr/bin/git',
      ...noGoLand,
    });

    await expect(launcher.getAvailability()).resolves.toEqual({
      git: { available: true, path: '/usr/bin/git' },
      vscode: {
        available: true,
        path: '/Applications/Visual Studio Code.app',
      },
      cursor: { available: true, path: systemCursorAppPath },
      goland: {
        available: false,
        reasonCode: 'NOT_FOUND',
        reason:
          'GoLand was not found in /Applications, ~/Applications, or JetBrains Toolbox.',
      },
    });
  });

  it('checks the user Applications directory and returns actionable reasons', async () => {
    const accessPath = vi.fn(async (filePath: string) => {
      if (filePath === userCursorAppPath) return;
      throw Object.assign(new Error('not found'), { code: 'ENOENT' });
    });
    const launcher = new EditorLauncher(async () => paths, {
      accessPath,
      homeDirectory: '/Users/rose',
      ...noGoLand,
      resolveGitPath: async () => {
        throw new Error('no git');
      },
    });

    const availability = await launcher.getAvailability();

    expect(availability.git).toMatchObject({ available: false });
    expect(availability.git.reason).toContain('Git was not found');
    expect(availability.vscode.reason).toContain('/Applications');
    expect(availability.cursor).toEqual({
      available: true,
      path: userCursorAppPath,
    });
    expect(availability.goland).toMatchObject({
      available: false,
      reasonCode: 'NOT_FOUND',
    });
  });

  it.each([
    ['openVSCode', ['-a', 'Visual Studio Code', paths.workspaceFilePath]],
    ['revealInFinder', ['-R', paths.rootPath]],
  ] as const)('keeps using the fixed open executable for %s', async (method, args) => {
    const calls: ProcessCall[] = [];
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: availableAccess,
      spawnProcess: successfulSpawner(calls),
    });

    await launcher[method]('ws_1');

    expect(calls).toEqual([
      {
        command: '/usr/bin/open',
        args,
        options: { shell: false, stdio: 'ignore', windowsHide: true },
      },
    ]);
  });

  it.each([
    ['openCursor', paths.workspaceFilePath],
    ['openCursorRoot', paths.rootPath],
  ] as const)('opens %s in a new Cursor IDE window', async (method, targetPath) => {
    const calls: ProcessCall[] = [];
    const accessPath = vi.fn(availableAccess);
    const launcher = new EditorLauncher(async () => paths, {
      accessPath,
      processEnvironment: { PATH: '/usr/bin:/bin' },
      spawnProcess: successfulSpawner(calls),
    });

    await launcher[method]('ws_1');

    expect(accessPath).toHaveBeenCalledWith(
      systemCursorCliPath,
      fsConstants.X_OK,
    );
    expect(calls).toEqual([
      {
        command: systemCursorCliPath,
        args: ['editor', '--new-window', targetPath],
        options: {
          env: { PATH: '/usr/bin:/bin' },
          shell: false,
          stdio: 'ignore',
          windowsHide: true,
        },
      },
    ]);
  });

  it('removes the remote CLI hook without mutating the inherited environment', async () => {
    const calls: ProcessCall[] = [];
    const processEnvironment = {
      PATH: '/remote-cli/bin:/usr/bin',
      REQWS_PRESERVED: 'yes',
      VSCODE_IPC_HOOK_CLI: '/tmp/remote-cli.sock',
    };
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: availableAccess,
      processEnvironment,
      spawnProcess: successfulSpawner(calls),
    });

    await launcher.openCursor('ws_1');

    expect(calls[0]?.options.env).toEqual({
      PATH: '/remote-cli/bin:/usr/bin',
      REQWS_PRESERVED: 'yes',
    });
    expect(processEnvironment.VSCODE_IPC_HOOK_CLI).toBe(
      '/tmp/remote-cli.sock',
    );
  });

  it('uses the bundled Cursor CLI from the user Applications directory', async () => {
    const calls: ProcessCall[] = [];
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: accessOnly(
        paths.workspaceFilePath,
        userCursorAppPath,
        userCursorCliPath,
      ),
      homeDirectory: '/Users/rose',
      spawnProcess: successfulSpawner(calls),
    });

    await launcher.openCursor('ws_1');

    expect(calls).toEqual([
      expect.objectContaining({
        command: userCursorCliPath,
        args: ['editor', '--new-window', paths.workspaceFilePath],
      }),
    ]);
  });

  it('falls back to the bundled code CLI when the Cursor CLI is not executable', async () => {
    const calls: ProcessCall[] = [];
    const accessPath = vi.fn(accessOnly(
      paths.workspaceFilePath,
      systemCursorAppPath,
      systemCodeCliPath,
    ));
    const launcher = new EditorLauncher(async () => paths, {
      accessPath,
      processEnvironment: {
        REQWS_PRESERVED: 'yes',
        VSCODE_IPC_HOOK_CLI: '/tmp/remote-cli.sock',
      },
      spawnProcess: successfulSpawner(calls),
    });

    await launcher.openCursor('ws_1');

    expect(accessPath).toHaveBeenCalledWith(
      systemCursorCliPath,
      fsConstants.X_OK,
    );
    expect(accessPath).toHaveBeenCalledWith(
      systemCodeCliPath,
      fsConstants.X_OK,
    );
    expect(calls).toEqual([
      expect.objectContaining({
        command: systemCodeCliPath,
        args: ['--new-window', paths.workspaceFilePath],
        options: expect.objectContaining({
          env: { REQWS_PRESERVED: 'yes' },
        }),
      }),
    ]);
  });

  it('falls back to LaunchServices when neither bundled CLI is executable', async () => {
    const calls: ProcessCall[] = [];
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: accessOnly(
        paths.workspaceFilePath,
        systemCursorAppPath,
      ),
      spawnProcess: successfulSpawner(calls),
    });

    await launcher.openCursor('ws_1');

    expect(calls).toEqual([
      {
        command: '/usr/bin/open',
        args: ['-a', 'Cursor', paths.workspaceFilePath],
        options: { shell: false, stdio: 'ignore', windowsHide: true },
      },
    ]);
  });

  it('rejects a missing workspace file before starting an editor', async () => {
    const spawnProcess = vi.fn<SpawnOpenProcess>();
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: async (filePath) => {
        if (filePath.includes('.app')) return;
        throw Object.assign(new Error('missing'), { code: 'ENOENT' });
      },
      spawnProcess,
    });

    await expect(launcher.openVSCode('ws_1')).rejects.toMatchObject({
      code: 'WORKSPACE_PATH_MISSING',
      stage: 'launching',
    } satisfies Partial<ReqwsError>);
    expect(spawnProcess).not.toHaveBeenCalled();
  });

  it('returns a stable error when the requested application is absent', async () => {
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: async (filePath) => {
        if (filePath === paths.workspaceFilePath) return;
        throw Object.assign(new Error('missing'), { code: 'ENOENT' });
      },
    });

    await expect(launcher.openCursor('ws_1')).rejects.toMatchObject({
      code: 'EDITOR_NOT_FOUND',
      stage: 'launching',
    } satisfies Partial<ReqwsError>);
  });

  it('does not fall back when the selected Cursor CLI fails to spawn', async () => {
    const calls: ProcessCall[] = [];
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: availableAccess,
      spawnProcess: (command, args, options) => {
        calls.push({ command, args, options });
        const child = new EventEmitter();
        queueMicrotask(() => child.emit('error', new Error('spawn failed')));
        return child as ChildProcess;
      },
    });

    await expect(launcher.openCursor('ws_1')).rejects.toMatchObject({
      name: 'ReqwsError',
      code: 'EDITOR_NOT_FOUND',
      detail: `Unable to start ${systemCursorCliPath}.`,
    });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.command).toBe(systemCursorCliPath);
  });

  it('does not fall back when the selected code CLI exits unsuccessfully', async () => {
    const calls: ProcessCall[] = [];
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: accessOnly(
        paths.rootPath,
        systemCursorAppPath,
        systemCodeCliPath,
      ),
      spawnProcess: (command, args, options) => {
        calls.push({ command, args, options });
        const child = new EventEmitter();
        queueMicrotask(() => child.emit('close', 7));
        return child as ChildProcess;
      },
    });

    await expect(launcher.openCursorRoot('ws_1')).rejects.toMatchObject({
      name: 'ReqwsError',
      code: 'EDITOR_NOT_FOUND',
      detail: `${systemCodeCliPath} exited with code 7.`,
    });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.command).toBe(systemCodeCliPath);
  });
});
