// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import i18n, { initializeI18n } from '../../src/renderer/i18n';
import type { ReqwsAPI, WorkspaceSummary } from '../../src/shared/types';

const workspace: WorkspaceSummary = {
  id: 'ws-goland',
  name: 'GoLand workspace',
  featureBranch: 'feature/goland',
  rootPath: '/Users/rose/Developer/features/goland',
  workspaceFilePath: '/Users/rose/Developer/workspaces/goland.code-workspace',
  repositoryNames: ['service-a'],
  status: 'ready',
  createdAt: '2026-08-14T00:00:00.000Z',
  updatedAt: '2026-08-14T00:00:00.000Z',
};

const openGoLand = vi.fn<ReqwsAPI['editors']['openGoLand']>();
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
      list: vi.fn().mockResolvedValue([workspace]),
      get: vi.fn(),
      create: vi.fn(),
      addRepository: vi.fn(),
      removeRepository: vi.fn(),
      sync: vi.fn(),
      forget: vi.fn(),
    },
    settings: {
      get: vi.fn(),
      save: vi.fn(),
    },
    dialogs: { selectDirectory: vi.fn() },
    editors: {
      getAvailability: vi.fn().mockResolvedValue({
        git: { available: true },
        vscode: { available: true },
        cursor: { available: true },
        goland: { available: true },
      }),
      openVSCode: vi.fn(),
      openCursor: vi.fn(),
      openCursorRoot: vi.fn(),
      openGoLand,
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
  await i18n.changeLanguage('en-US');
  openGoLand.mockReset();
});

afterEach(cleanup);

function renderApp(): void {
  render(
    <App
      initialSettings={{
        localePreference: 'en-US',
        effectiveLocale: 'en-US',
        workspaceParentDirectory: null,
        workspaceFileDirectory: null,
      }}
    />,
  );
}

describe('GoLand launch UI', () => {
  it('prevents duplicate launches while the first request is pending', async () => {
    let resolveLaunch: (() => void) | undefined;
    openGoLand.mockImplementation(() => new Promise<void>((resolve) => {
      resolveLaunch = resolve;
    }));
    const user = userEvent.setup();
    renderApp();

    const button = await screen.findByRole('button', { name: 'GoLand' });
    await user.dblClick(button);

    expect(openGoLand).toHaveBeenCalledTimes(1);
    expect(openGoLand).toHaveBeenCalledWith(workspace.id);
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');

    act(() => resolveLaunch?.());
    expect(await screen.findByText('Workspace opened in GoLand.'))
      .toBeInTheDocument();
    await waitFor(() => expect(button).toBeEnabled());
  });

  it('shows a localized stable error and releases the pending state', async () => {
    openGoLand.mockRejectedValue({
      code: 'EDITOR_NOT_FOUND',
      message: 'GoLand is not installed.',
    });
    const user = userEvent.setup();
    renderApp();

    const button = await screen.findByRole('button', { name: 'GoLand' });
    await user.click(button);

    expect(await screen.findByText(/EDITOR_NOT_FOUND/u)).toBeInTheDocument();
    expect(button).toBeEnabled();
  });
});
