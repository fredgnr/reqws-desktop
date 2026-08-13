import { afterEach, describe, expect, it, vi } from 'vitest';

interface ElectronFixture {
  app: {
    setName: ReturnType<typeof vi.fn>;
    requestSingleInstanceLock: ReturnType<typeof vi.fn>;
    quit: ReturnType<typeof vi.fn>;
    on: ReturnType<typeof vi.fn>;
    whenReady: ReturnType<typeof vi.fn>;
    getPath: ReturnType<typeof vi.fn>;
  };
  BrowserWindow: {
    getAllWindows: ReturnType<typeof vi.fn>;
    fromWebContents: ReturnType<typeof vi.fn>;
  };
}

function electronFixture(hasLock: boolean): ElectronFixture {
  return {
    app: {
      setName: vi.fn(),
      requestSingleInstanceLock: vi.fn(() => hasLock),
      quit: vi.fn(),
      on: vi.fn(),
      whenReady: vi.fn(() => new Promise<void>(() => undefined)),
      getPath: vi.fn(() => '/tmp/user-data'),
    },
    BrowserWindow: {
      getAllWindows: vi.fn(() => []),
      fromWebContents: vi.fn(),
    },
  };
}

async function importMain(fixture: ElectronFixture): Promise<void> {
  vi.doMock('electron', () => ({
    app: fixture.app,
    BrowserWindow: fixture.BrowserWindow,
    ipcMain: {},
    dialog: {},
  }));
  await import('../../src/main/index');
}

afterEach(() => {
  vi.doUnmock('electron');
  vi.resetModules();
});

describe('main single-instance lifecycle', () => {
  it('quits a second process without initializing the application', async () => {
    const fixture = electronFixture(false);

    await importMain(fixture);

    expect(fixture.app.setName).toHaveBeenCalledWith('ReqWS');
    expect(fixture.app.requestSingleInstanceLock).toHaveBeenCalledOnce();
    expect(fixture.app.quit).toHaveBeenCalledOnce();
    expect(fixture.app.whenReady).not.toHaveBeenCalled();
    expect(fixture.app.on).not.toHaveBeenCalled();
  });

  it('focuses and restores the existing window when another instance starts', async () => {
    const fixture = electronFixture(true);
    const window = {
      isMinimized: vi.fn(() => true),
      restore: vi.fn(),
      focus: vi.fn(),
    };
    fixture.BrowserWindow.getAllWindows.mockReturnValue([window]);

    await importMain(fixture);

    const secondInstance = fixture.app.on.mock.calls.find(
      ([event]) => event === 'second-instance',
    )?.[1] as (() => void) | undefined;
    expect(secondInstance).toBeTypeOf('function');
    secondInstance?.();
    expect(window.restore).toHaveBeenCalledOnce();
    expect(window.focus).toHaveBeenCalledOnce();
    expect(fixture.app.whenReady).toHaveBeenCalledOnce();
  });
});
