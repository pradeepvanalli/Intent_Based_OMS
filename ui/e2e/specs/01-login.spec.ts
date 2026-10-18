import { test, expect } from '../support/fixtures';
import { TENANT } from '../support/roles';

test.describe('Sign in', () => {
  test('a demo user signs in and lands on the Control Tower', async ({ page }) => {
    await page.goto('/login');
    const tenant = page.getByLabel('Tenant');
    if (await tenant.evaluate((el) => el.tagName === 'SELECT')) await tenant.selectOption(TENANT); else await tenant.fill(TENANT);
    await page.getByLabel('User').fill('ops');
    await page.getByLabel('Password').fill('ops');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/control-tower/);
    await expect(page.getByRole('heading', { level: 1, name: 'Control Tower' })).toBeVisible();
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
