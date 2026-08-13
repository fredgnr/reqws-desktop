export interface DisplayError {
  code: string;
  message: string;
  detail?: string;
  repositoryName?: string;
  stage?: string;
}

export function toDisplayError(error: unknown): DisplayError {
  if (typeof error === 'object' && error !== null) {
    const candidate = error as Record<string, unknown>;
    return {
      code: typeof candidate.code === 'string' ? candidate.code : 'UNKNOWN_ERROR',
      message: typeof candidate.message === 'string' ? candidate.message : '操作失败',
      detail: typeof candidate.detail === 'string' ? candidate.detail : undefined,
      repositoryName: typeof candidate.repositoryName === 'string' ? candidate.repositoryName : undefined,
      stage: typeof candidate.stage === 'string' ? candidate.stage : undefined,
    };
  }
  return { code: 'UNKNOWN_ERROR', message: typeof error === 'string' ? error : '操作失败' };
}
