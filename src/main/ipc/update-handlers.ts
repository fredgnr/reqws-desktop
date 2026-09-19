import type { IpcMainInvokeEvent } from 'electron';
import { ReqwsError } from '../../shared/errors';
import { IPC_CHANNELS } from '../../shared/ipc-channels';
import { updateNoArgumentsSchema, updateStateSchema } from '../../shared/update-schemas';
import type { UpdateState } from '../../shared/update-types';
import type { UpdateService } from '../services/update-service';
import { toIpcResult } from './ipc-result';
import type { IpcHandlerMap } from './repository-handlers';

export interface UpdateHandlerDependencies {
  updateService: Pick<UpdateService, 'getState' | 'check' | 'download' | 'install' | 'onStateChanged' | 'dispose'>;
  isTrustedUpdateSender: (event: IpcMainInvokeEvent) => boolean;
  broadcastUpdateState: (state: UpdateState) => void;
}

export function createUpdateHandlers(dependencies: UpdateHandlerDependencies): IpcHandlerMap {
  const validate = (event: IpcMainInvokeEvent, args: unknown[]) => {
    updateNoArgumentsSchema.parse(args);
    if (!dependencies.isTrustedUpdateSender(event)) {
      throw new ReqwsError({ code: 'INVALID_INPUT', message: 'Update IPC requires the trusted main window.' });
    }
  };
  return {
    [IPC_CHANNELS.updates.getState]: (event, ...args) => toIpcResult(() => {
      validate(event, args);
      return updateStateSchema.parse(dependencies.updateService.getState());
    }),
    [IPC_CHANNELS.updates.check]: (event, ...args) => toIpcResult(async () => {
      validate(event, args);
      return updateStateSchema.parse(await dependencies.updateService.check());
    }),
    [IPC_CHANNELS.updates.download]: (event, ...args) => toIpcResult(async () => {
      validate(event, args);
      return updateStateSchema.parse(await dependencies.updateService.download());
    }),
    [IPC_CHANNELS.updates.install]: (event, ...args) => toIpcResult(async () => {
      validate(event, args);
      await dependencies.updateService.install();
    }),
  };
}
