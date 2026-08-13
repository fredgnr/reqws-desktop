// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Repository, SystemAvailability, WorkspaceSummary } from '../../src/shared/types';
import { ConfirmDialog } from '../../src/renderer/components/ConfirmDialog';
import { CreateWorkspaceDialog } from '../../src/renderer/components/CreateWorkspaceDialog';
import { RepositoryDialog } from '../../src/renderer/components/RepositoryDialog';
import { WorkspacesPage } from '../../src/renderer/pages/WorkspacesPage';

afterEach(cleanup);

const repositories: Repository[] = [
  { id: 'repo-order', name: 'order-api', url: 'git@example.com:shop/order-api.git', defaultBranch: 'main', createdAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z' },
  { id: 'repo-account', name: 'account-sdk', url: 'https://example.com/shared/account-sdk.git', defaultBranch: 'develop', createdAt: '2026-08-12T00:00:00Z', updatedAt: '2026-08-12T00:00:00Z' },
];

const workspace: WorkspaceSummary = {
  id: 'ws-one',
  name: 'FEAT-123-payment-refund',
  featureBranch: 'feature/FEAT-123',
  rootPath: '/Users/rose/Developer/features/FEAT-123',
  workspaceFilePath: '/Users/rose/Developer/workspaces/FEAT-123.code-workspace',
  repositoryNames: ['order-api'],
  status: 'ready',
  createdAt: '2026-08-12T00:00:00Z',
  updatedAt: '2026-08-12T00:00:00Z',
};

const unavailableEditors: SystemAvailability = {
  git: { available: true, path: '/usr/bin/git' },
  vscode: { available: false, reason: '未安装 VS Code' },
  cursor: { available: false, reason: '未安装 Cursor' },
};

describe('Workspace list', () => {
  it('searches name, branch, repository and path and renders no-result state', async () => {
    const user = userEvent.setup();
    const onSearch = vi.fn();
    const view = render(
      <WorkspacesPage
        availability={unavailableEditors}
        loading={false}
        onCreate={vi.fn()}
        onDetails={vi.fn()}
        onOpenCursor={vi.fn()}
        onOpenVSCode={vi.fn()}
        onSearch={onSearch}
        repositoryCount={2}
        search="order-api"
        workspaces={[workspace]}
      />,
    );
    expect(screen.getByText('FEAT-123-payment-refund')).toBeInTheDocument();
    view.rerender(
      <WorkspacesPage
        availability={unavailableEditors}
        loading={false}
        onCreate={vi.fn()}
        onDetails={vi.fn()}
        onOpenCursor={vi.fn()}
        onOpenVSCode={vi.fn()}
        onSearch={onSearch}
        repositoryCount={2}
        search="does-not-exist"
        workspaces={[workspace]}
      />,
    );
    expect(screen.getByText('没有匹配的 Workspace')).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox', { name: '搜索 Workspace' }), 'abc');
    expect(onSearch).toHaveBeenCalled();
  });

  it('disables unavailable editors with an explanatory title', () => {
    render(
      <WorkspacesPage
        availability={unavailableEditors}
        loading={false}
        onCreate={vi.fn()}
        onDetails={vi.fn()}
        onOpenCursor={vi.fn()}
        onOpenVSCode={vi.fn()}
        onSearch={vi.fn()}
        repositoryCount={2}
        search=""
        workspaces={[workspace]}
      />,
    );
    expect(screen.getByRole('button', { name: 'VS Code' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'VS Code' })).toHaveAttribute('title', '未找到 Visual Studio Code');
    expect(screen.getByRole('button', { name: 'Cursor' })).toBeDisabled();
  });
});

describe('Repository form', () => {
  it('derives a repository name from URL and submits a valid form', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <RepositoryDialog busy={false} gitAvailable onClose={vi.fn()} onSave={onSave} onTest={vi.fn()} />,
    );
    await user.type(screen.getByLabelText(/Git 地址/), 'git@example.com:team/invoice-api.git');
    expect(screen.getByLabelText(/^名称/)).toHaveValue('invoice-api');
    await user.click(screen.getByRole('button', { name: '保存 Repository' }));
    await waitFor(() => expect(onSave).toHaveBeenCalledWith({
      name: 'invoice-api',
      url: 'git@example.com:team/invoice-api.git',
      defaultBranch: 'main',
    }));
  });

  it('allows saving after a failed connection test', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <RepositoryDialog
        busy={false}
        gitAvailable
        onClose={vi.fn()}
        onSave={onSave}
        onTest={vi.fn()}
        repository={repositories[0]}
        testResult={{ success: false, detail: 'Permission denied (publickey)' }}
      />,
    );
    expect(screen.getByText('连接测试不影响保存。')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '保存 Repository' }));
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
  });
});

