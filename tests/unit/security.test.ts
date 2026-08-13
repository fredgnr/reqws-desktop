import { describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  BrowserWindow: class BrowserWindow {},
}));

import { browserWindowOptions } from '../../src/main/create-window';
import {
  DENIED_WINDOW_OPEN,
  installWebContentsSecurity,
} from '../../src/main/security';

describe('BrowserWindow security', () => {
  it('enables isolation, sandboxing and web security with Node disabled', () => {
    const options = browserWindowOptions('/build/preload.js', 'darwin');

    expect(options).toMatchObject({
      titleBarStyle: 'hiddenInset',
      webPreferences: {
        preload: '/build/preload.js',
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
        webSecurity: true,
        allowRunningInsecureContent: false,
      },
    });
    expect(options).not.toHaveProperty('trafficLightPosition');
  });

  it('does not apply macOS title chrome settings on other platforms', () => {
    expect(browserWindowOptions('/build/preload.js', 'linux')).not.toHaveProperty(
      'titleBarStyle',
    );
  });

  it('denies popups and prevents renderer navigation', () => {
    let navigateListener: ((event: { preventDefault(): void }) => void) | undefined;
    const webContents = {
      setWindowOpenHandler: vi.fn(),
      on: vi.fn((name: string, listener: typeof navigateListener) => {
        if (name === 'will-navigate') navigateListener = listener;
      }),
    };
    installWebContentsSecurity(webContents as never);

    const openHandler = webContents.setWindowOpenHandler.mock.calls[0]?.[0];
    expect(openHandler?.({})).toEqual(DENIED_WINDOW_OPEN);
    const event = { preventDefault: vi.fn() };
    navigateListener?.(event);
    expect(event.preventDefault).toHaveBeenCalledOnce();
  });
});
