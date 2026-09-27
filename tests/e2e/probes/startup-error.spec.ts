import { test } from '@playwright/test';
import { cp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { Desktop } from '../fixtures/desktop';
import { createIsolation } from '../fixtures/isolation';
import { createGitOrigin } from '../fixtures/git-origin';

// eslint-disable-next-line no-empty-pattern
test('S0 an early renderer error must fail even when the UI becomes ready', async ({}, info) => {
  const isolation = await createIsolation();
  const origin = await createGitOrigin(isolation);
  const entry = path.join(isolation.root, 'early-error-app');
  const desktop = new Desktop(isolation, origin, info, entry);
  try {
    await cp(path.resolve('.vite/e2e'), entry, { recursive: true });
    const renderer = path.join(entry, 'renderer/main_window');
    await writeFile(path.join(renderer, 'startup-error.js'), "console.error('REQWS_E2E_STARTUP_ERROR_PROBE');\n");
    const index = path.join(renderer, 'index.html');
    await writeFile(index, (await readFile(index, 'utf8')).replace('</head>', '<script src="./startup-error.js"></script></head>'));
    await desktop.start();
  } finally { await desktop.finish(); }
});
