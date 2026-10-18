import { expect, type Locator, type Page } from '@playwright/test';

/**
 * The app's switches are a visually hidden checkbox (role switch) inside a styled label, so a person clicks the label.
 * Sets the switch to `on` and waits until it reports that state.
 */
export async function setSwitch(scope: Page | Locator, name: string, on: boolean): Promise<void> {
  const input = scope.getByRole('switch', { name });
  if ((await input.isChecked()) !== on) await scope.locator('label.switch', { hasText: name }).click();
  await expect(input).toBeChecked({ checked: on });
}

/** The most recent toast with this title (toasts stack; danger ones are role=alert, the rest role=status). */
export function toast(page: Page, title: string | RegExp): Locator {
  return page.locator('.toast').filter({ has: page.locator('.title', { hasText: title }) }).last();
}

/** A drawer or dialog by its title. */
export function dialog(page: Page, name: string | RegExp): Locator {
  return page.getByRole('dialog', { name });
}
