import { execFile } from 'node:child_process';
import { lstat, mkdir, readFile, readdir, rename, rmdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import type { Locator } from '@playwright/test';
import type { LocalePreference, WorkspaceManifest } from '../../../src/shared/types';
import { test, expect, type Desktop } from '../fixtures/desktop';
import { addRepository, createWorkspace, fillWorkspace, navigate, submitWorkspace } from '../fixtures/ui';

const exec = promisify(execFile);
const unavailableDirectory = 'The previously selected folder no longer exists or cannot be accessed. Choose another folder.';

// Preparation and disk/Git assertions run outside the application services.
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

function statePath(desktop: Desktop): string {
  return path.join(desktop.isolation.userData, 'reqws/state.v1.json');
}

async function artifacts(root: string, workspaceFile: string): Promise<{ manifest: string; workspace: string }> {
  return {
    manifest: await readFile(path.join(root, '.reqws/workspace.json'), 'utf8'),
    workspace: await readFile(workspaceFile, 'utf8'),
  };
}

async function showWorkspaces(desktop: Desktop): Promise<void> {
  await desktop.page.getByRole('navigation').getByRole('button', { name: /^(Workspaces|工作区)/u }).click();
  await expect(desktop.page.getByRole('heading', { level: 1, name: /^(Workspaces|工作区)$/u })).toBeVisible();
}

async function saveLocale(desktop: Desktop, locale: LocalePreference): Promise<void> {
  const { page } = desktop;
  await page.getByRole('navigation').getByRole('button', { name: /^(Settings|设置)$/u }).click();
  await page.getByLabel(/^(Interface language|界面语言)$/u).selectOption(locale);
  await page.getByRole('button', { name: /^(Save settings|保存设置)$/u }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', locale === 'zh-CN' ? 'zh-CN' : 'en-US');
  await expect.poll(async () => (await desktop.state()).settings.localePreference).toBe(locale);
}

async function openDetails(desktop: Desktop, name: string, locale: 'en-US' | 'zh-CN' = 'en-US'): Promise<Locator> {
  await desktop.page.getByRole('button', {
    name: locale === 'en-US' ? `View details for ${name}` : `查看 ${name} 的详情`, exact: true,
  }).click();
  const drawer = desktop.page.getByRole('dialog', { name, exact: true });
  await expect(drawer).toBeVisible();
  return drawer;
}

async function closeDrawer(drawer: Locator): Promise<void> {
  await drawer.getByRole('button', { name: /^(Close|关闭)$/u }).click();
  await expect(drawer).toBeHidden();
}

async function closeOperation(desktop: Desktop, title: string): Promise<void> {
  const operation = desktop.page.getByRole('dialog', { name: title, exact: true });
  await expect(operation.getByText('Complete', { exact: true })).toBeVisible();
  await operation.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(operation).toBeHidden();
}

async function pickCreationDirectory(dialog: Locator, label: string): Promise<void> {
  await dialog.getByLabel(label, { exact: true }).locator('..')
    .getByRole('button', { name: 'Choose…', exact: true }).click();
}

async function expectPrivateState(desktop: Desktop): Promise<void> {
  const filename = statePath(desktop);
  expect((await lstat(path.dirname(filename))).mode & 0o777).toBe(0o700);
  expect((await lstat(filename)).mode & 0o777).toBe(0o600);
  const stored = await desktop.state();
  expect(stored.schemaVersion).toBe(1);
  expect(Object.keys(stored).sort()).toEqual(['repositories', 'schemaVersion', 'settings', 'workspaces']);
  const inspect = (value: unknown): void => {
    if (!value || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      expect(key).not.toMatch(/password|secret|token|credential/iu);
      inspect(child);
    }
  };
  inspect(stored);
  for (const repository of stored.repositories) {
    const url = new URL(repository.url);
    expect(url.protocol).toBe('https:');
    expect(url.username).toBe('');
    expect(url.password).toBe('');
    expect(url.search).toBe('');
    expect(url.hash).toBe('');
  }
}

test('D02 V fixed en-US system provider selects the initial language and applies Follow system again', async ({ desktop }) => {
  const { page } = desktop;
  // test-main deliberately fixes getPreferredSystemLanguages to ['en-US'];
  // this proves the selection/UI chain, not this machine's macOS preference.
  await navigate(desktop, 'Settings');
  await expect(page.getByLabel('Interface language', { exact: true })).toHaveValue('system');
  await expect(page.locator('html')).toHaveAttribute('lang', 'en-US');
  await saveLocale(desktop, 'zh-CN');
  await expect(page.getByRole('heading', { level: 1, name: '设置', exact: true })).toBeVisible();
  await saveLocale(desktop, 'system');
  await expect(page.getByRole('heading', { level: 1, name: 'Settings', exact: true })).toBeVisible();
  await expect(page.getByLabel('Interface language', { exact: true })).toHaveValue('system');
  await expectPrivateState(desktop);
});

test('D02 D04 V settings preserve existing business data and separate defaults survive one-time overrides and cold restart', async ({ desktop }) => {
  await addRepository(desktop, 'defaults-alpha');
  const existingRoot = await createWorkspace(desktop, 'existing-business', ['defaults-alpha']);
  const existingFile = path.join(desktop.isolation.outputRoot, 'existing-business.code-workspace');
  const existingArtifacts = await artifacts(existingRoot, existingFile);
  const before = await desktop.state();
  const parentDefault = path.join(desktop.isolation.root, 'default-parent');
  const fileDefault = path.join(desktop.isolation.root, 'default-files');
  const parentOverride = path.join(desktop.isolation.root, 'override-parent');
  const fileOverride = path.join(desktop.isolation.root, 'override-files');
  for (const directory of [parentDefault, fileDefault, parentOverride, fileOverride]) await mkdir(directory, { mode: 0o700 });
  await navigate(desktop, 'Settings');
  await desktop.control({ directories: [parentDefault, fileDefault] });
  await desktop.page.getByRole('button', { name: 'Choose a folder for Default workspace parent folder', exact: true }).click();
  await desktop.page.getByRole('button', { name: 'Choose a folder for .code-workspace file folder', exact: true }).click();
  await desktop.page.getByRole('button', { name: 'Save settings', exact: true }).click();
  const settings = { localePreference: 'system', workspaceParentDirectory: parentDefault, workspaceFileDirectory: fileDefault };
  await expect.poll(async () => (await desktop.state()).settings).toEqual(settings);
  const afterSave = await desktop.state();
  expect(afterSave.repositories).toEqual(before.repositories);
  expect(afterSave.workspaces).toEqual(before.workspaces);
  expect(await artifacts(existingRoot, existingFile)).toEqual(existingArtifacts);

  await navigate(desktop, 'Workspaces');
  await desktop.page.locator('header').getByRole('button', { name: 'Create workspace', exact: true }).click();
  const create = desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true });
  await create.getByLabel('Workspace name', { exact: true }).fill('override-once');
  await expect(create.getByLabel('Workspace code folder', { exact: true })).toHaveValue(path.join(parentDefault, 'override-once'));
  await expect(create.getByLabel('.code-workspace file folder', { exact: true })).toHaveValue(fileDefault);
  await desktop.control({ directories: [parentOverride, fileOverride] });
  await pickCreationDirectory(create, 'Workspace code folder');
  await pickCreationDirectory(create, '.code-workspace file folder');
  const root = path.join(parentOverride, 'override-once');
  const workspaceFile = path.join(fileOverride, 'override-once.code-workspace');
  await expect(create.getByLabel('Workspace code folder', { exact: true })).toHaveValue(root);
  await expect(create.getByLabel('.code-workspace file folder', { exact: true })).toHaveValue(fileOverride);
  await create.getByLabel('Feature branch', { exact: true }).fill('feat/e2e');
  await create.getByRole('checkbox', { name: /^defaults-alpha\s/u }).check();
  await submitWorkspace(desktop);
  await closeOperation(desktop, 'Creating workspace');
  const stored = JSON.parse((await artifacts(root, workspaceFile)).manifest) as WorkspaceManifest;
  expect(stored).toMatchObject({ rootPath: root, workspaceFilePath: workspaceFile, name: 'override-once' });
  expect(JSON.parse(await readFile(workspaceFile, 'utf8')).folders).toEqual([
    { name: 'defaults-alpha', path: path.join(root, 'defaults-alpha') },
  ]);
  expect((await lstat(path.join(root, 'defaults-alpha/.git'))).isDirectory()).toBe(true);
  expect(await readdir(parentDefault)).toEqual([]);
  expect(await readdir(fileDefault)).toEqual([]);
  const persisted = await desktop.state();
  expect(persisted.settings).toEqual(settings);
  expect(persisted.repositories).toEqual(before.repositories);
  expect(persisted.workspaces.find((workspace) => workspace.name === 'existing-business')).toEqual(before.workspaces[0]);
  expect(persisted.workspaces.find((workspace) => workspace.name === 'override-once')).toMatchObject({ rootPath: root, workspaceFilePath: workspaceFile, status: 'ready' });
  await expectPrivateState(desktop);

  const originalPid = desktop.app.process().pid;
  await desktop.restart();
  expect(desktop.app.process().pid).not.toBe(originalPid);
  await expect(desktop.page.getByRole('row').filter({ has: desktop.page.getByText('existing-business', { exact: true }) })).toContainText('Ready');
  await expect(desktop.page.getByRole('row').filter({ has: desktop.page.getByText('override-once', { exact: true }) })).toContainText('Ready');
  await navigate(desktop, 'Repositories');
  await expect(desktop.page.getByRole('row').filter({ has: desktop.page.getByText('defaults-alpha', { exact: true }) })).toContainText(before.repositories[0]!.url);
  expect(await desktop.state()).toEqual(persisted);
  expect(await artifacts(existingRoot, existingFile)).toEqual(existingArtifacts);
  await navigate(desktop, 'Workspaces');
  await desktop.page.locator('header').getByRole('button', { name: 'Create workspace', exact: true }).click();
  const reopened = desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true });
  await reopened.getByLabel('Workspace name', { exact: true }).fill('next-default');
  await expect(reopened.getByLabel('Workspace code folder', { exact: true })).toHaveValue(path.join(parentDefault, 'next-default'));
  await expect(reopened.getByLabel('.code-workspace file folder', { exact: true })).toHaveValue(fileDefault);
  await reopened.getByRole('button', { name: 'Cancel', exact: true }).click();
});

