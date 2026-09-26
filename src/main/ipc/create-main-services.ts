import path from 'node:path';
import {
  app,
  BrowserWindow,
  dialog,
  type IpcMainInvokeEvent,
} from 'electron';

import { ReqwsError, toReqwsError } from '../../shared/errors';
import { IPC_CHANNELS } from '../../shared/ipc-channels';
import { isTrustedReqwsWebContents } from '../security';
import { ApplicationActivityGate } from '../services/application-activity-gate';
import { createUpdateService } from '../services/create-update-service';
import { AppStateStore } from '../services/app-state-store';
import { BranchService } from '../services/branch-service';
import {
  EditorLauncher,
  type EditorLauncherDependencies,
  type WorkspacePaths,
} from '../services/editor-launcher';
import { GoLandWorkspaceService } from '../services/goland-workspace-service';
import { GitRunner, type SpawnGitProcess } from '../services/git-runner';
import { UpdateService, type UpdateServiceOptions } from '../services/update-service';
import { OperationReporter } from '../services/operation-reporter';
import { RepositoryService } from '../services/repository-service';
import { DefaultSettingsService } from '../services/settings-service';
import { WorkspaceFileWriter } from '../services/workspace-file-writer';
import {
  WorkspaceMutationCoordinator,
  WorkspaceService,
  type OperationProgressPort,
} from '../services/workspace-service';
import type { RegisterIpcDependencies } from './register-ipc';

export const STATE_FILE_RELATIVE_PATH = path.join('reqws', 'state.v1.json');

function unavailableGit(error: ReqwsError): GitRunner {
  const reject = (): Promise<never> => Promise.reject(error);
  const adapter = {
    clone: reject,
    originUrlMatches: reject,
    checkBranchName: reject,
    fetch: reject,
    refExists: reject,
    run: reject,
  };
  // WorkspaceService and BranchService currently accept their concrete service
  // classes. Git-gated IPC routes prevent this adapter from being invoked; it
  // keeps read/sync/remove/forget workspace operations available without Git.
  return adapter as unknown as GitRunner;
}

function normalizeGitUnavailable(error: unknown): ReqwsError {
  const normalized = toReqwsError(error, {
    code: 'GIT_NOT_FOUND',
    message: 'Git is required for this operation but was not found.',
  });
  if (normalized.code === 'GIT_NOT_FOUND') return normalized;
  return new ReqwsError(
    {
      code: 'GIT_NOT_FOUND',
      message: 'Git is required for this operation but was not found.',
      detail: normalized.detail ?? normalized.message,
    },
    { cause: error },
  );
}

export interface MainServiceFactoryOptions {
  resolveGit?: () => Promise<GitRunner>;
  spawnGitProcess?: SpawnGitProcess;
  getPreferredSystemLanguages?: () => readonly string[];
  dialog?: RegisterIpcDependencies['dialog'];
  editor?: Pick<EditorLauncherDependencies,
    'spawnProcess' | 'homeDirectory' | 'systemApplicationsDirectory' | 'processEnvironment'>;
  update?: Omit<UpdateServiceOptions, 'activity' | 'flush'>;
}

export async function createMainServices(
  userDataPath: string,
  options: MainServiceFactoryOptions = {},
): Promise<RegisterIpcDependencies> {
  const activityGate = new ApplicationActivityGate();
  const stateStore = new AppStateStore(
    path.join(userDataPath, STATE_FILE_RELATIVE_PATH),
    activityGate,
  );
  const repositoryService = new RepositoryService(stateStore);
  const settingsService = new DefaultSettingsService(
    stateStore,
    options.getPreferredSystemLanguages ?? (() => app.getPreferredSystemLanguages()),
  );
  const workspaceFiles = new WorkspaceFileWriter();

  let git: GitRunner | null = null;
  let gitUnavailableError = new ReqwsError({
    code: 'GIT_NOT_FOUND',
    message: 'Git is required for this operation but was not found.',
  });
  try {
    git = await (options.resolveGit ?? (() => GitRunner.create(options.spawnGitProcess, { activityGate })))();
  } catch (error) {
    gitUnavailableError = normalizeGitUnavailable(error);
  }

  const workspaceGit = git ?? unavailableGit(gitUnavailableError);
  const branchService = new BranchService(workspaceGit);
  const noProgress: OperationProgressPort = { report: () => undefined };
  const workspaceMutations = new WorkspaceMutationCoordinator(activityGate);

  const buildWorkspaceService = (
    progress: OperationProgressPort,
  ): WorkspaceService =>
    new WorkspaceService(
      stateStore,
      workspaceFiles,
      workspaceGit,
      branchService,
      progress,
      workspaceMutations,
    );

  const editorWorkspaceService = buildWorkspaceService(noProgress);
  const resolveEditorWorkspacePaths = async (
    workspaceId: string,
  ): Promise<WorkspacePaths> => {
    const workspace = await editorWorkspaceService.get(workspaceId);
    return {
      workspaceFilePath: workspace.workspaceFilePath,
      rootPath: workspace.rootPath,
    };
  };
  const goLandWorkspaces = new GoLandWorkspaceService(editorWorkspaceService, workspaceMutations);
  const editorLauncher = new EditorLauncher(
    resolveEditorWorkspacePaths,
    {
      ...options.editor,
      resolveGitPath: git
        ? async () => git.gitPath
        : async () => Promise.reject(gitUnavailableError),
      prepareGoLandWorkspace: (workspaceId) => goLandWorkspaces.prepare({ workspaceId }),
    },
  );

  return {
    activityGate,
    updateService: options.update
      ? new UpdateService({ ...options.update, activity: activityGate, flush: () => stateStore.flush() })
      : await createUpdateService(activityGate, () => stateStore.flush()),
    isTrustedUpdateSender: (event) => isTrustedReqwsWebContents(event.sender)
      && event.senderFrame !== null && event.senderFrame === event.sender.mainFrame,
    broadcastUpdateState: (state) => {
      for (const window of BrowserWindow.getAllWindows()) {
        if (window.isDestroyed() || !isTrustedReqwsWebContents(window.webContents)) continue;
        try { window.webContents.send(IPC_CHANNELS.updates.stateChanged, state); } catch {
          // A window can close between the check and send.
        }
      }
    },
    repositoryService,
    settingsService,
    git,
    gitAvailable: git !== null,
    gitUnavailableError,
    createOperationReporter: (event: IpcMainInvokeEvent) =>
      new OperationReporter(event.sender),
    createWorkspaceService: (event: IpcMainInvokeEvent) =>
      buildWorkspaceService(new OperationReporter(event.sender)),
    editorLauncher,
    goLandWorkspaces,
    dialog: options.dialog ?? dialog,
    windowFromWebContents: (webContents) =>
      BrowserWindow.fromWebContents(webContents),
  };
}
