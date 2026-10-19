import { test, expect } from '../support/fixtures';
import { TENANT } from '../support/roles';

test.describe('Sign in', () => {
  test('a demo user signs in and lands on the Control Tower', async ({ page }) => {
    await page.goto('/login');
    // The tenant field is a text box until the tenant list has loaded, then a list; decide once the page has settled.
    await page.waitForLoadState('networkidle');
    const tenant = page.getByLabel('Tenant');
    if (await tenant.evaluate((el) => el.tagName === 'SELECT')) await tenant.selectOption(TENANT); else await tenant.fill(TENANT);
    await page.getByLabel('User').fill('ops');
    await page.getByLabel('Password').fill('ops');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/control-tower/);
    await expect(page.getByRole('heading', { level: 1, name: 'Control Tower' })).toBeVisible();
  });

  test('the demo user list includes the platform engineer, who signs in with one click', async ({ page }) => {
    await page.goto('/login');
    const platform = page.getByRole('button', { name: /^platform\s*platform engineer$/ });
    await expect(platform).toBeVisible();
    await platform.click();
    await expect(page).toHaveURL(/control-tower/);
    const principal = await page.evaluate(() => JSON.parse(localStorage.getItem('oms.principal') ?? '{}') as { id?: string; roles?: string[] });
    expect(principal).toMatchObject({ id: 'platform', roles: ['platform_engineer'] });
  });

  test('a wrong password explains the failure without technical text', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('User').fill('ops');
    await page.getByLabel('Password').fill('not-the-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'Sign-in failed' });
    await expect(alert).toBeVisible();
    await expect(alert).not.toContainText(/exception|java\.|org\./i);
  });
});
