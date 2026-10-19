import { test, expect } from '@playwright/test';
import { trackCsp, cspViolations } from './helpers.js';

const openA = async (page) => {
  await page.goto('/');
  await page.getByTestId('open-b').click();
  await expect(page.getByTestId('mod-b')).toBeVisible();
};

test('WP1.4a: mismatched remote is rejected at registration with a clear message', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('rejected')).toContainText('mismatch: incompatible: module needs react ^18.0.0, shell provides 19');
  await expect(page.getByTestId('open-mismatch')).toHaveCount(0);
  await expect(page.getByTestId('open-a')).toBeVisible();
});

// Characterization tests (finding F-MF-1): with strictVersion the runtime outcome for a version-mismatched remote depends on
// load order. First remote loaded: fails closed with RUNTIME-015 (contained). After another remote initialised the shared scope:
// fails open (logs errors, renders with its own React copy). Runtime checks are therefore not a control; WP1.4a is mandatory.
test('WP1.4b: runtime strictVersion, mismatched remote loaded first: outcome is not reliable (closed or open)', async ({ page }) => {
  await page.goto('/?skipRequires=1');
  await page.getByTestId('open-mismatch').click();
  const win = page.getByTestId('window-mismatch');
  await expect(win.getByTestId('window-error').or(page.getByTestId('mod-mismatch'))).toBeVisible();
  const closed = (await win.getByTestId('window-error').count()) > 0;
  console.log('WP1.4b outcome when loaded first:', closed ? 'failed closed (RUNTIME-015)' : 'rendered with its own React copy (failed open)');
  await expect(page.getByTestId('toggle-theme')).toBeEnabled(); // either way the shell survives
});

test('WP1.4c: runtime strictVersion, mismatched remote loaded after another remote fails open', async ({ page }) => {
  const logs = [];
  page.on('console', (m) => logs.push(`${m.type()}: ${m.text()}`));
  await page.goto('/?skipRequires=1');
  await page.getByTestId('open-b').click();
  await expect(page.getByTestId('mod-b')).toBeVisible();
  await page.getByTestId('open-mismatch').click();
  await expect(page.getByTestId('mod-mismatch')).toBeVisible();
  expect(logs.some((l) => /Failed to bridge external shared module "react"/.test(l))).toBe(true);
});

test('WP1.7a: remote unreachable (network abort) is contained to its window', async ({ page }) => {
  await page.route('**/modules/a/remote/remoteEntry.js', (r) => r.abort());
  await page.goto('/');
  await page.getByTestId('open-b').click();
  await page.getByTestId('open-a').click();
  await expect(page.getByTestId('window-a').getByTestId('window-error')).toBeVisible();
  await expect(page.getByTestId('mod-b')).toBeVisible();
  await expect(page.getByTestId('toggle-theme')).toBeEnabled();
});

test('WP1.7b: remote returns 500', async ({ page }) => {
  await page.route('**/modules/a/remote/remoteEntry.js', (r) => r.fulfill({ status: 500, body: 'boom' }));
  await page.goto('/');
  await page.getByTestId('open-b').click();
  await page.getByTestId('open-a').click();
  await expect(page.getByTestId('window-a').getByTestId('window-error')).toBeVisible();
  await expect(page.getByTestId('mod-b')).toBeVisible();
});

test('WP1.7c: remote throws on render; other windows stay usable; retry remounts', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('open-b').click();
  await page.getByTestId('open-a').click();
  await expect(page.getByTestId('mod-a')).toBeVisible();
  await page.getByRole('button', { name: /Throw render error/ }).click();
  await expect(page.getByTestId('window-a').getByTestId('window-error')).toBeVisible();
  await expect(page.getByTestId('mod-b')).toBeVisible();
  await page.getByTestId('toggle-theme').click();
  await expect(page.locator('html')).toHaveAttribute('data-bs-theme', 'dark');
  await page.getByTestId('window-a').getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByTestId('mod-a')).toBeVisible();
});

test('WP1.7d: remote entry served with the wrong content (HTML instead of JS) is contained', async ({ page }) => {
  await page.route('**/modules/a/remote/remoteEntry.js', (r) => r.fulfill({ status: 200, contentType: 'text/html', body: '<html>not js</html>' }));
  await page.goto('/');
  await page.getByTestId('open-b').click();
  await page.getByTestId('open-a').click();
  await expect(page.getByTestId('window-a').getByTestId('window-error')).toBeVisible();
  await expect(page.getByTestId('mod-b')).toBeVisible();
});

test('WP1.2: registry entries outside /modules/<id>/remote/ are rejected', async ({ page }) => {
  const bad = (id, entry) => ({ id, name: { en: id, de: id }, version: '1', scope: `mod_${id}`, entry, requires: {} });
  await page.route('**/registry/modules.json', (r) =>
    r.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        modules: [
          bad('abs', 'https://evil.example/remoteEntry.js'),
          bad('up', '/modules/up/remote/../../b/remote/remoteEntry.js'),
          bad('enc', '/modules/enc/remote/%2e%2e/b/remoteEntry.js'),
          bad('dbl', '/modules/dbl/remote//evil.js'),
          bad('other', '/modules/b/remote/remoteEntry.js'),
        ],
      }),
    })
  );
  await page.goto('/');
  await expect(page.getByTestId('rejected')).toBeVisible();
  const text = await page.getByTestId('rejected').innerText();
  for (const id of ['abs', 'up', 'enc', 'dbl', 'other']) expect(text).toContain(`${id}:`);
  await expect(page.getByTestId('launcher').getByRole('button')).toHaveCount(0);
});
