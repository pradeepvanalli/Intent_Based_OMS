import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { proposal, seedStatus } from '../support/data';

/**
 * D5-5 (XP-02): the Changes-list row never read p.criticReview, so a proposal the automatic critic flagged RISKY (or
 * NEEDS_CHANGES) showed no indication in the list -- only the drawer (opened separately) showed it. The seeded T0
 * exception-triage change (prop_demo_t0_exception_triage) is exactly that case: its IPS cross-check and mature-share
 * checks flag it RISKY (see 35-exploration-and-critic.spec.ts for the drawer-side assertion), yet it stays EVALUATED
 * (RISKY blocks auto-promotion), so a reviewer skimming the list could not tell without opening every row.
 */
test.describe('Changes list critic flag', () => {
  test('a row for a critic-flagged proposal shows the flag; a not-yet-reviewed one does not', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const ids = (await seedStatus(api)).ids;

    // Confirm, against the live data, exactly what the row must now reflect (the drawer-side spec already checks
    // the drawer shows this same review).
    const t0 = await proposal(api, ids.t0Shadow);
    expect((t0 as unknown as { criticReview?: { verdict: string } }).criticReview?.verdict).toBe('RISKY');
    const draft = await proposal(api, ids.draft);
    expect((draft as unknown as { criticReview?: unknown }).criticReview ?? null, 'a draft has not been evaluated, so the critic has not reviewed it yet').toBeNull();

    const page = await pageAs('strategy');
    await page.goto('/learning?tab=changes'); // default group ("To review") holds DRAFT and EVALUATED

    const t0Row = page.getByTestId(`change-${ids.t0Shadow}`);
    await expect(t0Row).toBeVisible();
    await expect(t0Row.getByTestId('critic-flag')).toBeVisible();
    await expect(t0Row.getByTestId('critic-flag')).toContainText('Risky');

    const draftRow = page.getByTestId(`change-${ids.draft}`);
    await expect(draftRow).toBeVisible();
    await expect(draftRow.getByTestId('critic-flag')).toHaveCount(0);
  });
});
