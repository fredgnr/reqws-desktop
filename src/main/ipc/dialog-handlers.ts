import type {
  BrowserWindow,
  Dialog,
  WebContents,
} from 'electron';
import { z } from 'zod';

import { IPC_CHANNELS } from '../../shared/ipc-channels';
import { selectDirectoryInputSchema } from '../../shared/schemas';
import type { IpcHandlerMap } from './repository-handlers';
import { toIpcResult } from './ipc-result';

export interface DialogHandlerDependencies {
  dialog: Pick<Dialog, 'showOpenDialog'>;
  windowFromWebContents(webContents: WebContents): BrowserWindow | null;
}

const argumentsSchema = z.tuple([selectDirectoryInputSchema]);

export function createDialogHandlers(
  dependencies: DialogHandlerDependencies,
): IpcHandlerMap {
  return {
    [IPC_CHANNELS.dialogs.selectDirectory]: (event, ...args) =>
      toIpcResult<string | null>(async () => {
        const [input] = argumentsSchema.parse(args);
        const properties: Array<'openDirectory' | 'createDirectory'> = [
          'openDirectory',
        ];
        if (input.createDirectory) properties.push('createDirectory');

        const options = {
          title: input.title,
          ...(input.defaultPath ? { defaultPath: input.defaultPath } : {}),
          properties,
        };
        const owner = dependencies.windowFromWebContents(event.sender);
        const result = owner
          ? await dependencies.dialog.showOpenDialog(owner, options)
          : await dependencies.dialog.showOpenDialog(options);
        return result.canceled ? null : (result.filePaths[0] ?? null);
      }),
  };
}
