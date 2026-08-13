import type { WebContents } from 'electron';

import { IPC_CHANNELS } from '../../shared/ipc-channels';
import type { OperationProgress } from '../../shared/types';

export type ProgressWebContents = Pick<WebContents, 'isDestroyed' | 'send'>;

/** Sends operation progress only to the renderer that owns the operation. */
export class OperationReporter {
  constructor(private readonly webContents: ProgressWebContents) {}

  report(progress: OperationProgress): void {
    if (this.webContents.isDestroyed()) return;
    // Progress is best-effort telemetry. A renderer closing between the
    // isDestroyed check and send must never abort Git work or skip rollback.
    try {
      this.webContents.send(IPC_CHANNELS.operationProgress, progress);
    } catch {
      // The owning renderer disappeared; the service operation continues.
    }
  }
}
