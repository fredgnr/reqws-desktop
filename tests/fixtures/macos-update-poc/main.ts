// Isolated S1 application. Never used as a ReqWS production entry point.
import { app, autoUpdater, dialog } from 'electron';
import { MacUpdater } from 'electron-updater';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const config = JSON.parse(readFileSync(path.join(process.resourcesPath, 'poc.json'), 'utf8')) as {
  root: string; smoke: boolean;
};
if (!/^\/private\/(?:tmp|var\/folders\/[^\r\n]+)\/reqws-update-poc-[A-Za-z0-9]+$/u.test(config.root)) {
  throw new Error('PoC requires its own generated temporary root.');
}
app.setName('ReqWS Update PoC');
process.env.HOME = path.join(config.root, 'home');
for (const name of ['home', 'userData', 'sessionData', 'logs']) {
  mkdirSync(path.join(config.root, name), { recursive: true });
}
app.setPath('userData', path.join(config.root, 'userData'));
app.setPath('sessionData', path.join(config.root, 'sessionData'));
app.setAppLogsPath(path.join(config.root, 'logs'));

void app.whenReady().then(async () => {
  const updater = new MacUpdater();
  updater.logger = null;
  updater.autoDownload = false;
  updater.autoInstallOnAppQuit = false;
  updater.autoRunAppAfterInstall = true;
  updater.allowPrerelease = false;
  updater.allowDowngrade = false;
  updater.disableDifferentialDownload = true;
  const record = (stage: string, error?: Error) => {
    const value = {
      stage, version: app.getVersion(), electron: process.versions.electron,
      packaged: app.isPackaged, userData: app.getPath('userData'),
      ...(error ? { diagnostic: error.message.replace(/https?:\/\/\S+/gu, '[url]').slice(0, 512) } : {}),
    };
    writeFileSync(path.join(config.root, 'result.json'), JSON.stringify(value, null, 2));
    appendFileSync(path.join(config.root, 'events.jsonl'), `${JSON.stringify(value)}\n`);
  };
  autoUpdater.on('error', (error) => record('native-signature-or-install-error', error));
  record('started');
  if (config.smoke) {
    record('offline-smoke-passed');
    app.exit(0);
    return;
  }
  if (app.getVersion() === '0.0.2') {
    record('restarted-into-r2');
    await dialog.showMessageBox({ message: 'PoC restarted into 0.0.2', buttons: ['Close'] });
    app.quit();
    return;
  }
  let failed = false;
  updater.on('error', (error) => {
    failed = true;
    record('update-error-old-app-running', error);
    void dialog.showMessageBox({ message: 'Update rejected or failed. The old PoC is still running.', buttons: ['Close'] })
      .then(() => app.quit());
  });
  const first = await dialog.showMessageBox({
    message: 'Isolated update PoC 0.0.1 → 0.0.2',
    detail: 'Uses a loopback feed and disposable application data. Start the generated feed server first.',
    buttons: ['Check and download', 'Cancel'], cancelId: 1,
  });
  if (first.response !== 0) { app.quit(); return; }
  try {
    const result = await updater.checkForUpdates();
    if (!result || result.updateInfo.version !== '0.0.2') throw new Error('PoC R2 unavailable.');
    await updater.downloadUpdate();
    record('downloaded-native-validation-pending');
    if (failed) return;
    const install = await dialog.showMessageBox({
      message: 'Downloaded. Native signature verification runs at install.',
      buttons: ['Install and restart', 'Quit without installing'], cancelId: 1,
    });
    if (install.response === 0 && !failed) {
      record('install-requested');
      updater.quitAndInstall();
    } else {
      record('quit-without-installing');
      app.quit();
    }
  } catch {
    if (!failed) {
      record('check-or-download-failed');
      await dialog.showMessageBox({ message: 'PoC check/download failed.', buttons: ['Close'] });
      app.quit();
    }
  }
}).catch(() => app.exit(1));
