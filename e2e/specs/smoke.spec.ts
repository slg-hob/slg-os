import { expect, test } from '../support/fixtures';
import { REALM, SHELL, SSO, signIn } from '../support/login';

test.describe('smoke: sign-in, desktop placeholder and sign-out on the real stack', () => {
  test('an unauthenticated visit is redirected to the Keycloak login in German', async ({ page }) => {
    await page.goto('/app/');
    await expect(page).toHaveURL(new RegExp(`^${SSO}/realms/${REALM}/protocol/openid-connect/auth`));
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
    await expect(page.locator('#password')).toBeVisible();
  });

  test('the Keycloak login page renders in English when the browser asks for it', async ({ newContext }) => {
    const context = await newContext({ locale: 'en-US' });
    const page = await context.newPage();
    await page.goto('/app/');
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.locator('#kc-login')).toBeVisible();
  });

  test('sign-in ends on the desktop placeholder with a hardened session cookie', async ({ page, context }) => {
    await signIn(page, 'user-a');
    const cookies = await context.cookies(SHELL);
    const session = cookies.find((c) => c.name === '__Host-slg-session');
    expect(session, 'session cookie present').toBeDefined();
    expect(session).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Strict', path: '/' });
    expect(cookies.some((c) => c.name === '__Host-slg-login'), 'the pre-login cookie is gone').toBe(false);
    expect(session?.domain, 'host-only cookie').toBe('shell.slg.test');
  });

  test('the session view has the profile and the CSRF token but no token of any kind', async ({ page }) => {
    await signIn(page, 'admin');
    const view = await page.evaluate(async () => {
      const response = await fetch('/core/api/v1/session');
      return { status: response.status, text: await response.text() };
    });
    expect(view.status).toBe(200);
    const json = JSON.parse(view.text);
    expect(json.sub).toBeTruthy();
    expect(json.permissions).toEqual(['platform-admin']);
    expect(json.csrfToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(view.text).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);
    expect(view.text.toLowerCase()).not.toContain('access_token');
    expect(view.text.toLowerCase()).not.toContain('refresh');
  });

  test('a user without a mapped group has no permissions', async ({ page }) => {
    await signIn(page, 'user-b');
    const permissions = await page.evaluate(async () => (await (await fetch('/core/api/v1/session')).json()).permissions);
    expect(permissions).toEqual([]);
  });

  test('state-changing requests without the CSRF header are refused', async ({ page }) => {
    await signIn(page, 'user-a');
    const status = await page.evaluate(async () => (await fetch('/core/api/v1/session/touch', { method: 'POST' })).status);
    expect(status).toBe(403);
  });

  test('sign-out ends the session at the server and at Keycloak', async ({ page }) => {
    await signIn(page, 'user-a');
    const redirect = await page.evaluate(async () => {
      const view = await (await fetch('/core/api/v1/session')).json();
      const response = await fetch('/core/auth/logout', {
        method: 'POST',
        headers: { 'X-Slg-Csrf': view.csrfToken, Accept: 'application/json' },
      });
      return (await response.json()).redirectUrl as string;
    });
    expect(redirect).toContain(`${SSO}/realms/${REALM}/protocol/openid-connect/logout`);
    await page.goto(redirect);
    await expect(page).toHaveURL(`${SHELL}/core/auth/signed-out`);
    await expect(page.getByRole('heading', { name: 'Abgemeldet' })).toBeVisible();
    await page.goto('/app/');
    await expect(page).toHaveURL(new RegExp(`^${SSO}/realms/${REALM}/protocol/openid-connect/auth`));
  });

  test('a wrong password shows the Keycloak error and creates no session', async ({ page, context }) => {
    await page.goto('/app/');
    await page.locator('#username').fill('user-a');
    await page.locator('#password').fill('definitely-wrong');
    await page.locator('#kc-login').click();
    await expect(page.locator('#input-error, .kc-feedback-text, [role=alert]').first()).toBeVisible();
    expect((await context.cookies(SHELL)).some((c) => c.name === '__Host-slg-session')).toBe(false);
  });

  test('the shell makes no request to a third party', async ({ page }) => {
    const origins = new Set<string>();
    page.on('request', (request) => origins.add(new URL(request.url()).origin));
    await signIn(page, 'user-a');
    expect([...origins].filter((o) => o !== SHELL && o !== SSO)).toEqual([]);
  });
});
