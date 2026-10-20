import { expect, type Page } from '@playwright/test';

export const SHELL = 'https://shell.slg.test';
export const SSO = 'https://sso.slg.test';
export const REALM = 'slg-e2e';

/** The generated test password of this run (deploy/.generated/e2e.env). */
export function password(): string {
  const value = process.env.E2E_USER_PASSWORD;
  if (!value) {
    throw new Error('E2E_USER_PASSWORD is not set: source deploy/.generated/e2e.env');
  }
  return value;
}

/** Fills the Keycloak login form and submits it. */
export async function submitCredentials(page: Page, user: string): Promise<void> {
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(password());
  await page.locator('#kc-login').click();
}

/** Opens the shell, signs in as the user and waits for the desktop placeholder. */
export async function signIn(page: Page, user: string, target = '/app/'): Promise<void> {
  await page.goto(target);
  await expect(page).toHaveURL(new RegExp(`^${SSO}/realms/${REALM}/protocol/openid-connect/auth`));
  await submitCredentials(page, user);
  await expect(page).toHaveURL(new RegExp(`^${SHELL}/app/`));
  await expect(page.getByRole('heading', { name: 'SLG OS' })).toBeVisible();
}
