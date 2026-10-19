import { test, expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { trackCsp, cspViolations } from './helpers.js';

const fx = (f) => readFileSync(join(process.cwd(), '..', 'fixtures', 'integrity', f)); // tests run with cwd = spikes/shell/tests
const sri = (buf) => `sha384-${createHash('sha384').update(buf).digest('base64')}`;
const GOOD = { entry: sri(fx('entry.js')), chunk: sri(fx('chunk.js')), standalone: sri(fx('standalone.js')) };
const BAD = `sha384-${Buffer.alloc(48, 1).toString('base64')}`;
const BASE = '/fixtures-open/integrity';

const tamper = (page, file) =>
  page.route(new RegExp(`${BASE}/${file}(\\?.*)?$`), async (route) => {
    const res = await route.fetch();
    const body = (await res.text()) + '\n// tampered\n';
    await route.fulfill({ response: res, body });
  });

const log = (page) => page.evaluate(() => window.__log || []);

test.describe('WP1.5a browser SRI behaviour (Chromium only; Firefox not available in this environment)', () => {
  test('(i) <script type=module integrity>: correct hash loads, wrong hash blocks, tampered body blocks', async ({ page }) => {
    await page.goto(`${BASE}/blank.html`);
    const load = (integrity) => page.evaluate(([src, integrity]) => new Promise((res) => {
      const s = document.createElement('script');
      s.type = 'module'; s.src = src; s.integrity = integrity;
      s.onload = () => res('loaded'); s.onerror = () => res('blocked');
      document.head.append(s);
    }), [`${BASE}/standalone.js?${Math.random()}`, integrity]);
    const good = await load(GOOD.standalone);
    const bad = await load(BAD);
    await tamper(page, 'standalone.js');
    const tampered = await load(GOOD.standalone);
    console.log('WP1.5a(i):', JSON.stringify({ good, bad, tampered }));
    expect({ good, bad, tampered }).toEqual({ good: 'loaded', bad: 'blocked', tampered: 'blocked' });
  });

  test('(ii) <link rel=modulepreload integrity> followed by import(): does import() honour the preload hash?', async ({ page }) => {
    await page.goto(`${BASE}/blank.html`);
    const result = await page.evaluate(async ([url, integrity]) => {
      const l = document.createElement('link');
      l.rel = 'modulepreload'; l.href = url; l.integrity = integrity;
      const outcome = await new Promise((res) => { l.onload = () => res('preload-ok'); l.onerror = () => res('preload-blocked'); document.head.append(l); });
      let imp;
      try { await import(url); imp = 'import-ok'; } catch { imp = 'import-failed'; }
      return { outcome, imp };
    }, [`${BASE}/standalone.js`, BAD]);
    console.log('WP1.5a(ii) modulepreload with wrong hash then import():', JSON.stringify(result));
    expect(result.outcome).toBe('preload-blocked');
    // Recorded behaviour either way; the assertion documents what Chromium does today.
    console.log('WP1.5a(ii) log after import:', JSON.stringify(await log(page)));
  });

  test('(iii)+(iv) import map integrity covers dynamic import() and chunks imported by a verified entry', async ({ page }) => {
    await page.goto(`${BASE}/blank.html`);
    const supported = await page.evaluate(() => (HTMLScriptElement.supports ? HTMLScriptElement.supports('importmap') : false));
    console.log('WP1.5a(iii) importmap supported:', supported);
    // Import maps must be registered before the first module load of the affected URLs.
    const map = (integrity) => ({ imports: {}, integrity });
    await page.evaluate(([m]) => {
      const s = document.createElement('script');
      s.type = 'importmap'; s.textContent = JSON.stringify(m);
      document.head.append(s);
    }, [map({ [`${BASE}/entry.js`]: GOOD.entry, [`${BASE}/chunk.js`]: GOOD.chunk })]);
    await tamper(page, 'chunk.js');
    const outcome = await page.evaluate(async (url) => { try { await import(url); return 'import-ok'; } catch (e) { return `import-failed: ${String(e.message).slice(0, 80)}`; } }, `${BASE}/entry.js`);
    console.log('WP1.5a(iii/iv) tampered chunk, import map hashes both:', outcome, JSON.stringify(await log(page)));
    expect(outcome).toMatch(/^import-failed/);
  });

  test('(iv) entry verified only by script integrity: a tampered chunk is NOT detected', async ({ page }) => {
    await page.goto(`${BASE}/blank.html`);
    await tamper(page, 'chunk.js');
    const r = await page.evaluate(([src, integrity]) => new Promise((res) => {
      const s = document.createElement('script');
      s.type = 'module'; s.src = src; s.integrity = integrity;
      s.onload = () => res('loaded'); s.onerror = () => res('blocked');
      document.head.append(s);
    }), [`${BASE}/entry.js`, GOOD.entry]);
    expect(r).toBe('loaded');
    expect(await log(page)).toContain('chunk');
  });
});

test.describe('WP1.6 under the strict CSP', () => {
  test('inline import map is blocked by script-src self (dynamic import-map integrity needs a nonce/hash)', async ({ page }) => {
    await trackCsp(page);
    await page.goto('/fixtures/integrity/blank.html');
    await page.evaluate(() => {
      const s = document.createElement('script');
      s.type = 'importmap'; s.textContent = JSON.stringify({ imports: {} });
      document.head.append(s);
    });
    const v = await cspViolations(page);
    console.log('WP1.6 inline importmap violations:', JSON.stringify(v));
    expect(v.length).toBeGreaterThan(0);
  });
});
