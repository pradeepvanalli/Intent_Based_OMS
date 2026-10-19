import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { liveDomains, ok, proposeLiveUse, seedStatus, uid } from '../support/data';

/** Seeded reference data is complete, written as the demo-seed actor, and labelled wherever people see it. */
test.describe('Demo reference data', () => {
  test('the seed is complete and every seeded history row carries the demo-seed actor', async () => {
    const api = await apiAs('strategy');
    const s = await seedStatus(api);
    expect(s.enabled).toBe(true);
    expect(s.complete).toBe(true);
    expect(s.actor).toBe('system:demo-seed');
    for (const k of ['draft', 'evaluated', 'approved', 'canary', 'promotedDecision', 'rejected', 'rolledBack', 'canaryExperiment', 'holdoutExperiment', 'objectiveVersion',
      't0Shadow', 't0ShadowExperiment', 'notLive']) {
      expect(s.ids[k], `seed-status ids.${k}`).toBeTruthy();
    }
    const counts = s.counts as { decisionRecords: Record<string, number>; episodes: number; outcomes: number; holdoutOrders: number; agentMandates: number;
      proposalsByStatus: Record<string, number> };
    expect(counts.episodes).toBeGreaterThan(1000);
    expect(counts.outcomes).toBeGreaterThan(counts.episodes);
    expect(Object.keys(counts.decisionRecords)).toEqual(expect.arrayContaining(['sourcing_node_score', 'sourcing_policy_bandit', 'sla_breach_risk', 'payment_risk', 'exception_classify']));
    expect(counts.holdoutOrders, 'orders routed to the holdout by the live router').toBeGreaterThan(0);
    expect(counts.agentMandates, 'agent reputation learns from real mandates').toBeGreaterThanOrEqual(29);
    expect(Object.keys(counts.proposalsByStatus).sort()).toEqual(['APPROVED', 'CANARY', 'DRAFT', 'EVALUATED', 'PROMOTED', 'REJECTED', 'ROLLED_BACK']);

    // A seeded decision record is labelled as demo data (one of a golden-case candidate's, else a T0 shadow pair's).
    const candidates = await ok<Array<{ sourceRecordIds: string[] }>>(api.get('/api/v1/learning/golden-candidates'));
    const seeded = candidates.flatMap((c) => c.sourceRecordIds).find((id) => id.startsWith('dr-demo-')) ?? 'dr-demo-shadow-live-demo-shadow-001';
    const record = await ok<{ actor: string; details: Record<string, unknown> }>(api.get(`/api/v1/decision-records/${seeded}`));
    expect(record.actor).toBe('system:demo-seed');
    expect(record.details.demoData).toBe(true);
  });

  test('governance steps in the seed are taken by labelled demo actors, and the T0 change passed a real shadow test', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const ids = (await seedStatus(api)).ids;
    const t0 = await ok<{ status: string; tier: string; createdBy: string; evaluationReport: { confidenceInterval?: { lower: number }; shadow?: { passed: boolean; experimentId?: string } } }>(
      api.get(`/api/v1/learning/proposals/${ids.t0Shadow}`));
    expect(t0).toMatchObject({ status: 'EVALUATED', tier: 'T0', createdBy: 'system:demo-seed.band_threshold' });
    expect(t0.evaluationReport.confidenceInterval!.lower, 'replay above zero').toBeGreaterThan(0);
    expect(t0.evaluationReport.shadow?.passed).toBe(true);
    const shadow = await ok<{ experiment: { mode: string; status: string; proposalId: string }; measurement: { paired: number; passed: boolean } }>(
      api.get(`/api/v1/learning/experiments/${ids.t0ShadowExperiment}`));
    expect(shadow.experiment).toMatchObject({ mode: 'SHADOW', status: 'COMPLETED', proposalId: ids.t0Shadow });
    expect(shadow.measurement.passed).toBe(true);
    expect(shadow.measurement.paired, 'shadow records paired with live ones').toBeGreaterThanOrEqual(30);

    for (const id of ids.promotedLiveUse.split(',')) {
      const p = await ok<{ createdBy: string; approvedBy: string }>(api.get(`/api/v1/learning/proposals/${id}`));
      expect(p.createdBy, id).toBe('system:demo-seed.strategy');
      expect(p.approvedBy, id).toBe('system:demo-seed.admin');
    }
    const live = await ok<Array<{ parameter: string; status: string }>>(api.get('/api/v1/learning/live-parameters'));
    expect(live.find((x) => x.parameter === ids.notLive)?.status ?? 'absent', `${ids.notLive} is left for a person to switch on`).not.toBe('LIVE');

    const page = await pageAs('strategy');
    await page.goto(`/learning?tab=changes&change=${ids.t0Shadow}`);
    const drawer = page.getByRole('dialog').filter({ hasText: 'What would change' });
    await expect(drawer).toContainText('Shadow test passed.');
    await expect(drawer.getByTestId('demo-badge')).toBeVisible();
    await expect(drawer).toContainText('Suggested by Confidence band tuner (demo)');
    await expect(drawer).not.toContainText('system:demo-seed');
  });

  test('every learned value the seed switched on fits its family\'s domain and has learned values behind it; what it left out is listed with the reason', async () => {
    const api = await apiAs('strategy');
    const s = await seedStatus(api) as Awaited<ReturnType<typeof seedStatus>> & { liveFamilies: string[]; skipped: Array<{ item: string; reason: string }> };
    expect(Array.isArray(s.skipped), 'seed-status lists what was skipped').toBe(true);
    for (const x of s.skipped) expect(x.reason, x.item).toBeTruthy();
    const domains = new Map((await liveDomains(api)).map((d) => [d.family, d]));
    const live = (await ok<Array<{ parameter: string; learnerKey: string; status: string; min?: number | null; max?: number | null; maxStep?: number | null }>>(
      api.get('/api/v1/learning/live-parameters'))).filter((p) => p.status === 'LIVE' && s.liveFamilies.includes(p.parameter));
    expect(live.length).toBeGreaterThanOrEqual(8);
    for (const p of live) {
      const d = domains.get(p.parameter)!;
      expect(d, `${p.parameter} has a domain`).toBeTruthy();
      if (d.kind === 'SWITCH') expect([p.min, p.max, p.maxStep].every((v) => v == null), `${p.parameter} is a switch: no range`).toBe(true);
      if (d.kind === 'NUMBER') {
        if (p.min != null) expect(p.min, `${p.parameter} min`).toBeGreaterThanOrEqual(d.min!);
        if (p.max != null) expect(p.max, `${p.parameter} max`).toBeLessThanOrEqual(d.max!);
        if (p.maxStep != null) expect(p.maxStep, `${p.parameter} max step`).toBeLessThanOrEqual(d.maxStepLimit!);
      }
    }
    // Warn-early is a 0-4 breach-risk score; the default score is 1, so a range starting above it warns only real risk (audit B-1).
    const warn = live.find((p) => p.parameter === 'monitoring.warn_early_threshold');
    if (warn) expect(warn.min!).toBeGreaterThan(1);
    // Learners that need matured or labelled history found it: fraud signal rates, a calibration curve, lane corrections, promise buffers.
    const refs = (s.counts as { referenceFacts: Record<string, number> }).referenceFacts;
    for (const key of ['FRAUD_SIGNAL_RATE', 'CALIBRATION_CURVE', 'LANE_CALIBRATION_OFFSET', 'PROMISE_BUFFER_DAYS', 'STOCK_AGE_DAYS', 'WARN_EARLY_THRESHOLD']) {
      expect(refs[key], `seeded learners produced ${key}`).toBeGreaterThan(0);
    }
    expect(refs.fraudTunerDrafts, 'the fraud threshold tuner proposed from the matured cohort').toBeGreaterThan(0);
    const fraud = await ok<Array<{ factKey: string; learner: string }>>(api.get('/api/v1/learning/facts', { params: { subjectType: 'DECISION', subjectId: 'payment_risk|velocity_1h|0' } }));
    expect(fraud.find((f) => f.factKey === 'FRAUD_SIGNAL_RATE')?.learner).toBe('fraud_signals');
  });

  test('seeded rows wear a "Demo data" badge; anything a person creates does not', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const ids = (await seedStatus(api)).ids;
    const mine = await proposeLiveUse(api, { family: 'promise.lane_calibration', learnerKey: 'lane_transit', min: -0.2, max: 0.2, maxStep: 0.05, reason: `e2e demo-badge ${uid()}` });
    try {
      const page = await pageAs('strategy');
      await page.goto('/learning?tab=changes&changes=live');
      for (const id of [ids.promotedDecision, ...ids.promotedLiveUse.split(',')]) await expect(page.getByTestId(`change-${id}`).getByTestId('demo-badge'), id).toBeVisible();
      await page.goto('/learning?tab=changes&changes=review');
      await expect(page.getByTestId(`change-${ids.evaluated}`).getByTestId('demo-badge')).toBeVisible();
      await expect(page.getByTestId(`change-${mine.proposalId}`)).toBeVisible();
      await expect(page.getByTestId(`change-${mine.proposalId}`).getByTestId('demo-badge')).toHaveCount(0);

      await page.goto('/learning?tab=experiments');
      await expect(page.getByRole('table', { name: 'Experiments' }).getByTestId('demo-badge').first()).toBeVisible();
      await expect(page.getByTestId('demo-badge').first()).toHaveAttribute('title', /Seeded reference data/);
    } finally {
      await (await apiAs('admin')).post(`/api/v1/learning/proposals/${mine.proposalId}/reject`, { data: { reason: 'e2e cleanup' } });
    }
  });
});
