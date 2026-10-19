import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, ok, waitForDecision } from '../support/data';
import { setSwitch } from '../support/ui';

type Trace = { learning?: { learnedFacts?: Array<{ subjectId: string; factKey: string; version?: number; source: string; reason?: string }>; learnedFactsServed?: number } };

/** XP-03: the Why drawer for a real order placed after seeding shows which learned values its sourcing decision used. */
test('the Why drawer shows how learning shaped a sourcing decision, with versions on request and nothing internal for customers', async ({ pageAs }) => {
  const api = await apiAs('admin');
  const orderId = await createOrder(api);
  const record = await waitForDecision(api, orderId, 'sourcing_node_score');
  const trace = await ok<Trace>(api.get(`/api/v1/decision-records/${record.id}/why`));
  const facts = trace.learning?.learnedFacts ?? [];
  expect(facts.length, 'node facts were consulted').toBeGreaterThan(0);
  const served = facts.filter((f) => f.source === 'LEARNED');
  expect(served.length, 'the demo promotes node reliability for live use, so some facts are served').toBeGreaterThan(0);

  const page = await pageAs('admin');
  await page.goto(`/orders/${orderId}?tab=decisions`);
  const table = page.getByRole('table', { name: 'Decision records' });
  await table.getByRole('row').filter({ hasText: 'sourcing node score' }).first().getByRole('button', { name: 'Why' }).click();
  const drawer = page.getByRole('dialog', { name: /Why/ });
  const learning = drawer.getByRole('region', { name: 'How learning shaped this decision' });
  await expect(learning).toBeVisible();
  await expect(learning).toContainText(new RegExp(`It used ${served.length} of ${facts.length} learned value`));
  const items = learning.getByRole('list', { name: 'Learned values read' }).getByRole('listitem');
  await expect(items).toHaveCount(facts.length);
  await expect(learning.getByText(/^used/).first()).toBeVisible();
  await expect(learning).toContainText(/Node .*: (on-time rate|reject rate)/);
  await expect(learning).not.toContainText(/ · v\d+/);

  await setSwitch(drawer, 'Technical details', true);
  await expect(learning).toContainText(/ · v\d+/);
  await setSwitch(drawer, 'Technical details', false);

  await setSwitch(drawer, 'Customer-safe view', true);
  await expect(drawer.getByText(/Customer-safe view \(FR-XAI-04\)/)).toBeVisible();
  await expect(drawer.getByRole('region', { name: 'How learning shaped this decision' })).toHaveCount(0);
  const known = drawer.locator('section', { has: page.getByText('What we know from past orders') });
  if (await known.count()) {
    await expect(known).not.toContainText(/\d\.\d|v\d+|NODE_|_RATE/);
  }
});
