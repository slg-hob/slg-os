import { test, expect } from '@playwright/test';
import { trackCsp, cspViolations } from './helpers.js';

test.describe('WP1.11 proxy hardening', () => {
  test('headers a module container must not control are stripped', async ({ request }) => {
    const res = await request.get('/modules/evil/remote/x.js');
    const h = res.headers();
    expect(h['set-cookie']).toBeUndefined();
    expect(h['content-security-policy']).toBeUndefined();
    expect(h['access-control-allow-origin']).toBeUndefined();
    expect(h['service-worker-allowed']).toBeUndefined();
    expect(h['x-content-type-options']).toBe('nosniff');
  });

  test('wrong MIME type is not executed as a module (nosniff)', async ({ page }) => {
    await page.goto('/fixtures/integrity/blank.html');
    const r = await page.evaluate(() => import('/modules/evil/remote/wrong-mime.js').then(() => 'ran', () => 'refused'));
    expect(r).toBe('refused');
    expect(await page.evaluate(() => window.__pwned)).toBeUndefined();
  });

  test('path traversal variants never return content outside the served roots', async ({ request }) => {
    const attempts = [
      '/modules/a/remote/%2e%2e/%2e%2e/%2e%2e/etc/passwd',
      '/modules/a/remote/..%2f..%2f..%2fetc/passwd',
      '/modules/a/remote/%252e%252e/%252e%252e/etc/passwd',
      '/modules/a//remote/../../../etc/passwd',
      '/registry/..%2f..%2f.certs/localhost.key',
      '/registry/%2e%2e/.certs/localhost.key',
      '/fixtures/..%2f..%2f.certs/localhost.key',
    ];
    for (const path of attempts) {
      const res = await request.get(path, { maxRedirects: 0 });
      const body = await res.text();
      expect(body, path).not.toContain('root:x:');
      expect(body, path).not.toContain('PRIVATE KEY');
    }
  });

  test('client-supplied forwarding and identity headers are overwritten or dropped', async ({ request }) => {
    const res = await request.get('/api/stub/echo', {
      headers: { 'X-Forwarded-Host': 'evil.example', 'X-Forwarded-Proto': 'http', 'X-Forwarded-For': '6.6.6.6', 'X-User': 'admin', 'X-Remote-User': 'admin', 'X-Forwarded-User': 'admin', 'X-Auth-User': 'admin' },
    });
    const { headers } = await res.json();
    expect(headers['x-forwarded-host']).toBe('localhost');
    expect(headers['x-forwarded-proto']).toBe('https');
    expect(headers['x-forwarded-for']).not.toContain('6.6.6.6');
    for (const h of ['x-user', 'x-remote-user', 'x-forwarded-user', 'x-auth-user']) expect(headers[h], h).toBeUndefined();
  });

  // Finding F-CSP-1: without worker-src 'none' a module could register a service worker for its own path scope
  // (verified on the first CSP draft; widening the scope was already refused because Service-Worker-Allowed is stripped).
  // Served over plain http on localhost because service workers are refused under the self-signed certificate.
  test('service worker registration from a module path is blocked by worker-src none', async ({ page }) => {
    await trackCsp(page);
    await page.goto('http://localhost:8081/');
    for (const scope of ['/', '/modules/evil/remote/']) {
      const r = await page.evaluate((scope) =>
        navigator.serviceWorker.register('/modules/evil/remote/sw.js', { scope }).then(() => 'registered', (e) => `refused: ${e.name}`), scope);
      expect(r, scope).toMatch(/^refused/);
    }
    const v = await cspViolations(page);
    console.log('WP1.11 CSP violations (expected worker-src):', JSON.stringify(v));
    expect(v.some((x) => x.directive.startsWith('worker-src'))).toBe(true);
  });
});
