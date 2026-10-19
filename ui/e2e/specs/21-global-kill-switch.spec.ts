import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, learners, ok, waitForDecision } from '../support/data';
import { dialog, toast } from '../support/ui';

type KillSwitchState = { globalKillSwitch: boolean; globalSource?: string | null; globalSetBy?: string | null; tenantPaused: boolean; canSetGlobal: boolean };
type Trace = { learning?: { learnedFacts?: Array<{ source: string; reason?: string }> } };

/**
 * GV-03 at platform level: one switch stops learning for every tenant. Only a platform administrator (the admin role
 * holds every permission) may flip it; a strategy lead, who otherwise runs learning, is refused in words and never
 * sees the button. While it is on, learners stop and live decisions read their standard values.
 */
test('the global kill switch is for platform administrators only, stops learning everywhere and can be restarted', async ({ pageAs }) => {
  const strategyApi = await apiAs('strategy');
  const adminApi = await apiAs('admin');

  // A strategy lead may pause this tenant's learning but not every tenant's.
  expect((await ok<KillSwitchState>(strategyApi.get('/api/v1/learning/kill-switch'))).canSetGlobal).toBe(false);
  const refused = await strategyApi.post('/api/v1/learning/kill-switch', { data: { scope: 'global', enabled: false } });
  expect(refused.status()).toBe(403);
  expect(await refused.text()).toContain('Only a platform administrator can switch learning off or on for every tenant');
  expect((await ok<KillSwitchState>(adminApi.get('/api/v1/learning/kill-switch'))).globalKillSwitch, 'the refusal changed nothing').toBe(false);

  const strategy = await pageAs('strategy');
  await strategy.goto('/learning?tab=learners');
  await expect(strategy.getByRole('button', { name: 'Pause all learning' })).toBeVisible();
  await expect(strategy.getByRole('button', { name: 'Stop learning for every tenant' })).toHaveCount(0);

  const admin = await pageAs('admin');
  try {
    expect((await ok<KillSwitchState>(adminApi.get('/api/v1/learning/kill-switch'))).canSetGlobal).toBe(true);
    await admin.goto('/learning?tab=learners');
    await admin.getByRole('button', { name: 'Stop learning for every tenant' }).click();
    const confirm = dialog(admin, 'Stop learning for every tenant?');
    await expect(confirm).toContainText('platform-wide kill switch');
    await confirm.getByRole('button', { name: 'Stop learning everywhere' }).click();
    await expect(toast(admin, 'Learning stopped for every tenant')).toBeVisible();
    const banner = admin.getByTestId('global-kill-switch');
    await expect(banner).toContainText('Learning is stopped for every tenant');
    await expect(banner).toContainText('by admin');
    await expect(banner).toContainText('live decisions use their standard values');

    const state = await ok<KillSwitchState>(adminApi.get('/api/v1/learning/kill-switch'));
    expect(state).toMatchObject({ globalKillSwitch: true, globalSource: 'runtime', globalSetBy: 'user:admin' });
    const all = await learners(adminApi);
    expect(all.filter((l) => l.running).map((l) => l.learnerKey), 'no learner may run').toEqual([]);
    expect(new Set(all.map((l) => l.gateReason))).toEqual(new Set(['global_kill_switch']));

    // A live decision made now still runs, on the standard values, even for the learned values the demo switched on.
    const orderId = await createOrder(adminApi);
    const record = await waitForDecision(adminApi, orderId, 'sourcing_node_score');
    const facts = (await ok<Trace>(adminApi.get(`/api/v1/decision-records/${record.id}/why`))).learning?.learnedFacts ?? [];
    expect(facts.filter((f) => f.source === 'LEARNED'), 'no learned value served while learning is stopped').toEqual([]);

    // The strategy lead sees who stopped it, but has no way to restart it.
    await strategy.goto('/learning');
    await expect(strategy.getByTestId('global-kill-switch')).toContainText('Learning is stopped for every tenant by admin');
    await expect(strategy.getByTestId('global-kill-switch').getByRole('button', { name: 'Restart learning' })).toHaveCount(0);

    await banner.getByRole('button', { name: 'Restart learning' }).click();
    await expect(toast(admin, 'Learning restarted for every tenant')).toBeVisible();
    await expect(admin.getByTestId('global-kill-switch')).toHaveCount(0);
    await expect(admin.getByRole('button', { name: 'Stop learning for every tenant' })).toBeVisible();
  } finally {
    // Never leave the platform switched off for the specs that follow.
    await adminApi.post('/api/v1/learning/kill-switch', { data: { scope: 'global', enabled: true } });
  }
  const after = await ok<KillSwitchState>(adminApi.get('/api/v1/learning/kill-switch'));
  expect(after.globalKillSwitch).toBe(false);
  await expect.poll(async () => (await learners(adminApi)).some((l) => l.running)).toBe(true);
});
