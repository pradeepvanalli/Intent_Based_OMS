import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';

type Page<T> = { items: T[]; total: number };
type Task = { id: string; type: string; queue: string; status: string; title: string; subjectType?: string; subjectId?: string };

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

  test('re-escalations of a de-duplicated decision never open a second task for the same subject', async () => {
    const admin = await apiAs('admin');
    const open = (await (await admin.get('/api/v1/tasks', { params: { status: 'OPEN,CLAIMED', limit: 500 } })).json()) as Page<Task>;
    const deduped = open.items.filter((t) => t.type === 'decision_escalation' && /sla_breach_risk|stuck_order_diagnosis/.test(t.title));
    const perSubject = new Map<string, number>();
    for (const t of deduped) perSubject.set(`${t.title}|${t.subjectType}|${t.subjectId}`, (perSubject.get(`${t.title}|${t.subjectType}|${t.subjectId}`) ?? 0) + 1);
    const repeats = [...perSubject.entries()].filter(([, n]) => n > 1);
    expect(repeats, 'one open task per subject for sla_breach_risk and stuck_order_diagnosis').toEqual([]);
  });

  test('"My queues" is the default and "Every queue" widens it; queue counts add up', async ({ pageAs }) => {
    const ops = await apiAs('ops');
    const mine = (await (await ops.get('/api/v1/tasks', { params: { status: 'OPEN,CLAIMED', limit: 1, mine: true } })).json()) as Page<Task>;
    const all = (await (await ops.get('/api/v1/tasks', { params: { status: 'OPEN,CLAIMED', limit: 1 } })).json()) as Page<Task>;
    expect(mine.total).toBeLessThanOrEqual(all.total);
    const queues = (await (await ops.get('/api/v1/queues')).json()) as Array<{ name: string; openCount: number; claimedCount: number }>;

    const page = await pageAs('ops');
    await page.goto('/tasks');
    const scope = page.getByRole('combobox', { name: 'Which tasks' });
    await expect(scope).toHaveValue('mine');
    await scope.selectOption({ label: 'Every queue' });
    await expect(page).toHaveURL(/scope=all/);
    await expect(page.getByRole('listitem').filter({ hasText: 'All queues' }).locator('.cnt')).toHaveText(String(queues.reduce((n, q) => n + (q.openCount ?? 0), 0)));
    await scope.selectOption({ label: 'My queues' });
    await expect(scope).toHaveValue('mine');
  });
});
