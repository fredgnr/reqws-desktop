import { mkdir, readdir } from 'node:fs/promises';
import path from 'node:path';

import { test, expect } from '../fixtures/desktop';
import { addRepository, createWorkspace, navigate } from '../fixtures/ui';

type SecurityProbe = typeof globalThis & {
  __reqwsNavigationProbe?: { url: string; prevented: boolean };
};

test('D10 unavailable, successful and failed editor launches use the real EditorLauncher OS boundary', async ({ desktop }) => {
  await addRepository(desktop, 'one');
  await createWorkspace(desktop, 'native-open', ['one']);
  const workspace = (await desktop.state()).workspaces.find((item) => item.name === 'native-open')!;
  const launch = desktop.page.getByRole('button', { name: 'VS Code', exact: true });
  await expect(launch).toBeDisabled();
  await expect(launch).toHaveAttribute('title', 'Visual Studio Code was not found');
  const unavailable = await desktop.page.evaluate(async (id) => {
    try { await window.reqws.editors.openVSCode(id); return null; }
    catch (error) { return error; }
  }, workspace.id);
  expect(unavailable).toMatchObject({ code: 'EDITOR_NOT_FOUND', stage: 'launching' });
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.launches)).toEqual([]);

  // Discovery data belongs to the disposable profile. No IDE is installed or run.
  await mkdir(path.join(desktop.isolation.home, 'Applications', 'Visual Studio Code.app'), { recursive: true, mode: 0o700 });
  await desktop.page.locator('header').getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(launch).toBeEnabled();
  await launch.click();
  await expect.poll(() => desktop.app.evaluate(() => globalThis.__reqwsE2E.launches.length)).toBe(1);
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.launches[0])).toEqual({
    command: '/usr/bin/open', args: ['-a', 'Visual Studio Code', workspace.workspaceFilePath], shell: false,
  });
  await expect(launch).toBeEnabled();
  await desktop.control({ launcherFails: true });
  await launch.click();
  await expect(desktop.page.getByRole('status').filter({ hasText: 'EDITOR_NOT_FOUND' })).toBeVisible();
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.launches)).toHaveLength(2);
  expect((await desktop.state()).workspaces.find((item) => item.id === workspace.id)).toEqual(workspace);
});

test('D11 navigation and popups are denied and malformed input is rejected through the real preload', async ({ desktop }) => {
  await addRepository(desktop, 'one');
  const before = await desktop.state();
  const originalUrl = desktop.page.url();
  await desktop.app.evaluate(({ BrowserWindow }) => {
    const window = BrowserWindow.getAllWindows()[0]!;
    delete (globalThis as SecurityProbe).__reqwsNavigationProbe;
    window.webContents.once('will-navigate', (event, url) => {
      (globalThis as SecurityProbe).__reqwsNavigationProbe = { url, prevented: event.defaultPrevented };
    });
  });
  await desktop.page.evaluate(() => window.location.assign('https://example.invalid/blocked-navigation'));
  await expect.poll(() => desktop.app.evaluate(() => (globalThis as SecurityProbe).__reqwsNavigationProbe)).toEqual({
    url: 'https://example.invalid/blocked-navigation', prevented: true,
  });
  expect(desktop.page.url()).toBe(originalUrl);
  expect(await desktop.page.evaluate(() => window.open('https://example.invalid/blocked-popup', '_blank') === null)).toBe(true);
  expect(await desktop.app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length)).toBe(1);
  expect(desktop.app.context().pages()).toHaveLength(1);
  const rejected = await desktop.page.evaluate(async () => {
    try {
      await window.reqws.repositories.create({ name: 'unsafe', url: '/outside/local-repository.git', defaultBranch: 'main' });
      return null;
    } catch (error) { return error; }
  });
  expect(rejected).toMatchObject({ code: 'INVALID_INPUT' });
  expect(await desktop.state()).toEqual(before);
  expect(await readdir(desktop.isolation.workspaceRoot)).toEqual([]);
  // Electron has already prevented the real navigation event above. Playwright
  // can keep locator navigation tracking pending after that denial; inspect the
  // unchanged document directly without reloading or weakening the assertion.
  const remainingWindow = await desktop.page.evaluate(() => {
    const navigation = document.querySelector('nav');
    const style = navigation ? window.getComputedStyle(navigation) : null;
    const bounds = navigation?.getBoundingClientRect();
    return {
      url: window.location.href,
      navigationVisible: Boolean(navigation && !navigation.hidden && style
        && style.display !== 'none' && style.visibility === 'visible'
        && bounds && bounds.width > 0 && bounds.height > 0),
      navigationLabels: navigation ? Array.from(navigation.querySelectorAll('button'), (button) => button.textContent?.trim()) : [],
      bridge: typeof window.reqws.repositories.create,
      require: typeof (window as unknown as { require?: unknown }).require,
      process: typeof (window as unknown as { process?: unknown }).process,
    };
  });
  expect(remainingWindow).toMatchObject({
    url: originalUrl, navigationVisible: true,
    bridge: 'function', require: 'undefined', process: 'undefined',
  });
  for (const label of ['Workspaces', 'Repositories', 'Settings']) {
    expect(remainingWindow.navigationLabels.some((text) => text?.startsWith(label))).toBe(true);
  }
});

