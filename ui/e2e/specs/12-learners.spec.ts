import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { learners, ok, type Learner } from '../support/data';
import { dialog, toast } from '../support/ui';

const AUTONOMY_WORDS: Record<string, string> = {
  L0_OBSERVE: 'Watching only', L1_RECOMMEND: 'Suggests changes', L2_ACT_WITH_APPROVAL: 'Acts with your approval', L3_AUTONOMOUS: 'Acts on its own within limits',
};
const AREA: Record<string, string> = {
  FS: 'Fulfilment and delivery promises', CX: 'Fulfilment and delivery promises', FD: 'Fraud and abuse', IU: 'Inventory', CI: 'Customers', DF: 'Decision quality',
};
const areaOf = (l: Learner) => AREA[l.requirement.slice(0, 2).toUpperCase()] ?? 'Other';

test.describe('Learners', () => {
  test('every learner is listed under its area with a plain-English name and autonomy in words', async ({ pageAs }) => {
    const all = await learners(await apiAs('strategy'));
    expect(all.length).toBeGreaterThanOrEqual(20);
    const page = await pageAs('strategy');
    await page.goto('/learning?tab=learners');
    for (const l of all) {
      const row = page.getByRole('region', { name: areaOf(l) }).getByTestId(`learner-${l.learnerKey}`);
      await expect(row, l.learnerKey).toContainText(l.displayName);
      expect(l.displayName, `${l.learnerKey} has a person-readable name`).not.toMatch(/_/);
      await expect(row.getByRole('combobox', { name: `How far ${l.displayName} may go` })).toHaveValue(l.autonomyLevel);
      await expect(row.getByRole('combobox', { name: `How far ${l.displayName} may go` }).locator('option:checked')).toHaveText(AUTONOMY_WORDS[l.autonomyLevel]);
    }
    // The internal key only appears with technical details on.
    await expect(page.getByTestId('learner-rule_utilization')).not.toContainText('rule_utilization');
  });

  test('a strategy lead changes how far a learner may go, then restores it', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const before = (await learners(api)).find((l) => l.learnerKey === 'rule_utilization')!;
    const page = await pageAs('strategy');
    await page.goto('/learning?tab=learners');
    const select = page.getByTestId('learner-rule_utilization').getByRole('combobox', { name: `How far ${before.displayName} may go` });
    try {
      await select.selectOption({ label: 'Acts with your approval' });
      await expect(toast(page, `${before.displayName}: acts with your approval`)).toBeVisible();
      await expect.poll(async () => (await ok<Learner>(api.get('/api/v1/learning/learners/rule_utilization'))).autonomyLevel).toBe('L2_ACT_WITH_APPROVAL');
      await select.selectOption({ label: AUTONOMY_WORDS[before.autonomyLevel] });
      await expect.poll(async () => (await ok<Learner>(api.get('/api/v1/learning/learners/rule_utilization'))).autonomyLevel).toBe(before.autonomyLevel);
    } finally {
      await api.put('/api/v1/learning/learners/rule_utilization/autonomy', { data: { autonomyLevel: before.autonomyLevel } });
    }
  });

  test('one learner can be switched off and back on', async ({ pageAs }) => {
    const api = await apiAs('admin');
    const page = await pageAs('admin');
    await page.goto('/learning?tab=learners');
    const row = page.getByTestId('learner-inventory_transfer');
    const name = (await ok<Learner>(api.get('/api/v1/learning/learners/inventory_transfer'))).displayName;
    try {
      await row.getByRole('button', { name: `Switch off ${name}` }).click();
      await expect(toast(page, `${name} switched off`)).toBeVisible();
      await expect(row).toContainText('Switched off');
      expect((await ok<Learner>(api.get('/api/v1/learning/learners/inventory_transfer'))).gateReason).toBe('learner_kill_switch');
      await row.getByRole('button', { name: `Switch on ${name}` }).click();
      await expect(row).toContainText('Running');
    } finally {
      await api.post('/api/v1/learning/kill-switch', { data: { learnerKey: 'inventory_transfer', enabled: true } });
    }
    expect((await ok<Learner>(api.get('/api/v1/learning/learners/inventory_transfer'))).running).toBe(true);
  });

  test('"Pause all learning" asks first, shows a banner, and resumes', async ({ pageAs }) => {
    const api = await apiAs('admin');
    const page = await pageAs('admin');
    await page.goto('/learning?tab=learners');
    try {
      await page.getByRole('button', { name: 'Pause all learning' }).click();
      const confirm = dialog(page, 'Pause all learning?');
      await expect(confirm).toContainText('live decisions go back to their standard values');
      await confirm.getByRole('button', { name: 'Pause all learning' }).click();
      await expect(toast(page, 'All learning paused')).toBeVisible();
      await expect(page.getByRole('status').filter({ hasText: 'All learning is paused.' })).toBeVisible();
      await expect(page.getByTestId('learner-rule_utilization')).toContainText('All learning is paused');
      expect((await ok<{ tenantPaused: boolean }>(api.get('/api/v1/learning/kill-switch'))).tenantPaused).toBe(true);

      await page.getByRole('button', { name: 'Resume all learning' }).click();
      await expect(toast(page, 'Learning resumed')).toBeVisible();
      await expect(page.getByRole('status').filter({ hasText: 'All learning is paused.' })).toHaveCount(0);
      await expect(page.getByTestId('learner-rule_utilization')).toContainText('Running');
    } finally {
      await api.post('/api/v1/learning/kill-switch', { data: { learnerKey: '*', enabled: true } });
    }
  });

  test('"Run now" appears only for batch learners, and running one says so', async ({ pageAs }) => {
    const all = await learners(await apiAs('admin'));
    const page = await pageAs('admin');
    await page.goto('/learning?tab=learners');
    for (const l of all) {
      const run = page.getByTestId(`learner-${l.learnerKey}`).getByRole('button', { name: `Run ${l.displayName} now` });
      if (l.runnable && l.running) await expect(run, l.learnerKey).toBeVisible();
      else await expect(run, l.learnerKey).toHaveCount(0);
    }
    await expect(page.getByTestId('learner-node_reliability')).toContainText(/updates as outcomes arrive|last ran/);
    const rule = all.find((l) => l.learnerKey === 'rule_utilization')!;
    await page.getByRole('button', { name: `Run ${rule.displayName} now` }).click();
    await expect(toast(page, `${rule.displayName} ran`)).toBeVisible();
    await expect(page.getByTestId('learner-rule_utilization')).toContainText(/last ran/);
  });

  test('the paused learner explains why and offers Resume to an admin', async ({ pageAs }) => {
    const tunerState = await ok<Learner>((await apiAs('admin')).get('/api/v1/learning/learners/fraud_threshold_tuner'));
    expect(tunerState.pausedReason, 'the demo seed leaves the fraud tuner paused for low outcome coverage').toMatch(/^outcome coverage for address_risk is \d+% \(floor 80%\)$/);
    const page = await pageAs('admin');
    await page.goto('/learning?tab=learners');
    const tuner = page.getByTestId('learner-fraud_threshold_tuner');
    await expect(tuner).toContainText(`Paused: ${tunerState.pausedReason}`);
    await expect(tuner.getByRole('button', { name: 'Resume' })).toBeVisible();
    await expect(tuner.getByRole('button', { name: /^Run .* now$/ })).toHaveCount(0);
  });

  test('an ops analyst sees every learner but cannot change anything', async ({ pageAs }) => {
    const all = await learners(await apiAs('ops'));
    const page = await pageAs('ops');
    await page.goto('/learning?tab=learners');
    await expect(page.getByTestId(`learner-${all[0].learnerKey}`)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Pause all learning' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Switch (off|on) / })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Run .* now$/ })).toHaveCount(0);
    for (const l of all.slice(0, 5)) await expect(page.getByRole('combobox', { name: `How far ${l.displayName} may go` })).toBeDisabled();
  });
});
