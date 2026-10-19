import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

export const secret = (name) => readFileSync(join(process.cwd(), '..', '.run', 'secrets', name), 'utf8').trim();

// Signs in alice and bob once and stores only the Keycloak SSO cookies. Tokens are in memory only (Q11),
// so every test starts with no token and re-establishes the session through the SSO cookie.
export default async function globalSetup() {
  mkdirSync(join(process.cwd(), '.auth'), { recursive: true });
  for (const [user, pw] of [['alice', 'ALICE'], ['bob', 'BOB']]) {
    const browser = await chromium.launch();
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await ctx.newPage();
    await page.goto('https://localhost:8443/');
    await page.locator('#username').fill(user);
    await page.locator('#password').fill(secret(pw));
    await page.locator('#kc-login').click();
    await page.getByTestId('user').waitFor();
    await ctx.storageState({ path: join(process.cwd(), '.auth', `${user}.json`) });
    await browser.close();
  }
}