test('D02 V a controlled legacy schema-v1 state loads business records and historical directory defaults', async ({ desktop }) => {
  await addRepository(desktop, 'legacy-alpha');
  const root = await createWorkspace(desktop, 'legacy-business', ['legacy-alpha']);
  const workspaceFile = path.join(desktop.isolation.outputRoot, 'legacy-business.code-workspace');
  const originalArtifacts = await artifacts(root, workspaceFile);
  const current = await desktop.state();
  const originalPid = desktop.app.process().pid;
  await desktop.stop();
  const legacy = {
    ...current,
    settings: {
      lastWorkspaceParentDirectory: desktop.isolation.workspaceRoot,
      lastWorkspaceFileDirectory: desktop.isolation.outputRoot,
    },
  };
  await writeFile(statePath(desktop), `${JSON.stringify(legacy, null, 2)}\n`, { mode: 0o600 });
  await desktop.start();
  expect(desktop.app.process().pid).not.toBe(originalPid);
  await expect(desktop.page.getByRole('row').filter({ has: desktop.page.getByText('legacy-business', { exact: true }) })).toContainText('Ready');
  await navigate(desktop, 'Repositories');
  await expect(desktop.page.getByRole('row').filter({ has: desktop.page.getByText('legacy-alpha', { exact: true }) })).toContainText(current.repositories[0]!.url);
  await navigate(desktop, 'Settings');
  await expect(desktop.page.getByLabel('Interface language', { exact: true })).toHaveValue('system');
  await expect(desktop.page.getByLabel('Default workspace parent folder', { exact: true })).toHaveValue(desktop.isolation.workspaceRoot);
  await expect(desktop.page.getByLabel('.code-workspace file folder', { exact: true })).toHaveValue(desktop.isolation.outputRoot);
  await saveLocale(desktop, 'zh-CN');
  const persisted = await desktop.state();
  expect(persisted.settings).toEqual({
    localePreference: 'zh-CN', workspaceParentDirectory: desktop.isolation.workspaceRoot,
    workspaceFileDirectory: desktop.isolation.outputRoot,
  });
  expect(persisted.repositories).toEqual(current.repositories);
  expect(persisted.workspaces).toEqual(current.workspaces);
  expect(await artifacts(root, workspaceFile)).toEqual(originalArtifacts);
  await expectPrivateState(desktop);
});

