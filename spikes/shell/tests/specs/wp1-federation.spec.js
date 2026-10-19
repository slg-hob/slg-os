import { test, expect } from '@playwright/test';
import { trackCsp, cspViolations } from './helpers.js';

// Collects CSP violations (WP1.6) and console errors during a page session.
function watch(page) {
  const violations = [];
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));
  return { violations, errors };
}

test('E1: two compliant remotes load at runtime from registry data', async ({ page }) => {
  const w = watch(page);
  await trackCsp(page);
  await page.goto('/');
  await expect(page.getByTestId('open-a')).toBeVisible();
  await page.getByTestId('open-a').click();
  await expect(page.getByTestId('mod-a')).toBeVisible();
  await page.getByTestId('open-b').click();
  await expect(page.getByTestId('mod-b')).toBeVisible();
  expect(w.errors).toEqual([]);
  expect(await cspViolations(page)).toEqual([]);
});

test('WP1.8: theme and language switch live without reload and keep module state', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('open-a').click();
  await page.locator('#a-input').fill('unsaved text');
  await page.getByTestId('toggle-theme').click();
  await expect(page.locator('html')).toHaveAttribute('data-bs-theme', 'dark');
  await expect(page.getByTestId('a-theme')).toHaveText('dark');
  await page.getByTestId('toggle-lang').click();
  await expect(page.getByTestId('a-lang')).toHaveText('de');
  await expect(page.getByTestId('mod-a').getByRole('heading', { name: 'Modul A' })).toBeVisible();
  await expect(page.locator('#a-input')).toHaveValue('unsaved text');
});
