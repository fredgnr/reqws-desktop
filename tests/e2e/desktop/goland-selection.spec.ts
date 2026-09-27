import { lstat, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { Locator } from '@playwright/test';

import type { GoLandProject } from '../../../src/shared/goland-workspace';
import { test, expect, type Desktop } from '../fixtures/desktop';
import { addRepository, createWorkspace, navigate } from '../fixtures/ui';

const projectPath = (root: string): string => path.join(root, '.reqws', 'ide', 'goland', 'reqws-project.json');
const readProject = async (root: string): Promise<GoLandProject> => JSON.parse(await readFile(projectPath(root), 'utf8')) as GoLandProject;

async function openLoading(desktop: Desktop, name: string): Promise<Locator> {
  await navigate(desktop, 'Workspaces');
  await desktop.page.getByRole('button', { name: `View details for ${name}`, exact: true }).click();
  const section = desktop.page.getByRole('dialog', { name, exact: true })
    .getByRole('region', { name: 'GoLand repository loading', exact: true });
  await expect(section.getByRole('button', { name: 'Save selection', exact: true })).toBeEnabled();
  return section;
}

async function doubleWorkspace(desktop: Desktop, name: string): Promise<string> {
  await addRepository(desktop, 'one');
  await addRepository(desktop, 'two');
  return createWorkspace(desktop, name, ['one', 'two']);
}

async function saveSelection(section: Locator, root: string, revision: number): Promise<GoLandProject> {
  await section.getByRole('button', { name: 'Save selection', exact: true }).click();
  await expect(section.getByRole('status')).toHaveText('Selection saved');
  await expect.poll(async () => (await readProject(root)).revision).toBe(revision);
  return readProject(root);
}

test('D08 all, subset, empty and 2→1→0→2 selections preserve membership and have independent bindings', async ({ desktop }) => {
  const root = await doubleWorkspace(desktop, 'loading-one');
  const manifestPath = path.join(root, '.reqws', 'workspace.json');
  const manifestBefore = await readFile(manifestPath, 'utf8');
  const workspace = (await desktop.state()).workspaces.find((item) => item.name === 'loading-one')!;
  const codeWorkspaceBefore = await readFile(workspace.workspaceFilePath, 'utf8');
  const repositoryIds = (await desktop.state()).repositories.map((item) => item.id);
  const oneId = (await desktop.state()).repositories.find((item) => item.name === 'one')!.id;
  const userFile = path.join(root, 'two', 'user-note.txt');
  await writeFile(userFile, 'Preserve this unloaded repository.');
  const section = await openLoading(desktop, 'loading-one');
  await expect(section.getByRole('radio', { name: 'All by default', exact: true })).toBeChecked();
  const initial = await saveSelection(section, root, 1);
  expect(initial).toMatchObject({ schemaVersion: 1, adapterProtocol: 1, workspaceId: workspace.id, selection: { mode: 'all' } });
  expect(initial.bindingId).toMatch(/^[0-9a-f-]{36}$/u);

  await section.getByRole('radio', { name: 'Selected repositories', exact: true }).check();
  await section.getByRole('checkbox', { name: 'two', exact: true }).uncheck();
  await expect(section.getByText('1 repository selected (2 total)', { exact: true })).toBeVisible();
  expect((await saveSelection(section, root, 2)).selection).toEqual({ mode: 'selected', repositoryIds: [oneId] });

  await section.getByRole('checkbox', { name: 'one', exact: true }).uncheck();
  await expect(section.getByText('0 repositories selected (2 total)', { exact: true })).toBeVisible();
  expect((await saveSelection(section, root, 3)).selection).toEqual({ mode: 'selected', repositoryIds: [] });

  await section.getByRole('radio', { name: 'All by default', exact: true }).check();
  const restored = await saveSelection(section, root, 4);
  expect(restored.selection).toEqual({ mode: 'all' });
  expect(restored.bindingId).toBe(initial.bindingId);
  const restoredBytes = await readFile(projectPath(root), 'utf8');
  await section.getByRole('radio', { name: 'Selected repositories', exact: true }).check();
  await section.getByRole('checkbox', { name: 'two', exact: true }).uncheck();
  await section.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(section.getByRole('radio', { name: 'All by default', exact: true })).toBeChecked();
  expect(await readFile(projectPath(root), 'utf8')).toBe(restoredBytes);

  await desktop.page.getByRole('dialog', { name: 'loading-one', exact: true }).getByRole('button', { name: 'Close', exact: true }).click();
  const otherRoot = await createWorkspace(desktop, 'loading-two', ['one', 'two']);
  const otherSection = await openLoading(desktop, 'loading-two');
  await otherSection.getByRole('radio', { name: 'Selected repositories', exact: true }).check();
  await otherSection.getByRole('checkbox', { name: 'two', exact: true }).uncheck();
  const other = await saveSelection(otherSection, otherRoot, 1);
  expect(other.bindingId).not.toBe(initial.bindingId);
  expect(other.workspaceId).not.toBe(initial.workspaceId);
  expect(other.selection).toEqual({ mode: 'selected', repositoryIds: [oneId] });
  expect(await readFile(projectPath(root), 'utf8')).toBe(restoredBytes);
  expect(await readFile(manifestPath, 'utf8')).toBe(manifestBefore);
  expect(await readFile(workspace.workspaceFilePath, 'utf8')).toBe(codeWorkspaceBefore);
  expect((await desktop.state()).workspaces.find((item) => item.id === workspace.id)).toEqual(workspace);
  expect((await desktop.state()).repositories.map((item) => item.id)).toEqual(repositoryIds);
  for (const repository of ['one', 'two']) {
    expect((await lstat(path.join(root, repository, '.git'))).isDirectory()).toBe(true);
    expect(await readFile(path.join(root, repository, 'README.txt'), 'utf8')).toBe(`ReqWS Git fixture: ${repository}\n`);
  }
  expect(await readFile(userFile, 'utf8')).toBe('Preserve this unloaded repository.');
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.launches)).toEqual([]);
});

