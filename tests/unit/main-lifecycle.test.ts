import { afterEach, describe, expect, it, vi } from 'vitest';

interface ElectronFixture {
  app: {
    setName: ReturnType<typeof vi.fn>;
    setPath: ReturnType<typeof vi.fn>;
    setAppLogsPath: ReturnType<typeof vi.fn>;
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
      setPath: vi.fn(),
      setAppLogsPath: vi.fn(),
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

function mockElectron(fixture: ElectronFixture): void {
  vi.doMock('electron', () => ({
    app: fixture.app,
    BrowserWindow: fixture.BrowserWindow,
    ipcMain: {},
    dialog: {},
  }));
}

async function importMain(fixture: ElectronFixture): Promise<void> {
  mockElectron(fixture);
  await import('../../src/main/index');
}

afterEach(() => {
  vi.doUnmock('electron');
  vi.resetModules();
});

describe('main single-instance lifecycle', () => {
  it('keeps importing bootstrap inert and sets all paths synchronously before the lock', async () => {
    const fixture = electronFixture(true);
    mockElectron(fixture);
    const { bootstrapApplication } = await import('../../src/main/bootstrap');
    expect(fixture.app.setName).not.toHaveBeenCalled();
    void bootstrapApplication({
      userDataPath: '/tmp/fixture/userData',
      sessionDataPath: '/tmp/fixture/sessionData',
      logsPath: '/tmp/fixture/logs',
    });
    expect(fixture.app.setPath.mock.calls).toEqual([
      ['userData', '/tmp/fixture/userData'],
      ['sessionData', '/tmp/fixture/sessionData'],
    ]);
    expect(fixture.app.setAppLogsPath).toHaveBeenCalledWith('/tmp/fixture/logs');
    const lockOrder = fixture.app.requestSingleInstanceLock.mock.invocationCallOrder[0]!;
    expect(fixture.app.setName.mock.invocationCallOrder[0]).toBeLessThan(lockOrder);
    for (const order of fixture.app.setPath.mock.invocationCallOrder) expect(order).toBeLessThan(lockOrder);
    expect(fixture.app.setAppLogsPath.mock.invocationCallOrder[0]).toBeLessThan(lockOrder);
    expect(lockOrder).toBeLessThan(fixture.app.whenReady.mock.invocationCallOrder[0]!);
    expect(fixture.app.getPath).not.toHaveBeenCalled();
  });

  it.each(['userDataPath', 'sessionDataPath', 'logsPath'] as const)(
    'rejects invalid %s before changing paths or taking a lock', async (key) => {
      const fixture = electronFixture(true);
      mockElectron(fixture);
      const { bootstrapApplication } = await import('../../src/main/bootstrap');
      for (const invalid of ['', 'relative/path', '/tmp/path\0suffix']) {
        expect(() => bootstrapApplication({ userDataPath: '/tmp/valid', [key]: invalid })).toThrow();
      }
      expect(fixture.app.setName).not.toHaveBeenCalled();
      expect(fixture.app.setPath).not.toHaveBeenCalled();
      expect(fixture.app.requestSingleInstanceLock).not.toHaveBeenCalled();
    },
  );

  it('quits a second process without initializing the application', async () => {
    const fixture = electronFixture(false);

    await importMain(fixture);

    expect(fixture.app.setName).toHaveBeenCalledWith('ReqWS');
    expect(fixture.app.requestSingleInstanceLock).toHaveBeenCalledOnce();
    expect(fixture.app.quit).toHaveBeenCalledOnce();
    expect(fixture.app.whenReady).not.toHaveBeenCalled();
    expect(fixture.app.on).not.toHaveBeenCalled();
    expect(fixture.app.setPath).not.toHaveBeenCalled();
    expect(fixture.app.setAppLogsPath).not.toHaveBeenCalled();
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

  it('reads the configured path after readiness and disposes services and IPC once on quit', async () => {
    const fixture = electronFixture(true);
    fixture.app.whenReady.mockResolvedValue(undefined);
    mockElectron(fixture);
    const { startApplication } = await import('../../src/main/bootstrap');
    const dispose = vi.fn();
    const services = { updateService: { dispose } };
    const createServices = vi.fn().mockResolvedValue(services);
    const unregister = vi.fn();
    const registerHandlers = vi.fn(() => unregister);
    const createMainWindow = vi.fn();
    const serviceOptions = { getPreferredSystemLanguages: () => ['en-US'] };
    await startApplication({ createServices, registerHandlers, createMainWindow }, serviceOptions);
    expect(createServices).toHaveBeenCalledWith('/tmp/user-data', serviceOptions);
    expect(registerHandlers).toHaveBeenCalledWith({}, services);
    expect(registerHandlers.mock.invocationCallOrder[0]).toBeLessThan(createMainWindow.mock.invocationCallOrder[0]!);
    const quit = fixture.app.on.mock.calls.find(([event]) => event === 'will-quit')?.[1] as () => void;
    quit();
    quit();
    expect(unregister).toHaveBeenCalledOnce();
    expect(dispose).toHaveBeenCalledOnce();
    const activate = fixture.app.on.mock.calls.find(([event]) => event === 'activate')?.[1] as () => void;
    activate();
    expect(createMainWindow).toHaveBeenCalledTimes(2);
  });

  it('cleans up registered IPC and services when window construction fails', async () => {
    const fixture = electronFixture(true);
    fixture.app.whenReady.mockResolvedValue(undefined);
    mockElectron(fixture);
    const { startApplication } = await import('../../src/main/bootstrap');
    const dispose = vi.fn();
    const unregister = vi.fn();
    await expect(startApplication({
      createServices: vi.fn().mockResolvedValue({ updateService: { dispose } }),
      registerHandlers: vi.fn(() => unregister),
      createMainWindow: () => { throw new Error('window failed'); },
    })).rejects.toThrow('window failed');
    expect(unregister).toHaveBeenCalledOnce();
    expect(dispose).toHaveBeenCalledOnce();
  });

  it('reports readiness failures and quits without resolving storage', async () => {
    const fixture = electronFixture(true);
    fixture.app.whenReady.mockRejectedValue(new Error('ready failed'));
    mockElectron(fixture);
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { bootstrapApplication } = await import('../../src/main/bootstrap');
    await bootstrapApplication();
    expect(error).toHaveBeenCalledWith('ReqWS failed to start.', expect.any(Error));
    expect(fixture.app.quit).toHaveBeenCalledOnce();
    expect(fixture.app.getPath).not.toHaveBeenCalled();
  });
});
