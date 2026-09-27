import type { ChildProcessWithoutNullStreams } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { PassThrough } from 'node:stream';
import type { IpcMainInvokeEvent } from 'electron';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createMainServices, STATE_FILE_RELATIVE_PATH } from '../../src/main/ipc/create-main-services';
import { createDialogHandlers } from '../../src/main/ipc/dialog-handlers';
import { createDefaultAppState } from '../../src/main/services/app-state-store';
import { EditorLauncher } from '../../src/main/services/editor-launcher';
import { GitRunner, type SpawnGitProcess } from '../../src/main/services/git-runner';
import { RepositoryService } from '../../src/main/services/repository-service';
import { UpdateService } from '../../src/main/services/update-service';
import { WorkspaceService } from '../../src/main/services/workspace-service';
import { IPC_CHANNELS } from '../../src/shared/ipc-channels';

vi.mock('electron', () => ({
  app: { isPackaged: false, getVersion: () => '0.1.7', getPreferredSystemLanguages: () => ['en-US'] },
  BrowserWindow: { getAllWindows: () => [], fromWebContents: () => null },
  dialog: { showOpenDialog: vi.fn() },
}));

const directories: string[] = [];
async function directory() {
  const root = await realpath(await mkdtemp(path.join(os.tmpdir(), 'reqws-main-boundaries-')));
  directories.push(root);
  return root;
}
function child(): ChildProcessWithoutNullStreams {
  return Object.assign(new EventEmitter(), {
    stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(),
    kill: vi.fn(),
  }) as unknown as ChildProcessWithoutNullStreams;
}
const successfulGit: SpawnGitProcess = () => {
  const process = child();
  queueMicrotask(() => process.emit('close', 0, null));
  return process;
};
afterEach(async () => {
  await Promise.all(directories.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe('main service boundary injection', () => {
  it('retains real storage, workspace resolution and native request validation', async () => {
    const root = await directory();
    const statePath = path.join(root, STATE_FILE_RELATIVE_PATH);
    const state = createDefaultAppState();
    state.workspaces.push({
      id: 'ws_fixture', name: 'Fixture', featureBranch: 'feature/fixture',
      rootPath: root, workspaceFilePath: path.join(root, 'fixture.code-workspace'),
      repositoryNames: [], status: 'ready',
      createdAt: '2026-09-26T00:00:00.000Z', updatedAt: '2026-09-26T00:00:00.000Z',
    });
    await mkdir(path.dirname(statePath));
    await writeFile(statePath, JSON.stringify(state));
    const spawnProcess = vi.fn(() => {
      const process = child();
      queueMicrotask(() => process.emit('close', 0, null));
      return process;
    });
    const showOpenDialog = vi.fn().mockResolvedValue({ canceled: false, filePaths: [root] });
    const services = await createMainServices(root, {
      spawnGitProcess: successfulGit,
      dialog: { showOpenDialog },
      editor: {
        spawnProcess, homeDirectory: path.join(root, 'home'),
        systemApplicationsDirectory: path.join(root, 'applications'), processEnvironment: {},
      },
    });
    expect(services.repositoryService).toBeInstanceOf(RepositoryService);
    expect(services.editorLauncher).toBeInstanceOf(EditorLauncher);
    expect(services.git).toBeInstanceOf(GitRunner);
    expect(services.updateService).toBeInstanceOf(UpdateService);
    expect(services.updateService.getState()).toMatchObject({ phase: 'disabled', reason: 'development' });
    expect(services.createWorkspaceService({ sender: {} } as IpcMainInvokeEvent)).toBeInstanceOf(WorkspaceService);
    await services.settingsService.save({
      localePreference: 'en-US', workspaceParentDirectory: root, workspaceFileDirectory: null,
    });
    expect(JSON.parse(await readFile(statePath, 'utf8')).settings.workspaceParentDirectory).toBe(root);
    await services.editorLauncher.revealInFinder('ws_fixture');
    expect(spawnProcess).toHaveBeenCalledWith('/usr/bin/open', ['-R', root], expect.objectContaining({ shell: false }));
    await expect(services.editorLauncher.revealInFinder('ws_missing')).rejects.toMatchObject({ code: 'WORKSPACE_NOT_FOUND' });
    expect(spawnProcess).toHaveBeenCalledOnce();

    const event = { sender: {} } as IpcMainInvokeEvent;
    const selectDirectory = createDialogHandlers(services)[IPC_CHANNELS.dialogs.selectDirectory]!;
    await expect(selectDirectory(event, { title: 'Fixture', createDirectory: true })).resolves.toEqual({ ok: true, value: root });
    expect(showOpenDialog).toHaveBeenCalledWith({ title: 'Fixture', properties: ['openDirectory', 'createDirectory'] });
    await expect(selectDirectory(event, { title: 42 })).resolves.toMatchObject({ ok: false });
    expect(showOpenDialog).toHaveBeenCalledOnce();
    showOpenDialog.mockResolvedValue({ canceled: true, filePaths: [] });
    await expect(selectDirectory(event, { title: 'Fixture', createDirectory: false })).resolves.toEqual({ ok: true, value: null });
    services.updateService.dispose();
  });

  it('shares update admission with real Git and state services when only external adapters are injected', async () => {
    const root = await directory();
    let pending: ChildProcessWithoutNullStreams | undefined;
    const spawnGitProcess = vi.fn<SpawnGitProcess>((_command, args) => {
      const process = child();
      if (args[0] === '--version') queueMicrotask(() => process.emit('close', 0, null));
      else pending = process;
      return process;
    });
    const adapter = {
      check: vi.fn(async () => ({ version: '0.1.8' })), download: vi.fn(async () => undefined),
      install: vi.fn(), disarmInstallation: vi.fn(), dispose: vi.fn(),
    };
    const services = await createMainServices(root, {
      spawnGitProcess,
      update: {
        currentVersion: '0.1.7', createAdapter: () => adapter,
        validateInstallLocation: async () => undefined,
      },
    });
    expect(services.updateService).toBeInstanceOf(UpdateService);
    const git = services.git;
    if (!(git instanceof GitRunner)) throw new Error('Expected the real Git service.');
    await services.updateService.check();
    await services.updateService.download();
    const operation = git.run(['fetch']);
    await expect(services.updateService.install()).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
    expect(adapter.install).not.toHaveBeenCalled();
    pending!.emit('close', 0, null);
    await operation;
    await services.updateService.install();
    expect(adapter.install).toHaveBeenCalledOnce();
    await expect(git.run(['fetch'])).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
    await expect(services.settingsService.get()).rejects.toMatchObject({ code: 'SETTINGS_READ_FAILED' });
    expect(spawnGitProcess).toHaveBeenCalledTimes(2);
    services.updateService.dispose();
    await expect(services.settingsService.get()).resolves.toMatchObject({ effectiveLocale: 'en-US' });
  });
});
