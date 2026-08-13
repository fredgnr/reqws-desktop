import { EventEmitter } from 'node:events';
import type { ChildProcess, SpawnOptions } from 'node:child_process';
import { describe, expect, it, vi } from 'vitest';

import {
  EditorLauncher,
  type SpawnOpenProcess,
} from '../../src/main/services/editor-launcher';
import { ReqwsError } from '../../src/shared/errors';

const paths = {
  workspaceFilePath: '/workspaces/feature.code-workspace',
  rootPath: '/features/feature',
};

interface OpenCall {
  command: string;
  args: readonly string[];
  options: SpawnOptions;
}

function successfulSpawner(calls: OpenCall[]): SpawnOpenProcess {
  return (command, args, options) => {
    calls.push({ command, args, options });
    const child = new EventEmitter();
    queueMicrotask(() => child.emit('close', 0));
    return child as ChildProcess;
  };
}

function availableAccess(filePath: string): Promise<void> {
  if (
    filePath === paths.workspaceFilePath ||
    filePath === paths.rootPath ||
    filePath === '/Applications/Visual Studio Code.app' ||
    filePath === '/Applications/Cursor.app'
  ) {
    return Promise.resolve();
  }
  return Promise.reject(Object.assign(new Error('not found'), { code: 'ENOENT' }));
}

describe('EditorLauncher', () => {
  it('detects Git and editor applications in system locations', async () => {
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: availableAccess,
      homeDirectory: '/Users/rose',
      resolveGitPath: async () => '/usr/bin/git',
    });

    await expect(launcher.getAvailability()).resolves.toEqual({
      git: { available: true, path: '/usr/bin/git' },
      vscode: {
        available: true,
        path: '/Applications/Visual Studio Code.app',
      },
      cursor: { available: true, path: '/Applications/Cursor.app' },
    });
  });

  it('checks the user Applications directory and returns actionable reasons', async () => {
    const accessPath = vi.fn(async (filePath: string) => {
      if (filePath === '/Users/rose/Applications/Cursor.app') return;
      throw Object.assign(new Error('not found'), { code: 'ENOENT' });
    });
    const launcher = new EditorLauncher(async () => paths, {
      accessPath,
      homeDirectory: '/Users/rose',
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
      path: '/Users/rose/Applications/Cursor.app',
    });
  });

  it.each([
    ['openVSCode', ['-a', 'Visual Studio Code', paths.workspaceFilePath]],
    ['openCursor', ['-a', 'Cursor', paths.workspaceFilePath]],
    ['openCursorRoot', ['-a', 'Cursor', paths.rootPath]],
    ['revealInFinder', ['-R', paths.rootPath]],
  ] as const)('uses a fixed open executable and arguments for %s', async (method, args) => {
    const calls: OpenCall[] = [];
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

  it('maps a nonzero open exit to a stable ReqwsError', async () => {
    const launcher = new EditorLauncher(async () => paths, {
      accessPath: availableAccess,
      spawnProcess: () => {
        const child = new EventEmitter();
        queueMicrotask(() => child.emit('close', 1));
        return child as ChildProcess;
      },
    });

    await expect(launcher.openCursorRoot('ws_1')).rejects.toMatchObject({
      name: 'ReqwsError',
      code: 'EDITOR_NOT_FOUND',
      detail: '/usr/bin/open exited with code 1.',
    });
  });
});

