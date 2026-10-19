import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { liveParameters, liveUseThroughTheGate, ok, proposal, proposals, proposeLiveUse, seedStatus, uid, type Proposal } from '../support/data';
import { dialog, toast } from '../support/ui';

const WORDS: Record<string, string> = {
  DRAFT: 'Waiting for review', EVALUATED: 'Tested, waiting for review', APPROVED: 'Approved, not live yet', CANARY: 'Being tried on some customers',
  PROMOTED: 'Live', REJECTED: 'Rejected', ROLLED_BACK: 'Rolled back',
};

/** The drawer for one proposal, opened by deep link the way a shared link would. */
async function openChange(page: import('@playwright/test').Page, id: string) {
  await page.goto(`/learning?tab=changes&change=${encodeURIComponent(id)}`);
  const drawer = page.getByRole('dialog').filter({ hasText: 'What would change' });
  await expect(drawer).toBeVisible();
  return drawer;
}

test.describe('Changes', () => {
  test('every seeded proposal shows under its status filter, in words', async ({ pageAs }) => {
    const ids = (await seedStatus(await apiAs('strategy'))).ids;
    const groups: Array<[string, string[]]> = [
      ['To review', [ids.draft, ids.evaluated]],
      ['Approved', [ids.approved]],
      ['Being tried', [ids.canary]],
      ['Live', [ids.promotedDecision, ...ids.promotedLiveUse.split(',')]],
      ['Rejected or rolled back', [ids.rejected, ids.rolledBack]],
    ];
    const api = await apiAs('strategy');
    const page = await pageAs('strategy');
    await page.goto('/learning?tab=changes');
    const filters = page.getByRole('tablist', { name: 'Change status' });
    for (const [label, wanted] of groups) {
      await filters.getByRole('tab', { name: new RegExp(`^${label}`) }).click();
      for (const id of wanted) {
        const p = await proposal(api, id);
        const row = page.getByTestId(`change-${id}`);
        await expect(row, `${id} under ${label}`).toBeVisible();
        await expect(row).toContainText(WORDS[p.status]);
        await expect(row).not.toContainText(id); // ids only with technical details on
      }
    }
  });

  test('the change card explains what, why, the replay result and what changed since the last approval', async ({ pageAs }) => {
    const ids = (await seedStatus(await apiAs('strategy'))).ids;
    const page = await pageAs('strategy');
    const drawer = await openChange(page, ids.promotedDecision);
    for (const section of ['What would change', 'Why it was suggested', 'Tested on past decisions?', 'History']) await expect(drawer).toContainText(section);
    await expect(drawer).toContainText('move the act confidence threshold from 0.80 to 0.75');
    await expect(drawer.getByRole('table', { name: 'Values' })).toBeVisible();
    await expect(drawer).toContainText(/Looks better|No clear difference|Looks worse|No replay estimate/);
    await expect(drawer).toContainText('Since the last approved version');
    await expect(drawer).toContainText(/Approved by (strategy|admin)/);
    await expect(drawer).toContainText(/Went live/);
    await expect(drawer.getByTestId('demo-badge')).toBeVisible();
    // A strategy lead is a learning admin, so the live change offers its one-click rollback.
    await expect(drawer.getByRole('button', { name: 'Roll back' })).toBeVisible();
  });

  test('live use goes through the gate: the author cannot approve, an admin tests, approves, takes it live and rolls it back', async ({ pageAs }) => {
    const strategyApi = await apiAs('strategy');
    const adminApi = await apiAs('admin');
    const marker = `e2e lifecycle ${uid()}`;

    // Strategy drafts it in the UI.
    const strategy = await pageAs('strategy');
    await strategy.goto('/learning?tab=changes');
    await strategy.getByRole('button', { name: 'Propose live use' }).click();
    const form = dialog(strategy, 'Propose using a learned value live');
    await form.getByLabel('Learned value').selectOption({ label: 'Prefer aged stock when costs are close — from Stock age' });
    // B-1: an on/off family shows its domain and takes no range; one typed in is refused before anything is sent.
    await expect(form.getByTestId('live-domain')).toContainText('Values: an on/off switch (near-equal plans prefer the node with the oldest stock)');
    await form.getByLabel('Lowest allowed').fill('0');
    await expect(form.getByRole('alert')).toContainText('sourcing.aged_stock_tiebreak is an on/off switch');
    await expect(form.getByRole('alert')).toContainText('leave min, max and max step empty');
    await expect(form.getByRole('button', { name: 'Draft proposal' })).toBeDisabled();
    await form.getByLabel('Lowest allowed').fill('');
    await expect(form.getByRole('alert')).toHaveCount(0);
    await form.getByLabel('Why (optional)').fill(marker);
    await form.getByRole('button', { name: 'Draft proposal' }).click();
    await expect(toast(strategy, 'Proposal drafted')).toBeVisible();

    let mine: Proposal | undefined;
    await expect.poll(async () => {
      mine = (await proposals(strategyApi, 'DRAFT')).find((p) => (p.changeset as { motivatedBy?: string }).motivatedBy === marker);
      return !!mine;
    }).toBe(true);
    const id = mine!.proposalId;
    expect(mine!.changeset.target.name).toBe('live:sourcing.aged_stock_tiebreak');

    // The author sees why they cannot approve; the server refuses too.
    const own = await openChange(strategy, id);
    await expect(own).toContainText('You suggested this change, so someone else needs to approve it.');
    await expect(own.getByRole('button', { name: 'Approve' })).toBeDisabled();
    const refused = await strategyApi.post(`/api/v1/learning/proposals/${id}/approve`, { data: {} });
    expect(refused.status()).toBe(422);
    expect(await refused.text()).toContain('cannot approve it themselves');

    // An admin tests it, approves, takes it live and rolls it back.
    const admin = await pageAs('admin');
    const drawer = await openChange(admin, id);
    await drawer.getByRole('button', { name: 'Test on past decisions' }).click();
    await expect(toast(admin, 'Tested on past decisions')).toBeVisible();
    await expect(drawer).toContainText(/Cannot be replayed; needs a person to judge|Compared similar past orders|Replayed against past decisions/);
    await drawer.getByRole('button', { name: 'Approve' }).click();
    await expect(toast(admin, 'Approved')).toBeVisible();
    await expect(drawer).toContainText('Approved, not live yet');
    // Live use cannot be replayed, so going live means waiving full evidence with a written reason (EX-07).
    await drawer.getByRole('button', { name: /^Go live/ }).click();
    const waive = dialog(admin, 'Go live without full evidence?');
    await waive.getByLabel('Why go live anyway?').fill('Shadow run not possible for a live-use family; e2e suite');
    await waive.getByRole('button', { name: 'Go live anyway' }).click();
    await expect(toast(admin, 'Live now')).toBeVisible();
    await expect.poll(async () => (await liveParameters(adminApi)).find((p) => p.parameter === 'sourcing.aged_stock_tiebreak')?.status).toBe('LIVE');

    await drawer.getByRole('button', { name: 'Roll back' }).click();
    const reason = dialog(admin, 'Roll back this change');
    await reason.getByLabel('Reason').fill(`${marker}: rolled back by the e2e suite`);
    await reason.getByRole('button', { name: 'Roll back' }).click();
    await expect(toast(admin, 'Rolled back')).toBeVisible();
    await expect(drawer).toContainText(/Rolled back .*rolled back by the e2e suite/);
    const after = await proposal(adminApi, id);
    expect(after.status).toBe('ROLLED_BACK');
    expect(after.approvedBy).toBe('user:admin');
    await expect.poll(async () => (await liveParameters(adminApi)).find((p) => p.parameter === 'sourcing.aged_stock_tiebreak')?.status).toBe('REVOKED');
  });

  test('a change can be rejected with a reason', async ({ pageAs }) => {
    const strategyApi = await apiAs('strategy');
    const p = await proposeLiveUse(strategyApi, { family: 'promise.buffer_days', learnerKey: 'promise_buffer', min: 0, max: 3, maxStep: 1, reason: `e2e reject ${uid()}` });
    const admin = await pageAs('admin');
    const drawer = await openChange(admin, p.proposalId);
    await drawer.getByRole('button', { name: 'Reject' }).click();
    const reason = dialog(admin, 'Reject this change');
    await expect(reason.getByRole('button', { name: 'Reject' })).toBeDisabled(); // a reason is required
    await reason.getByLabel('Reason').fill('Buffer days should wait for real storefront conversion data');
    await reason.getByRole('button', { name: 'Reject' }).click();
    await expect(toast(admin, 'Rejected')).toBeVisible();
    const after = await proposal(await apiAs('admin'), p.proposalId);
    expect(after.status).toBe('REJECTED');
    expect(after.rejectedReason).toBe('Buffer days should wait for real storefront conversion data');
  });

  test('approving a postal-code change requires a reason code', async ({ pageAs }) => {
    const strategyApi = await apiAs('strategy');
    const adminApi = await apiAs('admin');
    // No live family is keyed by postal code, so switching one on is refused (B-1: it has no domain to check a range
    // against); the switch-off form of the proposal needs no domain and is still a change that names postal codes.
    const refused = await strategyApi.post('/api/v1/learning/live-parameters/proposals', { data: { enabled: true, family: 'sourcing.blocked_zip3', learnerKey: 'band_threshold' } });
    expect(refused.status()).toBe(422);
    expect(await refused.text()).toContain('is not a learned value any live path reads');
    const p = await ok<Proposal>(strategyApi.post('/api/v1/learning/live-parameters/proposals', {
      data: { enabled: false, family: 'sourcing.blocked_zip3', learnerKey: 'band_threshold', reason: `e2e postal ${uid()}` },
    }), 200, 201);
    try {
      await ok(adminApi.post(`/api/v1/learning/proposals/${p.proposalId}/evaluate`)); // a draft is tested before approval
      expect((await (await apiAs('ops')).post(`/api/v1/learning/proposals/${p.proposalId}/approve`, { data: {} })).status()).toBe(403);
      const noCode = await adminApi.post(`/api/v1/learning/proposals/${p.proposalId}/approve`, { data: {} });
      expect(noCode.status()).toBe(422);
      expect(await noCode.text()).toContain('reason code');

      const admin = await pageAs('admin');
      const drawer = await openChange(admin, p.proposalId);
      await drawer.getByRole('button', { name: 'Approve' }).click();
      const codes = dialog(admin, 'Approve a postal-code change');
      await expect(codes).toContainText('can disadvantage a neighbourhood');
      await expect(codes.getByRole('button', { name: 'Approve' })).toBeDisabled();
      await codes.getByLabel('Reason code').selectOption({ label: 'Carrier does not serve these areas reliably' });
      await codes.getByRole('button', { name: 'Approve' }).click();
      await expect(toast(admin, 'Approved')).toBeVisible();
      expect((await proposal(adminApi, p.proposalId)).status).toBe('APPROVED');
    } finally {
      await adminApi.post(`/api/v1/learning/proposals/${p.proposalId}/reject`, { data: { reason: 'e2e cleanup' } });
    }
  });

  test('learned values in live use are listed, and switching one off takes effect at once', async ({ pageAs }) => {
    const strategyApi = await apiAs('strategy');
    const adminApi = await apiAs('admin');
    await liveUseThroughTheGate(strategyApi, adminApi, 'sourcing.node_backlog_breach', 'node_backlog_breach');

    const admin = await pageAs('admin');
    await admin.goto('/learning?tab=changes');
    const panel = admin.locator('section', { has: admin.getByRole('heading', { name: /Learned values used in live decisions/ }) });
    for (const name of ['Store and warehouse reject rates', 'Store and warehouse on-time rates', 'Carrier transit times']) {
      const row = panel.locator('.row-between', { hasText: name });
      await expect(row).toContainText('In use');
      await expect(row.getByTestId('demo-badge')).toBeVisible();
    }
    const backlog = panel.locator('.row-between', { hasText: 'Backlog-to-late-delivery curve' });
    await expect(backlog).toContainText('In use');
    await expect(backlog.getByTestId('demo-badge')).toHaveCount(0);

    await backlog.getByRole('button', { name: 'Switch off Backlog-to-late-delivery curve' }).click();
    const confirm = dialog(admin, 'Stop using this learned value?');
    await confirm.getByRole('button', { name: 'Switch off' }).click();
    await expect(toast(admin, 'Switched off')).toBeVisible();
    await expect(backlog).toContainText('Switched off');
    expect((await liveParameters(adminApi)).find((p) => p.parameter === 'sourcing.node_backlog_breach')?.status).toBe('REVOKED');
  });

  test('only learning admins see the actions', async ({ pageAs }) => {
    const ids = (await seedStatus(await apiAs('ops'))).ids;
    const ops = await pageAs('ops');
    await ops.goto('/learning?tab=changes');
    await expect(ops.getByRole('button', { name: 'Propose live use' })).toHaveCount(0);
    const drawer = await openChange(ops, ids.approved);
    await expect(drawer.getByRole('button', { name: 'Go live' })).toHaveCount(0);
    await expect(drawer.getByRole('button', { name: 'Close' }).last()).toBeVisible();
    // A read-only role still gets the same plain explanation the API gives.
    const res = await (await apiAs('ops')).post(`/api/v1/learning/proposals/${ids.approved}/promote`, { data: {} });
    expect(res.status()).toBe(403);
    await ok(await (await apiAs('ops')).get(`/api/v1/learning/proposals/${ids.approved}/card`));
  });
});
