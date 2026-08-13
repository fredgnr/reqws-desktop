export interface DisplayError {
  code: string;
  message: string;
  detail?: string;
  repositoryName?: string;
  stage?: string;
}

export function errorMessageKey(code: string): string {
  return `errors.codes.${code}`;
}

export function toDisplayError(error: unknown): DisplayError {
  if (typeof error === 'object' && error !== null) {
    const candidate = error as Record<string, unknown>;
    const code = typeof candidate.code === 'string' ? candidate.code : 'UNKNOWN';
    const message = typeof candidate.message === 'string'
      ? candidate.message
      : 'ReqWS operation failed.';
    return {
      code,
      message,
      detail: typeof candidate.detail === 'string'
        ? candidate.detail
        : code === 'UNKNOWN' && typeof candidate.message === 'string'
          ? candidate.message
          : undefined,
      repositoryName: typeof candidate.repositoryName === 'string' ? candidate.repositoryName : undefined,
      stage: typeof candidate.stage === 'string' ? candidate.stage : undefined,
    };
  }
  const message = typeof error === 'string' ? error : 'ReqWS operation failed.';
  return {
    code: 'UNKNOWN',
    message,
    ...(typeof error === 'string' ? { detail: error } : {}),
  };
}