test('D09 a final atomic selection-write failure preserves the draft and retries the same revision', async ({ desktop }) => {
  const root = await doubleWorkspace(desktop, 'loading-retry');
  const section = await openLoading(desktop, 'loading-retry');
  await saveSelection(section, root, 1);
  const before = await readFile(projectPath(root), 'utf8');
  const oneId = (await desktop.state()).repositories.find((item) => item.name === 'one')!.id;
  await section.getByRole('radio', { name: 'Selected repositories', exact: true }).check();
  await section.getByRole('checkbox', { name: 'two', exact: true }).uncheck();
  await desktop.control({ failWrite: 'selection' });
  await section.getByRole('button', { name: 'Save selection', exact: true }).click();
  await expect(section.getByRole('alert')).toContainText('GOLAND_WRITE_FAILED');
  await expect(section.getByRole('status')).toHaveText('Unsaved changes');
  expect(await readFile(projectPath(root), 'utf8')).toBe(before);
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.failedWrites)).toContain(projectPath(root));
  await expect(section.getByRole('checkbox', { name: 'one', exact: true })).toBeChecked();
  await expect(section.getByRole('checkbox', { name: 'two', exact: true })).not.toBeChecked();
  await expect(section.getByRole('button', { name: 'Save selection', exact: true })).toBeEnabled();
  const retried = await saveSelection(section, root, 2);
  expect(retried.selection).toEqual({ mode: 'selected', repositoryIds: [oneId] });
  await expect(section.getByRole('alert')).toHaveCount(0);
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.launches)).toEqual([]);
});

test('D09 an external revision conflict cannot overwrite the newer selection and requires reload', async ({ desktop }) => {
  const root = await doubleWorkspace(desktop, 'loading-conflict');
  const section = await openLoading(desktop, 'loading-conflict');
  const initial = await saveSelection(section, root, 1);
  await section.getByRole('radio', { name: 'Selected repositories', exact: true }).check();
  await section.getByRole('checkbox', { name: 'two', exact: true }).uncheck();
  const external: GoLandProject = { ...initial, revision: 2, selection: { mode: 'selected', repositoryIds: [] }, updatedAt: new Date().toISOString() };
  const externalBytes = `${JSON.stringify(external, null, 2)}\n`;
  const temporary = `${projectPath(root)}.external`;
  await writeFile(temporary, externalBytes, { flag: 'wx', mode: 0o600 });
  await rename(temporary, projectPath(root));
  await section.getByRole('button', { name: 'Save selection', exact: true }).click();
  await expect(section.getByRole('alert')).toContainText('GOLAND_SELECTION_CONFLICT');
  expect(await readFile(projectPath(root), 'utf8')).toBe(externalBytes);
  await expect(section.getByRole('checkbox', { name: 'one', exact: true })).toBeChecked();
  await expect(section.getByRole('checkbox', { name: 'two', exact: true })).not.toBeChecked();
  await expect(section.getByRole('button', { name: 'Save selection', exact: true })).toBeDisabled();
  await expect(section.getByRole('button', { name: 'Save and open GoLand', exact: true })).toBeDisabled();
  await section.getByRole('button', { name: 'Reload saved configuration', exact: true }).click();
  await expect(section.getByRole('checkbox', { name: 'one', exact: true })).not.toBeChecked();
  await expect(section.getByRole('checkbox', { name: 'two', exact: true })).not.toBeChecked();
  await section.getByRole('checkbox', { name: 'one', exact: true }).check();
  const saved = await saveSelection(section, root, 3);
  expect(saved.bindingId).toBe(initial.bindingId);
  expect(saved.selection).toEqual({ mode: 'selected', repositoryIds: [(await desktop.state()).repositories.find((item) => item.name === 'one')!.id] });
});
