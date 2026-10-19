import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import type { Role } from '../support/roles';

/**
 * Who may see and change learning (§9.3 permissions, Wave A role grants): learning.read for admin, strategy lead,
 * ops analyst and risk analyst; learning.admin (changes) for admin and strategy lead only; autonomy also needs
 * config.admin. CS reps and finance controllers have no learning access.
 */
const MATRIX: Record<Role, { read: boolean; change: boolean }> = {
  admin: { read: true, change: true },
  strategy: { read: true, change: true },
  ops: { read: true, change: false },
  risk: { read: true, change: false },
  cs: { read: false, change: false },
  finance: { read: false, change: false },
};

test.describe('Learning permissions by role', () => {
  for (const [role, may] of Object.entries(MATRIX) as Array<[Role, { read: boolean; change: boolean }]>) {
    test(`${role}: ${may.read ? 'reads' : 'cannot read'}, ${may.change ? 'changes' : 'cannot change'}`, async ({ pageAs }) => {
      const api = await apiAs(role);
      for (const p of ['/api/v1/learning/learners', '/api/v1/learning/proposals?status=PROMOTED', '/api/v1/learning/experiments', '/api/v1/learning/lift', '/api/v1/learning/objective', '/api/v1/learning/coverage', '/api/v1/learning/metrics']) {
        expect((await api.get(p)).status(), `${role} GET ${p}`).toBe(may.read ? 200 : 403);
      }
      // A no-op change: switch on a learner that is already on.
      const change = await api.post('/api/v1/learning/kill-switch', { data: { learnerKey: 'inventory_transfer', enabled: true } });
      expect(change.status(), `${role} POST kill-switch`).toBe(may.change ? 200 : 403);
      const autonomy = await api.put('/api/v1/learning/learners/inventory_transfer/autonomy', { data: { autonomyLevel: 'L1_RECOMMEND' } });
      expect(autonomy.status(), `${role} PUT autonomy`).toBe(may.change ? 200 : 403);
      if (!may.change) {
        const body = await change.text();
        expect(body, 'a refusal names the missing permission in words').toMatch(/permission|learning\.admin/i);
      }

      const page = await pageAs(role);
      await page.goto('/control-tower');
      const nav = page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Learning' });
      await expect(nav).toHaveCount(may.read ? 1 : 0);
      await page.goto('/learning');
      if (!may.read) {
        await expect(page.getByText("You don't have access to this")).toBeVisible();
        await expect(page.getByText('Ask an admin for the learning.read permission.')).toBeVisible();
        return;
      }
      await expect(page.getByRole('tablist', { name: 'Learning sections' })).toBeVisible();
      await page.goto('/learning?tab=learners');
      await expect(page.getByRole('button', { name: 'Pause all learning' })).toHaveCount(may.change ? 1 : 0);
      await page.goto('/learning?tab=changes');
      await expect(page.getByRole('button', { name: 'Propose live use' })).toHaveCount(may.change ? 1 : 0);
    });
  }
});
