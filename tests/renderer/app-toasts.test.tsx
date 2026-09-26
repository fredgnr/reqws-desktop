// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import i18n, { initializeI18n } from '../../src/renderer/i18n';
import type { ReqwsAPI } from '../../src/shared/types';

vi.mock('../../src/renderer/pages/RepositoriesPage', () => ({
  RepositoriesPage: ({ onTest }: { onTest: (repository: { id: string; name: string; url: string }) => void }) => (
    <button onClick={() => onTest({ id: 'repo', name: 'orders', url: 'https://example.test/orders.git' })}>test-repository</button>
  ),
}));
vi.mock('../../src/renderer/pages/settings/SettingsPage', () => ({
  SettingsPage: ({ onToast }: { onToast: (message: string) => void }) => (
    <button onClick={() => onToast('设置已保存。')}>raw-toast</button>
  ),
}));
let App: typeof import('../../src/renderer/App').App;
const list = vi.fn().mockResolvedValue([]);
const initialSettings = {
  localePreference: 'zh-CN' as const, effectiveLocale: 'zh-CN' as const,
  workspaceParentDirectory: null, workspaceFileDirectory: null,
};
beforeAll(async () => {
  await initializeI18n('zh-CN');
  Object.defineProperty(window, 'reqws', { configurable: true, value: {
    repositories: { list, testConnection: vi.fn().mockResolvedValue({ success: true }) },
    workspaces: { list: vi.fn().mockResolvedValue([]) },
    editors: { getAvailability: vi.fn().mockResolvedValue({ git: { available: true, path: '/usr/bin/git' }, vscode: { available: false }, cursor: { available: false }, goland: { available: false } }) },
    operations: { onProgress: vi.fn(() => vi.fn()) },
  } as unknown as ReqwsAPI });
  ({ App } = await import('../../src/renderer/App'));
});
beforeEach(async () => { list.mockClear(); await i18n.changeLanguage('zh-CN'); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

async function showKeyedToast() {
  const view = render(<App initialSettings={initialSettings} />);
  await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  fireEvent.click(screen.getByRole('button', { name: /仓库/u }));
  await act(async () => fireEvent.click(screen.getByText('test-repository')));
  return view;
}

describe('App toast scheduling', () => {
  it('clears scheduled expiration on dismissal and unmount', async () => {
    const view = await showKeyedToast();
    expect(vi.getTimerCount()).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: '关闭通知' }));
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => fireEvent.click(screen.getByText('test-repository')));
    expect(vi.getTimerCount()).toBe(1);
    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('preserves delayed translation, raw text, and independent 3200ms expiration', async () => {
    await showKeyedToast();
    expect(screen.getByText(i18n.t('app.toasts.repositoryTestSucceeded', { name: 'orders' }))).toBeInTheDocument();
    await act(async () => vi.advanceTimersByTimeAsync(1_000));
    fireEvent.click(screen.getByRole('button', { name: /设置/u }));
    fireEvent.click(screen.getByText('raw-toast'));
    await act(async () => { await i18n.changeLanguage('en-US'); });
    const translated = i18n.t('app.toasts.repositoryTestSucceeded', { name: 'orders' });
    expect(screen.getByText(translated)).toBeInTheDocument();
    expect(screen.getByText('设置已保存。')).toBeInTheDocument();
    await act(async () => vi.advanceTimersByTimeAsync(2_199));
    expect(screen.getByText(translated)).toBeInTheDocument();
    await act(async () => vi.advanceTimersByTimeAsync(1));
    expect(screen.queryByText(translated)).not.toBeInTheDocument();
    expect(screen.getByText('设置已保存。')).toBeInTheDocument();
    await act(async () => vi.advanceTimersByTimeAsync(1_000));
    expect(screen.queryByText('设置已保存。')).not.toBeInTheDocument();
  });
});
