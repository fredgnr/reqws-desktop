// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import { GoLandLoadingSection } from '../../src/renderer/components/GoLandLoadingSection';
import { initializeI18n } from '../../src/renderer/i18n';
import type { WorkspaceDetail } from '../../src/shared/types';
import type { GoLandProject } from '../../src/shared/goland-workspace';

const workspace: WorkspaceDetail = { schemaVersion: 1, id: 'ws_1', name: 'Fixture', featureBranch: 'feature/test', rootPath: '/fixture', workspaceFilePath: '/fixture.code-workspace', status: 'ready', repositories: ['one','two'].map((id) => ({ catalogRepositoryId: id, name: id, relativePath: id, url: `https://example.com/${id}.git`, defaultBranch: 'main' })), createdAt: '2026-09-19T00:00:00Z', updatedAt: '2026-09-19T00:00:00Z' };
const binding: GoLandProject = { schemaVersion: 1, adapterProtocol: 1, workspaceId: 'ws_1', bindingId: '95dc7c6a-0eaa-4c96-824a-e117316a1db3', revision: 1, selection: { mode: 'all' }, updatedAt: '2026-09-19T00:00:00Z' };
const read = vi.fn();
const prepare = vi.fn();
const save = vi.fn();
const open = vi.fn();
beforeAll(() => initializeI18n('en-US'));
beforeEach(() => {
  vi.resetAllMocks();
  read.mockResolvedValue({ project: null, shellPath: '/fixture/.reqws/ide/goland' });
  prepare.mockImplementation(async (input) => ({ project: { ...binding, selection: input.selection }, shellPath: '/fixture/.reqws/ide/goland' }));
  Object.defineProperty(window, 'reqws', { configurable: true, value: { goLandWorkspaces: { read, prepare, save }, editors: { openGoLand: open } } });
});
afterEach(cleanup);

it('publishes an explicit empty selection before opening and distinguishes saved configuration from sync', async () => {
  const user = userEvent.setup();
  render(<GoLandLoadingSection available busy={false} workspace={workspace} />);
  await waitFor(() => expect(screen.getByRole('radio', { name: 'Selected repositories' })).toBeEnabled());
  await user.click(screen.getByRole('radio', { name: 'Selected repositories' }));
  await user.click(screen.getByRole('checkbox', { name: 'one' }));
  await user.click(screen.getByRole('checkbox', { name: 'two' }));
  await user.click(screen.getByRole('button', { name: 'Save and open GoLand' }));
  expect(prepare).toHaveBeenCalledWith({ workspaceId: 'ws_1', selection: { mode: 'selected', repositoryIds: [] } });
  expect(open).toHaveBeenCalledWith('ws_1');
  expect(await screen.findByRole('status')).toHaveTextContent('Selection saved');
  expect(screen.getByText('Check the IDE sync status in the ReqWS plugin in GoLand.')).toBeVisible();
});

it.each(['GOLAND_SELECTION_CONFLICT', 'GOLAND_BINDING_INVALID'])('keeps unsaved choices on %s, blocks opening and offers explicit reload', async (code) => {
  read.mockResolvedValue({ project: binding, shellPath: '/fixture' });
  save.mockRejectedValue({ code, message: 'Conflict' });
  const user = userEvent.setup();
  render(<GoLandLoadingSection available busy={false} workspace={workspace} />);
  await waitFor(() => expect(screen.getByRole('radio', { name: 'Selected repositories' })).toBeEnabled());
  await user.click(screen.getByRole('radio', { name: 'Selected repositories' }));
  await user.click(screen.getByRole('checkbox', { name: 'two' }));
  await user.click(screen.getByRole('button', { name: 'Save and open GoLand' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(code);
  expect(screen.getByRole('checkbox', { name: 'one' })).toBeChecked();
  expect(screen.getByRole('checkbox', { name: 'two' })).not.toBeChecked();
  expect(open).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: 'Save selection' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Save and open GoLand' })).toBeDisabled();
  await user.click(screen.getByRole('button', { name: 'Reload saved configuration' }));
  expect(screen.getByRole('radio', { name: 'All by default' })).toBeChecked();
});

it('retries a transient save failure with the same draft and revision before opening', async () => {
  read.mockResolvedValue({ project: binding, shellPath: '/fixture' });
  save.mockRejectedValueOnce({ code: 'GOLAND_WRITE_FAILED', message: 'Disk full' });
  save.mockImplementationOnce(async (input) => ({ project: { ...binding, revision: 2, selection: input.selection }, shellPath: '/fixture' }));
  const user = userEvent.setup();
  render(<GoLandLoadingSection available busy={false} workspace={workspace} />);
  await waitFor(() => expect(screen.getByRole('radio', { name: 'Selected repositories' })).toBeEnabled());
  await user.click(screen.getByRole('radio', { name: 'Selected repositories' }));
  await user.click(screen.getByRole('checkbox', { name: 'two' }));
  await user.click(screen.getByRole('button', { name: 'Save selection' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('GOLAND_WRITE_FAILED');
  expect(screen.getByRole('checkbox', { name: 'one' })).toBeChecked();
  expect(screen.getByRole('checkbox', { name: 'two' })).not.toBeChecked();
  expect(screen.getByRole('button', { name: 'Save selection' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Save and open GoLand' })).toBeEnabled();
  expect(open).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Save and open GoLand' }));
  const expectedInput = { workspaceId: 'ws_1', expectedBindingId: binding.bindingId, expectedRevision: 1, selection: { mode: 'selected', repositoryIds: ['one'] } };
  expect(save).toHaveBeenNthCalledWith(1, expectedInput);
  expect(save).toHaveBeenNthCalledWith(2, expectedInput);
  expect(read).toHaveBeenCalledTimes(1);
  expect(open).toHaveBeenCalledOnce();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('Selection saved');
});

it('requires a reload after an initial prepare fails even with a write error', async () => {
  prepare.mockRejectedValueOnce({ code: 'GOLAND_WRITE_FAILED', message: 'Disk full' });
  const user = userEvent.setup();
  render(<GoLandLoadingSection available busy={false} workspace={workspace} />);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save selection' })).toBeEnabled());
  await user.click(screen.getByRole('button', { name: 'Save selection' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('GOLAND_WRITE_FAILED');
  expect(screen.getByRole('button', { name: 'Save selection' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Save and open GoLand' })).toBeDisabled();
});

it('cancels a draft without writing and disables unavailable workspace operations', async () => {
  const user = userEvent.setup();
  const view = render(<GoLandLoadingSection available busy={false} workspace={workspace} />);
  await waitFor(() => expect(screen.getByRole('radio', { name: 'Selected repositories' })).toBeEnabled());
  await user.click(screen.getByRole('radio', { name: 'Selected repositories' }));
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.getByRole('radio', { name: 'All by default' })).toBeChecked();
  expect(prepare).not.toHaveBeenCalled();
  view.rerender(<GoLandLoadingSection available busy={false} workspace={{ ...workspace, status: 'missing' }} />);
  expect(screen.getByRole('button', { name: 'Save selection' })).toBeDisabled();
});
