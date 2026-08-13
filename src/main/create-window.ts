import { BrowserWindow, type BrowserWindowConstructorOptions } from 'electron';
import path from 'node:path';

import { installWebContentsSecurity } from './security';

export interface CreateWindowOptions {
  BrowserWindowClass?: typeof BrowserWindow;
  platform?: NodeJS.Platform;
  preloadPath?: string;
  devServerUrl?: string;
  rendererName?: string;
  mainDirectory?: string;
}

export function browserWindowOptions(
  preloadPath: string,
  platform: NodeJS.Platform = process.platform,
): BrowserWindowConstructorOptions {
  return {
    width: 1280,
    height: 820,
    minWidth: 1040,
    minHeight: 680,
    show: false,
    ...(platform === 'darwin' ? { titleBarStyle: 'hiddenInset' } : {}),
    webPreferences: {
      preload: preloadPath,
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
    },
  };
}

export function createWindow(options: CreateWindowOptions = {}): BrowserWindow {
  const BrowserWindowClass = options.BrowserWindowClass ?? BrowserWindow;
  const mainDirectory = options.mainDirectory ?? __dirname;
  const preloadPath =
    options.preloadPath ?? path.join(mainDirectory, 'preload.js');
  const devServerUrl =
    options.devServerUrl ??
    (typeof MAIN_WINDOW_VITE_DEV_SERVER_URL === 'string'
      ? MAIN_WINDOW_VITE_DEV_SERVER_URL
      : undefined);
  const rendererName =
    options.rendererName ??
    (typeof MAIN_WINDOW_VITE_NAME === 'string'
      ? MAIN_WINDOW_VITE_NAME
      : 'main_window');

  const window = new BrowserWindowClass(
    browserWindowOptions(preloadPath, options.platform),
  );
  installWebContentsSecurity(window.webContents);
  window.once('ready-to-show', () => window.show());

  if (devServerUrl) {
    void window.loadURL(devServerUrl);
  } else {
    void window.loadFile(
      path.join(mainDirectory, `../renderer/${rendererName}/index.html`),
    );
  }

  return window;
}

