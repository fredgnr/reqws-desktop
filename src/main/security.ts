import type { WebContents } from 'electron';

export const DENIED_WINDOW_OPEN = { action: 'deny' } as const;
const trustedWindows = new WeakSet<WebContents>();

export function isTrustedReqwsWebContents(webContents: WebContents): boolean {
  return trustedWindows.has(webContents) && !webContents.isDestroyed();
}

/** ReqWS has no browser navigation or popup capability. */
export function installWebContentsSecurity(webContents: WebContents): void {
  trustedWindows.add(webContents);
  webContents.setWindowOpenHandler(() => DENIED_WINDOW_OPEN);
  webContents.on('will-navigate', (event) => event.preventDefault());
}
