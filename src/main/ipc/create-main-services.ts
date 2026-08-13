import path from 'node:path';
import {
  BrowserWindow,
  dialog,
  type IpcMainInvokeEvent,
} from 'electron';

import { ReqwsError, toReqwsError } from '../../shared/errors';
import { AppStateStore } from '../services/app-state-store';
import { BranchService } from '../services/branch-service';
import { EditorLauncher } from '../services/editor-launcher';
import { GitRunner } from '../services/git-runner';
import { OperationReporter } from '../services/operation-reporter';
import { RepositoryService } from '../services/repository-service';
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
}

export async function createMainServices(
  userDataPath: string,
  options: MainServiceFactoryOptions = {},
): Promise<RegisterIpcDependencies> {
  const stateStore = new AppStateStore(
    path.join(userDataPath, STATE_FILE_RELATIVE_PATH),
  );
  const repositoryService = new RepositoryService(stateStore);
  const workspaceFiles = new WorkspaceFileWriter();

  let git: GitRunner | null = null;
  let gitUnavailableError = new ReqwsError({
    code: 'GIT_NOT_FOUND',
    message: 'Git is required for this operation but was not found.',
  });
  try {
    git = await (options.resolveGit ?? (() => GitRunner.create()))();
  } catch (error) {
    gitUnavailableError = normalizeGitUnavailable(error);
  }

  const workspaceGit = git ?? unavailableGit(gitUnavailableError);
  const branchService = new BranchService(workspaceGit);
  const noProgress: OperationProgressPort = { report: () => undefined };
  const workspaceMutations = new WorkspaceMutationCoordinator();

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

  const editorLauncher = new EditorLauncher(async (workspaceId) => {
    const workspace = await buildWorkspaceService(noProgress).get(workspaceId);
    return {
      workspaceFilePath: workspace.workspaceFilePath,
      rootPath: workspace.rootPath,
    };
  }, {
    resolveGitPath: git
      ? async () => git.gitPath
      : async () => Promise.reject(gitUnavailableError),
  });

  return {
    repositoryService,
    git,
    gitAvailable: git !== null,
    gitUnavailableError,
    createOperationReporter: (event: IpcMainInvokeEvent) =>
      new OperationReporter(event.sender),
    createWorkspaceService: (event: IpcMainInvokeEvent) =>
      buildWorkspaceService(new OperationReporter(event.sender)),
    editorLauncher,
    dialog,
    windowFromWebContents: (webContents) =>
      BrowserWindow.fromWebContents(webContents),
  };
}
