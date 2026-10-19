import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, customerRefFor, ok, waitForDecision } from '../support/data';

type Fact = { subjectId: string; factKey: string; source: string; reason?: string };
type Trace = { learning?: { learnedFacts?: Fact[]; experiment?: { experimentId?: string; arm?: string } } };

async function sourcingTrace(api: Awaited<ReturnType<typeof apiAs>>, holdout: boolean) {
  const { ref, experimentId } = await customerRefFor(api, holdout);
  const orderId = await createOrder(api, { ref });
  const record = await waitForDecision(api, orderId, 'sourcing_node_score');
  const trace = await ok<Trace>(api.get(`/api/v1/decision-records/${record.id}/why`));
  return { orderId, experimentId, facts: trace.learning?.learnedFacts ?? [], experiment: trace.learning?.experiment };
}

/**
 * EX-04: a customer in the persistent holdout is kept on the baseline, learned values included, so lift can be
 * measured honestly. The Why drawer of their order says it read the learned values but used the standard ones, and
 * why; a customer outside the holdout, ordering the same thing, gets the learned values.
 */
test('a holdout customer gets the standard values, and the Why drawer says so in words', async ({ pageAs }) => {
  const api = await apiAs('admin');

  const treated = await sourcingTrace(api, false);
  expect(treated.facts.filter((f) => f.source === 'LEARNED').length, 'a treated customer is served learned node values').toBeGreaterThan(0);
  expect(treated.experiment?.arm).not.toBe('holdout');

  const held = await sourcingTrace(api, true);
  expect(held.facts.length, 'the same node facts are read').toBeGreaterThan(0);
  expect(held.facts.filter((f) => f.source === 'LEARNED'), 'none of them is used').toEqual([]);
  // Every value the treated customer was served is held back for this customer, because of the holdout (values never
  // switched on for live use say so instead).
  const heldBack = held.facts.filter((f) => (f.reason ?? '').includes('holdout customer'));
  expect(heldBack.length, 'held back because of the holdout').toBeGreaterThanOrEqual(new Set(treated.facts.filter((f) => f.source === 'LEARNED').map((f) => f.factKey)).size);
  for (const f of held.facts) expect(f.reason, `${f.subjectId} ${f.factKey}`).toMatch(/holdout customer|not promoted for live use/);
  expect(held.experiment).toMatchObject({ experimentId: held.experimentId, arm: 'holdout' });

  const page = await pageAs('admin');
  await page.goto(`/orders/${held.orderId}?tab=decisions`);
  await page.getByRole('table', { name: 'Decision records' }).getByRole('row').filter({ hasText: 'sourcing node score' }).first().getByRole('button', { name: 'Why' }).click();
  const drawer = page.getByRole('dialog', { name: /Why/ });
  const learning = drawer.getByRole('region', { name: 'How learning shaped this decision' });
  await expect(learning).toBeVisible();
  await expect(learning).toContainText(new RegExp(`It read ${held.facts.length} learned values? but used the standard values\\.`));
  const items = learning.getByRole('list', { name: 'Learned values read' }).getByRole('listitem');
  await expect(items).toHaveCount(held.facts.length);
  await expect(items.filter({ hasText: 'not used: this customer is in the comparison group that keeps the standard values' })).toHaveCount(heldBack.length);
  await expect(learning).toContainText('Part of an experiment: this customer is in the holdout group');
  await expect(learning).not.toContainText('EX-04');
});
