import { test } from '@playwright/test';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

// WP1.10 (indicative, container hardware): sign-in to usable desktop and module window open, cold and warm,
// LAN-like throttling and an optional 4x CPU slowdown. Raw data goes to results/perf-<profile>.json.
const RUNS = 20;
const WARMUP = 3;
const p95 = (xs) => [...xs].sort((a, b) => a - b)[Math.max(0, Math.ceil(xs.length * 0.95) - 1)];
const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

for (const profile of ['lan', 'lan-cpu4x']) {
  test(`WP1.10 timing profile ${profile}`, async ({ browser }) => {
    test.setTimeout(900000);
    const raw = { profile, cold: { desktop: [], open: [] }, warm: { desktop: [], open: [] }, transfer: null };
    for (let i = 0; i < RUNS + WARMUP; i++) {
      const ctx = await browser.newContext({ storageState: '.auth/alice.json', ignoreHTTPSErrors: true });
      const page = await ctx.newPage();
      const cdp = await ctx.newCDPSession(page);
      await cdp.send('Network.enable');
      await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 5, downloadThroughput: (100 * 1e6) / 8, uploadThroughput: (100 * 1e6) / 8 });
      if (profile === 'lan-cpu4x') await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
      await cdp.send('Network.setCacheDisabled', { cacheDisabled: false });
      for (const phase of ['cold', 'warm']) {
        if (phase === 'cold') await cdp.send('Network.clearBrowserCache');
        const t0 = Date.now();
        await page.goto('/?wm=inhouse');
        await page.getByTestId('user').waitFor();
        const desktop = Date.now() - t0;
        const t1 = Date.now();
        await page.getByTestId('open-a').click();
        await page.getByTestId('mod-a').waitFor();
        const open = Date.now() - t1;
        if (i >= WARMUP) { raw[phase].desktop.push(desktop); raw[phase].open.push(open); }
        if (phase === 'cold' && i === WARMUP) {
          raw.transfer = await page.evaluate(() => {
            const r = performance.getEntriesByType('resource');
            return { files: r.length, encodedKB: Math.round(r.reduce((s, x) => s + x.encodedBodySize, 0) / 1024), transferKB: Math.round(r.reduce((s, x) => s + x.transferSize, 0) / 1024) };
          });
        }
        await page.evaluate(() => { sessionStorage.clear(); localStorage.removeItem('spike.wm.desktop'); });
      }
      await ctx.close();
    }
    const summary = Object.fromEntries(['cold', 'warm'].map((ph) => [ph, { desktopMedianMs: median(raw[ph].desktop), desktopP95Ms: p95(raw[ph].desktop), openMedianMs: median(raw[ph].open), openP95Ms: p95(raw[ph].open) }]));
    const dir = join(process.cwd(), '..', 'results');
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, `perf-${profile}.json`), JSON.stringify({ ...raw, summary, runs: RUNS, warmup: WARMUP, note: 'indicative: container hardware, headless Chromium, not NFR-6 evidence' }, null, 2));
    console.log('WP1.10', profile, JSON.stringify({ summary, transfer: raw.transfer }));
  });
}
