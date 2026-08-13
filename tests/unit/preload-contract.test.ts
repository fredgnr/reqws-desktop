import { beforeEach, describe, expect, it, vi } from 'vitest';

const electronMock = vi.hoisted(() => ({
  exposeInMainWorld: vi.fn(),
  invoke: vi.fn(),
  on: vi.fn(),
  removeListener: vi.fn(),
}));

vi.mock('electron', () => ({
  contextBridge: { exposeInMainWorld: electronMock.exposeInMainWorld },
  ipcRenderer: {
    invoke: electronMock.invoke,
    on: electronMock.on,
    removeListener: electronMock.removeListener,
  },
}));

import { api, exposeReqwsAPI } from '../../src/preload';
import { IPC_CHANNELS } from '../../src/shared/ipc-channels';

describe('preload ReqwsAPI contract', () => {
  beforeEach(() => {
    electronMock.invoke.mockResolvedValue({ ok: true, value: undefined });
  });

  it('exposes only the typed ReqWS API through contextBridge', () => {
    exposeReqwsAPI();
    expect(electronMock.exposeInMainWorld).toHaveBeenCalledOnce();
    expect(electronMock.exposeInMainWorld).toHaveBeenCalledWith('reqws', api);
    expect(Object.keys(api)).toEqual([
      'repositories',
      'workspaces',
      'settings',
      'dialogs',
      'editors',
      'operations',
    ]);
    expect(api).not.toHaveProperty('ipcRenderer');
  });

  it('uses fixed invoke channels and unwraps successful values', async () => {
    const repository = { id: 'repo_1', name: 'order-api' };
    electronMock.invoke.mockResolvedValueOnce({ ok: true, value: repository });

    await expect(
      api.repositories.create({
        name: 'order-api',
        url: 'git@example.test:team/order-api.git',
        defaultBranch: 'main',
      }),
    ).resolves.toBe(repository);
    expect(electronMock.invoke).toHaveBeenLastCalledWith(
      IPC_CHANNELS.repositories.create,
      {
        name: 'order-api',
        url: 'git@example.test:team/order-api.git',
        defaultBranch: 'main',
      },
    );

    await api.workspaces.sync('ws_1');
    expect(electronMock.invoke).toHaveBeenLastCalledWith(
      IPC_CHANNELS.workspaces.sync,
      'ws_1',
    );

    await api.settings.get();
    expect(electronMock.invoke).toHaveBeenLastCalledWith(
      IPC_CHANNELS.settings.get,
    );

    const settings = {
      localePreference: 'system' as const,
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    };
    await api.settings.save(settings);
    expect(electronMock.invoke).toHaveBeenLastCalledWith(
      IPC_CHANNELS.settings.save,
      settings,
    );

    await api.editors.openCursorRoot('ws_1');
    expect(electronMock.invoke).toHaveBeenLastCalledWith(
      IPC_CHANNELS.editors.openCursorRoot,
      'ws_1',
    );
  });

  it('preserves the stable serializable error payload across contextBridge', async () => {
    electronMock.invoke.mockResolvedValueOnce({
      ok: false,
      error: {
        code: 'WORKSPACE_NOT_FOUND',
        message: 'Workspace not found.',
      },
    });

    const promise = api.workspaces.get('missing');
    await expect(promise).rejects.toMatchObject({
      code: 'WORKSPACE_NOT_FOUND',
      message: 'Workspace not found.',
    });
  });

  it('subscribes to one fixed progress channel and precisely unsubscribes', () => {
    const listener = vi.fn();
    const unsubscribe = api.operations.onProgress(listener);
    const [channel, wrapped] = electronMock.on.mock.calls.at(-1) ?? [];

    expect(channel).toBe(IPC_CHANNELS.operationProgress);
    expect(typeof wrapped).toBe('function');

    const progress = {
      operationId: 'op_1',
      kind: 'create-workspace' as const,
      stage: 'cloning' as const,
      current: 1,
      total: 2,
      message: 'Cloning order-api',
    };
    wrapped({}, progress);
    expect(listener).toHaveBeenCalledWith(progress);

    unsubscribe();
    expect(electronMock.removeListener).toHaveBeenCalledWith(
      IPC_CHANNELS.operationProgress,
      wrapped,
    );
  });
});
