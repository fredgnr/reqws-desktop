import { test, expect, type Locator } from '@playwright/test';
import { lstat, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { GoLandProject } from '../../../src/shared/goland-workspace';
import { Desktop } from '../fixtures/desktop';
import { createIsolation } from '../fixtures/isolation';
import { createGitOrigin } from '../fixtures/git-origin';
import { addRepository, createWorkspace, navigate } from '../fixtures/ui';
import { openDesktopLink, publishLinkJson, waitLinkRequest, type LinkRequest } from '../fixtures/desktop-link';

type Workspace = { root: string; manifest: string; codeWorkspace: string; workspaceFilePath: string; workspaceId: string; bindingId?: string; revision: number };

// Playwright requires destructuring even though this test allocates its own shared fixture.
// eslint-disable-next-line no-empty-pattern
test('S4 Desktop UI drives the local IDE through an isolated session', async ({}, info) => {
  const link = await openDesktopLink();
  const isolation = await createIsolation(link.root);
  const origin = await createGitOrigin(isolation);
  const desktop = new Desktop(isolation, origin, info);
  const workspaces = new Map<string, Workspace>();
  const openSelection = async (name: string): Promise<Locator> => {
    const dialog = desktop.page.getByRole('dialog');
    if (await dialog.count()) await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await navigate(desktop, 'Workspaces');
    await desktop.page.getByRole('button', { name: `View details for ${name}`, exact: true }).click();
    return desktop.page.getByRole('region', { name: 'GoLand repository loading', exact: true });
  };
  const save = async (name: string, selected: string[]) => {
    const workspace = workspaces.get(name)!;
    const section = await openSelection(name);
    await section.getByRole('radio', { name: 'Selected repositories', exact: true }).check();
    for (const repo of ['repo-a', 'repo-b']) await section.getByRole('checkbox', { name: repo, exact: true }).setChecked(selected.includes(repo));
    await section.getByRole('button', { name: 'Save selection', exact: true }).click();
    await expect(section.getByRole('status')).toHaveText('Selection saved');
    const shell = path.join(workspace.root, '.reqws', 'ide', 'goland');
    const project = JSON.parse(await readFile(path.join(shell, 'reqws-project.json'), 'utf8')) as GoLandProject;
    expect(project.workspaceId).toBe(workspace.workspaceId);
    expect(project.revision).toBe(workspace.revision + 1);
    if (workspace.bindingId) expect(project.bindingId).toBe(workspace.bindingId);
    workspace.bindingId = project.bindingId;
    workspace.revision = project.revision;
    const repositories = (await desktop.state()).repositories.map(({ id, name }) => ({ id, name }));
    expect(project.selection).toEqual({ mode: 'selected', repositoryIds: repositories.filter((repo) => selected.includes(repo.name)).map((repo) => repo.id) });
    expect(await readFile(path.join(workspace.root, '.reqws/workspace.json'), 'utf8')).toBe(workspace.manifest);
    expect(await readFile(workspace.workspaceFilePath, 'utf8')).toBe(workspace.codeWorkspace);
    for (const repo of ['repo-a', 'repo-b']) {
      expect((await lstat(path.join(workspace.root, repo, '.git'))).isDirectory()).toBe(true);
      expect(await readFile(path.join(workspace.root, repo, 'README.txt'), 'utf8')).toBe(`ReqWS Git fixture: ${repo}\n`);
      expect(await readFile(path.join(workspace.root, repo, 'docs/probe.txt'), 'utf8')).toBe('ordinary text fixture\n');
    }
    expect(await readFile(path.join(workspace.root, 'user-content/keep.txt'), 'utf8')).toBe('user owned\n');
    expect(desktop.errors).toEqual([]);
    await desktop.page.screenshot({ path: info.outputPath(`${name}-revision-${project.revision}.png`) });
    return { name, root: workspace.root, shell, workspaceId: project.workspaceId, bindingId: project.bindingId,
      revision: project.revision, selected, repositories };
  };
  const execute = async (request: Exclude<LinkRequest, { operation: 'finish' }>) => {
    if (request.operation === 'create') {
      if (workspaces.has(request.name)) throw new Error('A linked workspace cannot be reused by another scenario.');
      const dialog = desktop.page.getByRole('dialog');
      if (await dialog.count()) await dialog.getByRole('button', { name: 'Close', exact: true }).click();
      const root = await createWorkspace(desktop, request.name, ['repo-a', 'repo-b']);
      const workspace = (await desktop.state()).workspaces.find((item) => item.name === request.name)!;
      // Ordinary user files, never business manifests or a test projection.
      for (const repo of ['repo-a', 'repo-b']) {
        await mkdir(path.join(root, repo, 'docs'));
        await writeFile(path.join(root, repo, 'docs/probe.txt'), 'ordinary text fixture\n', { flag: 'wx' });
      }
      await mkdir(path.join(root, 'user-content'));
      await writeFile(path.join(root, 'user-content/keep.txt'), 'user owned\n', { flag: 'wx' });
      workspaces.set(request.name, { root, workspaceId: workspace.id, workspaceFilePath: workspace.workspaceFilePath,
        manifest: await readFile(path.join(root, '.reqws/workspace.json'), 'utf8'),
        codeWorkspace: await readFile(workspace.workspaceFilePath, 'utf8'), revision: 0 });
      return save(request.name, ['repo-a', 'repo-b']);
    }
    if (!workspaces.has(request.name)) throw new Error('Selection requires a workspace created by this session.');
    return save(request.name, request.selected);
  };
  try {
    await desktop.start();
    await addRepository(desktop, 'repo-a');
    await addRepository(desktop, 'repo-b');
    await publishLinkJson(path.join(link.directory, 'ready.json'), { schemaVersion: 1, sessionId: link.sessionId });
    for (let sequence = 1; ; sequence += 1) {
      const request = await waitLinkRequest(link.directory, link.sessionId, sequence, 20 * 60_000);
      const response = { schemaVersion: 1, sessionId: link.sessionId, sequence };
      try {
        if (request.operation === 'finish') {
          expect(workspaces.size).toBe(4);
          expect(desktop.errors).toEqual([]);
          await publishLinkJson(path.join(link.directory, `response-${sequence}.json`), { ...response, status: 'passed' });
          break;
        }
        const snapshot = await execute(request);
        await publishLinkJson(path.join(link.directory, `response-${sequence}.json`), { ...response, status: 'passed', snapshot });
      } catch (error) {
        await publishLinkJson(path.join(link.directory, `response-${sequence}.json`), { ...response, status: 'failed', error: String(error) });
        throw error;
      }
    }
  } finally {
    // The Driver might still be open after a failed step. Keep its project and
    // diagnostics; the coordinator confirms both process families separately.
    await desktop.finish({ preserveFixture: true });
  }
});
