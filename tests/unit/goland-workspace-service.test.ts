import { mkdtemp, mkdir, readFile, writeFile, rename, symlink, lstat } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { describe, expect, it, vi } from 'vitest';
import { GoLandWorkspaceService } from '../../src/main/services/goland-workspace-service';
import { WorkspaceMutationCoordinator } from '../../src/main/services/workspace-service';
import { ApplicationActivityGate } from '../../src/main/services/application-activity-gate';
import { writeJsonAtomically, writeJsonAtomicallyIfAbsent } from '../../src/main/services/atomic-json-store';
import type { WorkspaceDetail } from '../../src/shared/types';

async function fixture() {
  const rootPath = await mkdtemp(path.join(os.tmpdir(), 'reqws-goland-selection-'));
  await mkdir(path.join(rootPath, '.reqws'));
  const workspace: WorkspaceDetail = {
    schemaVersion: 1, id: 'ws_1', name: 'Fixture', featureBranch: 'feature/test', rootPath,
    workspaceFilePath: path.join(rootPath, 'fixture.code-workspace'), status: 'ready',
    repositories: ['repo_1', 'repo_2'].map((id) => ({ catalogRepositoryId: id, name: id, relativePath: id, url: `https://example.com/${id}.git`, defaultBranch: 'main' })),
    createdAt: '2026-09-19T00:00:00.000Z', updatedAt: '2026-09-19T00:00:00.000Z',
  };
  await writeFile(path.join(rootPath, '.reqws/workspace.json'), JSON.stringify(workspace));
  await writeFile(workspace.workspaceFilePath, 'unchanged VS Code/Cursor workspace');
  const get = vi.fn(async () => workspace);
  const gate = new ApplicationActivityGate();
  const mutations = new WorkspaceMutationCoordinator(gate);
  return { rootPath, workspace, get, gate, mutations, service: new GoLandWorkspaceService({ get }, mutations) };
}

