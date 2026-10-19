import { test, expect } from '@playwright/test';
import { appendFileSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { trackCsp, cspViolations } from './helpers.js';

const ROOT = join(process.cwd(), '..');

async function integrityKeys(request, id) {
  const res = await request.get('/registry/modules.json');
  const { modules } = await res.json();
  return Object.keys(modules.find((m) => m.id === id).integrity);
}

// Simulates a changed file between registration and load (CDN/container/MITM): appends code to the response.
const tamperUrl = (page, path) =>
  page.route(`**${path}`, async (route) => {
    const res = await route.fetch();
    await route.fulfill({ response: res, body: (await res.text()) + '\nwindow.__pwned = true;\n' });
  });

test.describe('WP1.5b registry integrity + immutable store (real federation remotes)', () => {
  test('tampered chunk of a registered remote is refused and its code does not run', async ({ page, request }) => {
    await trackCsp(page);
    const keys = await integrityKeys(request, 'a');
    const chunk = keys.find((k) => /\/assets\/App-.*\.js$/.test(k));
    await tamperUrl(page, chunk);
    await page.goto('/');
    await page.getByTestId('open-a').click();
    await expect(page.getByTestId('window-a').getByTestId('window-error')).toBeVisible();
    expect(await page.evaluate(() => window.__pwned)).toBeUndefined();
    await expect(page.getByTestId('toggle-theme')).toBeEnabled();
  });

  test('tampered remoteEntry is refused', async ({ page }) => {
    await tamperUrl(page, '/modules/b/remote/remoteEntry.js');
    await page.goto('/');
    await page.getByTestId('open-b').click();
    await expect(page.getByTestId('window-b').getByTestId('window-error')).toBeVisible();
    expect(await page.evaluate(() => window.__pwned)).toBeUndefined();
  });

  test('untampered remote still loads under enforcing CSP with the nonce import map (no violations)', async ({ page }) => {
    await trackCsp(page);
    await page.goto('/');
    await page.getByTestId('open-a').click();
    await expect(page.getByTestId('mod-a')).toBeVisible();
    expect(await cspViolations(page)).toEqual([]);
  });

  test('TOCTOU: modifying the container build after registration does not change what is served', async ({ page }) => {
    const dist = join(ROOT, 'remote-b', 'dist', 'assets');
    const file = readdirSync(dist).find((f) => f.startsWith('App-') && f.endsWith('.js'));
    const original = readFileSync(join(dist, file));
    try {
      appendFileSync(join(dist, file), '\nwindow.__pwned = true;\n');
      await page.goto('/');
      await page.getByTestId('open-b').click();
      await expect(page.getByTestId('mod-b')).toBeVisible();
      expect(await page.evaluate(() => window.__pwned)).toBeUndefined();
    } finally {
      writeFileSync(join(dist, file), original);
    }
  });
});
