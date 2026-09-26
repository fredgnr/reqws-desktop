import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e/desktop',
  testMatch: '**/*.spec.ts',
  globalSetup: './scripts/build-e2e.mts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  outputDir: 'test-results/desktop',
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/desktop-report.json' }],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
});
