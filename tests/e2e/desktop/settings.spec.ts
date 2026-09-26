import { test, expect } from '../fixtures/desktop';
import { navigate } from '../fixtures/ui';

test('D02 settings and language survive a cold process restart @smoke', async ({ desktop }) => {
  const { page, isolation } = desktop;
  await navigate(desktop, 'Settings');
  await desktop.control({ directories: [null, isolation.workspaceRoot, isolation.outputRoot] });
  const chooseParent = page.getByRole('button', { name: 'Choose a folder for Default workspace parent folder', exact: true });
  await chooseParent.click();
  await expect(page.getByLabel('Default workspace parent folder', { exact: true })).toHaveValue('');
  await chooseParent.click();
  await expect(page.getByLabel('Default workspace parent folder', { exact: true })).toHaveValue(isolation.workspaceRoot);
  await page.getByRole('button', { name: 'Choose a folder for .code-workspace file folder', exact: true }).click();
  expect(await desktop.app.evaluate(() => globalThis.__reqwsE2E.dialogCalls)).toEqual([
    expect.objectContaining({ properties: ['openDirectory', 'createDirectory'] }),
    expect.objectContaining({ properties: ['openDirectory', 'createDirectory'] }),
    expect.objectContaining({ properties: ['openDirectory', 'createDirectory'] }),
  ]);
  await page.getByLabel('Interface language', { exact: true }).selectOption('zh-CN');
  await page.getByRole('button', { name: 'Save settings', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: '设置' })).toBeVisible();
  expect((await desktop.state()).settings).toMatchObject({
    localePreference: 'zh-CN', workspaceParentDirectory: isolation.workspaceRoot,
    workspaceFileDirectory: isolation.outputRoot,
  });
  const originalPid = desktop.app.process().pid;
  await desktop.restart();
  expect(desktop.app.process().pid).not.toBe(originalPid);
  await desktop.page.getByRole('navigation').getByRole('button', { name: '设置', exact: true }).click();
  await expect(desktop.page.getByRole('combobox')).toHaveValue('zh-CN');
  await desktop.page.getByRole('combobox').selectOption('en-US');
  await desktop.page.getByRole('button', { name: '保存设置', exact: true }).click();
  await expect(desktop.page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();
  await expect(desktop.page.getByLabel('Default workspace parent folder', { exact: true })).toHaveValue(isolation.workspaceRoot);
  await expect(desktop.page.getByLabel('.code-workspace file folder', { exact: true })).toHaveValue(isolation.outputRoot);
  expect((await desktop.state()).settings.localePreference).toBe('en-US');
});
