import { test, expect } from '@playwright/test';

// WP3.6 / E7: what can a compromised module do inside the shell origin? Each result is recorded; the test asserts
// the documented outcome so any change in behaviour (a new control or a regression) is noticed.
async function run(page, url) {
  await page.goto(url);
  await expect(page.getByTestId('user')).toHaveText('alice');
  await page.getByTestId('open-a').click();
  await page.locator('#a-input').fill('secret form text');
  await page.getByTestId('open-evil').click();
  await expect(page.getByTestId('evil-done')).toHaveText('done');
  // Module A calls its API while the hostile fetch wrapper is installed (cross-module token capture).
  await page.getByTestId('a-call-api').click();
  await expect(page.getByTestId('a-api-result')).toHaveText('200');
  const r = await page.evaluate(async () => {
    const o = window.__evil;
    return {
      ownToken: o.ownToken,
      offOrigin: o.offOrigin,
      sameOrigin: o.sameOrigin,
      reactTreeTokens: o.reactTreeTokens,
      globals: o.globals,
      cookie: o.cookie,
      storageKeys: o.storageKeys,
      otherWindowText: o.otherWindowText,
      serviceWorker: o.serviceWorker,
      capturedAuthHeaders: o.captured.filter((c) => c.auth).length,
      capturedRefreshGrants: o.captured.filter((c) => /refresh_token=/.test(c.body || '')).length,
      replay: await o.replay(),
    };
  });
  console.log('WP3.6 RESULT', JSON.stringify(r, null, 1));
  return r;
}

async function forceRefresh(page) {
  // A token grant by the shell (module token request) while the hostile module is active.
  await page.evaluate(() => window.__spike.mgr.signinSilent()); // library-driven refresh grant (as in automatic renewal)
  return page.evaluate(() => ({ params: window.__evil.paramsSeen.length, grants: window.__evil.captured.filter((c) => /refresh_token=/.test(c.body || '')).length, fetchWrap: window.__evil.fetchWrap }));
}

test('WP3.6 hostile remote demonstration (baseline: fetch not locked)', async ({ page }) => {
  const r = await run(page, '/?nolock');
  expect(r.reactTreeTokens).toEqual([]);                    // fixed in the shell: no token objects in React state
  expect(r.offOrigin).toMatch(/^blocked/);                 // CSP connect-src stops off-origin exfiltration
  expect(r.sameOrigin).toMatch(/^sent/);                    // same-origin exfiltration is possible (residual)
  expect(r.serviceWorker).toMatch(/^refused/);              // worker-src 'none'
  expect(r.otherWindowText).toBe('secret form text');       // other windows' DOM is readable (residual)
  expect(r.capturedAuthHeaders).toBeGreaterThan(0);         // fetch wrapping captures other modules' tokens (residual)
  expect(r.replay).toMatch(/200/);                          // ... and they work against that module's API (residual)
  expect(r.capturedRefreshGrants).toBeGreaterThan(0);       // refresh token visible in grant bodies (residual)
});

test('WP3.6 mitigation experiment: locked fetch stops wrapping but not prototype patching', async ({ page }) => {
  const r = await run(page, '/?debug=1');
  const m = await page.evaluate(() => ({ fetchWrap: window.__evil.fetchWrap, captured: window.__evil.captured.length }));
  console.log('WP3.6 LOCKED', JSON.stringify(m));
  expect(m.fetchWrap).toMatch(/^refused/);
  expect(m.captured).toBe(0);
  expect(r.replay).toBe('nothing captured');
  const after = await forceRefresh(page);
  console.log('WP3.6 LOCKED after grant', JSON.stringify(after));
  expect(after.params).toBeGreaterThan(0);                  // URLSearchParams patching still sees refresh_token
});
