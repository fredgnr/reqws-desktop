import { ZodError } from 'zod';

import { ReqwsError, serializeReqwsError } from '../../shared/errors';
import type { IpcResult } from '../../shared/ipc-channels';

function invalidInput(error: ZodError): ReqwsError {
  return new ReqwsError(
    {
      code: 'INVALID_INPUT',
      message: 'IPC input is invalid.',
      detail: error.issues
        .map((issue) => {
          const location = issue.path.length > 0 ? issue.path.join('.') : 'input';
          return `${location}: ${issue.message}`;
        })
        .join('\n'),
    },
    { cause: error },
  );
}

/**
 * Electron IPC must only receive plain result envelopes. Service exceptions,
 * including Zod validation errors, are converted here and never cross the
 * process boundary as thrown Error instances.
 */
export async function toIpcResult<T>(
  operation: () => T | Promise<T>,
): Promise<IpcResult<T>> {
  try {
    const value = await operation();
    return { ok: true, value };
  } catch (error) {
    return {
      ok: false,
      error: serializeReqwsError(
        error instanceof ZodError ? invalidInput(error) : error,
      ),
    };
  }
}

