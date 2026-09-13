import type { IpcMainInvokeEvent } from 'electron';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ReqwsError } from '../../src/shared/errors';
import { IPC_CHANNELS } from '../../src/shared/ipc-channels';
import { createDialogHandlers } from '../../src/main/ipc/dialog-handlers';
import { createEditorHandlers } from '../../src/main/ipc/editor-handlers';
import { createRepositoryHandlers } from '../../src/main/ipc/repository-handlers';
import { registerIpcHandlers } from '../../src/main/ipc/register-ipc';
import { createSettingsHandlers } from '../../src/main/ipc/settings-handlers';
import { createWorkspaceHandlers } from '../../src/main/ipc/workspace-handlers';

const event = {
  sender: { isDestroyed: () => false, send: vi.fn() },
} as unknown as IpcMainInvokeEvent;

const gitUnavailable = new ReqwsError({
  code: 'GIT_NOT_FOUND',
  message: 'Git is not installed.',
});

describe('main IPC handlers', () => {
  beforeEach(() => vi.clearAllMocks());

  it('validates renderer input in main and returns a plain error envelope', async () => {
    const create = vi.fn();
    const handlers = createRepositoryHandlers({
      repositoryService: {
        list: vi.fn(),
        create,
        update: vi.fn(),
        remove: vi.fn(),
      },
      git: null,
      gitUnavailableError: gitUnavailable,
      createOperationReporter: () => ({ report: vi.fn() }),
    });

    const result = await handlers[IPC_CHANNELS.repositories.create]?.(
      event,
      { name: '../unsafe', url: '', defaultBranch: '' },
    );

    expect(result).toMatchObject({
      ok: false,
      error: { code: 'INVALID_INPUT', message: 'IPC input is invalid.' },
    });
    expect(create).not.toHaveBeenCalled();
  });

  it('turns connection failures into TestRepositoryResult success:false', async () => {
    const handlers = createRepositoryHandlers({
      repositoryService: {
        list: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        remove: vi.fn(),
      },
      git: {
        lsRemote: vi.fn().mockRejectedValue(
          new ReqwsError({
            code: 'REPOSITORY_UNREACHABLE',
            message: 'Cannot reach repository.',
            detail: 'permission denied',
          }),
        ),
      },
      gitUnavailableError: gitUnavailable,
      createOperationReporter: () => ({ report: vi.fn() }),
    });

    const result = await handlers[IPC_CHANNELS.repositories.test]?.(event, {
      url: 'git@example.test:team/repository.git',
    });

    expect(result).toEqual({
      ok: true,
      value: {
        success: false,
        detail: 'permission denied',
        error: {
          code: 'REPOSITORY_UNREACHABLE',
          message: 'Cannot reach repository.',
          detail: 'permission denied',
        },
      },
    });
  });

  it('keeps repository CRUD available but reports GIT_NOT_FOUND for Git operations', async () => {
    const repositories = [{ id: 'repo_1', name: 'order-api' }];
    const repositoryHandlers = createRepositoryHandlers({
      repositoryService: {
        list: vi.fn().mockResolvedValue(repositories),
        create: vi.fn(),
        update: vi.fn(),
        remove: vi.fn(),
      },
      git: null,
      gitUnavailableError: gitUnavailable,
      createOperationReporter: () => ({ report: vi.fn() }),
    });
    const workspaceService = {
      list: vi.fn(),
      get: vi.fn(),
      create: vi.fn(),
      addRepository: vi.fn(),
      removeRepository: vi.fn(),
      sync: vi.fn(),
      forget: vi.fn(),
    };
    const workspaceHandlers = createWorkspaceHandlers({
      gitAvailable: false,
      gitUnavailableError: gitUnavailable,
      createWorkspaceService: () => workspaceService,
    });

    await expect(
      repositoryHandlers[IPC_CHANNELS.repositories.list]?.(event),
    ).resolves.toEqual({ ok: true, value: repositories });
    await expect(
      repositoryHandlers[IPC_CHANNELS.repositories.test]?.(event, {
        url: 'git@example.test:team/repository.git',
      }),
    ).resolves.toMatchObject({
      ok: true,
      value: { success: false, error: { code: 'GIT_NOT_FOUND' } },
    });
    await expect(
      workspaceHandlers[IPC_CHANNELS.workspaces.create]?.(event, {
        name: 'FEAT-1',
        featureBranch: 'feature/FEAT-1',
        rootPath: '/features/FEAT-1',
        workspaceFileDirectory: '/workspaces',
        repositoryIds: ['repo_1'],
      }),
    ).resolves.toMatchObject({
      ok: false,
      error: { code: 'GIT_NOT_FOUND' },
    });
    await expect(
      workspaceHandlers[IPC_CHANNELS.workspaces.addRepository]?.(event, {
        workspaceId: 'ws_1',
        repositoryId: 'repo_1',
      }),
    ).resolves.toMatchObject({
      ok: false,
      error: { code: 'GIT_NOT_FOUND' },
    });
    expect(workspaceService.create).not.toHaveBeenCalled();
    expect(workspaceService.addRepository).not.toHaveBeenCalled();
  });

  it('exposes settings get/save through fixed channels with strict input', async () => {
    const settings = {
      localePreference: 'system' as const,
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    };
    const resolved = { ...settings, effectiveLocale: 'en-US' as const };
    const settingsService = {
      get: vi.fn().mockResolvedValue(resolved),
      save: vi.fn().mockResolvedValue(resolved),
    };
    const handlers = createSettingsHandlers({ settingsService });

    await expect(
      handlers[IPC_CHANNELS.settings.get]?.(event),
    ).resolves.toEqual({ ok: true, value: resolved });
    await expect(
      handlers[IPC_CHANNELS.settings.save]?.(event, settings),
    ).resolves.toEqual({ ok: true, value: resolved });
    expect(settingsService.save).toHaveBeenCalledWith(settings);

    await expect(
      handlers[IPC_CHANNELS.settings.save]?.(event, {
        ...settings,
        localePreference: 'fr-FR',
      }),
    ).resolves.toMatchObject({
      ok: false,
      error: { code: 'SETTINGS_INVALID_LOCALE' },
    });
    await expect(
      handlers[IPC_CHANNELS.settings.save]?.(event, {
        ...settings,
        stateFilePath: '/tmp/attacker-state.json',
      }),
    ).resolves.toMatchObject({
      ok: false,
      error: { code: 'INVALID_INPUT' },
    });
    expect(settingsService.save).toHaveBeenCalledOnce();
  });

  it('uses openDirectory and opts into createDirectory only when requested', async () => {
    const showOpenDialog = vi
      .fn()
      .mockResolvedValue({ canceled: false, filePaths: ['/features'] });
    const handlers = createDialogHandlers({
      dialog: { showOpenDialog },
      windowFromWebContents: () => null,
    });

    const result = await handlers[IPC_CHANNELS.dialogs.selectDirectory]?.(
      event,
      { title: '选择目录', createDirectory: true },
    );

    expect(result).toEqual({ ok: true, value: '/features' });
    expect(showOpenDialog).toHaveBeenCalledWith({
      title: '选择目录',
      properties: ['openDirectory', 'createDirectory'],
    });
  });

  it('validates the workspace ID before invoking the GoLand launcher', async () => {
    const openGoLand = vi.fn().mockResolvedValue(undefined);
    const handlers = createEditorHandlers({
      editorLauncher: {
        getAvailability: vi.fn(),
        openVSCode: vi.fn(),
        openCursor: vi.fn(),
        openCursorRoot: vi.fn(),
        openGoLand,
        revealInFinder: vi.fn(),
      },
    });

    await expect(
      handlers[IPC_CHANNELS.editors.openGoLand]?.(event, 'ws_1'),
    ).resolves.toEqual({ ok: true, value: undefined });
    expect(openGoLand).toHaveBeenCalledWith('ws_1');

    await expect(
      handlers[IPC_CHANNELS.editors.openGoLand]?.(event, ''),
    ).resolves.toMatchObject({
      ok: false,
      error: { code: 'INVALID_INPUT' },
    });
    expect(openGoLand).toHaveBeenCalledOnce();
  });

  it('validates the discriminated editor availability response contract', async () => {
    const getAvailability = vi.fn().mockResolvedValue({
      git: { available: true, path: '/usr/bin/git' },
      vscode: { available: false, reasonCode: 'NOT_FOUND' },
      cursor: { available: true, path: '/Applications/Cursor.app' },
      goland: { available: true, path: '/Applications/GoLand.app' },
    });
    const handlers = createEditorHandlers({
      editorLauncher: {
        getAvailability,
        openVSCode: vi.fn(),
        openCursor: vi.fn(),
        openCursorRoot: vi.fn(),
        openGoLand: vi.fn(),
        revealInFinder: vi.fn(),
      },
    });

    await expect(
      handlers[IPC_CHANNELS.editors.availability]?.(event),
    ).resolves.toMatchObject({ ok: true });

    getAvailability.mockResolvedValueOnce({
      git: { available: true, path: '/usr/bin/git' },
      vscode: { available: false, path: '/Applications/Code.app' },
      cursor: { available: false },
      goland: { available: false },
    });
    await expect(
      handlers[IPC_CHANNELS.editors.availability]?.(event),
    ).resolves.toMatchObject({
      ok: false,
      error: { code: 'INVALID_INPUT' },
    });
  });

  it('replaces every registered handler and cleanup is ownership-safe', () => {
    const ipcMain = {
      handle: vi.fn(),
      removeHandler: vi.fn(),
    };
    const services = {
      repositoryService: {
        list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(),
      },
      git: null,
      gitAvailable: false,
      gitUnavailableError: gitUnavailable,
      createOperationReporter: () => ({ report: vi.fn() }),
      createWorkspaceService: () => ({
        list: vi.fn(), get: vi.fn(), create: vi.fn(), addRepository: vi.fn(),
        removeRepository: vi.fn(), sync: vi.fn(), forget: vi.fn(),
      }),
      settingsService: { get: vi.fn(), save: vi.fn() },
      editorLauncher: {
        getAvailability: vi.fn(), openVSCode: vi.fn(), openCursor: vi.fn(),
        openCursorRoot: vi.fn(), openGoLand: vi.fn(), revealInFinder: vi.fn(),
      },
      dialog: { showOpenDialog: vi.fn() },
      windowFromWebContents: () => null,
    };

    const cleanupFirst = registerIpcHandlers(ipcMain, services);
    const channels = ipcMain.handle.mock.calls.map(([channel]) => channel);
    expect(new Set(channels).size).toBe(channels.length);
    expect(channels).toHaveLength(21);
    expect(ipcMain.removeHandler).toHaveBeenCalledTimes(21);

    const cleanupSecond = registerIpcHandlers(ipcMain, services);
    cleanupFirst();
    expect(ipcMain.removeHandler).toHaveBeenCalledTimes(42);
    cleanupSecond();
    expect(ipcMain.removeHandler).toHaveBeenCalledTimes(63);
  });
});
