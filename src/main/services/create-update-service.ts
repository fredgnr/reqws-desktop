import { app } from 'electron';
import { access, lstat, readFile, realpath } from 'node:fs/promises';
import { constants } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { parseUpdateConfig } from '../../shared/update-config';
import type { UpdateState } from '../../shared/update-types';
import type { ApplicationActivityGate } from './application-activity-gate';
import { UpdateService, updateError } from './update-service';

export async function validateUpdateInstallLocation(executable: string, home = os.homedir()) {
  const bundle = path.resolve(executable, '../../..');
  const allowed = ['/Applications/ReqWS.app', path.join(home, 'Applications/ReqWS.app')];
  try {
    if (!allowed.includes(bundle) || executable !== path.join(bundle, 'Contents/MacOS/ReqWS')
      || await realpath(executable) !== executable || await realpath(bundle) !== bundle
      || !(await lstat(bundle)).isDirectory() || !(await lstat(executable)).isFile()) {
      throw updateError('UPDATE_UNSUPPORTED_LOCATION');
    }
    await access(bundle, constants.W_OK);
    await access(path.dirname(bundle), constants.W_OK);
  } catch { throw updateError('UPDATE_UNSUPPORTED_LOCATION'); }
}

export interface UpdateRuntime {
  packaged: boolean;
  platform: string;
  arch: string;
  version: string;
  resourcesPath: string;
  executable: string;
}

export async function createUpdateService(
  activity: ApplicationActivityGate,
  flush: () => Promise<void>,
  runtime: UpdateRuntime = {
    packaged: app.isPackaged, platform: process.platform, arch: process.arch,
    version: app.getVersion(), resourcesPath: process.resourcesPath, executable: process.execPath,
  },
): Promise<UpdateService> {
  let disabledReason: UpdateState['reason'];
  let config: ReturnType<typeof parseUpdateConfig> | undefined;
  if (!runtime.packaged) disabledReason = 'development';
  else if (runtime.platform !== 'darwin' || runtime.arch !== 'arm64') disabledReason = 'unsupported-platform';
  else {
    try {
      const filename = path.join(runtime.resourcesPath, 'app-update.yml');
      const status = await lstat(filename);
      if (!status.isFile() || status.isSymbolicLink() || status.size > 8_192) throw new Error('Invalid update configuration.');
      config = parseUpdateConfig(await readFile(filename, 'utf8'));
    } catch (error) {
      disabledReason = (error as NodeJS.ErrnoException).code === 'ENOENT' ? 'local-build' : 'invalid-config';
    }
  }
  // Import only after ready and platform/build/resource checks. There is no
  // forceDevUpdateConfig or environment/user-directory feed override.
  let adapterModule: typeof import('./electron-update-adapter') | undefined;
  if (config) {
    try { adapterModule = await import('./electron-update-adapter'); } catch {
      disabledReason = 'invalid-config';
    }
  }
  const trustedConfig = config;
  return new UpdateService({
    currentVersion: runtime.version, disabledReason, activity, flush,
    validateInstallLocation: () => validateUpdateInstallLocation(runtime.executable),
    ...(adapterModule && trustedConfig ? { createAdapter: (events) => adapterModule.createElectronUpdateAdapter(trustedConfig, events) } : {}),
  });
}
