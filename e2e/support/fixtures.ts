import { test as base, expect, type BrowserContext, type BrowserContextOptions } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CA_FILE = process.env.E2E_CA_FILE ?? fileURLToPath(new URL('../../deploy/.generated/tls/ca.crt', import.meta.url));

/** Creates a Firefox profile whose certificate store trusts the per-run test CA (Firefox ignores the system store). */
function seedFirefoxProfile(directory: string): void {
  execFileSync('certutil', ['-d', `sql:${directory}`, '-N', '--empty-password']);
  execFileSync('certutil', ['-d', `sql:${directory}`, '-A', '-t', 'C,,', '-n', 'slg-test-ca', '-i', CA_FILE]);
}

type ContextFactory = (options?: BrowserContextOptions) => Promise<BrowserContext>;

/**
 * Every browser context of the suite comes from here, so each browser trusts the test CA without `ignoreHTTPSErrors`:
 * Chromium reads the NSS database of the user and WebKit the system store (set up by the CI job); Firefox gets a profile
 * with the CA imported.
 */
export const test = base.extend<{ newContext: ContextFactory }>({
  newContext: async ({ browser, browserName, playwright, baseURL, locale }, use) => {
    const created: { context: BrowserContext; directory?: string }[] = [];
    const factory: ContextFactory = async (options = {}) => {
      const merged: BrowserContextOptions = { baseURL, locale, ...options };
      if (browserName === 'firefox') {
        const directory = mkdtempSync(path.join(tmpdir(), 'slg-firefox-'));
        seedFirefoxProfile(directory);
        const context = await playwright.firefox.launchPersistentContext(directory, merged);
        created.push({ context, directory });
        return context;
      }
      const context = await browser.newContext(merged);
      created.push({ context });
      return context;
    };
    await use(factory);
    for (const { context, directory } of created) {
      await context.close();
      if (directory) rmSync(directory, { recursive: true, force: true });
    }
  },
  context: async ({ newContext }, use) => {
    await use(await newContext());
  },
});

export { expect };
