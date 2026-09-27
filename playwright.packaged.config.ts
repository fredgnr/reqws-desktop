import { defineConfig } from '@playwright/test';

// No global build setup: this suite consumes macos-package's exact candidate.
export default defineConfig({
  testDir: './tests/e2e/packaged',
  testMatch: 'app.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  outputDir: 'test-results/packaged',
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/packaged-report.json' }],
  ],
});