describe('GoLand entry and selection', () => {
  it('creates the selected entry atomically, reuses binding and never rewrites membership or other editors', async () => {
    const { rootPath, workspace, service } = await fixture();
    const manifest = await readFile(path.join(rootPath, '.reqws/workspace.json'), 'utf8');
    expect((await service.read('ws_1')).project).toBeNull();
    const state = await service.prepare({ workspaceId: 'ws_1', selection: { mode: 'selected', repositoryIds: ['repo_2'] } });
    expect(state.project).toMatchObject({ revision: 1, selection: { mode: 'selected', repositoryIds: ['repo_2'] } });
    expect(state.shellPath).toBe(path.join(rootPath, '.reqws/ide/goland'));
    expect(await service.prepare({ workspaceId: 'ws_1' })).toEqual(state);
    await expect(lstat(path.join(state.shellPath, '.idea'))).rejects.toMatchObject({ code: 'ENOENT' });
    for (const selection of [{ mode: 'selected' as const, repositoryIds: [] }, { mode: 'all' as const }]) {
      const current = (await service.read('ws_1')).project!;
      await service.save({ workspaceId: 'ws_1', expectedBindingId: current.bindingId, expectedRevision: current.revision, selection });
    }
    expect((await service.read('ws_1')).project).toMatchObject({ revision: 3, selection: { mode: 'all' } });
    expect(await readFile(path.join(rootPath, '.reqws/workspace.json'), 'utf8')).toBe(manifest);
    expect(await readFile(workspace.workspaceFilePath, 'utf8')).toBe('unchanged VS Code/Cursor workspace');
  });

  it.each(['unbound', 'foreign', 'invalid'] as const)('preserves a %s shell', async (kind) => {
    const { rootPath, service } = await fixture();
    const shell = path.join(rootPath, '.reqws/ide/goland');
    await mkdir(shell, { recursive: true });
    const file = path.join(shell, 'reqws-project.json');
    if (kind !== 'unbound') await writeFile(file, kind === 'invalid' ? '{broken' : JSON.stringify({ schemaVersion: 1, adapterProtocol: 1, workspaceId: 'foreign', bindingId: '95dc7c6a-0eaa-4c96-824a-e117316a1db3', revision: 1, selection: { mode: 'all' }, updatedAt: '2026-09-19T00:00:00Z' }));
    await writeFile(path.join(shell, 'user.txt'), 'preserve');
    await expect(service.prepare({ workspaceId: 'ws_1' })).rejects.toMatchObject({ code: kind === 'invalid' ? 'GOLAND_BINDING_INVALID' : 'GOLAND_ENTRY_CONFLICT' });
    expect(await readFile(path.join(shell, 'user.txt'), 'utf8')).toBe('preserve');
  });

  it('rejects stale revisions and binding recreation, including concurrent saves', async () => {
    const { service } = await fixture();
    const { project } = await service.prepare({ workspaceId: 'ws_1' });
    const input = { workspaceId: 'ws_1', expectedBindingId: project!.bindingId, expectedRevision: 1, selection: { mode: 'selected' as const, repositoryIds: [] } };
    const results = await Promise.allSettled([service.save(input), service.save(input)]);
    expect(results.map((result) => result.status)).toEqual(['fulfilled', 'rejected']);
    await expect(service.save({ ...input, expectedRevision: 2, expectedBindingId: '95dc7c6a-0eaa-4c96-824a-e117316a1db3' })).rejects.toMatchObject({ code: 'GOLAND_SELECTION_CONFLICT' });
  });

  it('retains persisted stale IDs on read but only accepts current members in a new save', async () => {
    const { workspace, service } = await fixture();
    const { project } = await service.prepare({ workspaceId: 'ws_1', selection: { mode: 'selected', repositoryIds: ['repo_2'] } });
    workspace.repositories.pop();
    expect((await service.read('ws_1')).project!.selection).toEqual(project!.selection);
    await expect(service.save({ workspaceId: 'ws_1', expectedBindingId: project!.bindingId, expectedRevision: 1, selection: project!.selection })).rejects.toMatchObject({ code: 'INVALID_INPUT' });
  });

  it('preserves the last complete file after a failed atomic write', async () => {
    const { service, get, mutations } = await fixture();
    const { project } = await service.prepare({ workspaceId: 'ws_1' });
    const failing = new GoLandWorkspaceService({ get }, mutations, {
      create: writeJsonAtomicallyIfAbsent, replace: async () => { throw new Error('disk full'); },
    });
    await expect(failing.save({ workspaceId: 'ws_1', expectedBindingId: project!.bindingId, expectedRevision: 1, selection: { mode: 'selected', repositoryIds: [] } })).rejects.toMatchObject({ code: 'GOLAND_WRITE_FAILED' });
    expect((await service.read('ws_1')).project).toEqual(project);
  });

  it('detects shell replacement before publication and preserves replacement contents', async () => {
    const { service, get, mutations } = await fixture();
    const { project, shellPath } = await service.prepare({ workspaceId: 'ws_1' });
    const replaced = new GoLandWorkspaceService({ get }, mutations, {
      create: writeJsonAtomicallyIfAbsent,
      replace: async (filename, value, options) => {
        await rename(shellPath, `${shellPath}-moved`);
        await mkdir(shellPath);
        await writeFile(path.join(shellPath, 'reqws-project.json'), 'user replacement');
        await writeJsonAtomically(filename, value, options);
      },
    });
    await expect(replaced.save({ workspaceId: 'ws_1', expectedBindingId: project!.bindingId, expectedRevision: 1, selection: { mode: 'all' } })).rejects.toMatchObject({ code: 'GOLAND_BINDING_INVALID' });
    expect(await readFile(path.join(shellPath, 'reqws-project.json'), 'utf8')).toBe('user replacement');
  });

  it('rejects symlink ancestors and non-ready workspaces', async () => {
    const { rootPath, workspace, service } = await fixture();
    workspace.status = 'error';
    await expect(service.prepare({ workspaceId: 'ws_1' })).rejects.toMatchObject({ code: 'WORKSPACE_PATH_MISSING' });
    workspace.status = 'ready';
    await mkdir(path.join(rootPath, 'external'));
    await symlink(path.join(rootPath, 'external'), path.join(rootPath, '.reqws/ide'));
    await expect(service.prepare({ workspaceId: 'ws_1' })).rejects.toMatchObject({ code: 'GOLAND_BINDING_INVALID' });
  });
  it('rereads ready membership for each operation and propagates manifest errors', async () => {
    const { service, get } = await fixture();
    await service.read('ws_1');
    await service.prepare({ workspaceId: 'ws_1' });
    expect(get).toHaveBeenCalledTimes(2);
    get.mockRejectedValueOnce({ code: 'MANIFEST_READ_FAILED' });
    await expect(service.read('ws_1')).rejects.toMatchObject({ code: 'MANIFEST_READ_FAILED' });
  });

});
