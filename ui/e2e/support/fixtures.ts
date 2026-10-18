import { test as base, type Page } from '@playwright/test';
import { TENANT, statePath, type Role } from './roles';

/**
 * `pageAs(role)` opens a signed-in page for a demo role. The "no AI key" banner is dismissed up front (the e2e
 * profile runs without AI keys on purpose) so it never shifts the layout under a click.
 */
export const test = base.extend<{ pageAs: (role: Role) => Promise<Page> }>({
  pageAs: async ({ browser }, use) => {
    const opened: Array<() => Promise<void>> = [];
    await use(async (role: Role) => {
      const context = await browser.newContext({ storageState: statePath(role) });
      await context.addInitScript((tenant: string) => {
        try { sessionStorage.setItem(`oms.nokey.dismissed.${tenant}`, '1'); } catch { /* ignore */ }
      }, TENANT);
      opened.push(() => context.close());
      return context.newPage();
    });
    for (const close of opened) await close();
  },
});

export { expect } from '@playwright/test';