for (const conflict of ['root', 'workspace-file'] as const) {
  test(`D06 V pre-existing ${conflict} rejects workspace creation without overwriting user sentinels`, async ({ desktop }) => {
    await addRepository(desktop, 'conflict-alpha');
    const before = await desktop.state();
    const name = `occupied-${conflict}`;
    const root = path.join(desktop.isolation.workspaceRoot, name);
    const workspaceFile = path.join(desktop.isolation.outputRoot, `${name}.code-workspace`);
    const content = `Existing user ${conflict} content must remain byte-for-byte intact.\n`;
    const sentinel = conflict === 'root' ? path.join(root, 'user-sentinel.txt') : workspaceFile;
    if (conflict === 'root') await mkdir(root, { mode: 0o700 });
    await writeFile(sentinel, content, { flag: 'wx', mode: 0o600 });
    await fillWorkspace(desktop, name, ['conflict-alpha']);
    await submitWorkspace(desktop);
    const operation = desktop.page.getByRole('dialog', { name: 'Creating workspace', exact: true });
    await expect(operation.getByRole('alert')).toContainText(conflict === 'root' ? 'WORKSPACE_ROOT_EXISTS' : 'WORKSPACE_FILE_EXISTS');
    expect(await readFile(sentinel, 'utf8')).toBe(content);
    expect(await desktop.state()).toEqual(before);
    expect(await readdir(desktop.isolation.workspaceRoot)).toEqual(conflict === 'root' ? [name] : []);
    expect(await readdir(desktop.isolation.outputRoot)).toEqual(conflict === 'workspace-file' ? [`${name}.code-workspace`] : []);
    if (conflict === 'root') expect(await readdir(root)).toEqual(['user-sentinel.txt']);
    else await expect(lstat(root)).rejects.toMatchObject({ code: 'ENOENT' });
    await operation.getByRole('button', { name: 'Close', exact: true }).click();
    await desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true }).getByRole('button', { name: 'Cancel', exact: true }).click();
  });
}

