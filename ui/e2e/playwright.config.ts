import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end suite against a running Intent OMS (UI served by the backend). Run it with `make e2e` or
 * `scripts/e2e.sh`, which boot a fresh database with the e2e profile first. Locally only; CI does not run it.
 */
export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:8095';
/** Installed Google Chrome by default (no browser download needed); E2E_CHANNEL=chromium uses Playwright's own build
 * after `npx playwright install chromium`. */
const CHANNEL = process.env.E2E_CHANNEL ?? 'chrome';

export default defineConfig({
  testDir: './specs',
  outputDir: './.results',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { outputFolder: './report', open: 'never' }]],
  globalSetup: './support/global-setup.ts',
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    viewport: { width: 1440, height: 900 },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 }, ...(CHANNEL === 'chromium' ? {} : { channel: CHANNEL }) } }],
});
