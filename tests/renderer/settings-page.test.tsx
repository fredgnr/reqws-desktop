// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ResolvedGlobalSettings } from '../../src/shared/types';
import i18n, { initializeI18n } from '../../src/renderer/i18n';
import { SettingsPage } from '../../src/renderer/pages/settings/SettingsPage';

const settings: ResolvedGlobalSettings = {
  localePreference: 'system',
  effectiveLocale: 'zh-CN',
  workspaceParentDirectory: '/Users/rose/features',
  workspaceFileDirectory: '/Users/rose/workspaces',
};

const saveSettings = vi.fn();
const selectDirectory = vi.fn();

beforeAll(async () => {
  await initializeI18n('zh-CN');
});

beforeEach(async () => {
  await i18n.changeLanguage('zh-CN');
  saveSettings.mockReset();
  selectDirectory.mockReset();
  Object.defineProperty(window, 'reqws', {
    configurable: true,
    value: {
      settings: { save: saveSettings },
      dialogs: { selectDirectory },
    },
  });
});

afterEach(cleanup);

describe('Settings page', () => {
  it('only enables saving after a setting changes', async () => {
    const user = userEvent.setup();
    render(
      <SettingsPage
        loading={false}
        onSaved={vi.fn()}
        onToast={vi.fn()}
        settings={settings}
      />,
    );

    const save = screen.getByRole('button', { name: '保存设置' });
    expect(save).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('界面语言'), 'en-US');
    expect(save).toBeEnabled();
  });

  it('uses the directory picker without making the path field editable', async () => {
    const user = userEvent.setup();
    selectDirectory.mockResolvedValue('/Users/rose/new-features');
    render(
      <SettingsPage
        loading={false}
        onSaved={vi.fn()}
        onToast={vi.fn()}
        settings={settings}
      />,
    );

    const parentDirectory = screen.getByLabelText('工作区默认父目录');
    expect(parentDirectory).toHaveAttribute('readonly');
    await user.click(screen.getByRole('button', { name: '为“工作区默认父目录”选择目录' }));

    await waitFor(() => expect(parentDirectory).toHaveValue('/Users/rose/new-features'));
    expect(selectDirectory).toHaveBeenCalledWith({
      title: '选择工作区的存放目录',
      defaultPath: '/Users/rose/features',
      createDirectory: true,
    });
    expect(screen.getByRole('button', { name: '保存设置' })).toBeEnabled();
  });

  it('explains when a previously configured directory is no longer usable', () => {
    render(
      <SettingsPage
        loading={false}
        onSaved={vi.fn()}
        onToast={vi.fn()}
        settings={{
          ...settings,
          workspaceParentDirectory: null,
          invalidDirectoryFields: ['workspaceParentDirectory'],
        }}
      />,
    );

    expect(screen.getByText('此前设置的目录已不存在或无法访问，请重新选择。')).toBeInTheDocument();
    expect(screen.getByLabelText('工作区默认父目录')).toHaveAttribute('aria-invalid', 'true');
  });

  it('switches the interface language immediately after saving', async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    const onToast = vi.fn();
    const result: ResolvedGlobalSettings = {
      ...settings,
      localePreference: 'en-US',
      effectiveLocale: 'en-US',
    };
    saveSettings.mockResolvedValue(result);
    render(
      <SettingsPage
        loading={false}
        onSaved={onSaved}
        onToast={onToast}
        settings={settings}
      />,
    );

    await user.selectOptions(screen.getByLabelText('界面语言'), 'en-US');
    await user.click(screen.getByRole('button', { name: '保存设置' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(result));
    expect(saveSettings).toHaveBeenCalledWith({
      localePreference: 'en-US',
      workspaceParentDirectory: '/Users/rose/features',
      workspaceFileDirectory: '/Users/rose/workspaces',
    });
    expect(document.documentElement).toHaveAttribute('lang', 'en-US');
    expect(screen.getByRole('heading', { name: 'General' })).toBeInTheDocument();
    expect(onToast).toHaveBeenCalledWith('Settings saved.');
  });

  it('keeps unsaved values after a save failure', async () => {
    const user = userEvent.setup();
    const onToast = vi.fn();
    saveSettings.mockRejectedValue({
      code: 'SETTINGS_WRITE_FAILED',
      message: 'The state file could not be written.',
    });
    render(
      <SettingsPage
        loading={false}
        onSaved={vi.fn()}
        onToast={onToast}
        settings={settings}
      />,
    );

    await user.selectOptions(screen.getByLabelText('界面语言'), 'zh-CN');
    await user.click(screen.getByRole('button', { name: '保存设置' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('无法保存设置。');
    expect(screen.getByLabelText('界面语言')).toHaveValue('zh-CN');
    expect(screen.getByRole('button', { name: '保存设置' })).toBeEnabled();
    expect(onToast).toHaveBeenCalledWith('无法保存设置。', 'error');
  });
});