test('D06 V a real origin without its configured default branch leaves no index, published artifacts, or staging', async ({ desktop }) => {
  await addRepository(desktop, 'missing-default-alpha');
  const bare = path.join(desktop.isolation.originRoot, 'missing-default-alpha.git');
  await git(desktop, bare, ['branch', '-m', 'main', 'trunk']);
  expect(await git(desktop, bare, ['for-each-ref', '--format=%(refname)'])).toBe('refs/heads/trunk');
  expect(await git(desktop, bare, ['symbolic-ref', 'HEAD'])).toBe('refs/heads/trunk');
  const refsBefore = await git(desktop, bare, ['show-ref']);
  const before = await desktop.state();
  const sentinel = path.join(desktop.isolation.workspaceRoot, 'user-sentinel.txt');
  await writeFile(sentinel, 'Unrelated user work survives a branch failure.\n', { flag: 'wx' });
  const root = await fillWorkspace(desktop, 'missing-default', ['missing-default-alpha']);
  await submitWorkspace(desktop);
  const operation = desktop.page.getByRole('dialog', { name: 'Creating workspace', exact: true });
  await expect(operation.getByRole('alert')).toContainText('DEFAULT_BRANCH_NOT_FOUND');
  await expect(operation.getByRole('alert')).toContainText('The default branch could not be found.');
  await expect(operation.getByRole('alert')).toContainText(/Stage:\s*Switching branch…/u);
  await expect(lstat(root)).rejects.toMatchObject({ code: 'ENOENT' });
  await expect(lstat(path.join(desktop.isolation.outputRoot, 'missing-default.code-workspace'))).rejects.toMatchObject({ code: 'ENOENT' });
  expect(await readdir(desktop.isolation.workspaceRoot)).toEqual(['user-sentinel.txt']);
  expect(await readdir(desktop.isolation.outputRoot)).toEqual([]);
  expect(await desktop.state()).toEqual(before);
  expect(await readFile(sentinel, 'utf8')).toBe('Unrelated user work survives a branch failure.\n');
  expect(await git(desktop, bare, ['show-ref'])).toBe(refsBefore);
  await operation.getByRole('button', { name: 'Close', exact: true }).click();
  await desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true }).getByRole('button', { name: 'Cancel', exact: true }).click();
});

const missingCases = [
  {
    artifact: 'workspace-file',
    english: 'Missing: .code-workspace file.',
    chinese: '缺失：.code-workspace 文件。',
    failureCode: 'WORKSPACE_FILE_WRITE_FAILED',
  },
  {
    artifact: 'manifest',
    english: 'Missing: Workspace manifest.',
    chinese: '缺失：工作区清单。',
    failureCode: 'MANIFEST_READ_FAILED',
  },
  {
    artifact: 'workspace-root',
    english: 'Missing: Code folder and Workspace manifest.',
    chinese: '缺失：代码目录和工作区清单。',
    failureCode: 'WORKSPACE_PATH_MISSING',
  },
] as const;

