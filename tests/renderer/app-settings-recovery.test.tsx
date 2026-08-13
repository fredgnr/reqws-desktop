// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ReqwsAPI } from '../../src/shared/types';
import { initializeI18n } from '../../src/renderer/i18n';

const getSettings = vi.fn();
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
      save: vi.fn(),
    },
    dialogs: { selectDirectory: vi.fn() },
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
});
