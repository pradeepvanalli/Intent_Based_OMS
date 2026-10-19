import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, liveParameters, ok, proposals, shipAndDeliver, uid, type Proposal } from '../support/data';
import { dialog, toast } from '../support/ui';

type EpisodeView = {
  episode: { episodeId: string; decisionName: string; orderId: string; subjectType?: string; subjectId?: string; lineId?: string; fulfillmentGroupId?: string };
  outcomes: Array<{ type: string; sourceEventId?: string }>;
};

/**
 * UC8 in the browser: a real order produces outcomes that land on its episodes, and a learned value reaches live
 * decisions only through a governed, reversible change.
 */
test('uc8: outcomes reach the order\'s episodes, then a learned value goes live through the gate and is rolled back', async ({ pageAs }) => {
  const admin = await apiAs('admin');
  const strategyApi = await apiAs('strategy');

  // 1. A real order is captured, sourced, shipped and delivered.
  const orderId = await createOrder(admin, {
    lines: [
      { lineId: '1', sku: 'TEE-BLK-M', qty: 1, unitPrice: { amount: 28, currency: 'USD' } },
      { lineId: '2', sku: 'BOOT-LTH-9', qty: 1, unitPrice: { amount: 220, currency: 'USD' } },
    ],
  });
  await shipAndDeliver(admin, orderId);

  // 2. The customer asks where their order is; the same contact re-sent is ignored.
  const externalId = `e2e-wismo-${uid()}`;
  const first = await ok<{ recorded: boolean }>(admin.post(`/api/v1/orders/${orderId}/contacts`, { data: { reason: 'wismo', channel: 'email', source: 'e2e', externalId } }), 200, 201);
  expect(first.recorded).toBe(true);
  const again = await ok<{ recorded: boolean }>(admin.post(`/api/v1/orders/${orderId}/contacts`, { data: { reason: 'wismo', channel: 'email', source: 'e2e', externalId } }), 200, 201);
  expect(again.recorded).toBe(false);

  // 3. The delivery and the contact are attached to the order's episodes (one per decision point).
  let episodes: EpisodeView[] = [];
  await expect.poll(async () => {
    episodes = await ok<EpisodeView[]>(strategyApi.get('/api/v1/learning/episodes', { params: { orderId } }));
    const types = new Set(episodes.flatMap((e) => e.outcomes.map((o) => o.type)));
    return ['DeliveryOutcome', 'ContactOutcome'].every((t) => types.has(t));
  }, { timeout: 60_000, message: 'delivery and contact outcomes on the order\'s episodes' }).toBe(true);
  const sourcing = episodes.find((e) => e.episode.decisionName === 'sourcing_node_score');
  expect(sourcing, 'the sourcing decision point has an episode').toBeTruthy();
  expect(sourcing!.outcomes.map((o) => o.type)).toEqual(expect.arrayContaining(['DeliveryOutcome', 'ContactOutcome']));
  const points = episodes.map((e) => [e.episode.decisionName, e.episode.subjectType, e.episode.subjectId, e.episode.lineId, e.episode.fulfillmentGroupId].join('|'));
  expect(new Set(points).size, 'one episode per decision point, however often a decision re-ran').toBe(points.length);
  const contacts = sourcing!.outcomes.filter((o) => o.type === 'ContactOutcome');
  expect(contacts.length, 'the re-sent contact did not attach twice').toBe(1);

  // 4. A learned value goes live only through a governed change, in the UI.
  const marker = `uc8 ${uid()}`;
  const strategy = await pageAs('strategy');
  await strategy.goto('/learning?tab=changes');
  await strategy.getByRole('button', { name: 'Propose live use' }).click();
  const form = dialog(strategy, 'Propose using a learned value live');
  await form.getByLabel('Learned value').selectOption({ label: 'Goodwill credit amount — from Appeasement level' });
  await form.getByLabel('Lowest allowed').fill('5');
  await form.getByLabel('Highest allowed').fill('25');
  await form.getByLabel('Biggest step from standard').fill('5');
  await form.getByLabel('Why (optional)').fill(marker);
  await form.getByRole('button', { name: 'Draft proposal' }).click();
  await expect(toast(strategy, 'Proposal drafted')).toBeVisible();
  let mine: Proposal | undefined;
  await expect.poll(async () => {
    mine = (await proposals(strategyApi, 'DRAFT')).find((p) => (p.changeset as { motivatedBy?: string }).motivatedBy === marker);
    return !!mine;
  }).toBe(true);

  const adminPage = await pageAs('admin');
  await adminPage.goto(`/learning?tab=changes&change=${mine!.proposalId}`);
  const drawer = adminPage.getByRole('dialog').filter({ hasText: 'What would change' });
  await expect(drawer).toContainText('Goodwill credit amount');
  await drawer.getByRole('button', { name: 'Test on past decisions' }).click();
  await expect(toast(adminPage, 'Tested on past decisions')).toBeVisible();
  await drawer.getByRole('button', { name: 'Approve' }).click();
  await expect(toast(adminPage, 'Approved')).toBeVisible();
  await drawer.getByRole('button', { name: 'Go live' }).click();
  await expect(toast(adminPage, 'Live now')).toBeVisible();
  await expect.poll(async () => (await liveParameters(admin)).find((p) => p.parameter === 'cs.appeasement_level')?.status).toBe('LIVE');

  await drawer.getByRole('button', { name: 'Roll back' }).click();
  const reason = dialog(adminPage, 'Roll back this change');
  await reason.getByLabel('Reason').fill('UC8 demo: roll back after review');
  await reason.getByRole('button', { name: 'Roll back' }).click();
  await expect(toast(adminPage, 'Rolled back')).toBeVisible();
  await expect.poll(async () => (await liveParameters(admin)).find((p) => p.parameter === 'cs.appeasement_level')?.status).toBe('REVOKED');

  await adminPage.getByRole('button', { name: 'Close' }).first().click();
  const panel = adminPage.locator('section', { has: adminPage.getByRole('heading', { name: /Learned values used in live decisions/ }) });
  await expect(panel.locator('.row-between', { hasText: 'Goodwill credit amount' })).toContainText('Switched off');
});
