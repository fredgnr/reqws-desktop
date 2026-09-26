import { defineConfig } from '@playwright/test';
import path from 'node:path';

const root = process.env.REQWS_LOCAL_IDE_RUN_ROOT;
if (!root || !path.isAbsolute(root)) throw new Error('Use scripts/run_local_ide.py run --suite desktop.');

export default defineConfig({
  testDir: './tests/e2e/local-ide',
  testMatch: 'desktop-link.spec.ts',
  globalSetup: './scripts/build-e2e.mts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 3_600_000,
  expect: { timeout: 15_000 },
  outputDir: path.join(root, 'desktop-evidence'),
  reporter: [['list'], ['json', { outputFile: path.join(root, 'desktop-report.json') }]],
});
