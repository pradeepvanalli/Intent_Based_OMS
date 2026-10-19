import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { ok, proposeLiveUse, seedStatus, uid } from '../support/data';

/** Seeded reference data is complete, written as the demo-seed actor, and labelled wherever people see it. */
test.describe('Demo reference data', () => {
  test('the seed is complete and every seeded history row carries the demo-seed actor', async () => {
    const api = await apiAs('strategy');
    const s = await seedStatus(api);
    expect(s.enabled).toBe(true);
    expect(s.complete).toBe(true);
    expect(s.actor).toBe('system:demo-seed');
    for (const k of ['draft', 'evaluated', 'approved', 'canary', 'promotedDecision', 'rejected', 'rolledBack', 'canaryExperiment', 'holdoutExperiment', 'objectiveVersion']) {
      expect(s.ids[k], `seed-status ids.${k}`).toBeTruthy();
    }
    const counts = s.counts as { decisionRecords: Record<string, number>; episodes: number; outcomes: number };
    expect(counts.episodes).toBeGreaterThan(1000);
    expect(counts.outcomes).toBeGreaterThan(counts.episodes);
    expect(Object.keys(counts.decisionRecords)).toEqual(expect.arrayContaining(['sourcing_node_score', 'sourcing_policy_bandit', 'sla_breach_risk', 'payment_risk']));

    // A seeded decision record (referenced by a golden-case candidate) is labelled as demo data.
    const candidates = await ok<Array<{ sourceRecordIds: string[] }>>(api.get('/api/v1/learning/golden-candidates'));
    const seeded = candidates.flatMap((c) => c.sourceRecordIds).find((id) => id.startsWith('dr-demo-'));
    test.skip(!seeded, 'every golden-case candidate was already signed off on this database');
    const record = await ok<{ actor: string; details: Record<string, unknown> }>(api.get(`/api/v1/decision-records/${seeded}`));
    expect(record.actor).toBe('system:demo-seed');
    expect(record.details.demoData).toBe(true);
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
