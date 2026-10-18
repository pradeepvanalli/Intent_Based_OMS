import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';

type Page<T> = { items: T[]; total: number };
type Task = { id: string; queue: string; status: string };

test.describe('Tasks', () => {
  test('the default "Open & claimed" view lists open work', async ({ pageAs }) => {
    const admin = await apiAs('admin');
    const open = (await (await admin.get('/api/v1/tasks', { params: { status: 'OPEN,CLAIMED', limit: 1 } })).json()) as Page<Task>;
    const openOnly = (await (await admin.get('/api/v1/tasks', { params: { status: 'OPEN', limit: 1 } })).json()) as Page<Task>;
    expect(open.total).toBeGreaterThanOrEqual(openOnly.total);
    expect(open.total).toBeGreaterThan(0);

    const page = await pageAs('admin');
    await page.goto('/tasks');
    await expect(page.getByRole('heading', { level: 1, name: 'Tasks' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Tasks' }).getByRole('row').nth(1)).toBeVisible();
  });

  test('the navigation badge counts only the queues a role works', async ({ pageAs }) => {
    for (const role of ['ops', 'cs'] as const) {
      const api = await apiAs(role);
      const mine = (await (await api.get('/api/v1/tasks', { params: { status: 'OPEN', limit: 200, mine: true } })).json()) as Page<Task>;
      const all = (await (await api.get('/api/v1/tasks', { params: { status: 'OPEN', limit: 1 } })).json()) as Page<Task>;
      expect(mine.total).toBeLessThanOrEqual(all.total);
      if (role === 'cs') for (const t of mine.items) expect(t.queue.startsWith('cs_')).toBeTruthy();

      const page = await pageAs(role);
      await page.goto('/control-tower');
      const link = page.getByRole('link', { name: /^Tasks/ });
      if (mine.total > 0) await expect(link.locator('.cnt')).toHaveText(String(mine.total));
      else await expect(link.locator('.cnt')).toHaveCount(0);
    }
  });
});
