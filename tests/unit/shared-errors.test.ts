import { describe, expect, it } from 'vitest';
import {
  ReqwsError,
  isReqwsErrorPayload,
  serializeReqwsError,
  toReqwsError,
} from '../../src/shared/errors';

describe('ReqWS errors', () => {
  it('round-trips stable payloads for IPC', () => {
    const error = new ReqwsError({
      code: 'CLONE_FAILED',
      message: 'Clone failed.',
      detail: 'network unavailable',
      repositoryName: 'order-api',
      stage: 'cloning',
    });
    expect(isReqwsErrorPayload(error.toPayload())).toBe(true);
    expect(serializeReqwsError(error)).toEqual(error.toPayload());
  });

  it('adapts structurally compatible service errors', () => {
    const error = toReqwsError({
      code: 'INVALID_BRANCH_NAME',
      message: 'bad branch',
      stage: 'validating',
    });
    expect(error).toMatchObject({
      code: 'INVALID_BRANCH_NAME',
      stage: 'validating',
    });
  });

  it('does not expose arbitrary error text for unknown failures', () => {
    expect(serializeReqwsError(new Error('secret')).code).toBe('UNKNOWN');
    expect(serializeReqwsError(new Error('secret')).message).not.toContain('secret');
  });
});
