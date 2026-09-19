import { ReqwsError } from '../../shared/errors';
import { updateStateSchema } from '../../shared/update-schemas';
import type { UpdateErrorCode, UpdateState } from '../../shared/update-types';
import type { ApplicationActivityGate } from './application-activity-gate';

export interface UpdateAdapterEvents {
  progress(percent: number): void;
  error(error: unknown): void;
}

export interface UpdateAdapter {
  check(): Promise<{ version: string; releaseNotes?: unknown } | null>;
  download(): Promise<void>;
  install(): void;
  disarmInstallation(): void;
  dispose(): void;
}

export interface UpdateServiceOptions {
  currentVersion: string;
  disabledReason?: UpdateState['reason'];
  createAdapter?: (events: UpdateAdapterEvents) => UpdateAdapter;
  activity: ApplicationActivityGate;
  flush: () => Promise<void>;
  validateInstallLocation: () => Promise<void>;
  timeoutMs?: number;
}

export function updateError(code: UpdateErrorCode): ReqwsError {
  return new ReqwsError({ code, message: 'Application update could not complete.' });
}

function safeNotes(value: unknown): string | undefined {
  const notes = Array.isArray(value)
    ? value.slice(0, 20).map((entry: unknown) => {
      if (entry && typeof entry === 'object' && 'note' in entry && typeof entry.note === 'string') return entry.note;
      return '';
    }).join('\n')
    : typeof value === 'string' ? value : '';
  return notes ? notes.slice(0, 8_000) : undefined;
}

function isNewerStableVersion(next: string, current: string): boolean {
  const pattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u;
  if (next.length > 80 || !pattern.test(next) || !pattern.test(current)) return false;
  const left = next.split('.').map(BigInt);
  const right = current.split('.').map(BigInt);
  for (let index = 0; index < 3; index += 1) {
    if (left[index] !== right[index]) return left[index]! > right[index]!;
  }
  return false;
}

export class UpdateService {
  private state: UpdateState;
  private readonly listeners = new Set<(state: UpdateState) => void>();
  private readonly diagnostics: { code: UpdateErrorCode; phase: UpdateState['phase'] }[] = [];
  private adapter?: UpdateAdapter;
  private checkFlight?: Promise<UpdateState>;
  private downloadFlight?: Promise<UpdateState>;
  private releaseShutdown?: () => void;
  private generation = 0;
  private disposed = false;

  constructor(private readonly options: UpdateServiceOptions) {
    this.state = updateStateSchema.parse({
      revision: 0, currentVersion: options.currentVersion,
      phase: options.disabledReason ? 'disabled' : 'idle',
      ...(options.disabledReason ? { reason: options.disabledReason } : {}),
      ...(options.disabledReason === 'invalid-config' ? { errorCode: 'UPDATE_CONFIG_INVALID' } : {}),
    });
  }

  getState(): UpdateState { return { ...this.state }; }

  getDiagnostics() { return this.diagnostics.map((entry) => ({ ...entry })); }

  onStateChanged(listener: (state: UpdateState) => void): () => void {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  }

  check(): Promise<UpdateState> {
    if (this.checkFlight) return this.checkFlight;
    const error = this.unavailable();
    if (error) return Promise.reject(error);
    if (this.downloadFlight || this.state.phase === 'downloaded' || this.state.phase === 'installing') {
      return Promise.reject(updateError('UPDATE_BUSY'));
    }
    const generation = ++this.generation;
    const flight = Promise.resolve().then(async () => {
      if (this.disposed || generation !== this.generation) throw updateError('UPDATE_DISABLED');
      this.publish({ phase: 'checking' });
      this.adapter?.dispose();
      this.adapter = this.options.createAdapter!({
        progress: (percent) => {
          if (generation === this.generation && this.state.phase === 'downloading' && Number.isFinite(percent)) {
            this.publish({ ...this.state, percent: Math.max(0, Math.min(100, percent)) });
          }
        },
        error: (caught) => this.handleAdapterError(generation, caught),
      });
      const info = await this.withTimeout(this.adapter.check());
      if (generation !== this.generation || this.state.phase !== 'checking') {
        throw updateError(this.state.errorCode ?? 'UPDATE_CHECK_FAILED');
      }
      if (!info || !isNewerStableVersion(info.version, this.options.currentVersion)) {
        this.publish({ phase: 'not-available' });
      } else {
        this.publish({ phase: 'available', nextVersion: info.version, releaseNotes: safeNotes(info.releaseNotes) });
      }
      return this.getState();
    }).catch((caught: unknown) => {
      const code = caught instanceof ReqwsError && caught.code === 'UPDATE_TIMEOUT' ? 'UPDATE_TIMEOUT' : 'UPDATE_CHECK_FAILED';
      if (generation === this.generation) {
        this.adapter?.dispose();
        this.adapter = undefined;
        this.fail(code);
      }
      throw updateError(code);
    }).finally(() => { if (this.checkFlight === flight) this.checkFlight = undefined; });
    this.checkFlight = flight;
    return flight;
  }

