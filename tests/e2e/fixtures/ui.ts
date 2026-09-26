import path from 'node:path';
import { expect, type Desktop } from './desktop';

export async function navigate(desktop: Desktop, page: 'Workspaces' | 'Repositories' | 'Settings'): Promise<void> {
  await desktop.page.getByRole('navigation').getByRole('button', { name: new RegExp(`^${page}`) }).click();
  await expect(desktop.page.getByRole('heading', { level: 1, name: page })).toBeVisible();
}

export async function addRepository(desktop: Desktop, name: string): Promise<string> {
  const { url } = await desktop.origin.addRepository(name);
  await navigate(desktop, 'Repositories');
  await desktop.page.locator('header').getByRole('button', { name: 'Add repository', exact: true }).click();
  const dialog = desktop.page.getByRole('dialog', { name: 'Add repository' });
  await dialog.getByLabel('Git repository URL', { exact: true }).fill(url);
  await dialog.getByLabel('Name', { exact: true }).fill(name);
  await dialog.getByLabel('Default branch', { exact: true }).fill('main');
  await dialog.getByRole('button', { name: 'Add repository', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect.poll(async () => (await desktop.state()).repositories.some((repo) => repo.name === name)).toBe(true);
  return url;
}

export async function fillWorkspace(desktop: Desktop, name: string, repositories: string[]): Promise<string> {
  await navigate(desktop, 'Workspaces');
  await desktop.page.locator('header').getByRole('button', { name: 'Create workspace', exact: true }).click();
  const dialog = desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true });
  const root = path.join(desktop.isolation.workspaceRoot, name);
  await dialog.getByLabel('Workspace name', { exact: true }).fill(name);
  await dialog.getByLabel('Feature branch', { exact: true }).fill('feat/e2e');
  await dialog.getByLabel('Workspace code folder', { exact: true }).fill(root);
  await dialog.getByLabel('.code-workspace file folder', { exact: true }).fill(desktop.isolation.outputRoot);
  for (const repository of repositories) {
    await dialog.getByRole('checkbox', { name: new RegExp(`^${repository}\\s`) }).check();
  }
  return root;
}

export async function submitWorkspace(desktop: Desktop): Promise<void> {
  await desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true })
    .getByRole('button', { name: 'Create workspace', exact: true }).click();
}

export async function createWorkspace(desktop: Desktop, name: string, repositories: string[]): Promise<string> {
  const root = await fillWorkspace(desktop, name, repositories);
  await submitWorkspace(desktop);
  await expect.poll(async () => (await desktop.state()).workspaces.some((workspace) => workspace.name === name)).toBe(true);
  const progress = desktop.page.getByRole('dialog');
  await progress.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(progress).toBeHidden();
  return root;
}
