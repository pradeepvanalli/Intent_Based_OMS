import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { liveParameters, ok, proposal, proposeLiveUse, uid } from '../support/data';
import { dialog, toast } from '../support/ui';

/** A family nothing else in the suite or the seed touches, so the lifecycle here starts clean on every run. */
const FAMILY = 'inventory.safety_stock';
const LEARNER = 'inventory_safety_stock';
const BOUNDS = { min: 0, max: 2, maxStep: 0.5 };

async function openChange(page: import('@playwright/test').Page, id: string) {
  await page.goto(`/learning?tab=changes&change=${encodeURIComponent(id)}`);
  const drawer = page.getByRole('dialog').filter({ hasText: 'What would change' });
  await expect(drawer).toBeVisible();
  return drawer;
}

/**
 * EX-07 and defect 26 on one learned value: going live without full evidence needs a written reason (the server's
 * 409 opens the reason dialog), and once rolled back the same change cannot be proposed again for the cooldown,
 * which the person is told in words. A different change to the same value is still allowed.
 */
test.describe.serial('Manual promotion and the rollback cooldown', () => {
  let id = '';
  let rollbackReason = '';

  test('a manual promotion that falls short of the evidence needs a written reason; the 409 opens the dialog', async ({ pageAs }) => {
    const strategyApi = await apiAs('strategy');
    const adminApi = await apiAs('admin');
    const p = await proposeLiveUse(strategyApi, { family: FAMILY, learnerKey: LEARNER, ...BOUNDS, reason: `e2e override ${uid()}` });
    id = p.proposalId;
    await ok(adminApi.post(`/api/v1/learning/proposals/${id}/evaluate`));
    await ok(adminApi.post(`/api/v1/learning/proposals/${id}/approve`, { data: {} }));

    // Over the API: no reason, then a reason that is too short, are both refused with the list of what would be waived.
    for (const body of [{}, { overrideReason: 'too short' }]) {
      const res = await adminApi.post(`/api/v1/learning/proposals/${id}/promote`, { data: body });
      expect(res.status(), JSON.stringify(body)).toBe(409);
      const problem = await res.json() as { code: string; detail: string; waivable: string[] };
      expect(problem.code).toBe('promotion_needs_override');
      expect(problem.detail).toContain('needs a written reason, because');
      expect(problem.waivable.length).toBeGreaterThan(0);
    }
    expect((await proposal(adminApi, id)).status).toBe('APPROVED');

    // In the console: hide the card's own warning so the click reaches the server, whose 409 opens the same dialog.
    const admin = await pageAs('admin');
    await admin.route(`**/api/v1/learning/proposals/${id}/card`, async (route) => {
      const res = await route.fetch();
      const card = await res.json() as { actions: Record<string, unknown> };
      card.actions.promotionConcerns = [];
      await route.fulfill({ response: res, json: card });
    });
    const drawer = await openChange(admin, id);
    await drawer.getByRole('button', { name: 'Go live', exact: true }).click();
    const waive = dialog(admin, 'Go live without full evidence?');
    await expect(waive).toContainText('This change would normally wait, because:');
    await expect(waive.getByRole('listitem').first()).toBeVisible();
    const confirm = waive.getByRole('button', { name: 'Go live anyway' });
    await waive.getByLabel('Why go live anyway?').fill('too short');
    await expect(waive).toContainText('At least 10 characters');
    await expect(confirm).toBeDisabled();
    const reason = `Safety stock only moves within 0 to 2 units; ops asked for it before peak (${uid()})`;
    await waive.getByLabel('Why go live anyway?').fill(reason);
    await confirm.click();
    await expect(toast(admin, 'Live now')).toBeVisible();
    await expect(toast(admin, 'Live now')).toContainText('Your reason is recorded with the change');
    await admin.unroute(`**/api/v1/learning/proposals/${id}/card`);

    const promoted = await proposal(adminApi, id);
    expect(promoted.status).toBe('PROMOTED');
    const card = await ok<{ promotionNote?: { by: string; reason: string; waived: string[] } }>(adminApi.get(`/api/v1/learning/proposals/${id}/card`));
    expect(card.promotionNote).toMatchObject({ by: 'user:admin', reason });
    expect(card.promotionNote!.waived.length).toBeGreaterThan(0);
    await expect.poll(async () => (await liveParameters(adminApi)).find((x) => x.parameter === FAMILY)?.status).toBe('LIVE');
    await expect(drawer.getByTestId('promotion-note')).toContainText(`Went live without full evidence: admin wrote "${reason}"`);
    await expect(drawer.getByTestId('promotion-note')).toContainText('waived:');
  });

  test('once rolled back, proposing the same change again is refused for 30 days, explained in words', async ({ pageAs }) => {
    test.skip(!id, 'needs the promoted change from the previous test');
    const strategyApi = await apiAs('strategy');
    const adminApi = await apiAs('admin');
    rollbackReason = `Safety stock pushed slow movers into backorder (${uid()})`;
    await ok(adminApi.post(`/api/v1/learning/promotions/${id}/rollback`, { data: { reason: rollbackReason } }));
    expect((await proposal(adminApi, id)).status).toBe('ROLLED_BACK');

    // The API refuses the identical change with a code a program can act on and a sentence a person can read.
    const again = await strategyApi.post('/api/v1/learning/live-parameters/proposals', { data: { enabled: true, family: FAMILY, learnerKey: LEARNER, ...BOUNDS, reason: 'e2e again' } });
    expect(again.status()).toBe(409);
    const problem = await again.json() as { code: string; detail: string; rolledBackProposalId: string; cooldownDays: number };
    expect(problem).toMatchObject({ code: 'proposal_cooling_down', rolledBackProposalId: id, cooldownDays: 30 });
    expect(problem.detail).toContain(`was rolled back on ${new Date().toISOString().slice(0, 10)} (${rollbackReason})`);
    expect(problem.detail).toContain('cannot be proposed again for 30 days without different evidence');

    // In the console the strategy lead is told why, in words, and nothing is drafted.
    const strategy = await pageAs('strategy');
    await strategy.goto('/learning?tab=changes');
    await strategy.getByRole('button', { name: 'Propose live use' }).click();
    const form = dialog(strategy, 'Propose using a learned value live');
    await form.getByLabel('Learned value').selectOption(`${LEARNER}|${FAMILY}`);
    await form.getByLabel('Lowest allowed').fill(String(BOUNDS.min));
    await form.getByLabel('Highest allowed').fill(String(BOUNDS.max));
    await form.getByLabel('Biggest step from standard').fill(String(BOUNDS.maxStep));
    await form.getByRole('button', { name: 'Draft proposal' }).click();
    const refusal = toast(strategy, 'Rolled back recently');
    await expect(refusal).toBeVisible();
    await expect(refusal).toContainText('was rolled back on');
    await expect(refusal).toContainText(rollbackReason);
    await expect(refusal).toContainText('cannot be proposed again for 30 days');
    await expect(refusal).not.toContainText('proposal_cooling_down');
    await expect(toast(strategy, 'Proposal drafted')).toHaveCount(0);
    await expect(form).toBeVisible(); // the form stays open so the bounds can be changed

    // Different evidence means a different change: narrower bounds may be proposed (then cleaned up).
    const narrower = await proposeLiveUse(strategyApi, { family: FAMILY, learnerKey: LEARNER, min: 0, max: 1, maxStep: 0.25, reason: `e2e narrower ${uid()}` });
    expect(narrower.status).toBe('DRAFT');
    await ok(adminApi.post(`/api/v1/learning/proposals/${narrower.proposalId}/reject`, { data: { reason: 'e2e cleanup' } }));
  });
});