  download(): Promise<UpdateState> {
    if (this.downloadFlight) return this.downloadFlight;
    const error = this.unavailable();
    if (error) return Promise.reject(error);
    if (this.checkFlight || this.state.phase === 'installing') return Promise.reject(updateError('UPDATE_BUSY'));
    if (this.state.phase !== 'available' || !this.adapter) return Promise.reject(updateError('UPDATE_NOT_AVAILABLE'));
    const generation = this.generation;
    const adapter = this.adapter;
    const flight = Promise.resolve().then(async () => {
      if (this.disposed || generation !== this.generation) throw updateError('UPDATE_DISABLED');
      this.publish({ ...this.state, phase: 'downloading', percent: 0 });
      await this.withTimeout(adapter.download());
      if (generation !== this.generation || this.state.phase !== 'downloading') {
        throw updateError(this.state.errorCode ?? 'UPDATE_DOWNLOAD_FAILED');
      }
      this.publish({ ...this.state, phase: 'downloaded', percent: 100 });
      return this.getState();
    }).catch((caught: unknown) => {
      const code = caught instanceof ReqwsError && caught.code === 'UPDATE_TIMEOUT' ? 'UPDATE_TIMEOUT' : 'UPDATE_DOWNLOAD_FAILED';
      if (generation === this.generation) {
        adapter.dispose();
        this.adapter = undefined;
        this.fail(code);
      }
      throw updateError(code);
    }).finally(() => { if (this.downloadFlight === flight) this.downloadFlight = undefined; });
    this.downloadFlight = flight;
    return flight;
  }

  async install(): Promise<void> {
    const error = this.unavailable();
    if (error) throw error;
    if (this.releaseShutdown || this.state.phase === 'installing') throw updateError('UPDATE_BUSY');
    if (this.checkFlight || this.downloadFlight || this.state.phase !== 'downloaded' || !this.adapter) {
      throw updateError('UPDATE_NOT_DOWNLOADED');
    }
    // Close admission before either filesystem validation or flush can yield.
    this.releaseShutdown = this.options.activity.acquireShutdown();
    let installAttempted = false;
    try {
      await this.options.validateInstallLocation();
      await this.options.flush();
      if (this.disposed) throw updateError('UPDATE_DISABLED');
      this.publish({ ...this.state, phase: 'installing', errorCode: undefined });
      installAttempted = true;
      this.adapter.install();
    } catch (caught) {
      const code = caught instanceof ReqwsError && caught.code === 'UPDATE_UNSUPPORTED_LOCATION'
        ? 'UPDATE_UNSUPPORTED_LOCATION' : 'UPDATE_INSTALL_FAILED';
      if (installAttempted) this.adapter?.disarmInstallation();
      this.releaseFreeze();
      this.fail(code, installAttempted, !installAttempted);
      throw updateError(code);
    }
  }

  dispose(): void {
    this.disposed = true;
    this.generation += 1;
    this.adapter?.dispose();
    this.releaseFreeze();
    this.listeners.clear();
  }

  private unavailable(): ReqwsError | undefined {
    if (this.disposed || this.state.phase === 'disabled' || !this.options.createAdapter) return updateError('UPDATE_DISABLED');
    if (this.state.reason === 'restart-required') return updateError('UPDATE_RESTART_REQUIRED');
    return undefined;
  }

  private handleAdapterError(generation: number, error: unknown) {
    if (generation !== this.generation || this.disposed || this.state.reason === 'restart-required') return;
    if (this.state.phase === 'installing') {
      const message = error instanceof Error ? error.message : '';
      const code = /code signature|code requirement|signature.*valid|代码要求|签名|密封资源/iu.test(message)
        ? 'UPDATE_SIGNATURE_INVALID' : 'UPDATE_INSTALL_FAILED';
      this.adapter?.disarmInstallation();
      this.releaseFreeze();
      this.fail(code, true);
    }
    // Check/download errors are handled by their promises. Their raw diagnostic
    // text never reaches renderer state or the bounded diagnostic log.
  }

  private releaseFreeze() {
    this.releaseShutdown?.();
    this.releaseShutdown = undefined;
  }

  private fail(code: UpdateErrorCode, restartRequired = false, retainDownload = false) {
    this.diagnostics.push({ code, phase: this.state.phase });
    if (this.diagnostics.length > 50) this.diagnostics.shift();
    // Admission failures have not touched native installation. Keep the one
    // downloaded adapter so retry cannot strand another native proxy server.
    this.publish(retainDownload
      ? { ...this.state, phase: 'downloaded', errorCode: code }
      : { phase: 'error', errorCode: code, ...(restartRequired ? { reason: 'restart-required' } : {}) });
  }

  private publish(next: Pick<UpdateState, 'phase'> & Partial<UpdateState>) {
    if (this.disposed) return;
    this.state = updateStateSchema.parse({ ...next, currentVersion: this.options.currentVersion, revision: this.state.revision + 1 });
    for (const listener of this.listeners) listener(this.getState());
  }

  private async withTimeout<T>(operation: Promise<T>): Promise<T> {
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        operation,
        new Promise<never>((_resolve, reject) => {
          timeout = setTimeout(() => reject(updateError('UPDATE_TIMEOUT')), this.options.timeoutMs ?? 120_000);
        }),
      ]);
    } finally { if (timeout) clearTimeout(timeout); }
  }
}
