import type { IpcMain } from 'electron';
import { createGoLandWorkspaceHandlers, type GoLandWorkspaceHandlerDependencies } from './goland-workspace-handlers';
import { serializeReqwsError } from '../../shared/errors';
import { IPC_CHANNELS } from '../../shared/ipc-channels';
import { updateStateSchema } from '../../shared/update-schemas';
import type { ApplicationActivityGate } from '../services/application-activity-gate';
import { createUpdateHandlers, type UpdateHandlerDependencies } from './update-handlers';

import { createDialogHandlers } from './dialog-handlers';
import type { DialogHandlerDependencies } from './dialog-handlers';
import { createEditorHandlers } from './editor-handlers';
import type { EditorHandlerDependencies } from './editor-handlers';
import { createRepositoryHandlers } from './repository-handlers';
import type {
  IpcHandlerMap,
  RepositoryHandlerDependencies,
} from './repository-handlers';
import { createSettingsHandlers } from './settings-handlers';
import type { SettingsHandlerDependencies } from './settings-handlers';
import { createWorkspaceHandlers } from './workspace-handlers';
import type { WorkspaceHandlerDependencies } from './workspace-handlers';

export type RegisterIpcDependencies = GoLandWorkspaceHandlerDependencies & RepositoryHandlerDependencies &
  WorkspaceHandlerDependencies &
  SettingsHandlerDependencies &
  EditorHandlerDependencies &
  DialogHandlerDependencies & UpdateHandlerDependencies & { activityGate: ApplicationActivityGate };

export type IpcMainPort = Pick<IpcMain, 'handle' | 'removeHandler'>;

const registrations = new WeakMap<IpcMainPort, { token: symbol; unsubscribe: () => void }>();

function handlerMap(dependencies: RegisterIpcDependencies): IpcHandlerMap {
  return {
    ...createGoLandWorkspaceHandlers(dependencies),
    ...createUpdateHandlers(dependencies),
    ...createRepositoryHandlers(dependencies),
    ...createWorkspaceHandlers(dependencies),
    ...createSettingsHandlers(dependencies),
    ...createDialogHandlers(dependencies),
    ...createEditorHandlers(dependencies),
  };
}

/**
 * Own all ReqWS invoke channels as one replaceable registration. Re-registering
 * (for example during tests or a controlled lifecycle restart) never leaves a
 * duplicate Electron handler behind.
 */
export function registerIpcHandlers(
  ipcMain: IpcMainPort,
  dependencies: RegisterIpcDependencies,
): () => void {
  const handlers = handlerMap(dependencies);
  const registration = Symbol('reqws-ipc-registration');
  registrations.get(ipcMain)?.unsubscribe();
  const updateChannels = new Set<string>(Object.values(IPC_CHANNELS.updates));

  for (const [channel, handler] of Object.entries(handlers)) {
    ipcMain.removeHandler(channel);
    ipcMain.handle(channel, updateChannels.has(channel) ? handler : async (event, ...args: unknown[]) => {
      let release: (() => void) | undefined;
      try {
        release = dependencies.activityGate.enter();
        return await handler(event, ...args);
      } catch (error) {
        return { ok: false, error: serializeReqwsError(error) };
      } finally { release?.(); }
    });
  }
  const unsubscribe = dependencies.updateService.onStateChanged((state) => {
    dependencies.broadcastUpdateState(updateStateSchema.parse(state));
  });
  registrations.set(ipcMain, { token: registration, unsubscribe });

  return () => {
    if (registrations.get(ipcMain)?.token !== registration) return;
    unsubscribe();
    for (const channel of Object.keys(handlers)) ipcMain.removeHandler(channel);
    registrations.delete(ipcMain);
  };
}
