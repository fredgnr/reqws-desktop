import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';

import type { IpcResult } from '../shared/ipc-channels';
import { IPC_CHANNELS } from '../shared/ipc-channels';
import type { OperationProgress, ReqwsAPI } from '../shared/types';
import { updateStateSchema } from '../shared/update-schemas';

async function invoke<T>(channel: string, ...args: unknown[]): Promise<T> {
  const result = (await ipcRenderer.invoke(channel, ...args)) as IpcResult<T>;
  if (result.ok) return result.value;
  // Keep the rejection structured-clone friendly across contextBridge. Electron
  // does not guarantee preservation of custom Error properties.
  throw { ...result.error };
}

const api: ReqwsAPI = {
  repositories: {
    list: () => invoke(IPC_CHANNELS.repositories.list),
    create: (input) => invoke(IPC_CHANNELS.repositories.create, input),
    update: (input) => invoke(IPC_CHANNELS.repositories.update, input),
    remove: (id, confirmReferenced) =>
      invoke(IPC_CHANNELS.repositories.remove, id, confirmReferenced),
    testConnection: (input) => invoke(IPC_CHANNELS.repositories.test, input),
  },
  workspaces: {
    list: () => invoke(IPC_CHANNELS.workspaces.list),
    get: (id) => invoke(IPC_CHANNELS.workspaces.get, id),
    create: (input) => invoke(IPC_CHANNELS.workspaces.create, input),
    addRepository: (input) =>
      invoke(IPC_CHANNELS.workspaces.addRepository, input),
    removeRepository: (input) =>
      invoke(IPC_CHANNELS.workspaces.removeRepository, input),
    sync: (id) => invoke(IPC_CHANNELS.workspaces.sync, id),
    forget: (id) => invoke(IPC_CHANNELS.workspaces.forget, id),
  },
  settings: {
    get: () => invoke(IPC_CHANNELS.settings.get),
    save: (settings) => invoke(IPC_CHANNELS.settings.save, settings),
  },
  updates: {
    getState: async () => updateStateSchema.parse(await invoke(IPC_CHANNELS.updates.getState)),
    check: async () => updateStateSchema.parse(await invoke(IPC_CHANNELS.updates.check)),
    download: async () => updateStateSchema.parse(await invoke(IPC_CHANNELS.updates.download)),
    install: () => invoke(IPC_CHANNELS.updates.install),
    onStateChanged: (listener) => {
      const wrapped = (_event: IpcRendererEvent, value: unknown): void => {
        const state = updateStateSchema.safeParse(value);
        if (state.success) listener(state.data);
      };
      ipcRenderer.on(IPC_CHANNELS.updates.stateChanged, wrapped);
      return () => { ipcRenderer.removeListener(IPC_CHANNELS.updates.stateChanged, wrapped); };
    },
  },
  dialogs: {
    selectDirectory: (input) =>
      invoke(IPC_CHANNELS.dialogs.selectDirectory, input),
  },
  editors: {
    getAvailability: () => invoke(IPC_CHANNELS.editors.availability),
    openVSCode: (workspaceId) =>
      invoke(IPC_CHANNELS.editors.openVSCode, workspaceId),
    openCursor: (workspaceId) =>
      invoke(IPC_CHANNELS.editors.openCursor, workspaceId),
    openCursorRoot: (workspaceId) =>
      invoke(IPC_CHANNELS.editors.openCursorRoot, workspaceId),
    openGoLand: (workspaceId) =>
      invoke(IPC_CHANNELS.editors.openGoLand, workspaceId),
    revealInFinder: (workspaceId) =>
      invoke(IPC_CHANNELS.editors.revealInFinder, workspaceId),
  },
  operations: {
    onProgress: (listener) => {
      const wrapped = (
        _event: IpcRendererEvent,
        progress: OperationProgress,
      ): void => listener(progress);
      ipcRenderer.on(IPC_CHANNELS.operationProgress, wrapped);
      return () => {
        ipcRenderer.removeListener(IPC_CHANNELS.operationProgress, wrapped);
      };
    },
  },
};

function exposeReqwsAPI(): void {
  contextBridge.exposeInMainWorld('reqws', api);
}

exposeReqwsAPI();

export { api, exposeReqwsAPI };
