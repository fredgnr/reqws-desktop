// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initializeI18n } from '../../src/renderer/i18n';
import { UpdateSection } from '../../src/renderer/pages/settings/UpdateSection';
import type { UpdateState } from '../../src/shared/update-types';

let listener: (state: UpdateState) => void;
const unsubscribe = vi.fn();
const idle: UpdateState = { revision: 0, phase: 'idle', currentVersion: '0.1.2' };
const updates = {
  getState: vi.fn(), check: vi.fn(), download: vi.fn(), install: vi.fn(),
  onStateChanged: vi.fn((callback) => { listener = callback; return unsubscribe; }),
};
beforeEach(async () => {
  await initializeI18n('zh-CN');
  vi.clearAllMocks();
  updates.getState.mockResolvedValue({ ...idle });
  updates.check.mockResolvedValue({ ...idle, revision: 1, phase: 'available', nextVersion: '0.2.0' });
  updates.download.mockResolvedValue({ ...idle, revision: 2, phase: 'downloaded', nextVersion: '0.2.0', percent: 100 });
  updates.install.mockResolvedValue(undefined);
  Object.defineProperty(window, 'reqws', { configurable: true, value: { updates } });
});
afterEach(cleanup);

describe('manual update controls', () => {
  it('requires separate check, download and confirmed install actions', async () => {
    const user = userEvent.setup();
    render(<UpdateSection />);
    await screen.findByText('当前版本 0.1.2');
    expect(updates.check).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '检查更新' }));
    await user.click(await screen.findByRole('button', { name: '下载更新' }));
    expect(updates.install).not.toHaveBeenCalled();
    await screen.findByText('已下载，安装时验证代码签名。');
    await user.click(screen.getByRole('button', { name: '安装并重启' }));
    const dialog = screen.getByRole('dialog');
    expect(updates.install).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: '安装并重启' }));
    expect(updates.install).toHaveBeenCalledOnce();
  });

  it('subscribes before loading a snapshot and ignores an older response', async () => {
    let resolve!: (state: UpdateState) => void;
    updates.getState.mockImplementationOnce(() => new Promise<UpdateState>((yes) => { resolve = yes; }));
    const view = render(<UpdateSection />);
    expect(updates.onStateChanged.mock.invocationCallOrder[0]).toBeLessThan(updates.getState.mock.invocationCallOrder[0]!);
    await act(async () => { listener({ ...idle, revision: 3, phase: 'available', nextVersion: '0.2.0' }); resolve(idle); });
    expect(screen.getByText('目标版本 0.2.0')).toBeInTheDocument();
    view.unmount();
    expect(unsubscribe).toHaveBeenCalledOnce();
  });

  it('renders remote release notes as text and shows bounded download progress', async () => {
    updates.getState.mockResolvedValueOnce({ ...idle, phase: 'downloading', nextVersion: '0.2.0', percent: 42, releaseNotes: '<img src=x onerror=alert(1)>' });
    render(<UpdateSection />);
    const progress = await screen.findByRole('progressbar', { name: '下载进度' });
    expect(progress).toHaveAttribute('value', '42');
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(document.querySelector('pre img')).toBeNull();
    expect(screen.getByRole('button', { name: '检查更新' })).toBeDisabled();
  });

  it('explains disabled local builds and blocks retries after native signature failure', async () => {
    updates.getState.mockResolvedValueOnce({ ...idle, phase: 'disabled', reason: 'local-build' });
    render(<UpdateSection />);
    await screen.findByText('此本地构建未配置更新源。');
    expect(screen.getByRole('button', { name: '检查更新' })).toBeDisabled();
    await act(async () => { listener({ ...idle, revision: 1, phase: 'error', errorCode: 'UPDATE_SIGNATURE_INVALID', reason: 'restart-required' }); });
    expect(screen.getByText('更新包未通过代码签名验证，旧版应用已保留。')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '检查更新' })).toBeDisabled();
    await waitFor(() => expect(screen.queryByRole('button', { name: '安装并重启' })).not.toBeInTheDocument());
  });
});
