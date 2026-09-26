import { test, expect } from '../fixtures/desktop';
import { addRepository, navigate } from '../fixtures/ui';

test('D03 repository CRUD rejects unsafe URLs and duplicate names through the real bridge @smoke', async ({ desktop }, info) => {
  const { page } = desktop;
  const url = await addRepository(desktop, 'catalog-alpha');
  const original = (await desktop.state()).repositories[0]!;
  await page.locator('header').getByRole('button', { name: 'Add repository', exact: true }).click();
  const create = page.getByRole('dialog', { name: 'Add repository', exact: true });
  await create.getByLabel('Git repository URL', { exact: true }).fill('file:///tmp/forbidden.git');
  await create.getByLabel('Name', { exact: true }).fill('catalog-invalid');
  await create.getByRole('button', { name: 'Add repository', exact: true }).click();
  await expect(create.getByRole('alert')).toContainText('INVALID_INPUT');
  expect((await desktop.state()).repositories).toEqual([original]);

  await create.getByLabel('Git repository URL', { exact: true }).fill(url);
  await create.getByLabel('Name', { exact: true }).fill('CATALOG-ALPHA');
  await create.getByRole('button', { name: 'Add repository', exact: true }).click();
  await expect(create.getByRole('alert')).toContainText('DUPLICATE_REPOSITORY_NAME');
  expect((await desktop.state()).repositories).toEqual([original]);
  await create.getByRole('button', { name: 'Cancel', exact: true }).click();

  const replacement = await desktop.origin.addRepository('catalog-replacement');
  await page.getByRole('button', { name: 'Edit catalog-alpha', exact: true }).click();
  const edit = page.getByRole('dialog', { name: 'Edit repository', exact: true });
  await expect.poll(() => edit.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath('repository-edit.png') });
  await edit.getByLabel('Name', { exact: true }).fill('catalog-renamed');
  await edit.getByLabel('Git repository URL', { exact: true }).fill(replacement.url);
  await edit.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(edit).toBeHidden();
  await expect.poll(async () => (await desktop.state()).repositories).toMatchObject([
    { id: original.id, name: 'catalog-renamed', url: replacement.url, defaultBranch: 'main' },
  ]);
  await expect(page.getByRole('row').filter({ has: page.getByText('catalog-renamed', { exact: true }) })).toContainText(replacement.url);
  await page.getByRole('button', { name: 'Edit catalog-renamed', exact: true }).click();
  await edit.getByRole('button', { name: 'Delete repository record', exact: true }).click();
  const confirmation = page.getByRole('dialog', { name: 'Delete repository record “catalog-renamed”?', exact: true });
  await expect(confirmation).toContainText('No local files or folders will be deleted.');
  await confirmation.getByRole('button', { name: 'Delete repository record', exact: true }).click();
  await expect(confirmation).toBeHidden();
  await expect.poll(async () => (await desktop.state()).repositories).toEqual([]);
  await expect(page.getByRole('heading', { name: 'No repositories yet', exact: true })).toBeVisible();
});

test('D03 real HTTPS connection fails on 503, remains saveable, and succeeds after recovery', async ({ desktop }, info) => {
  const { page } = desktop;
  const remote = await desktop.origin.addRepository('catalog-network');
  await navigate(desktop, 'Repositories');
  await page.locator('header').getByRole('button', { name: 'Add repository', exact: true }).click();
  const create = page.getByRole('dialog', { name: 'Add repository', exact: true });
  await create.getByLabel('Git repository URL', { exact: true }).fill(remote.url);
  await create.getByLabel('Name', { exact: true }).fill('catalog-network');
  desktop.origin.setUnavailable(true);
  try {
    await create.getByRole('button', { name: 'Test connection', exact: true }).click();
    const operation = page.getByRole('dialog', { name: 'Testing repository connection', exact: true });
    await expect(operation.getByRole('alert')).toContainText('REPOSITORY_UNREACHABLE');
    await operation.getByRole('button', { name: 'Close', exact: true }).click();
    await expect(create.getByRole('status')).toContainText('A failed connection test does not prevent saving.');
    await create.getByRole('button', { name: 'Add repository', exact: true }).click();
    await expect(create).toBeHidden();
    await expect.poll(async () => (await desktop.state()).repositories.map(({ name }) => name)).toEqual(['catalog-network']);
  } finally {
    desktop.origin.setUnavailable(false);
  }
  await page.getByRole('button', { name: 'Edit catalog-network', exact: true }).click();
  const edit = page.getByRole('dialog', { name: 'Edit repository', exact: true });
  await expect.poll(() => edit.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath('repository-edit.png') });
  await edit.getByRole('button', { name: 'Test connection', exact: true }).click();
  const operation = page.getByRole('dialog', { name: 'Testing repository connection', exact: true });
  await expect(operation.getByText('Complete', { exact: true })).toBeVisible();
  await operation.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(edit.getByRole('status')).toContainText('Successful');
  await expect(edit.getByRole('status')).toContainText('Remote default branch: main');
  await edit.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect((await desktop.state()).repositories).toHaveLength(1);
});