for (const missing of missingCases) {
  test(`D05 D07 V missing ${missing.artifact} names exact artifacts in both locales, recovers Error through Sync, and forgets only the index`, async ({ desktop }) => {
    await addRepository(desktop, 'recovery-alpha');
    const name = `recover-${missing.artifact}`;
    const root = await createWorkspace(desktop, name, ['recovery-alpha']);
    const workspaceFile = path.join(desktop.isolation.outputRoot, `${name}.code-workspace`);
    const originalArtifacts = await artifacts(root, workspaceFile);
    const originalRepositories = (await desktop.state()).repositories;
    const repository = path.join(root, 'recovery-alpha');
    const head = await git(desktop, repository, ['rev-parse', 'HEAD']);
    const userFile = path.join(repository, 'user-notes.txt');
    const userContent = 'Recovery and forgetting must preserve this user file.\n';
    await writeFile(userFile, userContent, { flag: 'wx' });
    const target = missing.artifact === 'workspace-root' ? root
      : missing.artifact === 'manifest' ? path.join(root, '.reqws/workspace.json') : workspaceFile;
    const moved = path.join(desktop.isolation.root, `saved-${missing.artifact}`);
    await rename(target, moved);
    await expect(lstat(target)).rejects.toMatchObject({ code: 'ENOENT' });
    await desktop.page.locator('header').getByRole('button', { name: 'Refresh', exact: true }).click();
    const row = desktop.page.getByRole('row').filter({ has: desktop.page.getByText(name, { exact: true }) });
    await expect(row).toContainText('Missing');
    let drawer = await openDetails(desktop, name);
    await expect(drawer.getByText(missing.english, { exact: true })).toBeVisible();
    await closeDrawer(drawer);

    await saveLocale(desktop, 'zh-CN');
    await showWorkspaces(desktop);
    drawer = await openDetails(desktop, name, 'zh-CN');
    await expect(drawer.getByText(missing.chinese, { exact: true })).toBeVisible();
    await expect(drawer.getByText(missing.english, { exact: true })).toHaveCount(0);
    await closeDrawer(drawer);
    await saveLocale(desktop, 'en-US');
    await showWorkspaces(desktop);
    drawer = await openDetails(desktop, name);
    // Missing workspace files are normally repairable immediately. Fail only
    // their final OS write once to exercise a real persisted Error recovery.
    if (missing.artifact === 'workspace-file') await desktop.control({ failWrite: 'workspace' });
    await drawer.getByRole('button', { name: 'Regenerate workspace file', exact: true }).click();
    const failed = desktop.page.getByRole('dialog', { name: 'Syncing workspace', exact: true });
    await expect(failed.getByRole('alert')).toContainText(missing.failureCode);
    await failed.getByRole('button', { name: 'Close', exact: true }).click();
    await closeDrawer(drawer);
    await expect.poll(async () => (await desktop.state()).workspaces.find((workspace) => workspace.name === name)?.status).toBe('error');
    if (missing.artifact === 'workspace-file') {
      expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.failedWrites)).toEqual([workspaceFile]);
    }

    await rename(moved, target);
    await desktop.page.locator('header').getByRole('button', { name: 'Refresh', exact: true }).click();
    await expect(row).toContainText('Error');
    drawer = await openDetails(desktop, name);
    await expect(drawer.getByRole('alert')).toContainText(missing.failureCode);
    await drawer.getByRole('button', { name: 'Regenerate workspace file', exact: true }).click();
    await closeOperation(desktop, 'Syncing workspace');
    await expect(drawer.locator('.status')).toHaveText('Ready');
    await expect(drawer.getByRole('alert')).toHaveCount(0);
    const recovered = (await desktop.state()).workspaces.find((workspace) => workspace.name === name)!;
    expect(recovered.status).toBe('ready');
    expect(recovered.lastError).toBeUndefined();
    expect(recovered.missingArtifacts).toBeUndefined();
    expect(await artifacts(root, workspaceFile)).toEqual(originalArtifacts);
    expect(await readFile(userFile, 'utf8')).toBe(userContent);

    await drawer.getByText('Manage workspace', { exact: true }).click();
    await drawer.getByRole('button', { name: 'Remove workspace record', exact: true }).click();
    const confirmation = desktop.page.getByRole('dialog', { name: `Remove workspace record “${name}”?`, exact: true });
    await expect(confirmation).toContainText('The code folder, workspace manifest, and .code-workspace file are preserved.');
    await confirmation.getByRole('button', { name: 'Remove workspace record', exact: true }).click();
    await expect(confirmation).toBeHidden();
    await expect(row).toHaveCount(0);
    const forgotten = await desktop.state();
    expect(forgotten.workspaces).toEqual([]);
    expect(forgotten.repositories).toEqual(originalRepositories);
    expect(await artifacts(root, workspaceFile)).toEqual(originalArtifacts);
    expect((await lstat(path.join(repository, '.git'))).isDirectory()).toBe(true);
    expect(await git(desktop, repository, ['rev-parse', 'HEAD'])).toBe(head);
    expect(await readFile(path.join(repository, 'README.txt'), 'utf8')).toBe('ReqWS Git fixture: recovery-alpha\n');
    expect(await readFile(userFile, 'utf8')).toBe(userContent);
    if (missing.artifact === 'workspace-file') {
      // After forgetting the workspace, deleting its catalog record must also
      // leave the already cloned repositories and all recovery artifacts intact.
      await navigate(desktop, 'Repositories');
      await desktop.page.getByRole('button', { name: 'Edit recovery-alpha', exact: true }).click();
      const edit = desktop.page.getByRole('dialog', { name: 'Edit repository', exact: true });
      await edit.getByRole('button', { name: 'Delete repository record', exact: true }).click();
      const confirmation = desktop.page.getByRole('dialog', { name: 'Delete repository record “recovery-alpha”?', exact: true });
      await confirmation.getByRole('button', { name: 'Delete repository record', exact: true }).click();
      await expect(confirmation).toBeHidden();
      await expect.poll(async () => (await desktop.state()).repositories).toEqual([]);
      expect(await artifacts(root, workspaceFile)).toEqual(originalArtifacts);
      expect((await lstat(path.join(repository, '.git'))).isDirectory()).toBe(true);
      expect(await git(desktop, repository, ['rev-parse', 'HEAD'])).toBe(head);
      expect(await readFile(userFile, 'utf8')).toBe(userContent);
    }
  });
}

