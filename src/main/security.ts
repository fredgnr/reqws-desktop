import type { WebContents } from 'electron';

export const DENIED_WINDOW_OPEN = { action: 'deny' } as const;

/** ReqWS has no browser navigation or popup capability. */
export function installWebContentsSecurity(webContents: WebContents): void {
  webContents.setWindowOpenHandler(() => DENIED_WINDOW_OPEN);
  webContents.on('will-navigate', (event) => event.preventDefault());
}

