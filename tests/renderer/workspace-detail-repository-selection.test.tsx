// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { WorkspaceDetailDrawer } from '../../src/renderer/components/WorkspaceDetailDrawer';
import i18n, { initializeI18n } from '../../src/renderer/i18n';
import type { Repository, WorkspaceDetail } from '../../src/shared/types';

function repository(id: string): Repository {
  return {
    id,
    name: id,
    url: `https://example.com/team/${id}.git`,
    defaultBranch: 'main',
    createdAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:00:00Z',
  };
}

const repoA = repository('repo-a');
const repoB = repository('repo-b');
const repoC = repository('repo-c');
const catalog = [repoA, repoB, repoC];

function workspaceWith(...repositories: Repository[]): WorkspaceDetail {
  return {
    schemaVersion: 1,
    id: 'ws-selection',
    name: 'Repository selection',
    featureBranch: 'feature/selection',
    rootPath: '/tmp/reqws-test/selection',
    workspaceFilePath: '/tmp/reqws-test/selection.code-workspace',
    repositories: repositories.map((item) => ({
      catalogRepositoryId: item.id,
      name: item.name,
      url: item.url,
      defaultBranch: item.defaultBranch,
      relativePath: item.name,
    })),
    status: 'ready',
    createdAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:00:00Z',
  };
}

function drawerProps() {
  return {
    availability: {
      git: { available: true, path: '/usr/bin/git' },
      vscode: { available: true, path: '/Applications/Visual Studio Code.app' },
      cursor: { available: true, path: '/Applications/Cursor.app' },
      goland: { available: true, path: '/Applications/GoLand.app' },
    },
    busy: false,
    editorLaunching: false,
    onAddRepository: vi.fn(),
    onClose: vi.fn(),
    onForget: vi.fn(),
    onOpenCursor: vi.fn(),
    onOpenCursorRoot: vi.fn(),
    onOpenVSCode: vi.fn(),
    onRemoveRepository: vi.fn(),
    onRevealFinder: vi.fn(),
    onSync: vi.fn(),
    repositories: catalog,
    workspace: workspaceWith(repoA, repoB),
  } satisfies ComponentProps<typeof WorkspaceDetailDrawer>;
}

beforeAll(() => initializeI18n('en-US'));
beforeEach(() => i18n.changeLanguage('en-US'));
beforeEach(() => {
  Object.defineProperty(window, 'reqws', { configurable: true, value: {
    goLandWorkspaces: { read: vi.fn().mockResolvedValue({ project: null, shellPath: '/unused' }) },
  } });
});
afterEach(cleanup);