test('D02 D04 V invalid and deleted directory defaults warn per field and recover through one-time folder choices', async ({ desktop }) => {
  await addRepository(desktop, 'stale-alpha');
  const initial = await desktop.state();
  const notDirectory = path.join(desktop.isolation.root, 'ordinary-file.txt');
  await writeFile(notDirectory, 'This is not a directory.\n', { flag: 'wx' });
  await navigate(desktop, 'Settings');
  await desktop.control({ directories: [notDirectory] });
  await desktop.page.getByRole('button', { name: 'Choose a folder for Default workspace parent folder', exact: true }).click();
  await desktop.page.getByRole('button', { name: 'Save settings', exact: true }).click();
  await expect(desktop.page.getByRole('alert')).toContainText('SETTINGS_DIRECTORY_NOT_DIRECTORY');
  await expect(desktop.page.getByRole('alert')).toContainText('The selected path is not a folder.');
  expect(await desktop.state()).toEqual(initial);

  const staleParent = path.join(desktop.isolation.root, 'deleted-parent');
  const staleFiles = path.join(desktop.isolation.root, 'deleted-files');
  await mkdir(staleParent, { mode: 0o700 });
  await mkdir(staleFiles, { mode: 0o700 });
  await desktop.control({ directories: [staleParent, staleFiles] });
  await desktop.page.getByRole('button', { name: 'Choose a folder for Default workspace parent folder', exact: true }).click();
  await desktop.page.getByRole('button', { name: 'Choose a folder for .code-workspace file folder', exact: true }).click();
  await desktop.page.getByRole('button', { name: 'Save settings', exact: true }).click();
  const settings = { localePreference: 'system', workspaceParentDirectory: staleParent, workspaceFileDirectory: staleFiles };
  await expect.poll(async () => (await desktop.state()).settings).toEqual(settings);
  await expect(desktop.page.getByRole('alert')).toHaveCount(0);
  await rmdir(staleParent);
  await rmdir(staleFiles);
  await desktop.restart();
  await navigate(desktop, 'Settings');
  for (const label of ['Default workspace parent folder', '.code-workspace file folder']) {
    await expect(desktop.page.getByLabel(label, { exact: true })).toHaveValue('');
    await expect(desktop.page.getByLabel(label, { exact: true })).toHaveAttribute('aria-invalid', 'true');
  }
  await expect(desktop.page.getByRole('status').filter({ hasText: unavailableDirectory })).toHaveCount(2);
  expect((await desktop.state()).settings).toEqual(settings);

  await navigate(desktop, 'Workspaces');
  await desktop.page.locator('header').getByRole('button', { name: 'Create workspace', exact: true }).click();
  const create = desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true });
  await create.getByLabel('Workspace name', { exact: true }).fill('stale-recovered');
  await create.getByLabel('Feature branch', { exact: true }).fill('feat/e2e');
  for (const label of ['Workspace code folder', '.code-workspace file folder']) {
    await expect(create.getByLabel(label, { exact: true })).toHaveValue('');
    await expect(create.getByLabel(label, { exact: true })).toHaveAttribute('aria-invalid', 'true');
  }
  await expect(create.getByRole('status').filter({ hasText: unavailableDirectory })).toHaveCount(2);
  await desktop.control({ directories: [desktop.isolation.workspaceRoot, desktop.isolation.outputRoot] });
  await pickCreationDirectory(create, 'Workspace code folder');
  await expect(create.getByLabel('Workspace code folder', { exact: true })).not.toHaveAttribute('aria-invalid', 'true');
  await expect(create.getByRole('status').filter({ hasText: unavailableDirectory })).toHaveCount(1);
  await pickCreationDirectory(create, '.code-workspace file folder');
  await expect(create.getByLabel('.code-workspace file folder', { exact: true })).not.toHaveAttribute('aria-invalid', 'true');
  await expect(create.getByRole('status').filter({ hasText: unavailableDirectory })).toHaveCount(0);
  await create.getByRole('checkbox', { name: /^stale-alpha\s/u }).check();
  await submitWorkspace(desktop);
  await closeOperation(desktop, 'Creating workspace');
  const root = path.join(desktop.isolation.workspaceRoot, 'stale-recovered');
  const workspaceFile = path.join(desktop.isolation.outputRoot, 'stale-recovered.code-workspace');
  expect(JSON.parse((await artifacts(root, workspaceFile)).manifest)).toMatchObject({ rootPath: root, workspaceFilePath: workspaceFile });
  expect((await lstat(path.join(root, 'stale-alpha/.git'))).isDirectory()).toBe(true);
  const stored = await desktop.state();
  expect(stored.settings).toEqual(settings);
  expect(stored.repositories).toEqual(initial.repositories);
  expect(stored.workspaces).toMatchObject([{ name: 'stale-recovered', status: 'ready' }]);
  await desktop.page.locator('header').getByRole('button', { name: 'Create workspace', exact: true }).click();
  const reopened = desktop.page.getByRole('dialog', { name: 'Create workspace', exact: true });
  await expect(reopened.getByRole('status').filter({ hasText: unavailableDirectory })).toHaveCount(2);
  await reopened.getByRole('button', { name: 'Cancel', exact: true }).click();
});

