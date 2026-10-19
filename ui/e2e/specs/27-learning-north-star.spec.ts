import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { liveDomains, ok, proposal, seedStatus } from '../support/data';
import { dialog } from '../support/ui';

type Headline = { nTreated: number; nHoldout: number; treatedMean?: number; holdoutMean?: number; lift?: number | null; enoughData: boolean; significant: boolean; improvement?: boolean; label: string };
type Guardrails = { weeks: number; verdict: 'worse' | 'not_worse' | 'insufficient'; summary: string; comparison: string; metrics: Array<{ metric: string; label: string; verdict: string }> };
type Critic = { verdict: 'LOOKS_SAFE' | 'NEEDS_CHANGES' | 'RISKY'; risks: string[]; source: 'llm' | 'rules'; reviewedAt: string };
type Fairness = { decision: string; groups: Array<{ group: string; label?: string; customers: number; decisions?: number; onTimeRate?: number | null }> };

const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
const VERDICT: Record<Critic['verdict'], string> = { LOOKS_SAFE: 'Looks safe', NEEDS_CHANGES: 'Needs changes', RISKY: 'Risky' };

/** Wave F on the Learning console: the north star, the guardrail report, the automatic critic, family domains and outcome fairness. */
test.describe('Learning: north star, guardrails, critic, domains, fairness', () => {
  test('the Overview leads with the perfect-order rate from seeded history, with learning vs the holdout', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const lift = await ok<{ headline: Headline; holdout?: unknown }>(api.get('/api/v1/learning/lift', { params: { days: 90 } }));
    const h = lift.headline;
    expect(h.label).toBe('Perfect-order rate');
    // The seeded history carries the unweighted perfect component on whole-order episodes, holdout included.
    expect(h.nTreated, 'orders with a perfect-order outcome, with learning').toBeGreaterThanOrEqual(30);
    expect(h.nHoldout, 'orders with a perfect-order outcome, in the holdout').toBeGreaterThanOrEqual(30);
    expect(h.treatedMean).toBeGreaterThan(0);
    expect(h.treatedMean).toBeLessThan(1);
    expect(h.holdoutMean).toBeGreaterThan(0);

    const page = await pageAs('strategy');
    await page.goto('/learning');
    const headline = page.getByTestId('perfect-order-headline');
    await expect(headline).toContainText('Perfect-order rate');
    await expect(headline).toContainText(`${pct(h.treatedMean!)} with learning vs ${pct(h.holdoutMean!)} in the holdout`);
    await expect(headline).toContainText('(95% interval');
    const verdict = !h.enoughData || h.lift == null ? 'Not enough data yet' : !h.significant ? 'No clear difference yet' : h.improvement ? 'Better with learning' : 'Worse with learning';
    await expect(headline).toContainText(verdict);
  });

  test('the 4-week guardrail report shows each guardrail with a verdict', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const report = await ok<Guardrails>(api.get('/api/v1/learning/guardrails/report', { params: { weeks: 4 } }));
    expect(report.weeks).toBe(4);
    expect(report.metrics.map((m) => m.label)).toEqual(expect.arrayContaining(['Override rate', 'Decision latency (p95)', 'Customer contact rate', 'Fraud false-positive rate', 'Perfect-order rate']));

    const page = await pageAs('strategy');
    await page.goto('/learning');
    await expect(page.getByRole('heading', { name: /^Guardrails, last 4 weeks/ })).toBeVisible();
    const card = page.getByTestId('guardrail-report');
    await expect(card).toContainText(report.summary);
    const table = card.getByRole('table', { name: 'Guardrails with learning vs without' });
    for (const col of ['Guardrail', 'With learning', 'Compared with', 'Result']) await expect(table.getByRole('columnheader', { name: col })).toBeVisible();
    await expect(table.locator('tbody tr')).toHaveCount(report.metrics.length);
    const words: Record<string, string> = { worse: 'Worse', not_worse: 'Not worse', insufficient: 'Too little data' };
    for (const m of report.metrics) await expect(table.getByRole('row').filter({ hasText: m.label })).toContainText(words[m.verdict]);
  });

  test('every tested proposal carries the automatic critic\'s review, shown as a second opinion', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const ids = (await seedStatus(api)).ids;
    const p = await proposal(api, ids.approved) as Awaited<ReturnType<typeof proposal>> & { criticReview?: Critic };
    expect(p.criticReview, 'the seed tested this change, so the critic reviewed it').toBeTruthy();
    expect(Object.keys(p.criticReview!).sort()).toEqual(['reviewedAt', 'risks', 'source', 'verdict']);
    expect(p.criticReview!.source, 'no AI key in the e2e profile: the rule critic').toBe('rules');
    expect(Array.isArray(p.criticReview!.risks)).toBe(true);
    expect(Number.isNaN(Date.parse(p.criticReview!.reviewedAt))).toBe(false);

    const page = await pageAs('strategy');
    await page.goto(`/learning?tab=changes&change=${encodeURIComponent(ids.approved)}`);
    const drawer = page.getByRole('dialog').filter({ hasText: 'What would change' });
    await expect(drawer).toContainText('Second opinion');
    await expect(drawer).toContainText(VERDICT[p.criticReview!.verdict]);
    if (p.criticReview!.risks.length) await expect(drawer).toContainText(p.criticReview!.risks[0]);
    else await expect(drawer).toContainText('No risks listed.');
    await expect(drawer.getByTestId('critic-source')).toContainText('Reviewed by fixed checks');
    await expect(drawer.getByTestId('critic-source')).toContainText('automatically after testing');
  });

  test('proposing live use shows the family\'s domain and refuses a range outside it, as the server does', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const warn = (await liveDomains(api)).find((d) => d.family === 'monitoring.warn_early_threshold')!;
    expect(warn).toMatchObject({ kind: 'NUMBER', min: 0, max: 4, maxStepLimit: 2 });

    // The server refuses the old probability-style range (B-1: 0.3-0.9 once warned every order) only by step and keeps
    // the units honest: a max above the score's top and a step over half the domain are both named.
    const refused = await api.post('/api/v1/learning/live-parameters/proposals', { data: { enabled: true, family: warn.family, learnerKey: 'warn_early', min: 1, max: 5, maxStep: 3 } });
    expect(refused.status()).toBe(422);
    const problem = await refused.json() as { problems: string[]; domain: { family: string } };
    expect(problem.domain.family).toBe(warn.family);
    expect(problem.problems).toEqual([
      `max 5 is outside the values ${warn.family} can take: ${warn.description}`,
      `max step 3 is too large for ${warn.family}: one promotion may move it at most 2 (half of 0 to 4)`,
    ]);

    const page = await pageAs('strategy');
    await page.goto('/learning?tab=changes');
    await page.getByRole('button', { name: 'Propose live use' }).click();
    const form = dialog(page, 'Propose using a learned value live');
    await form.getByLabel('Learned value').selectOption('warn_early|monitoring.warn_early_threshold');
    await expect(form.getByTestId('live-domain')).toContainText(`Values: ${warn.description} · one change may move it at most 2`);
    await expect(form.getByTestId('live-domain')).toContainText('read by sla_breach_risk');
    await form.getByLabel('Lowest allowed').fill('1');
    await form.getByLabel('Highest allowed').fill('5');
    await form.getByLabel('Biggest step from standard').fill('3');
    const problems = form.getByTestId('live-range-problems');
    await expect(problems).toContainText('This range cannot be used');
    for (const text of problem.problems) await expect(problems).toContainText(text);
    await expect(form.getByRole('button', { name: 'Draft proposal' })).toBeDisabled();
    await form.getByLabel('Highest allowed').fill('3.5');
    await form.getByLabel('Biggest step from standard').fill('1');
    await expect(problems).toHaveCount(0);
    await expect(form.getByRole('button', { name: 'Draft proposal' })).toBeEnabled();
    await form.getByRole('button', { name: 'Cancel' }).click();
  });

  test('fairness compares outcomes too: each customer group shows how often its orders arrived on time', async ({ pageAs }) => {
    const api = await apiAs('admin');
    const report = await ok<Fairness>(api.get('/api/v1/learning/fairness/sourcing_node_score'));
    const withOutcomes = report.groups.filter((g) => g.onTimeRate != null);
    expect(withOutcomes.length, 'seeded orders have delivery outcomes per group').toBeGreaterThan(0);

    const page = await pageAs('admin');
    await page.goto('/learning?tab=settings');
    await page.getByRole('combobox', { name: 'Decision to check' }).selectOption({ label: 'Sourcing node score' });
    const table = page.getByRole('table', { name: 'Fairness by customer group' });
    await expect(table.getByRole('columnheader', { name: 'Arrived on time' })).toHaveAttribute('title', "Share of this group's orders delivered on time");
    const g = withOutcomes[0];
    await expect(table.getByRole('row').filter({ hasText: g.label ?? g.group }).first()).toContainText(`${(g.onTimeRate! * 100).toFixed(0)}%`);
    await expect(table.getByRole('row').filter({ hasText: 'All customers' })).toBeVisible();
  });
});
