import config from './playwright.config';
import { defineConfig } from '@playwright/test';

export default defineConfig({
  ...config,
  testDir: './tests/e2e/probes',
  outputDir: 'test-results/probes',
  reporter: [['list'], ['json', { outputFile: 'test-results/probe-report.json' }]],
});