test('D03 D05 V list refresh and detail loading failures show stable codes and localized Toasts in both languages', async ({ desktop }) => {
  await addRepository(desktop, 'toast-alpha');
  const name = 'toast-business';
  const root = await createWorkspace(desktop, name, ['toast-alpha']);
  const workspaceFile = path.join(desktop.isolation.outputRoot, `${name}.code-workspace`);
  const originalArtifacts = await artifacts(root, workspaceFile);
  const manifestPath = path.join(root, '.reqws/workspace.json');
  const messages = [
    { locale: 'en-US', state: 'The application data file is corrupt.', manifest: 'The workspace manifest could not be read.' },
    { locale: 'zh-CN', state: '应用数据文件已损坏。', manifest: '无法读取工作区清单。' },
  ] as const;
  for (const message of messages) {
    if (message.locale === 'zh-CN') await saveLocale(desktop, message.locale);
    await showWorkspaces(desktop);
    const originalState = await readFile(statePath(desktop), 'utf8');
    const corrupt = '{ invalid isolated state fixture\n';
    await writeFile(statePath(desktop), corrupt);
    try {
      await desktop.page.locator('header').getByRole('button', { name: message.locale === 'en-US' ? 'Refresh' : '刷新', exact: true }).click();
      const toast = desktop.page.getByRole('status').filter({ hasText: 'STATE_CORRUPT' });
      await expect(toast).toContainText('STATE_CORRUPT');
      await expect(toast).toContainText(message.state);
      await toast.getByRole('button', { name: /^(Dismiss notification|关闭通知)$/u }).click();
      await expect(toast).toHaveCount(0);
      const backups = (await readdir(path.dirname(statePath(desktop)))).filter((filename) => filename.startsWith('state.v1.json.corrupt-'));
      expect(backups.length).toBeGreaterThan(0);
      for (const backup of backups) expect(await readFile(path.join(path.dirname(statePath(desktop)), backup), 'utf8')).toBe(corrupt);
    } finally {
      await writeFile(statePath(desktop), originalState);
    }
    await desktop.page.locator('header').getByRole('button', { name: message.locale === 'en-US' ? 'Refresh' : '刷新', exact: true }).click();
    const restored = await openDetails(desktop, name, message.locale);
    await expect(restored.locator('.status')).toHaveText(message.locale === 'en-US' ? 'Ready' : '就绪');
    await closeDrawer(restored);

    // Keep valid JSON/schema but break the actual manifest/index binding.
    await writeFile(manifestPath, `${JSON.stringify({ ...JSON.parse(originalArtifacts.manifest), name: 'mismatched-name' }, null, 2)}\n`);
    try {
      await desktop.page.getByRole('button', {
        name: message.locale === 'en-US' ? `View details for ${name}` : `查看 ${name} 的详情`, exact: true,
      }).click();
      const toast = desktop.page.getByRole('status').filter({ hasText: 'MANIFEST_READ_FAILED' });
      await expect(toast).toContainText('MANIFEST_READ_FAILED');
      await expect(toast).toContainText(message.manifest);
      await expect(desktop.page.getByRole('dialog', { name, exact: true })).toHaveCount(0);
      await toast.getByRole('button', { name: /^(Dismiss notification|关闭通知)$/u }).click();
      await expect(toast).toHaveCount(0);
    } finally {
      await writeFile(manifestPath, originalArtifacts.manifest);
    }
    const recovered = await openDetails(desktop, name, message.locale);
    await expect(recovered.locator('.status')).toHaveText(message.locale === 'en-US' ? 'Ready' : '就绪');
    await closeDrawer(recovered);
    expect(await readFile(statePath(desktop), 'utf8')).toBe(originalState);
    expect(await artifacts(root, workspaceFile)).toEqual(originalArtifacts);
  }
});

