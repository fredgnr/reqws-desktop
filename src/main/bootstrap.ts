import { app, BrowserWindow, ipcMain } from 'electron';
import path from 'node:path';

import { createWindow } from './create-window';
import { createMainServices, type MainServiceFactoryOptions } from './ipc/create-main-services';
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
  serviceOptions: MainServiceFactoryOptions = {},
): Promise<void> {
  const createMainWindow = dependencies.createMainWindow ?? createWindow;
  const createServices = dependencies.createServices ?? createMainServices;
  const registerHandlers = dependencies.registerHandlers ?? registerIpcHandlers;

  await app.whenReady();
  const services = await createServices(app.getPath('userData'), serviceOptions);
  let unregister: (() => void) | undefined;
  let disposed = false;
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    unregister?.();
    services.updateService.dispose();
  };
  try {
    unregister = registerHandlers(ipcMain, services);
    createMainWindow();
  } catch (error) {
    dispose();
    throw error;
  }
  app.on('will-quit', dispose);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
  });
}

export interface BootstrapOptions {
  userDataPath?: string;
  sessionDataPath?: string;
  logsPath?: string;
  serviceOptions?: MainServiceFactoryOptions;
}

/**
 * Callers create and validate supplied directories. The production entry does
 * not enable overrides through environment variables or command-line switches.
 * Paths and the lock are configured synchronously, before readiness or storage.
 */
export function bootstrapApplication(options: BootstrapOptions = {}): Promise<void> {
  for (const directory of [options.userDataPath, options.sessionDataPath, options.logsPath]) {
    if (directory !== undefined && (!path.isAbsolute(directory) || directory.includes('\0'))) {
      throw new Error('Application directories must be absolute paths without null bytes.');
    }
  }
  app.setName('ReqWS');
  if (options.userDataPath !== undefined) app.setPath('userData', options.userDataPath);
  if (options.sessionDataPath !== undefined) app.setPath('sessionData', options.sessionDataPath);
  if (options.logsPath !== undefined) app.setAppLogsPath(options.logsPath);

  if (!app.requestSingleInstanceLock()) {
    app.quit();
    return Promise.resolve();
  }
  app.on('second-instance', () => {
    const mainWindow = BrowserWindow.getAllWindows()[0];
    if (!mainWindow) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  });
  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  return startApplication({}, options.serviceOptions).catch((error: unknown) => {
    console.error('ReqWS failed to start.', error);
    app.quit();
  });
}
