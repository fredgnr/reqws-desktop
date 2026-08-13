import { appStateSchema } from '../../shared/schemas';
import type { AppState } from '../../shared/types';
import { AtomicJsonStore } from './atomic-json-store';

export function createDefaultAppState(): AppState {
  return {
    schemaVersion: 1,
    settings: {},
    repositories: [],
    workspaces: [],
  };
}

/**
 * Global application state with an in-process transaction queue. Every update
 * reads the result of the prior committed update, preventing read/modify/write
 * callers from silently losing each other's changes.
 */
export class AppStateStore {
  private readonly jsonStore: AtomicJsonStore<AppState>;
  private pending: Promise<void> = Promise.resolve();

  constructor(filePath: string) {
    this.jsonStore = new AtomicJsonStore(filePath, {
      defaultValue: createDefaultAppState,
      parse: (value) => appStateSchema.parse(value),
      readErrorCode: 'STATE_READ_FAILED',
      writeErrorCode: 'STATE_WRITE_FAILED',
      corruptErrorCode: 'STATE_CORRUPT',
    });
  }

  async read(): Promise<AppState> {
    return this.enqueue(async () => structuredClone(await this.jsonStore.read()));
  }

  async update(
    mutator: (state: AppState) => AppState | Promise<AppState>,
  ): Promise<AppState> {
    return this.enqueue(async () => {
      const current = structuredClone(await this.jsonStore.read());
      const next = appStateSchema.parse(await mutator(current));
      await this.jsonStore.write(next);
      return structuredClone(next);
    });
  }

  async replace(state: AppState): Promise<AppState> {
    return this.enqueue(async () => {
      const next = appStateSchema.parse(structuredClone(state));
      await this.jsonStore.write(next);
      return structuredClone(next);
    });
  }

  private enqueue<TResult>(operation: () => Promise<TResult>): Promise<TResult> {
    const result = this.pending.then(operation, operation);
    this.pending = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }
}
