import type { IpcMainInvokeEvent } from 'electron';
import { z } from 'zod';

import type { ReqwsError } from '../../shared/errors';
import { IPC_CHANNELS } from '../../shared/ipc-channels';
import {
  addWorkspaceRepositoryInputSchema,
  createWorkspaceInputSchema,
  idSchema,
  removeWorkspaceRepositoryInputSchema,
} from '../../shared/schemas';
import type {
  AppSettings,
  WorkspaceDetail,
  WorkspaceSummary,
} from '../../shared/types';
import type { WorkspaceService } from '../services/workspace-service';
import type { IpcHandlerMap } from './repository-handlers';
import { toIpcResult } from './ipc-result';

type WorkspaceServicePort = Pick<
  WorkspaceService,
  | 'list'
  | 'getSettings'
  | 'get'
  | 'create'
  | 'addRepository'
  | 'removeRepository'
  | 'sync'
  | 'forget'
>;

export interface WorkspaceHandlerDependencies {
  gitAvailable: boolean;
  gitUnavailableError: ReqwsError;
  createWorkspaceService(event: IpcMainInvokeEvent): WorkspaceServicePort;
}

const noArgumentsSchema = z.tuple([]);
const idArgumentsSchema = z.tuple([idSchema]);
const createArgumentsSchema = z.tuple([createWorkspaceInputSchema]);
const addArgumentsSchema = z.tuple([addWorkspaceRepositoryInputSchema]);
const removeArgumentsSchema = z.tuple([
  removeWorkspaceRepositoryInputSchema,
]);

function requireGit(dependencies: WorkspaceHandlerDependencies): void {
  if (!dependencies.gitAvailable) throw dependencies.gitUnavailableError;
}

export function createWorkspaceHandlers(
  dependencies: WorkspaceHandlerDependencies,
): IpcHandlerMap {
  return {
    [IPC_CHANNELS.workspaces.list]: (event, ...args) =>
      toIpcResult<WorkspaceSummary[]>(() => {
        noArgumentsSchema.parse(args);
        return dependencies.createWorkspaceService(event).list();
      }),
    [IPC_CHANNELS.workspaces.getSettings]: (event, ...args) =>
      toIpcResult<AppSettings>(() => {
        noArgumentsSchema.parse(args);
        return dependencies.createWorkspaceService(event).getSettings();
      }),
    [IPC_CHANNELS.workspaces.get]: (event, ...args) =>
      toIpcResult<WorkspaceDetail>(() => {
        const [id] = idArgumentsSchema.parse(args);
        return dependencies.createWorkspaceService(event).get(id);
      }),
    [IPC_CHANNELS.workspaces.create]: (event, ...args) =>
      toIpcResult<WorkspaceDetail>(() => {
        const [input] = createArgumentsSchema.parse(args);
        requireGit(dependencies);
        return dependencies.createWorkspaceService(event).create(input);
      }),
    [IPC_CHANNELS.workspaces.addRepository]: (event, ...args) =>
      toIpcResult<WorkspaceDetail>(() => {
        const [input] = addArgumentsSchema.parse(args);
        requireGit(dependencies);
        return dependencies.createWorkspaceService(event).addRepository(input);
      }),
    [IPC_CHANNELS.workspaces.removeRepository]: (event, ...args) =>
      toIpcResult<WorkspaceDetail>(() => {
        const [input] = removeArgumentsSchema.parse(args);
        return dependencies.createWorkspaceService(event).removeRepository(input);
      }),
    [IPC_CHANNELS.workspaces.sync]: (event, ...args) =>
      toIpcResult<WorkspaceDetail>(() => {
        const [id] = idArgumentsSchema.parse(args);
        return dependencies.createWorkspaceService(event).sync(id);
      }),
    [IPC_CHANNELS.workspaces.forget]: (event, ...args) =>
      toIpcResult<void>(() => {
        const [id] = idArgumentsSchema.parse(args);
        return dependencies.createWorkspaceService(event).forget(id);
      }),
  };
}