test('D02 V one-shot settings publication failures preserve saved state and expose localized diagnostic copy content', async ({ desktop }) => {
  await addRepository(desktop, 'diagnostic-alpha');
  await navigate(desktop, 'Settings');
  const copied: string[] = [];
  // Capture only the final clipboard OS boundary. Do not read or overwrite the
  // user's system clipboard, and do not replace the preload or settings service.
  await desktop.page.exposeFunction('recordCopiedError', (text: string) => { copied.push(text); });
  await desktop.page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: (text: string) => (window as unknown as {
          recordCopiedError(value: string): Promise<void>;
        }).recordCopiedError(text),
      },
    });
  });
  const failures = [
    {
      preference: 'zh-CN', language: 'Interface language', save: 'Save settings',
      message: 'Settings could not be saved.', details: 'Technical details', copy: 'Copy error log',
      technical: 'Technical information: Unable to write global settings.',
    },
    {
      preference: 'en-US', language: '界面语言', save: '保存设置',
      message: '无法保存设置。', details: '技术详情', copy: '复制错误日志',
      technical: '技术信息: Unable to write global settings.',
    },
  ] as const;
  for (const [index, failure] of failures.entries()) {
    const original = await readFile(statePath(desktop), 'utf8');
    await desktop.page.getByLabel(failure.language, { exact: true }).selectOption(failure.preference);
    await desktop.control({ failWrite: 'state' });
    await desktop.page.getByRole('button', { name: failure.save, exact: true }).click();
    const alert = desktop.page.getByRole('alert');
    await expect(alert).toContainText('SETTINGS_WRITE_FAILED');
    await expect(alert).toContainText(failure.message);
    await expect(alert.getByText('Unable to write JSON data.', { exact: true })).toBeHidden();
    await alert.getByText(failure.details, { exact: true }).click();
    await expect(alert.getByText('Unable to write JSON data.', { exact: true })).toBeVisible();
    await alert.getByRole('button', { name: failure.copy, exact: true }).click();
    await expect.poll(() => copied.length).toBe(index + 1);
    expect(copied[index]).toContain(`[SETTINGS_WRITE_FAILED] ${failure.message}`);
    expect(copied[index]).toContain(failure.technical);
    expect(copied[index]).toContain('Unable to write JSON data.');
    // Current settings-save errors omit stage; this does not claim stage coverage.
    expect(await readFile(statePath(desktop), 'utf8')).toBe(original);
    expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.failedWrites)).toEqual(Array.from({ length: index + 1 }, () => statePath(desktop)));
    await expect(desktop.page.getByLabel(failure.language, { exact: true })).toHaveValue(failure.preference);
    await desktop.page.getByRole('button', { name: failure.save, exact: true }).click();
    await expect(desktop.page.locator('html')).toHaveAttribute('lang', failure.preference);
    await expect.poll(async () => (await desktop.state()).settings.localePreference).toBe(failure.preference);
    await expect(desktop.page.getByRole('alert')).toHaveCount(0);
  }
  await expectPrivateState(desktop);
});