async function fillWorkspaceBasics(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.type(screen.getByLabelText(/Workspace 名称/), 'FEAT-789-export');
  await user.type(screen.getByLabelText(/^代码目录/), '/Users/rose/features/FEAT-789-export');
  await user.type(screen.getByLabelText(/Workspace 文件目录/), '/Users/rose/workspaces');
}

describe('Create Workspace form', () => {
  it('remembers directory defaults and treats the root picker as a parent picker', async () => {
    const user = userEvent.setup();
    const onPickDirectory = vi.fn().mockResolvedValue('/Users/rose/features');
    render(
      <CreateWorkspaceDialog
        busy={false}
        initialWorkspaceFileDirectory="/Users/rose/workspaces"
        initialWorkspaceParentDirectory="/Users/rose/old-features"
        onClose={vi.fn()}
        onCreate={vi.fn()}
        onPickDirectory={onPickDirectory}
        repositories={repositories}
      />,
    );

    await user.type(screen.getByLabelText(/Workspace 名称/), 'FEAT-900 Billing');
    expect(screen.getByLabelText(/^代码目录/)).toHaveValue('/Users/rose/old-features/FEAT-900-Billing');
    expect(screen.getByLabelText(/Workspace 文件目录/)).toHaveValue('/Users/rose/workspaces');
    await user.click(screen.getAllByRole('button', { name: '选择…' })[0]!);
    await waitFor(() => expect(screen.getByLabelText(/^代码目录/)).toHaveValue('/Users/rose/features/FEAT-900-Billing'));
    expect(onPickDirectory).toHaveBeenCalledWith('root', '/Users/rose/old-features');
  });

  it('preserves repository selection across filtering', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn().mockResolvedValue(undefined);
    render(
      <CreateWorkspaceDialog busy={false} onClose={vi.fn()} onCreate={onCreate} onPickDirectory={vi.fn()} repositories={repositories} />,
    );
    await fillWorkspaceBasics(user);
    await user.click(screen.getByRole('checkbox', { name: /order-api/ }));
    const search = screen.getByRole('textbox', { name: '搜索可选仓库' });
    await user.type(search, 'account');
    await user.click(screen.getByRole('checkbox', { name: /account-sdk/ }));
    await user.clear(search);
    expect(screen.getByRole('checkbox', { name: /order-api/ })).toBeChecked();
    await user.click(screen.getByRole('button', { name: '创建 Workspace' }));
    await waitFor(() => expect(onCreate).toHaveBeenCalledWith(expect.objectContaining({ repositoryIds: ['repo-order', 'repo-account'] })));
  });

  it('requires at least one repository', async () => {
    const user = userEvent.setup();
    render(
      <CreateWorkspaceDialog busy={false} onClose={vi.fn()} onCreate={vi.fn()} onPickDirectory={vi.fn()} repositories={repositories} />,
    );
    await fillWorkspaceBasics(user);
    await user.click(screen.getByRole('button', { name: '创建 Workspace' }));
    expect(screen.getByRole('alert')).toHaveTextContent('请至少选择一个 Repository');
  });

  it('prevents duplicate submissions while the first is pending', async () => {
    const user = userEvent.setup();
    let resolveCreate: (() => void) | undefined;
    const onCreate = vi.fn(() => new Promise<void>((resolve) => { resolveCreate = resolve; }));
    render(
      <CreateWorkspaceDialog busy={false} onClose={vi.fn()} onCreate={onCreate} onPickDirectory={vi.fn()} repositories={repositories} />,
    );
    await fillWorkspaceBasics(user);
    await user.click(screen.getByRole('checkbox', { name: /order-api/ }));
    const submit = screen.getByRole('button', { name: '创建 Workspace' });
    await user.dblClick(submit);
    expect(onCreate).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '创建中…' })).toBeDisabled();
    resolveCreate?.();
  });
});

describe('Destructive confirmation', () => {
  it('requires confirmation and states that local directories are preserved', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        confirmLabel="移除并保留目录"
        danger
        description="本地 repo 目录会完整保留。"
        onCancel={vi.fn()}
        onConfirm={onConfirm}
        title="移除 order-api？"
      />,
    );
    expect(screen.getByText(/本地 repo 目录会完整保留/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '移除并保留目录' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});
