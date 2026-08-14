export const reqwsErrorCodes = [
  'GIT_NOT_FOUND',
  'GIT_PROCESS_FAILED',
  'GIT_PROCESS_TIMEOUT',
  'INVALID_INPUT',
  'INVALID_REPOSITORY_NAME',
  'DUPLICATE_REPOSITORY_NAME',
  'INVALID_BRANCH_NAME',
  'REPOSITORY_UNREACHABLE',
  'REPOSITORY_IN_USE',
  'WORKSPACE_ROOT_EXISTS',
  'WORKSPACE_FILE_EXISTS',
  'CLONE_FAILED',
  'DEFAULT_BRANCH_NOT_FOUND',
  'FEATURE_BRANCH_CHECKOUT_FAILED',
  'WORKSPACE_NOT_FOUND',
  'WORKSPACE_PATH_MISSING',
  'REPOSITORY_NOT_FOUND',
  'REPOSITORY_ALREADY_ADDED',
  'REPOSITORY_PATH_CONFLICT',
  'EDITOR_NOT_FOUND',
  'STATE_READ_FAILED',
  'STATE_WRITE_FAILED',
  'STATE_CORRUPT',
  'SETTINGS_INVALID_LOCALE',
  'SETTINGS_DIRECTORY_NOT_FOUND',
  'SETTINGS_DIRECTORY_NOT_DIRECTORY',
  'SETTINGS_READ_FAILED',
  'SETTINGS_WRITE_FAILED',
  'MANIFEST_READ_FAILED',
  'MANIFEST_WRITE_FAILED',
  'WORKSPACE_FILE_WRITE_FAILED',
  'OPERATION_IN_PROGRESS',
  'UNKNOWN',
] as const;

export type ReqwsErrorCode = (typeof reqwsErrorCodes)[number];

export interface ReqwsErrorPayload {
  code: ReqwsErrorCode;
  message: string;
  detail?: string;
  repositoryName?: string;
  stage?: string;
}

const errorCodeSet = new Set<string>(reqwsErrorCodes);

export class ReqwsError extends Error {
  readonly code: ReqwsErrorCode;
  readonly detail?: string;
  readonly repositoryName?: string;
  readonly stage?: string;

  constructor(payload: ReqwsErrorPayload, options?: ErrorOptions) {
    super(payload.message, options);
    this.name = 'ReqwsError';
    this.code = payload.code;
    this.detail = payload.detail;
    this.repositoryName = payload.repositoryName;
    this.stage = payload.stage;
  }

  toPayload(): ReqwsErrorPayload {
    return compactPayload({
      code: this.code,
      message: this.message,
      detail: this.detail,
      repositoryName: this.repositoryName,
      stage: this.stage,
    });
  }
}

export function isReqwsErrorPayload(value: unknown): value is ReqwsErrorPayload {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.code === 'string' &&
    errorCodeSet.has(candidate.code) &&
    typeof candidate.message === 'string'
  );
}

export function toReqwsError(
  error: unknown,
  fallback: ReqwsErrorPayload = {
    code: 'UNKNOWN',
    message: 'ReqWS operation failed.',
  },
): ReqwsError {
  if (error instanceof ReqwsError) return error;
  if (isReqwsErrorPayload(error)) return new ReqwsError(error);
  if (error && typeof error === 'object') {
    const value = error as Record<string, unknown>;
    if (
      typeof value.code === 'string' &&
      errorCodeSet.has(value.code) &&
      typeof value.message === 'string'
    ) {
      return new ReqwsError(
        compactPayload({
          code: value.code as ReqwsErrorCode,
          message: value.message,
          detail: typeof value.detail === 'string' ? value.detail : undefined,
          repositoryName:
            typeof value.repositoryName === 'string'
              ? value.repositoryName
              : undefined,
          stage: typeof value.stage === 'string' ? value.stage : undefined,
        }),
        { cause: error },
      );
    }
  }
  return new ReqwsError(fallback, { cause: error });
}

export function serializeReqwsError(error: unknown): ReqwsErrorPayload {
  return toReqwsError(error).toPayload();
}

function compactPayload(payload: ReqwsErrorPayload): ReqwsErrorPayload {
  return Object.fromEntries(
    Object.entries(payload).filter(([, value]) => value !== undefined),
  ) as unknown as ReqwsErrorPayload;
}
