import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the compose stack (PRD-0003 SK-18). The stack uses the host names shell.slg.test and sso.slg.test;
 * they resolve through /etc/hosts and the generated test CA is trusted by the operating system and the browsers
 * (deploy/scripts/test-ca.sh, .github/workflows/ci.yml). `ignoreHTTPSErrors` is deliberately never set.
 *
 * Traces, videos and HAR files are off: they would contain the generated test password (SK-18 AC7 asks for masking, which
 * Playwright cannot do). Screenshots of failed tests show masked password fields only.
 */
export default defineConfig({
  testDir: './specs',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_SHELL_URL ?? 'https://shell.slg.test',
    locale: 'de-DE',
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'], launchOptions: { firefoxUserPrefs: { 'security.enterprise_roots.enabled': true } } },
    },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
});
