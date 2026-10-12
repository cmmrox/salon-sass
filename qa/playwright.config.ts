import path from 'node:path';

import { defineConfig, devices } from '@playwright/test';

import { reportDir, webUrl } from './shared/env';

// Every invocation gets its own output folder inside the run's folder: Playwright empties the
// output folder when it starts, and one run invokes it once per automation entry. Workers inherit
// the variable, so they agree with the process that chose it.
process.env.QA_PLAYWRIGHT_OUTPUT ??= path.join(
  reportDir,
  'playwright',
  `${new Date().toISOString().replace(/[:.]/g, '-')}-${process.pid}`,
);

export default defineConfig({
  testDir: '.',
  outputDir: process.env.QA_PLAYWRIGHT_OUTPUT,
  globalSetup: './shared/global-setup.ts',
  // A test that passes on retry has failed.
  retries: 0,
  forbidOnly: true,
  // Some tests stop and start a service of the one local environment, so nothing runs beside them.
  workers: 1,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  use: {
    // A trace stores what was typed, so it stays off; a screenshot is kept only when a test fails.
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'api',
      testMatch: 'api/**/*.spec.ts',
    },
    {
      name: 'web',
      testMatch: 'web/**/*.spec.ts',
      use: { ...devices['Desktop Chrome'], baseURL: webUrl },
    },
  ],
});
