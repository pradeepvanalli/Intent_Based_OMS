import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { liveParameters, ok, proposal, proposeLiveUse, seedStatus, uid, type Experiment } from '../support/data';

type Card = { actions: { promotionBlocker?: string | null; promotionConcerns?: string[] }; governingLearner?: string };

async function openChange(page: import('@playwright/test').Page, id: string) {
  await page.goto(`/learning?tab=changes&change=${encodeURIComponent(id)}`);
  const drawer = page.getByRole('dialog').filter({ hasText: 'What would change' });
  await expect(drawer).toBeVisible();
  return drawer;
}

/**
 * Audit 3 workstream D on the running app: live use of a learned value that feeds a T2 decision is T2 (D-3); moving a
 * live range further than one promotion may is a GV-02 step a person must explain (D-2); the owner of a change's
 * target governs it, so switching that learner off blocks another learner's draft (D-4); a canary cannot be started
 * below the tenant's minimum duration (D-5); and the outcome score is never labelled "0 to 1" (E3-N3).
 */
test.describe('Governance against the live state', () => {
  test('live use of fraud signal risk feeds payment risk, so it is a T2 change that needs a person', async ({ pageAs }) => {
    const strategy = await apiAs('strategy');
    const admin = await apiAs('admin');
    expect((await proposal(admin, 'prop_demo_live_fraud_signals')).tier, 'the seeded one too').toBe('T2');
    const p = await proposeLiveUse(strategy, { family: 'fraud.signal_risk', learnerKey: 'fraud_signals', min: 0, max: 1, maxStep: 0.2, reason: `e2e D-3 ${uid()}` });
    expect(p.tier).toBe('T2');
    const page = await pageAs('strategy');
    const drawer = await openChange(page, p.proposalId);
    await expect(drawer.getByTitle('Risk tier T2').first()).toBeVisible();
    await ok(admin.post(`/api/v1/learning/proposals/${p.proposalId}/reject`, { data: { reason: 'e2e clean-up after the tier check' } }));
  });

  test('moving a live range further than one promotion may is a step that needs a written reason', async () => {
    const strategy = await apiAs('strategy');
    const admin = await apiAs('admin');
    const live = (await liveParameters(admin)).find((x) => x.parameter === 'promise.lane_transit_days');
    test.skip(live?.status !== 'LIVE', 'needs the seeded live lane transit range');
    // Seeded range 1 to 8 days; the family's domain is 0 to 60, so one promotion may move a bound by at most 30.
    const p = await proposeLiveUse(strategy, { family: 'promise.lane_transit_days', learnerKey: 'lane_transit', min: 1, max: 45, maxStep: 2, reason: `e2e D-2 ${uid()}` });
    await ok(admin.post(`/api/v1/learning/proposals/${p.proposalId}/evaluate`));
    await ok(admin.post(`/api/v1/learning/proposals/${p.proposalId}/approve`, { data: {} }));
    const card = await ok<Card>(admin.get(`/api/v1/learning/proposals/${p.proposalId}/card`));
    expect(card.actions.promotionConcerns?.join(' ')).toContain('moves the live range of promise.lane_transit_days by 37');
    const res = await admin.post(`/api/v1/learning/proposals/${p.proposalId}/promote`, { data: {} });
    expect(res.status()).toBe(409);
    const problem = await res.json() as { code: string; waivable: string[] };
    expect(problem.code).toBe('promotion_needs_override');
    expect(problem.waivable.join(' ')).toContain('GV-02');
    await ok(admin.post(`/api/v1/learning/proposals/${p.proposalId}/reject`, { data: { reason: 'e2e clean-up after the step check' } }));
    expect((await liveParameters(admin)).find((x) => x.parameter === 'promise.lane_transit_days')?.status).toBe('LIVE');
  });

  test('the target owner governs: switching the fraud tuner off blocks the band tuner\'s return-risk change', async ({ pageAs }) => {
    const admin = await apiAs('admin');
    const ids = (await seedStatus(admin)).ids;
    const approved = await proposal(admin, ids.approved);
    test.skip(approved.status !== 'APPROVED', 'needs the seeded approved return-risk change');
    expect(approved.createdBy).toContain('band_threshold');
    await ok(admin.post('/api/v1/learning/kill-switch', { data: { learnerKey: 'fraud_threshold_tuner', enabled: false } }));
    try {
      const card = await ok<Card>(admin.get(`/api/v1/learning/proposals/${ids.approved}/card`));
      expect(card.governingLearner).toBe('fraud_threshold_tuner');
      expect(card.actions.promotionBlocker).toContain("learner 'fraud_threshold_tuner' is kill-switched off");
      const res = await admin.post(`/api/v1/learning/proposals/${ids.approved}/promote`, { data: { overrideReason: 'e2e: a reason never lifts a kill switch' } });
      expect(res.status()).toBe(409);
      const page = await pageAs('admin');
      const drawer = await openChange(page, ids.approved);
      await expect(drawer.getByTestId('promotion-blocker')).toContainText('fraud_threshold_tuner');
      await expect(drawer.getByRole('button', { name: /^Go live/ })).toBeDisabled(); // 'Go live anyway…' while its evidence or budget falls short
    } finally {
      await ok(admin.post('/api/v1/learning/kill-switch', { data: { learnerKey: 'fraud_threshold_tuner', enabled: true } }));
    }
    expect((await proposal(admin, ids.approved)).status).toBe('APPROVED');
  });

  test('a canary asked to run 0 days starts with the tenant minimum of 7 and says so', async ({ pageAs }) => {
    const admin = await apiAs('admin');
    const ids = (await seedStatus(admin)).ids;
    const approved = await proposal(admin, ids.approved);
    test.skip(approved.status !== 'APPROVED', 'needs the seeded approved return-risk change');
    const e = await ok<Experiment & { stopRule: Record<string, unknown>; startAt: string; endAt: string }>(
      admin.post('/api/v1/learning/experiments', { data: { proposalId: ids.approved, mode: 'CANARY', trafficShare: 0.1, stopRule: { minDurationDays: 0 } } }));
    try {
      expect(e.stopRule.minDurationDays).toBe(7);
      expect(String(e.stopRule.floorsApplied)).toContain('raised from 0 to 7 days');
      expect(new Date(e.endAt).getTime() - new Date(e.startAt).getTime()).toBeGreaterThanOrEqual(7 * 86_400_000);
      // The server refuses to complete it early, and stop cannot be used to complete it with a result of the caller's choosing.
      const early = await admin.post(`/api/v1/learning/experiments/${e.experimentId}/complete`);
      expect(early.status()).toBe(409);
      expect(await early.text()).toContain('of its minimum 7 days');
      const forged = await admin.post(`/api/v1/learning/experiments/${e.experimentId}/stop`, { data: { status: 'COMPLETED', result: { msprt: { verdict: 'TREATMENT_BETTER' } } } });
      expect(forged.status()).toBe(422);
      const page = await pageAs('admin');
      await page.goto(`/learning?tab=experiments&experiment=${e.experimentId}`);
      const button = page.getByRole('button', { name: 'Complete and save result' });
      await expect(button).toBeDisabled();
      await expect(button).toHaveAttribute('title', /at least 7 days/);
    } finally {
      await ok(admin.post(`/api/v1/learning/experiments/${e.experimentId}/stop`, { data: { status: 'STOPPED' } }));
    }
    expect((await proposal(admin, ids.approved)).status).toBe('APPROVED');
  });

  test('the outcome score is explained as a weighted sum, never labelled 0 to 1', async ({ pageAs }) => {
    const ids = (await seedStatus(await apiAs('strategy'))).ids;
    const page = await pageAs('strategy');
    const drawer = await openChange(page, ids.t0Shadow);
    const score = drawer.getByTestId('replay-outcome-score');
    await expect(score).toContainText('outcome score');
    await expect(score).toContainText('(higher is better)');
    await expect(drawer).not.toContainText('(0 to 1)');
    await page.goto('/learning?tab=overview');
    await expect(page.getByText('Overall outcome score').first()).toBeVisible();
    await expect(page.locator('body')).not.toContainText('(0 to 1)');
  });
});
