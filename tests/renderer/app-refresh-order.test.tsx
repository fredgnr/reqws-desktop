// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import i18n, { initializeI18n } from '../../src/renderer/i18n';
import type { RepositoryListItem, ReqwsAPI, SystemAvailability } from '../../src/shared/types';

let App: typeof import('../../src/renderer/App').App;
const repository: RepositoryListItem = {
  id: 'repo', name: 'order-api', url: 'https://example.test/order.git', defaultBranch: 'main',
  createdAt: '2026-09-26T00:00:00Z', updatedAt: '2026-09-26T00:00:00Z', workspaceUsageCount: 0, referencedBy: [],
};
const availability: SystemAvailability = {
  git: { available: true, path: '/usr/bin/git' }, vscode: { available: false },
  cursor: { available: false }, goland: { available: false },
};
const api = {
  repositories: { list: vi.fn(), remove: vi.fn() },
  workspaces: { list: vi.fn() },
  editors: { getAvailability: vi.fn() },
  operations: { onProgress: vi.fn(() => vi.fn()) },
};
const initialSettings = {
  localePreference: 'zh-CN' as const, effectiveLocale: 'zh-CN' as const,
  workspaceParentDirectory: null, workspaceFileDirectory: null,
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

beforeAll(async () => {
  await initializeI18n('zh-CN');
  Object.defineProperty(window, 'reqws', { configurable: true, value: api as unknown as ReqwsAPI });
  ({ App } = await import('../../src/renderer/App'));
});
beforeEach(async () => {
  vi.clearAllMocks();
  api.repositories.list.mockReset().mockResolvedValue([repository]);
  api.repositories.remove.mockResolvedValue({ removed: true, referencedBy: [] });
  api.workspaces.list.mockReset().mockResolvedValue([]);
  api.editors.getAvailability.mockReset().mockResolvedValue(availability);
  await i18n.changeLanguage('zh-CN');
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function removeDuringRefresh() {
  const user = userEvent.setup();
  render(<App initialSettings={initialSettings} />);
  await user.click(screen.getByRole('button', { name: /仓库/u }));
  await screen.findByText('order-api');
  const old = deferred<SystemAvailability>();
  const latest = deferred<SystemAvailability>();
  api.editors.getAvailability.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
  api.repositories.list.mockResolvedValueOnce([repository]).mockResolvedValueOnce([]);
  await user.click(screen.getByRole('button', { name: '刷新' }));
  await user.click(screen.getByRole('button', { name: '编辑 order-api' }));
  await user.click(screen.getByRole('button', { name: '删除仓库记录' }));
  const confirmation = screen.getByRole('dialog', { name: '删除仓库记录“order-api”？' });
  await user.click(within(confirmation).getByRole('button', { name: '删除仓库记录' }));
  await waitFor(() => expect(api.repositories.list).toHaveBeenCalledTimes(3));
  return { old, latest };
}

describe('App refresh ordering', () => {
  it.each(['success', 'failure'])('ignores an obsolete refresh %s after repository removal', async (outcome) => {
    const { old, latest } = await removeDuringRefresh();
    await act(async () => latest.resolve(availability));
    expect(screen.queryByText('order-api')).not.toBeInTheDocument();
    await act(async () => {
      if (outcome === 'success') old.resolve({ ...availability, git: { available: false } });
      else old.reject({ code: 'GIT_NOT_FOUND', message: 'old error' });
    });
    expect(screen.queryByText('order-api')).not.toBeInTheDocument();
    expect(screen.queryByText(/未检测到 Git/u)).not.toBeInTheDocument();
    expect(screen.queryByText('GIT_NOT_FOUND')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '刷新' })).toBeEnabled();
  });

  it('keeps the refresh indicator until the newer load finishes', async () => {
    const { old, latest } = await removeDuringRefresh();
    await act(async () => old.resolve(availability));
    expect(screen.getByRole('button', { name: '正在刷新…' })).toBeDisabled();
    await act(async () => latest.resolve(availability));
    expect(screen.getByRole('button', { name: '刷新' })).toBeEnabled();
    expect(screen.queryByText('order-api')).not.toBeInTheDocument();
  });

  it('invalidates pending loads and does not schedule an error toast after unmount', async () => {
    const pending = deferred<SystemAvailability>();
    api.editors.getAvailability.mockReturnValueOnce(pending.promise);
    const view = render(<App initialSettings={initialSettings} />);
    await waitFor(() => expect(api.repositories.list).toHaveBeenCalledTimes(1));
    view.unmount();
    const timer = vi.spyOn(window, 'setTimeout');
    await act(async () => pending.reject(new Error('late failure')));
    expect(timer).not.toHaveBeenCalled();
  });

  it('does not start a queued initial load after unmount', async () => {
    const view = render(<App initialSettings={initialSettings} />);
    view.unmount();
    await act(async () => undefined);
    expect(api.repositories.list).not.toHaveBeenCalled();
  });
});
