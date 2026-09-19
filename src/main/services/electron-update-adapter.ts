import { autoUpdater as nativeUpdater } from 'electron';
import { MacUpdater } from 'electron-updater';
import type { UpdateCheckResult } from 'electron-updater';
import type { parseUpdateConfig } from '../../shared/update-config';
import type { UpdateAdapter, UpdateAdapterEvents } from './update-service';

/** Own only listeners created by this adapter; never remove other consumers. */
export function createElectronUpdateAdapter(
  config: ReturnType<typeof parseUpdateConfig>,
  events: UpdateAdapterEvents,
): UpdateAdapter {
  const initialError = new Set(nativeUpdater.listeners('error'));
  const initialDownloaded = new Set(nativeUpdater.listeners('update-downloaded'));
  const updater = new MacUpdater(config);
  updater.logger = null;
  updater.autoDownload = false;
  updater.autoInstallOnAppQuit = false;
  updater.autoRunAppAfterInstall = true;
  updater.allowPrerelease = false;
  updater.allowDowngrade = false;
  updater.disableDifferentialDownload = true;
  const ownedError = nativeUpdater.listeners('error').filter((listener) => !initialError.has(listener)) as ((...args: any[]) => void)[];
  const ownedDownloaded = nativeUpdater.listeners('update-downloaded').filter((listener) => !initialDownloaded.has(listener)) as ((...args: any[]) => void)[];
  const installListeners = new Set<(...args: any[]) => void>();
  let checkResult: UpdateCheckResult | null = null;
  let installAttempted = false;
  let disarmed = false;
  const onProgress = ({ percent }: { percent: number }) => events.progress(percent);
  const onError = (error: Error) => events.error(error);
  updater.on('download-progress', onProgress);
  updater.on('error', onError);
  const disarmInstallation = () => {
    disarmed = true;
    for (const listener of installListeners) nativeUpdater.removeListener('update-downloaded', listener);
    installListeners.clear();
  };
  return {
    check: async () => {
      checkResult = await updater.checkForUpdates();
      return checkResult?.isUpdateAvailable ? checkResult.updateInfo : null;
    },
    download: async () => { await updater.downloadUpdate(checkResult?.cancellationToken); },
    install: () => {
      if (installAttempted) throw new Error('Native installation already attempted.');
      installAttempted = true;
      const before = new Set(nativeUpdater.listeners('update-downloaded'));
      try { updater.quitAndInstall(); } finally {
        for (const listener of nativeUpdater.listeners('update-downloaded')) {
          if (!before.has(listener)) installListeners.add(listener as (...args: any[]) => void);
        }
        if (disarmed) disarmInstallation();
      }
    },
    disarmInstallation,
    dispose: () => {
      checkResult?.cancellationToken?.cancel();
      disarmInstallation();
      updater.removeListener('download-progress', onProgress);
      updater.removeListener('error', onError);
      for (const listener of ownedError) nativeUpdater.removeListener('error', listener);
      for (const listener of ownedDownloaded) nativeUpdater.removeListener('update-downloaded', listener);
    },
  };
}
