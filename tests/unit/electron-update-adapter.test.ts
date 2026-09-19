import { EventEmitter } from 'node:events';
import { createRequire, Module } from 'node:module';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('electron', async () => {
  const { EventEmitter: Emitter } = await import('node:events');
  return {
    app: { getVersion: () => '0.1.2', getName: () => 'ReqWS', isPackaged: true },
    autoUpdater: Object.assign(new Emitter(), { checkForUpdates: vi.fn(), quitAndInstall: vi.fn() }),
  };
});

import { autoUpdater } from 'electron';
import { createElectronUpdateAdapter } from '../../src/main/services/electron-update-adapter';

const native = autoUpdater as unknown as EventEmitter & { checkForUpdates: ReturnType<typeof vi.fn>; quitAndInstall: ReturnType<typeof vi.fn> };
const require = createRequire(import.meta.url);
const electronPath = require.resolve('electron');
const originalElectronModule = require.cache[electronPath];
beforeEach(() => {
  // The pinned updater is CJS and its require('electron') bypasses vi.mock.
  // Substitute only the in-memory test module, never node_modules on disk.
  const module = new Module(electronPath);
  module.exports = { app: { getVersion: () => '0.1.2', getName: () => 'ReqWS', isPackaged: true }, autoUpdater: native };
  module.loaded = true;
  require.cache[electronPath] = module;
});
afterEach(() => {
  if (originalElectronModule) require.cache[electronPath] = originalElectronModule;
  else delete require.cache[electronPath];
  native.removeAllListeners();
  vi.clearAllMocks();
});

describe('pinned MacUpdater native listener ownership', () => {
  it('disarms installation after failure, forbids duplicate attempts, and preserves unrelated listeners on disposal', () => {
    const unrelatedError = vi.fn();
    const unrelatedDownloaded = vi.fn();
    native.on('error', unrelatedError);
    native.on('update-downloaded', unrelatedDownloaded);
    const adapter = createElectronUpdateAdapter({
      provider: 'github', owner: 'fredgnr', repo: 'reqws-desktop', private: false, updaterCacheDirName: 'reqws-desktop-updater',
    }, { progress: vi.fn(), error: () => adapter.disarmInstallation() });
    expect(native.listeners('error')).toHaveLength(2);
    expect(native.listeners('update-downloaded')).toHaveLength(2);
    adapter.install();
    expect(native.checkForUpdates).toHaveBeenCalledOnce();
    expect(native.listeners('update-downloaded')).toHaveLength(3);
    native.emit('error', new Error('Code signature did not pass validation'));
    expect(native.listeners('update-downloaded')).toHaveLength(2);
    native.emit('update-downloaded');
    expect(native.quitAndInstall).not.toHaveBeenCalled();
    expect(() => adapter.install()).toThrow('already attempted');
    adapter.dispose();
    expect(native.listeners('error')).toEqual([unrelatedError]);
    expect(native.listeners('update-downloaded')).toEqual([unrelatedDownloaded]);
  });
});
