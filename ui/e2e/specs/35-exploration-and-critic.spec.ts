import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { ok, proposal, uid } from '../support/data';

type Exception = { id: string; type: string; classificationRecordId?: string };
type Exploration = { share: number; explored: boolean; normalArm: string; applied: string; propensity: number; reason?: string };
type Record_ = { id: string; decision: string; layer: string; band: string; value: unknown; chosenArm?: string; propensity: number; details?: { exploration?: Exploration; modelAnswer?: unknown } };
type Critic = { verdict: 'LOOKS_SAFE' | 'NEEDS_CHANGES' | 'RISKY'; risks: string[]; source: string };
type Evaluated = { criticReview?: Critic; evaluationReport?: { ips?: { confidenceInterval?: { upper: number } }; rewards?: { matureShare?: number } } };

test.describe('EX-01 and XP-02', () => {
  /**
   * EX-01: the demo tenant lets its T0 exception triage explore 10% of live classifications, so every classification
   * that acts logs the true probability of what it applied (0.9 for its own choice, 0.1 over the other options when it
   * explored) instead of 1.0, which is what IPS needs to correct anything.
   */
  test('live exception classifications log real propensities under the exploration floor', async () => {
    const ops = await apiAs('admin');
    const logged: Record_[] = [];
    for (let i = 0; i < 6; i++) {
      const e = await ok<Exception>(ops.post('/api/v1/exceptions', { data: { source: 'e2e', code: `late_scan_${uid()}`, message: 'the carrier is late with the parcel' } }), 200, 201);
      expect(e.classificationRecordId, 'raised without a type, so it was classified').toBeTruthy();
      logged.push(await ok<Record_>(ops.get(`/api/v1/decision-records/${e.classificationRecordId}`)));
    }
    const acted = logged.filter((r) => r.layer === 'L1_SYSTEM_ONE' && r.band === 'ACT');
    expect(acted.length, 'classifications the model answered and acted on').toBeGreaterThan(0);
    // The demo tenant keeps a holdout slice on the baseline (EX-04); a held-out subject does not draw, logs the certain
    // arm (propensity 1) and says so. Every other acting classification draws and logs the true probability.
    const drew = acted.filter((r) => r.details?.exploration?.reason !== 'holdout');
    expect(drew.length, 'at least one classification outside the holdout').toBeGreaterThan(0);
    for (const r of acted) {
      const x = r.details?.exploration;
      expect(x, `${r.id} says whether it drew`).toMatchObject({ share: 0.1, applied: r.chosenArm });
      if (x!.reason === 'holdout') {
        expect(r.propensity, `${r.id} is held out, so its arm was certain`).toBe(1);
        expect(x!.explored).toBe(false);
        continue;
      }
      expect(r.propensity, `${r.id} logs a real propensity`).toBeLessThan(1);
      if (x!.explored) expect(r.details!.modelAnswer).toBe(x!.normalArm);
      else expect(r.propensity).toBeCloseTo(0.9, 6);
    }
  });

  /**
   * XP-02 (audit E3-N2): the seeded T0 triage change has a doubly-robust estimate above zero but an IPS interval
   * entirely below it and no final reward; the automatic rule critic now lists those risks instead of "looks safe",
   * and the change drawer shows it.
   */
  test('the critic flags the seeded T0 change on its IPS cross-check and mature share', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const p = await proposal(api, 'prop_demo_t0_exception_triage') as Awaited<ReturnType<typeof proposal>> & Evaluated;
    expect(p.criticReview, 'the seed tested it, so the critic reviewed it').toBeTruthy();
    expect(p.criticReview!.verdict).not.toBe('LOOKS_SAFE');
    if ((p.evaluationReport?.ips?.confidenceInterval?.upper ?? 0) < 0) expect(p.criticReview!.risks.join(' ')).toContain('inverse-propensity');
    if ((p.evaluationReport?.rewards?.matureShare ?? 1) < 0.5) expect(p.criticReview!.risks.join(' ')).toContain('of the rewards behind the estimate are final');

    const page = await pageAs('strategy');
    await page.goto(`/learning?tab=changes&change=${encodeURIComponent('prop_demo_t0_exception_triage')}`);
    const drawer = page.getByRole('dialog').filter({ hasText: 'What would change' });
    await expect(drawer).toContainText('Second opinion');
    await expect(drawer).not.toContainText('Looks safe');
    await expect(drawer).toContainText(p.criticReview!.risks[0]);
  });
});
