import { z } from 'zod';
import { IPC_CHANNELS } from '../../shared/ipc-channels';
import { prepareGoLandWorkspaceSchema, saveGoLandSelectionSchema } from '../../shared/goland-workspace';
import { idSchema } from '../../shared/schemas';
import type { GoLandWorkspaceService } from '../services/goland-workspace-service';
import type { IpcHandlerMap } from './repository-handlers';
import { toIpcResult } from './ipc-result';

export interface GoLandWorkspaceHandlerDependencies {
  goLandWorkspaces: Pick<GoLandWorkspaceService, 'read' | 'prepare' | 'save'>;
}

export function createGoLandWorkspaceHandlers({ goLandWorkspaces }: GoLandWorkspaceHandlerDependencies): IpcHandlerMap {
  return {
    [IPC_CHANNELS.goLandWorkspaces.read]: (_event, ...args) => toIpcResult(() => {
      const [id] = z.tuple([idSchema]).parse(args);
      return goLandWorkspaces.read(id);
    }),
    [IPC_CHANNELS.goLandWorkspaces.prepare]: (_event, ...args) => toIpcResult(() => {
      const [input] = z.tuple([prepareGoLandWorkspaceSchema]).parse(args);
      return goLandWorkspaces.prepare(input);
    }),
    [IPC_CHANNELS.goLandWorkspaces.save]: (_event, ...args) => toIpcResult(() => {
      const [input] = z.tuple([saveGoLandSelectionSchema]).parse(args);
      return goLandWorkspaces.save(input);
    }),
  };
}
