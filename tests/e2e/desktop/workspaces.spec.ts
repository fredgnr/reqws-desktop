import { execFile } from 'node:child_process';
import { lstat, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import type { WorkspaceManifest } from '../../../src/shared/types';
import { test, expect, type Desktop } from '../fixtures/desktop';
import { addRepository, createWorkspace, fillWorkspace, submitWorkspace } from '../fixtures/ui';

const exec = promisify(execFile);

// Fixture preparation and independent Git assertions do not reuse the application's GitRunner.
async function git(desktop: Desktop, cwd: string, args: string[]): Promise<string> {
  const result = await exec('/usr/bin/git', [
    '-c', 'credential.helper=', '-c', `core.hooksPath=${desktop.isolation.hooks}`,
    ...args,
  ], {
    cwd, env: { ...desktop.isolation.env, GIT_CONFIG_NOSYSTEM: '1' },
    shell: false, timeout: 15_000, maxBuffer: 1024 * 1024,
  });
  return result.stdout.trim();
}

async function manifest(root: string): Promise<WorkspaceManifest> {
  return JSON.parse(await readFile(path.join(root, '.reqws/workspace.json'), 'utf8')) as WorkspaceManifest;
}

async function expectMembers(desktop: Desktop, root: string, name: string, members: string[]): Promise<void> {
  const stored = await manifest(root);
  const workspaceFilePath = path.join(desktop.isolation.outputRoot, `${name}.code-workspace`);
  expect(stored).toMatchObject({ schemaVersion: 1, name, rootPath: root, workspaceFilePath, featureBranch: 'feat/e2e' });
  expect(stored.repositories.map((repository) => repository.name)).toEqual(members);
  expect(stored.repositories.map((repository) => repository.relativePath)).toEqual(members);
  const state = await desktop.state();
  const summary = state.workspaces.find((workspace) => workspace.name === name);
  expect(summary).toMatchObject({
    id: stored.id, rootPath: root, workspaceFilePath, featureBranch: 'feat/e2e', status: 'ready', repositoryNames: members,
  });
  expect(summary!.repositoryIds).toEqual(stored.repositories.map((repository) => repository.catalogRepositoryId));
  for (const repository of stored.repositories) {
    expect(state.repositories.find((entry) => entry.id === repository.catalogRepositoryId)).toMatchObject({
      name: repository.name, url: repository.url, defaultBranch: repository.defaultBranch,
    });
  }
  const generated = JSON.parse(await readFile(workspaceFilePath, 'utf8')) as { folders: Array<{ name: string; path: string }> };
  expect(generated.folders).toEqual(members.map((repository) => ({ name: repository, path: path.join(root, repository) })));
}

async function closeOperation(desktop: Desktop, title: string): Promise<void> {
  const operation = desktop.page.getByRole('dialog', { name: title, exact: true });
  await expect(operation.getByText('Complete', { exact: true })).toBeVisible();
  await operation.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(operation).toBeHidden();
}

test('D04 creates a real two-repository workspace with independent Git and disk artifacts @smoke', async ({ desktop }) => {
  const names = ['workspace-alpha', 'workspace-beta'];
  const urls = names.map((name) => desktop.origin.url(name));
  for (const name of names) await addRepository(desktop, name);
  const barePaths = names.map((name) => path.join(desktop.isolation.originRoot, `${name}.git`));
  // Only the first remote feature contains this commit, so choosing main while
  // merely setting the expected branch/upstream cannot satisfy the assertions.
  const featureSeed = path.join(desktop.isolation.root, 'feature-seed');
  const featureContent = 'This ordinary text exists only on the remote feature branch.\n';
  await git(desktop, desktop.isolation.root, ['clone', '--no-hardlinks', '--', barePaths[0]!, featureSeed]);
  await git(desktop, featureSeed, ['switch', '-c', 'feat/e2e']);
  await writeFile(path.join(featureSeed, 'FEATURE.txt'), featureContent, { flag: 'wx' });
  await git(desktop, featureSeed, ['add', '--', 'FEATURE.txt']);
  await git(desktop, featureSeed, [
    '-c', 'user.name=ReqWS Test', '-c', 'user.email=reqws@example.invalid',
    '-c', 'commit.gpgSign=false', 'commit', '-m', 'Add remote feature fixture',
  ]);
  await git(desktop, featureSeed, ['push', '--', barePaths[0]!, 'HEAD:refs/heads/feat/e2e']);
  const remoteFeatureHead = await git(desktop, barePaths[0]!, ['rev-parse', 'refs/heads/feat/e2e']);
  const mainHeads = await Promise.all(barePaths.map((bare) => git(desktop, bare, ['rev-parse', 'refs/heads/main'])));
  expect(remoteFeatureHead).not.toBe(mainHeads[0]);
  const refsBefore = await Promise.all(barePaths.map((bare) => git(desktop, bare, ['show-ref'])));
  const root = await createWorkspace(desktop, 'two-repositories', names);
  await expectMembers(desktop, root, 'two-repositories', names);
  const metadataPaths: string[] = [];
  for (const [index, name] of names.entries()) {
    const repository = path.join(root, name);
    const metadata = path.join(repository, '.git');
    expect((await lstat(metadata)).isDirectory()).toBe(true);
    expect((await lstat(path.join(metadata, 'objects'))).isDirectory()).toBe(true);
    await expect(lstat(path.join(metadata, 'commondir'))).rejects.toMatchObject({ code: 'ENOENT' });
    await expect(lstat(path.join(metadata, 'objects/info/alternates'))).rejects.toMatchObject({ code: 'ENOENT' });
    metadataPaths.push(await git(desktop, repository, ['rev-parse', '--absolute-git-dir']));
    expect(await git(desktop, repository, ['branch', '--show-current'])).toBe('feat/e2e');
    expect(await git(desktop, repository, ['remote', 'get-url', 'origin'])).toBe(urls[index]);
    expect(await git(desktop, repository, ['rev-parse', 'HEAD'])).toBe(index === 0 ? remoteFeatureHead : mainHeads[index]);
    expect(await readFile(path.join(repository, 'README.txt'), 'utf8')).toBe(`ReqWS Git fixture: ${name}\n`);
    if (index === 0) expect(await readFile(path.join(repository, 'FEATURE.txt'), 'utf8')).toBe(featureContent);
    else await expect(lstat(path.join(repository, 'FEATURE.txt'))).rejects.toMatchObject({ code: 'ENOENT' });
  }
  expect(metadataPaths).toEqual(names.map((name) => path.join(root, name, '.git')));
  expect(new Set(metadataPaths).size).toBe(2);
  expect(await git(desktop, path.join(root, names[0]!), ['rev-parse', '--abbrev-ref', '@{upstream}'])).toBe('origin/feat/e2e');
  expect(await Promise.all(barePaths.map((bare) => git(desktop, bare, ['show-ref'])))).toEqual(refsBefore);
  const registry = process.env.REQWS_E2E_PROCESS_REGISTRY;
  if (registry) {
    const records = (await readFile(registry, 'utf8')).trim().split('\n').map((line) => JSON.parse(line) as {
      event: string; parent: number; scope?: string;
    });
    expect(records.some((record) => record.event === 'start'
      && record.parent === desktop.app.process().pid && record.scope === desktop.isolation.home),
    'Main Git clients participate in runner ownership cleanup').toBe(true);
  }
  await expect(desktop.page.getByRole('row').filter({ has: desktop.page.getByText('two-repositories', { exact: true }) })).toContainText('Ready');
});

test('D05 adds and logically removes a repository while preserving its Git directory and user files', async ({ desktop }) => {
  await addRepository(desktop, 'members-alpha');
  await addRepository(desktop, 'members-beta');
  const root = await createWorkspace(desktop, 'membership', ['members-alpha']);
  await desktop.page.getByRole('button', { name: 'View details for membership', exact: true }).click();
  const detail = desktop.page.getByRole('dialog', { name: 'membership', exact: true });
  await detail.getByText('Manage workspace', { exact: true }).click();
  const added = (await desktop.state()).repositories.find((repository) => repository.name === 'members-beta')!;
  await detail.getByLabel('Choose a repository to add', { exact: true }).selectOption(added.id);
  await detail.getByRole('button', { name: 'Add', exact: true }).click();
  await closeOperation(desktop, 'Adding repository');
  await expectMembers(desktop, root, 'membership', ['members-alpha', 'members-beta']);
  const userFile = path.join(root, 'members-beta', 'user-notes.txt');
  await writeFile(userFile, 'User work must survive logical removal.\n', { flag: 'wx' });
  const head = await git(desktop, path.join(root, 'members-beta'), ['rev-parse', 'HEAD']);
  const row = detail.locator('.repo-manage-row').filter({ hasText: 'members-beta' });
  await row.getByRole('button', { name: 'Remove', exact: true }).click();
  const confirmation = desktop.page.getByRole('dialog', { name: 'Remove “members-beta” from the workspace?', exact: true });
  await expect(confirmation).toContainText('does not delete the local repository folder');
  await confirmation.getByRole('button', { name: 'Remove repository', exact: true }).click();
  await closeOperation(desktop, 'Removing repository');
  await expectMembers(desktop, root, 'membership', ['members-alpha']);
  expect((await lstat(path.join(root, 'members-beta/.git'))).isDirectory()).toBe(true);
  expect(await readFile(userFile, 'utf8')).toBe('User work must survive logical removal.\n');
  expect(await git(desktop, path.join(root, 'members-beta'), ['rev-parse', 'HEAD'])).toBe(head);
  expect((await desktop.state()).repositories.map((repository) => repository.name)).toEqual(['members-alpha', 'members-beta']);
});

test('D06 transport failure before publication cleans staging and permits a real retry', async ({ desktop }) => {
  await addRepository(desktop, 'retry-alpha');
  const sentinel = path.join(desktop.isolation.workspaceRoot, 'user-sentinel.txt');
  await writeFile(sentinel, 'unrelated user content', { flag: 'wx' });
  const root = await fillWorkspace(desktop, 'network-retry', ['retry-alpha']);
  desktop.origin.setUnavailable(true);
  try {
    await submitWorkspace(desktop);
    const operation = desktop.page.getByRole('dialog', { name: 'Creating workspace', exact: true });
    await expect(operation.getByRole('alert')).toContainText('CLONE_FAILED');
    await expect(lstat(root)).rejects.toMatchObject({ code: 'ENOENT' });
    expect(await readdir(desktop.isolation.workspaceRoot)).toEqual(['user-sentinel.txt']);
    expect(await readdir(desktop.isolation.outputRoot)).toEqual([]);
    expect((await desktop.state()).workspaces).toEqual([]);
    expect(await readFile(sentinel, 'utf8')).toBe('unrelated user content');
    await operation.getByRole('button', { name: 'Close', exact: true }).click();
  } finally {
    desktop.origin.setUnavailable(false);
  }
  await submitWorkspace(desktop);
  await closeOperation(desktop, 'Creating workspace');
  await expectMembers(desktop, root, 'network-retry', ['retry-alpha']);
  expect((await readdir(desktop.isolation.workspaceRoot)).sort()).toEqual(['network-retry', 'user-sentinel.txt']);
});

for (const fault of ['workspace', 'state'] as const) {
  test(`D07 ${fault} atomic publication failure retains published clones without a success index`, async ({ desktop }) => {
    await addRepository(desktop, 'retained-alpha');
    const name = `retained-${fault}`;
    const root = await fillWorkspace(desktop, name, ['retained-alpha']);
    const workspaceFile = path.join(desktop.isolation.outputRoot, `${name}.code-workspace`);
    const failurePath = fault === 'workspace' ? workspaceFile : path.join(desktop.isolation.userData, 'reqws/state.v1.json');
    await desktop.control({ failWrite: fault });
    await submitWorkspace(desktop);
    const operation = desktop.page.getByRole('dialog', { name: 'Creating workspace', exact: true });
    await expect(operation.getByRole('alert')).toContainText(fault === 'workspace' ? 'WORKSPACE_FILE_WRITE_FAILED' : 'STATE_WRITE_FAILED');
    await operation.getByText('Technical details', { exact: true }).click();
    await expect(operation.getByRole('alert')).toContainText('Published artifacts were retained for safe recovery:');
    await expect(operation.getByRole('alert')).toContainText(root);
    expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.failedWrites)).toEqual([failurePath]);
    const stored = await manifest(root);
    expect(stored.repositories.map((repository) => repository.name)).toEqual(['retained-alpha']);
    expect((await lstat(path.join(root, 'retained-alpha/.git'))).isDirectory()).toBe(true);
    expect(await readFile(path.join(root, 'retained-alpha/README.txt'), 'utf8')).toBe('ReqWS Git fixture: retained-alpha\n');
    expect((await desktop.state()).workspaces).toEqual([]);
    expect(await readdir(desktop.isolation.workspaceRoot)).toEqual([name]);
    if (fault === 'workspace') await expect(lstat(workspaceFile)).rejects.toMatchObject({ code: 'ENOENT' });
    else {
      const generated = JSON.parse(await readFile(workspaceFile, 'utf8'));
      expect(generated.folders).toEqual([{ name: 'retained-alpha', path: path.join(root, 'retained-alpha') }]);
      await expect(operation.getByRole('alert')).toContainText(workspaceFile);
    }
    const userFile = path.join(root, 'user-recovery-notes.txt');
    await writeFile(userFile, 'Keep this published workspace for recovery.', { flag: 'wx' });
    await operation.getByRole('button', { name: 'Close', exact: true }).click();
    await submitWorkspace(desktop);
    await expect(operation.getByRole('alert')).toContainText('WORKSPACE_ROOT_EXISTS');
    expect(await readFile(userFile, 'utf8')).toBe('Keep this published workspace for recovery.');
    expect(await manifest(root)).toEqual(stored);
    expect((await desktop.state()).workspaces).toEqual([]);
    await operation.getByRole('button', { name: 'Close', exact: true }).click();
    await desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true }).getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(desktop.page.getByRole('heading', { name: 'No workspaces yet', exact: true })).toBeVisible();
  });
}
