import { test, expect, type Locator } from '@playwright/test';
import { lstat, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { GoLandProject } from '../../../src/shared/goland-workspace';
import { Desktop } from '../fixtures/desktop';
import { createIsolation } from '../fixtures/isolation';
import { createGitOrigin } from '../fixtures/git-origin';
import { addRepository, createWorkspace, navigate } from '../fixtures/ui';
import { editorLaunchSchema, openDesktopLink, publishLinkJson, waitLinkRequest, type EditorLaunch, type LinkRequest } from '../fixtures/desktop-link';

const members = ['repo-a', 'repo-b', 'repo-c'];
type Workspace = { root: string; manifest: string; codeWorkspace: string; workspaceFilePath: string; workspaceId: string; bindingId?: string; revision: number; gitHeads: string[]; editorLaunch?: EditorLaunch };

// Playwright requires destructuring even though this test allocates its own shared fixture.
// eslint-disable-next-line no-empty-pattern
test('S4 Desktop UI drives the local IDE through an isolated session', async ({}, info) => {
  const link = await openDesktopLink();
  const isolation = await createIsolation(link.root);
  const origin = await createGitOrigin(isolation);
  const desktop = new Desktop(isolation, origin, info);
  const applicationPath = path.join(isolation.home, 'Applications', 'GoLand.app');
  const workspaces = new Map<string, Workspace>();
  const openSelection = async (name: string): Promise<Locator> => {
    const dialog = desktop.page.getByRole('dialog');
    if (await dialog.count()) await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await navigate(desktop, 'Workspaces');
    await desktop.page.getByRole('button', { name: `View details for ${name}`, exact: true }).click();
    return desktop.page.getByRole('region', { name: 'GoLand repository loading', exact: true });
  };
  const save = async (name: string, selected: string[], open = false) => {
    const workspace = workspaces.get(name)!;
    const section = await openSelection(name);
    await section.getByRole('radio', { name: 'Selected repositories', exact: true }).check();
    for (const repo of members) await section.getByRole('checkbox', { name: repo, exact: true }).setChecked(selected.includes(repo));
    const launchesBefore = await desktop.app.evaluate(() => globalThis.__reqwsE2E.launches.length);
    await section.getByRole('button', { name: open ? 'Save and open GoLand' : 'Save selection', exact: true }).click();
    await expect(section.getByRole('status')).toHaveText('Selection saved');
    await expect(section.getByRole('alert')).toHaveCount(0);
    const launches = await desktop.app.evaluate(() => globalThis.__reqwsE2E.launches);
    expect(launches).toHaveLength(launchesBefore + Number(open));
    if (open) {
      workspace.editorLaunch = editorLaunchSchema.parse({ ...launches.at(-1), boundary: 'os-spawn-only' });
      expect(workspace.editorLaunch).toEqual({ command: '/usr/bin/open',
        args: ['-a', applicationPath, path.join(workspace.root, '.reqws', 'ide', 'goland')],
        shell: false, boundary: 'os-spawn-only' });
    }
    if (!workspace.editorLaunch) throw new Error('The linked entry requires a successful real Save and open GoLand action.');
    // Starter receives the target observed at the real EditorLauncher's final OS boundary.
    const shell = workspace.editorLaunch.args[2];
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
    for (const [index, repo] of members.entries()) {
      expect((await lstat(path.join(workspace.root, repo, '.git'))).isDirectory()).toBe(true);
      expect(await readFile(path.join(workspace.root, repo, 'README.txt'), 'utf8')).toBe(`ReqWS Git fixture: ${repo}\n`);
      expect(await readFile(path.join(workspace.root, repo, 'docs/probe.txt'), 'utf8')).toBe('ordinary text fixture\n');
      expect(await readFile(path.join(workspace.root, repo, '.git/HEAD'), 'utf8')).toBe(workspace.gitHeads[index]);
    }
    expect(await readFile(path.join(workspace.root, 'user-content/keep.txt'), 'utf8')).toBe('user owned\n');
    expect(await readFile(path.join(workspace.root, 'notes/outside.txt'), 'utf8')).toBe('outside all project roots\n');
    expect(desktop.errors).toEqual([]);
    await desktop.page.screenshot({ path: info.outputPath(`${name}-revision-${project.revision}.png`) });
    return { name, root: workspace.root, shell, workspaceId: project.workspaceId, bindingId: project.bindingId,
      revision: project.revision, selected, repositories, editorLaunch: workspace.editorLaunch };
  };
  const execute = async (request: Exclude<LinkRequest, { operation: 'finish' }>) => {
    if (request.operation === 'create') {
      if (workspaces.has(request.name)) throw new Error('A linked workspace cannot be reused by another scenario.');
      const dialog = desktop.page.getByRole('dialog');
      if (await dialog.count()) await dialog.getByRole('button', { name: 'Close', exact: true }).click();
      const root = await createWorkspace(desktop, request.name, members);
      const workspace = (await desktop.state()).workspaces.find((item) => item.name === request.name)!;
      // Ordinary user files, never business manifests or a test projection.
      for (const repo of members) {
        await mkdir(path.join(root, repo, 'docs'));
        await writeFile(path.join(root, repo, 'docs/probe.txt'), 'ordinary text fixture\n', { flag: 'wx' });
      }
      await mkdir(path.join(root, 'user-content'));
      await writeFile(path.join(root, 'user-content/keep.txt'), 'user owned\n', { flag: 'wx' });
      await mkdir(path.join(root, 'user-extra'));
      await writeFile(path.join(root, 'user-extra/keep-extra.txt'), 'unclaimed root in the managed module\n', { flag: 'wx' });
      await mkdir(path.join(root, 'notes'));
      await writeFile(path.join(root, 'notes/outside.txt'), 'outside all project roots\n', { flag: 'wx' });
      workspaces.set(request.name, { root, workspaceId: workspace.id, workspaceFilePath: workspace.workspaceFilePath,
        manifest: await readFile(path.join(root, '.reqws/workspace.json'), 'utf8'),
        codeWorkspace: await readFile(workspace.workspaceFilePath, 'utf8'), revision: 0,
        gitHeads: await Promise.all(members.map((repo) => readFile(path.join(root, repo, '.git/HEAD'), 'utf8'))) });
      const snapshot = await save(request.name, ['repo-a', 'repo-b'], true);
      await writeFile(path.join(snapshot.shell, 'shell-probe.txt'), 'dedicated shell stays hidden\n', { flag: 'wx' });
      return snapshot;
    }
    if (!workspaces.has(request.name)) throw new Error('Selection requires a workspace created by this session.');
    return save(request.name, request.selected);
  };
  try {
    // Discovery uses real filesystem/plutil validation. Only the final OS spawn
    // remains the existing adapter; this fixture executable must never be run.
    await mkdir(path.join(applicationPath, 'Contents', 'MacOS'), { recursive: true });
    await writeFile(path.join(applicationPath, 'Contents', 'Info.plist'), `<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.jetbrains.goland</string>
<key>CFBundleExecutable</key><string>goland</string>
<key>CFBundleShortVersionString</key><string>2026.2.1.1</string>
</dict></plist>\n`, { flag: 'wx', mode: 0o600 });
    await writeFile(path.join(applicationPath, 'Contents', 'MacOS', 'goland'), '#!/bin/sh\nexit 97\n', { flag: 'wx', mode: 0o755 });
    await desktop.start();
    await addRepository(desktop, 'repo-a');
    await addRepository(desktop, 'repo-b');
    await addRepository(desktop, 'repo-c');
    await publishLinkJson(path.join(link.directory, 'ready.json'), { schemaVersion: 1, sessionId: link.sessionId });
    for (let sequence = 1; ; sequence += 1) {
      const request = await waitLinkRequest(link.directory, link.sessionId, sequence, 20 * 60_000);
      const response = { schemaVersion: 1, sessionId: link.sessionId, sequence };
      try {
        if (request.operation === 'finish') {
          expect(workspaces.size).toBe(5);
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
