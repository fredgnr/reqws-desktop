// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import i18n, { initializeI18n } from '../../src/renderer/i18n';
import type { ReqwsAPI } from '../../src/shared/types';

let App: typeof import('../../src/renderer/App').App;

beforeAll(async () => {
  await initializeI18n('zh-CN');
  await i18n.changeLanguage('zh-CN');
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
    settings: { get: vi.fn(), save: vi.fn() },
    dialogs: { selectDirectory: vi.fn() },
    editors: {
      getAvailability: vi.fn(() => new Promise(() => undefined)),
      openVSCode: vi.fn(),
      openCursor: vi.fn(),
      openCursorRoot: vi.fn(),
      openGoLand: vi.fn(),
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

afterAll(cleanup);

describe('Git availability state', () => {
  it('propagates unknown availability without claiming Git is missing', async () => {
    const user = userEvent.setup();
    render(
      <App initialSettings={{
        localePreference: 'zh-CN',
        effectiveLocale: 'zh-CN',
        workspaceParentDirectory: null,
        workspaceFileDirectory: null,
      }} />,
    );

    await user.click(screen.getByRole('button', { name: /仓库/u }));

    expect(screen.getByText('正在处理…')).toBeInTheDocument();
    expect(screen.queryByText(/未检测到 Git/u)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '添加仓库' }));

    expect(screen.getByRole('button', { name: '测试连接' })).toBeDisabled();
    expect(screen.queryByText(/未检测到 Git/u)).not.toBeInTheDocument();
  });
});