test('D12 the real updater reports network failure, retries checking and publishes held download progress', async ({ desktop }) => {
  await navigate(desktop, 'Settings');
  await desktop.control({ updateFails: true });
  const check = desktop.page.getByRole('button', { name: 'Check for updates', exact: true });
  await check.click();
  await expect(desktop.page.getByRole('alert')).toHaveText('Could not check for updates. Check your network connection and try again.');
  const failed = await desktop.page.evaluate(() => window.reqws.updates.getState());
  expect(failed).toMatchObject({ phase: 'error', errorCode: 'UPDATE_CHECK_FAILED' });
  await desktop.control({ updateFails: false, holdDownload: true });
  await check.click();
  await expect(desktop.page.getByRole('status').filter({ hasText: 'A new version is available.' })).toBeVisible();
  const available = await desktop.page.evaluate(() => window.reqws.updates.getState());
  expect(available).toMatchObject({ phase: 'available', nextVersion: '99.0.0' });
  expect(available.revision).toBeGreaterThan(failed.revision);
  await desktop.page.getByRole('button', { name: 'Download update', exact: true }).click();
  try {
    await expect(desktop.page.getByRole('progressbar', { name: 'Download progress', exact: true })).toHaveAttribute('value', '50');
    await expect(check).toBeDisabled();
    const busy = await desktop.page.evaluate(async () => {
      try { await window.reqws.updates.check(); return null; }
      catch (error) { return error; }
    });
    expect(busy).toMatchObject({ code: 'UPDATE_BUSY' });
  } finally {
    await desktop.app.evaluate(() => globalThis.__reqwsE2E.finishDownload?.());
  }
  await expect(desktop.page.getByRole('button', { name: 'Install and restart', exact: true })).toBeEnabled();
  expect(await desktop.page.evaluate(() => window.reqws.updates.getState())).toMatchObject({ phase: 'downloaded', percent: 100, nextVersion: '99.0.0' });
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.installed)).toBe(false);
});

test('D12 a real settings write blocks UI update installation until the shared activity gate is free', async ({ desktop }) => {
  await navigate(desktop, 'Settings');
  await desktop.page.getByRole('button', { name: 'Check for updates', exact: true }).click();
  await desktop.page.getByRole('button', { name: 'Download update', exact: true }).click();
  const install = desktop.page.getByRole('button', { name: 'Install and restart', exact: true });
  await expect(install).toBeEnabled();
  await desktop.control({ directories: [desktop.isolation.workspaceRoot] });
  await desktop.page.getByRole('button', { name: 'Choose a folder for Default workspace parent folder', exact: true }).click();
  await expect(desktop.page.getByLabel('Default workspace parent folder', { exact: true })).toHaveValue(desktop.isolation.workspaceRoot);
  await desktop.control({ holdWrite: 'state' });
  await desktop.page.getByRole('button', { name: 'Save settings', exact: true }).click();
  try {
    await expect.poll(() => desktop.app.evaluate(() => globalThis.__reqwsE2E.writeHeld)).toBe(true);
    await install.click();
    await desktop.page.getByRole('dialog', { name: 'Install the update and restart?', exact: true })
      .getByRole('button', { name: 'Install and restart', exact: true }).click();
    await expect(desktop.page.getByRole('alert')).toContainText('UPDATE_BUSY');
    expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.installed)).toBe(false);
    expect(await desktop.page.evaluate(() => window.reqws.updates.getState())).toMatchObject({ phase: 'downloaded' });
  } finally {
    await desktop.app.evaluate(() => globalThis.__reqwsE2E.finishWrite?.());
  }
  await expect(desktop.page.getByRole('button', { name: 'Save settings', exact: true })).toBeDisabled();
  expect((await desktop.state()).settings.workspaceParentDirectory).toBe(desktop.isolation.workspaceRoot);
  await install.click();
  await desktop.page.getByRole('dialog', { name: 'Install the update and restart?', exact: true })
    .getByRole('button', { name: 'Install and restart', exact: true }).click();
  await expect.poll(() => desktop.app.evaluate(() => globalThis.__reqwsE2E.installed)).toBe(true);
  await expect(desktop.page.getByRole('status').filter({ hasText: 'Verifying and installing update…' })).toBeVisible();
});
