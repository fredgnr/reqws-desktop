// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ReqwsAPI } from '../../src/shared/types';
import { initializeI18n } from '../../src/renderer/i18n';

const getSettings = vi.fn();
const saveSettings = vi.fn();
const selectDirectory = vi.fn();
let App: typeof import('../../src/renderer/App').App;

beforeAll(async () => {
  const api = {
    repositories: {
      list: vi.fn().mockResolvedValue([]),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
      testConnection: vi.fn(),
    },
    workspaces: {
      list: vi.fn().mockResolvedValue([]),
      get: vi.fn(),
      create: vi.fn(),
      addRepository: vi.fn(),
      removeRepository: vi.fn(),
      sync: vi.fn(),
      forget: vi.fn(),
    },
    settings: {
      get: getSettings,
      save: saveSettings,
    },
    dialogs: { selectDirectory },
    editors: {
      getAvailability: vi.fn().mockResolvedValue({
        git: { available: true },
        vscode: { available: true },
        cursor: { available: true },
      }),
      openVSCode: vi.fn(),
      openCursor: vi.fn(),
      openCursorRoot: vi.fn(),
      revealInFinder: vi.fn(),
    },
    operations: { onProgress: vi.fn(() => () => undefined) },
  } as unknown as ReqwsAPI;
  Object.defineProperty(window, 'reqws', {
    configurable: true,
    value: api,
  });
  ({ App } = await import('../../src/renderer/App'));
});

beforeEach(async () => {
  await initializeI18n('en-US');
  getSettings.mockReset();
  saveSettings.mockReset();
  selectDirectory.mockReset();
});

afterEach(cleanup);

describe('App settings recovery', () => {
  it('applies the persisted locale when a startup settings retry succeeds', async () => {
    getSettings.mockResolvedValue({
      localePreference: 'system',
      effectiveLocale: 'zh-CN',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    });

    render(<App initialSettings={null} />);

    await waitFor(() => expect(document.documentElement).toHaveAttribute('lang', 'zh-CN'));
    expect(screen.getByRole('heading', { name: '工作区' })).toBeInTheDocument();
  });

  it('keeps the stable error code in localized error toasts', async () => {
    getSettings.mockRejectedValue({
      code: 'SETTINGS_READ_FAILED',
      message: 'Unable to read global settings.',
      detail: 'state.v1.json: EACCES',
    });

    render(<App initialSettings={null} />);

    expect(await screen.findByRole('status')).toHaveTextContent(
      'SETTINGS_READ_FAILED · Settings could not be loaded.',
    );
  });

  it('passes stale default warnings to Create Workspace without saving replacements globally', async () => {
    const user = userEvent.setup();
    selectDirectory.mockResolvedValue('/Users/rose/new-features');

    render(<App initialSettings={{
      localePreference: 'system',
      effectiveLocale: 'en-US',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
      invalidDirectoryFields: ['workspaceParentDirectory'],
    }} />);

    await waitFor(() => expect(
      screen.getAllByRole('button', { name: 'Create workspace' })[0],
    ).toBeEnabled());
    await user.click(screen.getAllByRole('button', { name: 'Create workspace' })[0]!);

    const rootPath = await screen.findByLabelText('Workspace code folder');
    const warning = screen.getByRole('status');
    expect(rootPath).toHaveAttribute('aria-invalid', 'true');
    expect(rootPath.getAttribute('aria-describedby')).toContain(warning.id);

    await user.click(screen.getAllByRole('button', { name: 'Choose…' })[0]!);

    await waitFor(() => expect(rootPath).not.toHaveAttribute('aria-invalid'));
    expect(selectDirectory).toHaveBeenCalledWith(expect.objectContaining({
      createDirectory: true,
    }));
    expect(saveSettings).not.toHaveBeenCalled();
  });

  it('waits for recovered settings before opening Create Workspace', async () => {
    const user = userEvent.setup();
    let resolveSettings!: (settings: Awaited<ReturnType<ReqwsAPI['settings']['get']>>) => void;
    getSettings.mockReturnValue(new Promise((resolve) => {
      resolveSettings = resolve;
    }));

    render(<App initialSettings={null} />);

    const create = await screen.findByRole('button', { name: 'Create workspace' });
    expect(create).toBeDisabled();

    await act(async () => resolveSettings({
      localePreference: 'system',
      effectiveLocale: 'en-US',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
      invalidDirectoryFields: ['workspaceFileDirectory'],
    }));
    await waitFor(() => expect(create).toBeEnabled());
    await user.click(create);

    const workspaceFileDirectory = await screen.findByLabelText(
      '.code-workspace file folder',
    );
    expect(workspaceFileDirectory).toHaveAttribute('aria-invalid', 'true');
  });

  it('uses one copyable inline error surface for Settings save failures', async () => {
    const user = userEvent.setup();
    saveSettings.mockRejectedValue({
      code: 'SETTINGS_WRITE_FAILED',
      message: 'The state file could not be written.',
      detail: 'state.v1.json: EACCES',
      stage: 'writing',
    });

    render(<App initialSettings={{
      localePreference: 'en-US',
      effectiveLocale: 'en-US',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    }} />);

    await user.click(screen.getByRole('button', { name: 'Settings' }));
    await user.selectOptions(screen.getByLabelText('Interface language'), 'zh-CN');
    await user.click(screen.getByRole('button', { name: 'Save settings' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'SETTINGS_WRITE_FAILED · Settings could not be saved.',
    );
    expect(screen.getAllByText('SETTINGS_WRITE_FAILED')).toHaveLength(1);
  });
});
