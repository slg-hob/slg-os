import { expect, test, type Request } from '@playwright/test';
import { REALM, SHELL, SSO, submitCredentials } from '../support/login';

/**
 * PRD-0003 SK-3 AC3 / Q37: a SameSite=Strict cookie is not sent on a redirect chain that began at another site. The callback
 * therefore answers 200 with a document that navigates on. This test asserts, in every browser, that the first navigation
 * to /app/ after the callback carries the session cookie and that there is no second login bounce.
 */
test('the first request to /app after the callback carries the session cookie and there is no second login bounce', async ({ page }) => {
  const seen: { url: string; cookie: string | undefined; navigation: boolean }[] = [];
  const pending: Promise<void>[] = [];
  page.on('request', (request: Request) => {
    pending.push(
      request.allHeaders().then((headers) => {
        seen.push({ url: request.url(), cookie: headers['cookie'], navigation: request.isNavigationRequest() });
      }),
    );
  });

  await page.goto('/app/reference/notes');
  await expect(page).toHaveURL(new RegExp(`^${SSO}/realms/${REALM}/protocol/openid-connect/auth`));
  await submitCredentials(page, 'user-a');
  await expect(page).toHaveURL(`${SHELL}/app/reference/notes`);
  await expect(page.getByRole('heading', { name: 'SLG OS' })).toBeVisible();
  await Promise.all(pending);

  const callbackIndex = seen.findIndex((r) => r.url.startsWith(`${SHELL}/core/auth/callback`));
  expect(callbackIndex, 'the callback was requested').toBeGreaterThanOrEqual(0);
  const afterCallback = seen.slice(callbackIndex + 1).filter((r) => r.navigation && r.url === `${SHELL}/app/reference/notes`);
  expect(afterCallback.length, 'a navigation to the return target followed the callback').toBeGreaterThanOrEqual(1);
  expect(afterCallback[0].cookie ?? '', 'the first navigation after the callback carries the session cookie').toContain('__Host-slg-session=');

  const authRequests = seen.filter((r) => r.url.startsWith(`${SSO}/realms/${REALM}/protocol/openid-connect/auth`));
  expect(authRequests.length, 'exactly one authorization request, no second bounce').toBe(1);
  expect(seen.filter((r) => r.url.startsWith(`${SHELL}/core/auth/login`)).length).toBe(1);
});
