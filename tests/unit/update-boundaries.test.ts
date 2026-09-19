import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { IpcMainInvokeEvent } from 'electron';
import { ApplicationActivityGate } from '../../src/main/services/application-activity-gate';
import { createUpdateService, validateUpdateInstallLocation } from '../../src/main/services/create-update-service';
import { createUpdateHandlers } from '../../src/main/ipc/update-handlers';
import { IPC_CHANNELS } from '../../src/shared/ipc-channels';

vi.mock('electron', () => ({ app: {} }));
vi.mock('../../src/main/services/electron-update-adapter', () => ({ createElectronUpdateAdapter: vi.fn() }));
const directories: string[] = [];
async function directory() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reqws-update-boundary-'));
  directories.push(root);
  return await import('node:fs/promises').then(async (fs) => await fs.realpath(root));
}
afterEach(async () => { await Promise.all(directories.splice(0).map(async (root) => await rm(root, { recursive: true, force: true }))); });

describe('update runtime boundaries', () => {
  it('disables development, unsupported architectures, missing and malformed feeds', async () => {
    const resourcesPath = await directory();
    const runtime = { packaged: true, platform: 'darwin', arch: 'arm64', version: '0.1.2', resourcesPath, executable: '/Applications/ReqWS.app/Contents/MacOS/ReqWS' };
    const create = (override = {}) => createUpdateService(new ApplicationActivityGate(), async () => {}, { ...runtime, ...override });
    expect((await create({ packaged: false })).getState().reason).toBe('development');
    expect((await create({ arch: 'x64' })).getState().reason).toBe('unsupported-platform');
    expect((await create()).getState().reason).toBe('local-build');
    const filename = path.join(resourcesPath, 'app-update.yml');
    await writeFile(filename, 'not JSON');
    expect((await create()).getState()).toMatchObject({ reason: 'invalid-config', errorCode: 'UPDATE_CONFIG_INVALID' });
    const config = JSON.parse(await readFile(path.resolve('build/update/app-update.yml'), 'utf8'));
    await writeFile(filename, JSON.stringify({ ...config, token: 'forbidden' }));
    expect((await create()).getState().reason).toBe('invalid-config');
    await writeFile(filename, JSON.stringify(config));
    expect((await create()).getState().phase).toBe('idle');
    await rm(filename);
    await symlink(path.resolve('build/update/app-update.yml'), filename);
    expect((await create()).getState().reason).toBe('invalid-config');
  });

  it('allows a writable real personal Applications bundle but rejects external/symlink locations', async () => {
    const home = await directory();
    const bundle = path.join(home, 'Applications/ReqWS.app');
    const executable = path.join(bundle, 'Contents/MacOS/ReqWS');
    await mkdir(path.dirname(executable), { recursive: true });
    await writeFile(executable, 'disposable executable placeholder');
    await expect(validateUpdateInstallLocation(executable, home)).resolves.toBeUndefined();
    await expect(validateUpdateInstallLocation(path.join(home, 'out/ReqWS.app/Contents/MacOS/ReqWS'), home)).rejects.toMatchObject({ code: 'UPDATE_UNSUPPORTED_LOCATION' });
    await rm(executable);
    const target = path.join(home, 'external');
    await writeFile(target, 'placeholder');
    await symlink(target, executable);
    await expect(validateUpdateInstallLocation(executable, home)).rejects.toMatchObject({ code: 'UPDATE_UNSUPPORTED_LOCATION' });
  });

  it('rejects untrusted frames and every extra parameter before invoking an update capability', async () => {
    const updateService = {
      getState: vi.fn(() => ({ revision: 0, currentVersion: '0.1.2', phase: 'idle' })),
      check: vi.fn(), download: vi.fn(), install: vi.fn(), onStateChanged: vi.fn(), dispose: vi.fn(),
    };
    const isTrustedUpdateSender = vi.fn(() => false);
    const handlers = createUpdateHandlers({ updateService: updateService as any, isTrustedUpdateSender, broadcastUpdateState: vi.fn() });
    const event = {} as IpcMainInvokeEvent;
    for (const channel of [IPC_CHANNELS.updates.getState, IPC_CHANNELS.updates.check, IPC_CHANNELS.updates.download, IPC_CHANNELS.updates.install]) {
      expect(await handlers[channel]!(event)).toMatchObject({ ok: false, error: { code: 'INVALID_INPUT' } });
      isTrustedUpdateSender.mockReturnValue(true);
      expect(await handlers[channel]!(event, { url: 'https://example.invalid', path: '/tmp/update.zip' })).toMatchObject({ ok: false, error: { code: 'INVALID_INPUT' } });
      isTrustedUpdateSender.mockReturnValue(false);
    }
    expect(updateService.install).not.toHaveBeenCalled();
    expect(updateService.check).not.toHaveBeenCalled();
    isTrustedUpdateSender.mockReturnValue(true);
    expect(await handlers[IPC_CHANNELS.updates.getState]!(event)).toMatchObject({ ok: true, value: { phase: 'idle' } });
    updateService.getState.mockReturnValueOnce({ phase: 'idle', token: 'must not cross bridge' } as any);
    expect(await handlers[IPC_CHANNELS.updates.getState]!(event)).toMatchObject({ ok: false, error: { code: 'INVALID_INPUT' } });
  });
});
