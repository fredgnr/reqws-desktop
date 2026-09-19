import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApplicationActivityGate } from '../../src/main/services/application-activity-gate';
import { AppStateStore } from '../../src/main/services/app-state-store';
import { UpdateService, updateError, type UpdateAdapterEvents } from '../../src/main/services/update-service';
import { WorkspaceMutationCoordinator } from '../../src/main/services/workspace-service';

function deferred<T = void>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fixture(options: { timeoutMs?: number; disabledReason?: 'local-build' } = {}) {
  const activity = new ApplicationActivityGate();
  let events!: UpdateAdapterEvents;
  const adapter = {
    check: vi.fn(async () => ({ version: '0.2.0', releaseNotes: '<img src=x onerror=alert(1)>' })),
    download: vi.fn(async () => {}), install: vi.fn(), disarmInstallation: vi.fn(), dispose: vi.fn(),
  };
  const validateInstallLocation = vi.fn(async () => {});
  const flush = vi.fn(async () => {});
  const service = new UpdateService({
    currentVersion: '0.1.2', activity, validateInstallLocation, flush, ...options,
    createAdapter: (callbacks) => { events = callbacks; return adapter; },
  });
  return { activity, adapter, service, flush, validateInstallLocation, events: () => events };
}

async function downloaded(test: ReturnType<typeof fixture>) {
  await test.service.check();
  await test.service.download();
}

afterEach(() => vi.useRealTimers());

