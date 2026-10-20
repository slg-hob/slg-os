import { expect, test } from '../support/fixtures';
import { REALM, SHELL, SSO } from '../support/login';

/** Only login, logout, token and well-known paths of Keycloak are public; the internal tree of the core is not (SK-15 AC1, SK-3 AC5). */
test.describe('what the public proxy does not route', () => {
  for (const path of ['/admin/master/console/', '/admin/', '/realms/master/.well-known/openid-configuration', '/realms/master/protocol/openid-connect/auth',
    `/realms/${REALM}/account`, `/realms/${REALM}/clients-registrations/openid-connect`, '/metrics', '/health/ready', '/']) {
    test(`Keycloak host answers 404 for ${path}`, async ({ request }) => {
      const response = await request.get(`${SSO}${path}`, { maxRedirects: 0 });
      expect(response.status()).toBe(404);
    });
  }

  test('the realm discovery document is public', async ({ request }) => {
    const response = await request.get(`${SSO}/realms/${REALM}/.well-known/openid-configuration`);
    expect(response.status()).toBe(200);
    const document = await response.json();
    expect(document.issuer).toBe(`${SSO}/realms/${REALM}`);
    expect(document.code_challenge_methods_supported).toContain('S256');
  });

  test('the internal tree of the core is not reachable from the public host', async ({ request }) => {
    for (const path of ['/core/internal/backchannel-logout', '/core/internal/', '/core/%69nternal/backchannel-logout', '/core/internal/api/v1/permissions']) {
      const response = await request.post(`${SHELL}${path}`, { form: { logout_token: 'x' }, maxRedirects: 0 });
      expect(response.status(), path).toBe(404);
    }
  });

  test('the management port of the core is not routed', async ({ request }) => {
    for (const path of ['/actuator/health', '/actuator/prometheus']) {
      const response = await request.get(`${SHELL}${path}`, { maxRedirects: 0 });
      expect([401, 404]).toContain(response.status());
    }
  });

  test('the root of the shell host redirects to the app and unknown paths are 404', async ({ request }) => {
    const root = await request.get(`${SHELL}/`, { maxRedirects: 0 });
    expect(root.status()).toBe(302);
    expect(root.headers()['location']).toBe('/app/');
    expect((await request.get(`${SHELL}/nothing`, { maxRedirects: 0 })).status()).toBe(404);
  });

  test('forwarded headers from the client do not break the request and the response carries HSTS and a correlation id', async ({ request }) => {
    const response = await request.get(`${SHELL}/core/auth/signed-out`, {
      headers: { 'X-Forwarded-For': '6.6.6.6', 'X-Forwarded-Host': 'evil.example', Forwarded: 'for=6.6.6.6;host=evil.example' },
    });
    expect(response.status()).toBe(200);
    expect(response.headers()['strict-transport-security']).toContain('max-age=');
    expect(response.headers()['x-correlation-id']).toMatch(/^[0-9a-f-]{16,}$/);
  });

  test('a request for a host name the stack does not serve is refused', async ({ request }) => {
    const response = await request.get(`${SHELL}/core/auth/signed-out`, { headers: { Host: 'evil.example' } }).catch(() => null);
    if (response) {
      expect([421, 404]).toContain(response.status());
    }
  });
});
