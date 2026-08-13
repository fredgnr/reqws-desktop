import { app, BrowserWindow, ipcMain } from 'electron';

import { createWindow } from './create-window';
import { createMainServices } from './ipc/create-main-services';
import { registerIpcHandlers } from './ipc/register-ipc';

export interface ApplicationLifecycleDependencies {
  createMainWindow?: () => BrowserWindow;
  createServices?: typeof createMainServices;
  registerHandlers?: typeof registerIpcHandlers;
}

/**
 * Start only after Electron is ready: app.getPath, native dialogs and window
 * construction must not run during module evaluation. Dependencies are
 * injectable so lifecycle behavior can be tested without launching Electron.
 */
export async function startApplication(
  dependencies: ApplicationLifecycleDependencies = {},
): Promise<void> {
  const createMainWindow = dependencies.createMainWindow ?? createWindow;
  const createServices = dependencies.createServices ?? createMainServices;
  const registerHandlers = dependencies.registerHandlers ?? registerIpcHandlers;

  await app.whenReady();
  const services = await createServices(app.getPath('userData'));
  registerHandlers(ipcMain, services);
  createMainWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
  });
}

// Set the stable product name before userData is resolved.
app.setName('ReqWS');

const hasSingleInstanceLock = app.requestSingleInstanceLock();

if (!hasSingleInstanceLock) {
  // Do not initialize storage, IPC, or windows in a second process.
  app.quit();
} else {
  app.on('second-instance', () => {
    const mainWindow = BrowserWindow.getAllWindows()[0];
    if (!mainWindow) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  });

  void startApplication().catch((error: unknown) => {
    // Keep startup failures visible without leaking them across IPC. A failure
    // here means core storage/window setup failed; Git absence is handled by the
    // service factory and intentionally does not reach this branch.
    console.error('ReqWS failed to start.', error);
    app.quit();
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
