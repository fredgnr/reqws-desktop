import { describe, expect, it, vi } from 'vitest';

import { OperationReporter } from '../../src/main/services/operation-reporter';
import { IPC_CHANNELS } from '../../src/shared/ipc-channels';

const progress = {
  operationId: 'op_1',
  kind: 'create-workspace' as const,
  stage: 'cloning' as const,
  current: 1,
  total: 2,
  message: 'Cloning',
};

describe('OperationReporter', () => {
  it('uses the fixed channel while the renderer is alive', () => {
    const send = vi.fn();
    new OperationReporter({ isDestroyed: () => false, send }).report(progress);
    expect(send).toHaveBeenCalledWith(IPC_CHANNELS.operationProgress, progress);
  });

  it('never lets a renderer teardown failure abort the owning operation', () => {
    const send = vi.fn(() => { throw new Error('render frame was disposed'); });
    const reporter = new OperationReporter({ isDestroyed: () => false, send });
    expect(() => reporter.report(progress)).not.toThrow();
  });

  it('does not send after webContents is destroyed', () => {
    const send = vi.fn();
    new OperationReporter({ isDestroyed: () => true, send }).report(progress);
    expect(send).not.toHaveBeenCalled();
  });
});
