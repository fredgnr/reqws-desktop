import type { UpdatesAPI, UpdateState } from '../../src/shared/update-types';

export function disabledUpdates(): UpdatesAPI {
  const state: UpdateState = { revision: 0, currentVersion: '0.1.2', phase: 'disabled', reason: 'local-build' };
  const unavailable = async (): Promise<never> => { throw { code: 'UPDATE_DISABLED', message: 'Disabled test build.' }; };
  return {
    getState: async () => ({ ...state }), check: unavailable, download: unavailable,
    install: unavailable, onStateChanged: () => () => {},
  };
}
