import type { IpcMain } from 'electron';

import { createDialogHandlers } from './dialog-handlers';
import type { DialogHandlerDependencies } from './dialog-handlers';
import { createEditorHandlers } from './editor-handlers';
import type { EditorHandlerDependencies } from './editor-handlers';
import { createRepositoryHandlers } from './repository-handlers';
import type {
  IpcHandlerMap,
  RepositoryHandlerDependencies,
} from './repository-handlers';
import { createWorkspaceHandlers } from './workspace-handlers';
import type { WorkspaceHandlerDependencies } from './workspace-handlers';

export type RegisterIpcDependencies = RepositoryHandlerDependencies &
  WorkspaceHandlerDependencies &
  EditorHandlerDependencies &
  DialogHandlerDependencies;

export type IpcMainPort = Pick<IpcMain, 'handle' | 'removeHandler'>;

const registrations = new WeakMap<IpcMainPort, symbol>();

function handlerMap(dependencies: RegisterIpcDependencies): IpcHandlerMap {
  return {
    ...createRepositoryHandlers(dependencies),
    ...createWorkspaceHandlers(dependencies),
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

  for (const [channel, handler] of Object.entries(handlers)) {
    ipcMain.removeHandler(channel);
    ipcMain.handle(channel, handler);
  }
  registrations.set(ipcMain, registration);

  return () => {
    if (registrations.get(ipcMain) !== registration) return;
    for (const channel of Object.keys(handlers)) ipcMain.removeHandler(channel);
    registrations.delete(ipcMain);
  };
}

