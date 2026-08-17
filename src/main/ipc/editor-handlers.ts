import { z } from 'zod';

import { IPC_CHANNELS } from '../../shared/ipc-channels';
import {
  idSchema,
  systemAvailabilitySchema,
} from '../../shared/schemas';
import type { SystemAvailability } from '../../shared/types';
import type { EditorLauncher } from '../services/editor-launcher';
import type { IpcHandlerMap } from './repository-handlers';
import { toIpcResult } from './ipc-result';

export interface EditorHandlerDependencies {
  editorLauncher: Pick<
    EditorLauncher,
    | 'getAvailability'
    | 'openVSCode'
    | 'openCursor'
    | 'openCursorRoot'
    | 'openGoLand'
    | 'revealInFinder'
  >;
}

const noArgumentsSchema = z.tuple([]);
const idArgumentsSchema = z.tuple([idSchema]);

export function createEditorHandlers(
  dependencies: EditorHandlerDependencies,
): IpcHandlerMap {
  const invokeWithId = (
    method: (workspaceId: string) => Promise<void>,
    args: unknown[],
  ) =>
    toIpcResult<void>(() => {
      const [id] = idArgumentsSchema.parse(args);
      return method(id);
    });

  return {
    [IPC_CHANNELS.editors.availability]: (_event, ...args) =>
      toIpcResult<SystemAvailability>(async () => {
        noArgumentsSchema.parse(args);
        return systemAvailabilitySchema.parse(
          await dependencies.editorLauncher.getAvailability(),
        );
      }),
    [IPC_CHANNELS.editors.openVSCode]: (_event, ...args) =>
      invokeWithId(
        dependencies.editorLauncher.openVSCode.bind(
          dependencies.editorLauncher,
        ),
        args,
      ),
    [IPC_CHANNELS.editors.openCursor]: (_event, ...args) =>
      invokeWithId(
        dependencies.editorLauncher.openCursor.bind(
          dependencies.editorLauncher,
        ),
        args,
      ),
    [IPC_CHANNELS.editors.openCursorRoot]: (_event, ...args) =>
      invokeWithId(
        dependencies.editorLauncher.openCursorRoot.bind(
          dependencies.editorLauncher,
        ),
        args,
      ),
    [IPC_CHANNELS.editors.openGoLand]: (_event, ...args) =>
      invokeWithId(
        dependencies.editorLauncher.openGoLand.bind(
          dependencies.editorLauncher,
        ),
        args,
      ),
    [IPC_CHANNELS.editors.revealInFinder]: (_event, ...args) =>
      invokeWithId(
        dependencies.editorLauncher.revealInFinder.bind(
          dependencies.editorLauncher,
        ),
        args,
      ),
  };
}
