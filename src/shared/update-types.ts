export const updatePhases = [
  'disabled', 'idle', 'checking', 'available', 'not-available',
  'downloading', 'downloaded', 'installing', 'error',
] as const;
export const updateReasons = [
  'development', 'unsupported-platform', 'local-build', 'invalid-config', 'restart-required',
] as const;
export const updateErrorCodes = [
  'UPDATE_DISABLED', 'UPDATE_BUSY', 'UPDATE_NOT_AVAILABLE', 'UPDATE_NOT_DOWNLOADED',
  'UPDATE_CHECK_FAILED', 'UPDATE_DOWNLOAD_FAILED', 'UPDATE_SIGNATURE_INVALID',
  'UPDATE_INSTALL_FAILED', 'UPDATE_UNSUPPORTED_LOCATION', 'UPDATE_CONFIG_INVALID',
  'UPDATE_RESTART_REQUIRED', 'UPDATE_TIMEOUT',
] as const;
export type UpdateErrorCode = (typeof updateErrorCodes)[number];
export type UpdatePhase = (typeof updatePhases)[number];
export interface UpdateState {
  revision: number;
  phase: UpdatePhase;
  currentVersion: string;
  nextVersion?: string;
  percent?: number;
  reason?: (typeof updateReasons)[number];
  errorCode?: UpdateErrorCode;
  releaseNotes?: string;
}
export interface UpdatesAPI {
  getState(): Promise<UpdateState>;
  check(): Promise<UpdateState>;
  download(): Promise<UpdateState>;
  install(): Promise<void>;
  onStateChanged(listener: (state: UpdateState) => void): () => void;
}
