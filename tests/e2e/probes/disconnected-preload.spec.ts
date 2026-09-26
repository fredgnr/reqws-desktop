import { test } from '@playwright/test';
import { cp, rename } from 'node:fs/promises';
import path from 'node:path';
import { Desktop } from '../fixtures/desktop';
import { createIsolation } from '../fixtures/isolation';
import { createGitOrigin } from '../fixtures/git-origin';

// Intentionally fails. Run only via test:e2e:negative, which verifies both the
// nonzero result and complete failure evidence. It is never an expected-failure
// annotation in the normal suite.
// eslint-disable-next-line no-empty-pattern
test('S0 disconnected real preload must fail readiness', async ({}, info) => {
  const isolation = await createIsolation();
  const origin = await createGitOrigin(isolation);
  const entry = path.join(isolation.root, 'broken-app');
  const desktop = new Desktop(isolation, origin, info, entry);
  try {
    await cp(path.resolve('.vite/e2e'), entry, { recursive: true });
    await rename(path.join(entry, 'build/preload.js'), path.join(entry, 'build/disconnected.js'));
    await desktop.start();
  } finally {
    await desktop.finish();
  }
});
