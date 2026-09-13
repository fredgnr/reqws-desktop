import type { ChildProcessWithoutNullStreams } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import type { IpcMainInvokeEvent } from 'electron';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { IPC_CHANNELS } from '../../src/shared/ipc-channels';
import { createEditorHandlers } from '../../src/main/ipc/editor-handlers';
import { EditorLauncher } from '../../src/main/services/editor-launcher';
import {
  GitRunner,
  type SpawnGitProcess,
} from '../../src/main/services/git-runner';

function successfulSpawn(): SpawnGitProcess {
  return () => {
    const child = Object.assign(new EventEmitter(), {
      stdout: new PassThrough(),
      stderr: new PassThrough(),
      stdin: new PassThrough(),
      stdio: [new PassThrough(), new PassThrough(), new PassThrough()],
      kill: vi.fn(() => true),
    }) as unknown as ChildProcessWithoutNullStreams;
    queueMicrotask(() => child.emit('close', 0, null));
    return child;
  };
}

async function unavailablePath(): Promise<never> {
  throw Object.assign(new Error('not found'), { code: 'ENOENT' });
}

describe('Git availability contract', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('accepts a PATH-discovered Git through the availability IPC schema', async () => {
    vi.stubEnv('PATH', '/custom/bin');
    const git = await GitRunner.create(successfulSpawn());
    const launcher = new EditorLauncher(async () => ({
      rootPath: '/features/feature',
      workspaceFilePath: '/workspaces/feature.code-workspace',
    }), {
      accessPath: unavailablePath,
      homeDirectory: '/Users/rose',
      inspectPath: unavailablePath,
      realpathPath: unavailablePath,
      resolveGitPath: async () => git.gitPath,
    });
    const handlers = createEditorHandlers({ editorLauncher: launcher });

    const result = await handlers[IPC_CHANNELS.editors.availability]?.(
      {} as IpcMainInvokeEvent,
    );

    expect(result).toMatchObject({
      ok: true,
      value: {
        git: { available: true, path: '/custom/bin/git' },
      },
    });
  });
});
