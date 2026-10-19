import { test, expect } from '@playwright/test';
import { trackCsp, cspViolations } from './helpers.js';
import { secret } from '../global-setup.js';

const decode = (jwt) => JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString());

test.describe('WP3 auth thin slice (alice = finance, bob = ops)', () => {
  test('WP3.1 tokens are held in memory only (no token in storage or JS-readable cookies)', async ({ page }) => {
    await page.goto('/?debug=1');
    await expect(page.getByTestId('user')).toHaveText('alice');
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }) + document.cookie);
    expect(stored).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./); // no JWT anywhere
    expect(stored).not.toMatch(/access_token|refresh_token|id_token/);
  });

  test('WP3.2 reload and new tab re-establish the session without interaction; open windows are restored', async ({ page, context }) => {
    await trackCsp(page);
    await page.goto('/');
    await expect(page.getByTestId('user')).toHaveText('alice');
    await page.getByTestId('open-b').click();
    await expect(page.getByTestId('mod-b')).toBeVisible();
    await page.reload();
    await expect(page.getByTestId('user')).toHaveText('alice');
    await expect(page.getByTestId('mod-b')).toBeVisible();
    expect(await cspViolations(page)).toEqual([]);
    const tab2 = await context.newPage();
    await tab2.goto('/');
    await expect(tab2.getByTestId('user')).toHaveText('alice');
  });

  test('WP3.4/3.5 per-module audience token; finance allowed on mod-a, wrong audience and missing token refused', async ({ page }) => {
    await page.goto('/?debug=1');
    await page.getByTestId('open-a').click();
    await page.getByTestId('a-call-api').click();
    await expect(page.getByTestId('a-api-result')).toHaveText('200');
    const r = await page.evaluate(async () => {
      const { getAccessToken } = window.__spike;
      const base = await getAccessToken(); // the shell's own narrowed token
      const forB = await getAccessToken('mod-b');
      const call = async (path, token) => (await fetch(path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })).status;
      return {
        baseTokenOnA: await call('/api/stub/mod-a/data', base),
        modBTokenOnA: await call('/api/stub/mod-a/data', forB),
        noToken: await call('/api/stub/mod-a/data'),
        modBTokenOnB: await call('/api/stub/mod-b/data', forB), // alice is finance, not ops
        audB: JSON.parse(atob(forB.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).aud,
      };
    });
    console.log('WP3.5 results', JSON.stringify(r));
    expect(r.baseTokenOnA).toBe(401);
    expect(r.modBTokenOnA).toBe(401);
    expect(r.noToken).toBe(401);
    expect(r.modBTokenOnB).toBe(403);
    expect([r.audB].flat()).toEqual(['mod-b']);
  });

  test('WP3.4 ops user (bob) is refused on mod-a with 403', async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: '.auth/bob.json', ignoreHTTPSErrors: true });
    const page = await ctx.newPage();
    await page.goto('/');
    await expect(page.getByTestId('user')).toHaveText('bob');
    await page.getByTestId('open-a').click();
    await page.getByTestId('a-call-api').click();
    await expect(page.getByTestId('a-api-result')).toHaveText('403');
    await ctx.close();
  });

  test('WP3.3 silent renewal with rotating refresh token before expiry', async ({ page }) => {
    await page.clock.install();
    await page.goto('/?debug=1');
    await expect(page.getByTestId('user')).toHaveText('alice');
    const before = await page.evaluate(async () => (await window.__spike.mgr.getUser()).access_token);
    const refresh = page.waitForRequest((r) => r.url().includes('/protocol/openid-connect/token') && /refresh_token/.test(r.postData() || ''));
    await page.clock.fastForward('03:10'); // access token lifetime 5 min, renew 2 min before expiry
    await refresh;
    await expect.poll(async () => page.evaluate(async () => (await window.__spike.mgr.getUser()).access_token)).not.toBe(before);
  });

  test('WP3.3 failed renewal keeps window state and shows a non-modal banner (UI-13)', async ({ page }) => {
    await page.clock.install();
    await page.goto('/?debug=1');
    await page.getByTestId('open-a').click();
    await page.locator('#a-input').fill('keep me');
    await page.route('**/protocol/openid-connect/token', (r) => r.abort());
    await page.clock.fastForward('06:00');
    await expect(page.getByTestId('session-expired')).toBeVisible();
    await expect(page.locator('#a-input')).toHaveValue('keep me');
  });

  test('WP3.7 two concurrent refresh requests with a rotating token: reuse detection outcome', async ({ page }) => {
    await page.goto('/?debug=1');
    await expect(page.getByTestId('user')).toHaveText('alice');
    const out = await page.evaluate(async () => {
      const user = await window.__spike.mgr.getUser();
      const meta = await window.__spike.mgr.metadataService.getMetadata();
      const body = () => new URLSearchParams({ grant_type: 'refresh_token', client_id: 'shell', refresh_token: user.refresh_token });
      const post = () => fetch(meta.token_endpoint, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body() }).then((r) => r.status);
      const [first, second] = await Promise.all([post(), post()]);
      const third = await post(); // old token again
      return { first, second, third };
    });
    console.log('WP3.7 concurrent refresh', JSON.stringify(out));
    expect(out.third).toBe(400); // rotated token is single use
  });

  test('WP3.2 sign-out ends the session: a reload requires login again (own login, so shared sessions stay intact)', async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] }, ignoreHTTPSErrors: true });
    const page = await ctx.newPage();
    await page.goto('/');
    await page.locator('#username').fill('alice');
    await page.locator('#password').fill(secret('ALICE'));
    await page.locator('#kc-login').click();
    await expect(page.getByTestId('user')).toHaveText('alice');
    await page.getByTestId('sign-out').click();
    await page.waitForLoadState('networkidle');
    await page.goto('/');
    await expect(page.locator('#username')).toBeVisible({ timeout: 15000 });
    await ctx.close();
  });
});