describe('Workspace repository selection', () => {
  it('keeps Cursor workspace and folder actions distinct and closes its menu with Escape', async () => {
    const user = userEvent.setup();
    const props = drawerProps();
    render(<WorkspaceDetailDrawer {...props} />);
    const cursor = screen.getByRole('button', { name: 'Cursor' });
    await user.click(cursor);
    await user.click(screen.getByRole('button', { name: 'Open workspace file' }));
    expect(props.onOpenCursor).toHaveBeenCalledOnce();
    expect(props.onOpenCursorRoot).not.toHaveBeenCalled();
    await user.click(cursor);
    await user.click(screen.getByRole('button', { name: 'Open code folder in Cursor' }));
    expect(props.onOpenCursorRoot).toHaveBeenCalledOnce();
    await user.click(cursor);
    await user.keyboard('{Escape}');
    expect(cursor.closest('details')).not.toHaveAttribute('open');
    expect(cursor).toHaveFocus();
    expect(props.onClose).not.toHaveBeenCalled();
  });

  it('keeps workspace paths and membership actions behind separate disclosures', async () => {
    const user = userEvent.setup();
    const props = drawerProps();
    render(<WorkspaceDetailDrawer {...props} />);
    expect(screen.getByRole('combobox')).not.toBeVisible();
    await user.click(screen.getByText('Workspace files'));
    expect(screen.getByText(props.workspace.workspaceFilePath)).toBeVisible();
    expect(screen.getByText(i18n.t('workspaceDetail.managedFileNotice'))).toBeVisible();
    await user.click(screen.getByText('Manage workspace'));
    expect(screen.getByRole('combobox')).toBeVisible();
    const removeButton = screen.getAllByRole('button', { name: 'Remove' })[0];
    if (!removeButton) throw new Error('Expected a visible repository removal action');
    await user.click(removeButton);
    expect(props.onRemoveRepository).toHaveBeenCalledExactlyOnceWith(props.workspace.repositories[0]);
  });

  it('disables adding after the last available repository is added and submits the newly removed repository', async () => {
    const user = userEvent.setup();
    const props = drawerProps();
    const view = render(<WorkspaceDetailDrawer {...props} />);
    await user.click(screen.getByText('Manage workspace'));
    const select = screen.getByRole('combobox');
    const add = screen.getByRole('button', { name: /Add/u });

    expect(select).toHaveValue(repoC.id);
    await user.selectOptions(select, repoC.id);
    await user.click(add);
    expect(props.onAddRepository).toHaveBeenLastCalledWith(repoC.id);

    view.rerender(
      <WorkspaceDetailDrawer {...props} workspace={workspaceWith(repoA, repoB, repoC)} />,
    );
    expect(select).toHaveValue('');
    expect(select).toBeDisabled();
    expect(add).toBeDisabled();
    await user.click(add);
    expect(props.onAddRepository).toHaveBeenCalledTimes(1);

    view.rerender(
      <WorkspaceDetailDrawer {...props} workspace={workspaceWith(repoA, repoC)} />,
    );
    expect(select).toHaveValue(repoB.id);
    expect(select).toBeEnabled();
    expect(add).toBeEnabled();
    await user.click(add);
    expect(props.onAddRepository).toHaveBeenCalledTimes(2);
    expect(props.onAddRepository).toHaveBeenLastCalledWith(repoB.id);
  });

  it('preserves an available user selection when unrelated workspace and catalog details change', async () => {
    const user = userEvent.setup();
    const props = { ...drawerProps(), workspace: workspaceWith(repoA) };
    const view = render(<WorkspaceDetailDrawer {...props} />);
    await user.click(screen.getByText('Manage workspace'));
    const select = screen.getByRole('combobox');

    expect(select).toHaveValue(repoB.id);
    await user.selectOptions(select, repoC.id);
    view.rerender(
      <WorkspaceDetailDrawer
        {...props}
        repositories={catalog.map((item) => ({ ...item, defaultBranch: 'develop' }))}
        workspace={{ ...props.workspace, updatedAt: '2026-09-05T01:00:00Z' }}
      />,
    );

    expect(select).toHaveValue(repoC.id);
    await user.click(screen.getByRole('button', { name: /Add/u }));
    expect(props.onAddRepository).toHaveBeenCalledExactlyOnceWith(repoC.id);
  });

  it('falls back to an available repository when a selected catalog entry disappears and disables an empty catalog', async () => {
    const user = userEvent.setup();
    const props = { ...drawerProps(), workspace: workspaceWith(repoA) };
    const view = render(<WorkspaceDetailDrawer {...props} />);
    await user.click(screen.getByText('Manage workspace'));
    const select = screen.getByRole('combobox');
    const add = screen.getByRole('button', { name: /Add/u });
    await user.selectOptions(select, repoC.id);

    view.rerender(<WorkspaceDetailDrawer {...props} repositories={[repoA, repoB]} />);
    expect(select).toHaveValue(repoB.id);
    expect(add).toBeEnabled();
    await user.click(add);
    expect(props.onAddRepository).toHaveBeenCalledExactlyOnceWith(repoB.id);

    view.rerender(<WorkspaceDetailDrawer {...props} repositories={[]} />);
    expect(select).toHaveValue('');
    expect(select).toBeDisabled();
    expect(add).toBeDisabled();
    await user.click(add);
    expect(props.onAddRepository).toHaveBeenCalledTimes(1);
  });
});