describe('manual update service', () => {
  it('does not start deferred network operations after disposal', async () => {
    const checking = fixture();
    const check = checking.service.check();
    checking.service.dispose();
    await expect(check).rejects.toMatchObject({ code: 'UPDATE_CHECK_FAILED' });
    expect(checking.adapter.check).not.toHaveBeenCalled();
    const downloading = fixture();
    await downloading.service.check();
    const download = downloading.service.download();
    downloading.service.dispose();
    await expect(download).rejects.toMatchObject({ code: 'UPDATE_DOWNLOAD_FAILED' });
    expect(downloading.adapter.download).not.toHaveBeenCalled();
  });

  it('does not instantiate or request updates for a disabled build', async () => {
    const test = fixture({ disabledReason: 'local-build' });
    expect(test.service.getState()).toMatchObject({ phase: 'disabled', reason: 'local-build' });
    await expect(test.service.check()).rejects.toMatchObject({ code: 'UPDATE_DISABLED' });
    await expect(test.service.download()).rejects.toMatchObject({ code: 'UPDATE_DISABLED' });
    await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_DISABLED' });
    expect(test.adapter.check).not.toHaveBeenCalled();
  });

  it('shares check/download flights, publishes progress, and downloads only on request', async () => {
    const test = fixture();
    const check = test.service.check();
    expect(test.service.check()).toBe(check);
    expect(await check).toMatchObject({ phase: 'available', nextVersion: '0.2.0' });
    expect(test.adapter.download).not.toHaveBeenCalled();
    const pending = deferred();
    test.adapter.download.mockReturnValueOnce(pending.promise);
    const download = test.service.download();
    expect(test.service.download()).toBe(download);
    await Promise.resolve();
    test.events().progress(45.5);
    expect(test.service.getState()).toMatchObject({ phase: 'downloading', percent: 45.5 });
    test.events().progress(Number.NaN);
    expect(test.service.getState().percent).toBe(45.5);
    pending.resolve();
    expect(await download).toMatchObject({ phase: 'downloaded', percent: 100 });
    expect(test.adapter.install).not.toHaveBeenCalled();
  });

  it.each(['0.1.1', '0.1.2', '0.2.0-beta', 'https://example.invalid'])('does not offer a downgrade, unchanged or invalid version %s', async (version) => {
    const test = fixture();
    test.adapter.check.mockResolvedValue({ version, releaseNotes: '' });
    expect(await test.service.check()).toMatchObject({ phase: 'not-available' });
    await expect(test.service.download()).rejects.toMatchObject({ code: 'UPDATE_NOT_AVAILABLE' });
  });

  it('removes stale versions after a failed check, redacts diagnostics, and permits retry', async () => {
    const test = fixture();
    await test.service.check();
    test.adapter.check.mockRejectedValueOnce(new Error('https://example.invalid?token=secret'));
    await expect(test.service.check()).rejects.toMatchObject({ code: 'UPDATE_CHECK_FAILED' });
    expect(test.service.getState()).not.toHaveProperty('nextVersion');
    expect(JSON.stringify(test.service.getState())).not.toContain('secret');
    expect(JSON.stringify(test.service.getDiagnostics())).not.toContain('secret');
    expect(await test.service.check()).toMatchObject({ phase: 'available' });
  });

  it('times out checks and ignores late results from a retired generation', async () => {
    vi.useFakeTimers();
    const test = fixture({ timeoutMs: 100 });
    const late = deferred<{ version: string; releaseNotes: string }>();
    test.adapter.check.mockReturnValueOnce(late.promise);
    const result = expect(test.service.check()).rejects.toMatchObject({ code: 'UPDATE_TIMEOUT' });
    await vi.advanceTimersByTimeAsync(101);
    await result;
    expect(test.adapter.dispose).toHaveBeenCalled();
    await test.service.check();
    late.resolve({ version: '9.9.9', releaseNotes: '' });
    await Promise.resolve();
    expect(test.service.getState().nextVersion).toBe('0.2.0');
  });

  it('times out downloads, discards the adapter, and requires a fresh check', async () => {
    vi.useFakeTimers();
    const test = fixture({ timeoutMs: 100 });
    await test.service.check();
    test.adapter.download.mockReturnValueOnce(new Promise(() => {}));
    const result = expect(test.service.download()).rejects.toMatchObject({ code: 'UPDATE_TIMEOUT' });
    await vi.advanceTimersByTimeAsync(101);
    await result;
    await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_NOT_DOWNLOADED' });
    expect(await test.service.check()).toMatchObject({ phase: 'available' });
  });

  it('freezes admission before location/flush waits and releases it after native signature rejection', async () => {
    const test = fixture();
    await downloaded(test);
    const location = deferred();
    test.validateInstallLocation.mockReturnValueOnce(location.promise);
    const install = test.service.install();
    expect(() => test.activity.enter()).toThrow();
    await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
    expect(test.adapter.install).not.toHaveBeenCalled();
    location.resolve();
    await install;
    expect(test.flush).toHaveBeenCalledOnce();
    expect(test.adapter.install).toHaveBeenCalledOnce();
    test.events().error(new Error('Code signature at URL file:///private/path did not pass validation'));
    expect(test.service.getState()).toMatchObject({ phase: 'error', reason: 'restart-required', errorCode: 'UPDATE_SIGNATURE_INVALID' });
    expect(test.adapter.disarmInstallation).toHaveBeenCalledOnce();
    const release = test.activity.enter();
    release();
    await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_RESTART_REQUIRED' });
    await expect(test.service.check()).rejects.toMatchObject({ code: 'UPDATE_RESTART_REQUIRED' });
  });

  it('rejects unsupported install locations without invoking native installation or retaining the freeze', async () => {
    const test = fixture();
    await downloaded(test);
    test.validateInstallLocation.mockRejectedValueOnce(updateError('UPDATE_UNSUPPORTED_LOCATION'));
    await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_UNSUPPORTED_LOCATION' });
    expect(test.adapter.install).not.toHaveBeenCalled();
    expect(test.adapter.disarmInstallation).not.toHaveBeenCalled();
    expect(test.service.getState()).toMatchObject({ phase: 'downloaded', errorCode: 'UPDATE_UNSUPPORTED_LOCATION' });
    test.activity.enter()();
    await expect(test.service.check()).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
    await test.service.install();
    expect(test.adapter.install).toHaveBeenCalledOnce();
    expect(test.service.getState().errorCode).toBeUndefined();
    test.service.dispose();
  });

  it('protects active and queued workspace mutations from installation', async () => {
    const test = fixture();
    await downloaded(test);
    const queue = new WorkspaceMutationCoordinator(test.activity);
    const first = deferred();
    const second = deferred();
    const one = queue.run(() => first.promise);
    const two = queue.run(() => second.promise);
    await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
    first.resolve();
    await one;
    await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
    second.resolve();
    await two;
    await test.service.install();
    expect(test.adapter.install).toHaveBeenCalledOnce();
    test.service.dispose();
  });

  it('protects queued persistent writes and rejects new writes after shutdown admission', async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-update-state-'));
    const test = fixture();
    const store = new AppStateStore(path.join(directory, 'state.json'), test.activity);
    try {
      await downloaded(test);
      const pending = deferred();
      const update = store.update(async (state) => { await pending.promise; return state; });
      await expect(test.service.install()).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
      pending.resolve();
      await update;
      await store.flush();
      await test.service.install();
      await expect(store.update((state) => state)).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
      test.service.dispose();
      await expect(store.read()).resolves.toMatchObject({ schemaVersion: 1 });
    } finally { await rm(directory, { recursive: true, force: true }); }
  });

  it('publishes immutable monotonically revised snapshots and releases subscriptions', async () => {
    const test = fixture();
    const listener = vi.fn();
    const unsubscribe = test.service.onStateChanged(listener);
    await test.service.check();
    const snapshots = listener.mock.calls.map(([state]) => state);
    expect(snapshots.map((state) => state.phase)).toEqual(['checking', 'available']);
    expect(snapshots[1].revision).toBeGreaterThan(snapshots[0].revision);
    snapshots[1].phase = 'installing';
    expect(test.service.getState().phase).toBe('available');
    unsubscribe();
    await test.service.download();
    expect(listener).toHaveBeenCalledTimes(2);
  });
});
