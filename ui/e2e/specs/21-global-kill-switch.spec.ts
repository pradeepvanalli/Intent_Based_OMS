import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, learners, ok, waitForDecision } from '../support/data';
import { dialog, toast } from '../support/ui';

type KillSwitchState = { globalKillSwitch: boolean; globalSource?: string | null; globalSetBy?: string | null; tenantPaused: boolean; canSetGlobal: boolean };
type Trace = { learning?: { learnedFacts?: Array<{ source: string; reason?: string }> } };

/**
 * GV-03 at platform level (N3): one switch stops learning for every tenant. Only a platform engineer (role
 * platform_engineer, the dev user `platform`) may flip it; a tenant admin holds every permission inside their own
 * tenant but is refused like a strategy lead, in words, and neither sees the button. While it is on, learners stop and
 * live decisions read their standard values. Listing tenants needs the same role.
 */
test('the global kill switch is for platform engineers only, stops learning everywhere and can be restarted', async ({ pageAs }) => {
  const strategyApi = await apiAs('strategy');
  const adminApi = await apiAs('admin');
  const platformApi = await apiAs('platform');

  // A strategy lead and a tenant admin may pause this tenant's learning but not every tenant's.
  for (const api of [strategyApi, adminApi]) {
    expect((await ok<KillSwitchState>(api.get('/api/v1/learning/kill-switch'))).canSetGlobal).toBe(false);
    const refused = await api.post('/api/v1/learning/kill-switch', { data: { scope: 'global', enabled: false } });
    expect(refused.status()).toBe(403);
    expect(await refused.text()).toContain('Only a platform engineer can switch learning off or on for every tenant');
  }
  expect((await ok<KillSwitchState>(platformApi.get('/api/v1/learning/kill-switch'))).globalKillSwitch, 'the refusals changed nothing').toBe(false);

  // Tenants are listed by the platform engineer only (a tenant admin's "*" stays inside its own tenant).
  expect((await adminApi.get('/api/v1/admin/tenants')).status()).toBe(403);
  const tenants = await ok<Array<{ tenant_id: string }>>(platformApi.get('/api/v1/admin/tenants'));
  expect(tenants.map((t) => t.tenant_id)).toContain('demo');
  expect((await ok<{ tenant_id: string }>(adminApi.get('/api/v1/admin/tenants/current'))).tenant_id).toBe('demo');

  for (const role of ['strategy', 'admin'] as const) {
    const page = await pageAs(role);
    await page.goto('/learning?tab=learners');
    await expect(page.getByRole('button', { name: 'Pause all learning' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Stop learning for every tenant' })).toHaveCount(0);
  }

  const platform = await pageAs('platform');
  try {
    expect((await ok<KillSwitchState>(platformApi.get('/api/v1/learning/kill-switch'))).canSetGlobal).toBe(true);
    await platform.goto('/learning?tab=learners');
    await platform.getByRole('button', { name: 'Stop learning for every tenant' }).click();
    const confirm = dialog(platform, 'Stop learning for every tenant?');
    await expect(confirm).toContainText('platform-wide kill switch');
    await confirm.getByRole('button', { name: 'Stop learning everywhere' }).click();
    await expect(toast(platform, 'Learning stopped for every tenant')).toBeVisible();
    const banner = platform.getByTestId('global-kill-switch');
    await expect(banner).toContainText('Learning is stopped for every tenant');
    await expect(banner).toContainText('by platform');
    await expect(banner).toContainText('live decisions use their standard values');

    const state = await ok<KillSwitchState>(platformApi.get('/api/v1/learning/kill-switch'));
    expect(state).toMatchObject({ globalKillSwitch: true, globalSource: 'runtime', globalSetBy: 'user:platform' });
    const all = await learners(adminApi);
    expect(all.filter((l) => l.running).map((l) => l.learnerKey), 'no learner may run').toEqual([]);
    expect(new Set(all.map((l) => l.gateReason))).toEqual(new Set(['global_kill_switch']));

    // A live decision made now still runs, on the standard values, even for the learned values the demo switched on.
    const orderId = await createOrder(adminApi);
    const record = await waitForDecision(adminApi, orderId, 'sourcing_node_score');
    const facts = (await ok<Trace>(adminApi.get(`/api/v1/decision-records/${record.id}/why`))).learning?.learnedFacts ?? [];
    expect(facts.filter((f) => f.source === 'LEARNED'), 'no learned value served while learning is stopped').toEqual([]);

    // The tenant admin sees who stopped it, but has no way to restart it.
    const admin = await pageAs('admin');
    await admin.goto('/learning');
    await expect(admin.getByTestId('global-kill-switch')).toContainText('Learning is stopped for every tenant by platform');
    await expect(admin.getByTestId('global-kill-switch').getByRole('button', { name: 'Restart learning' })).toHaveCount(0);
    expect((await adminApi.post('/api/v1/learning/kill-switch', { data: { scope: 'global', enabled: true } })).status(), 'nor over the API').toBe(403);

    await banner.getByRole('button', { name: 'Restart learning' }).click();
    await expect(toast(platform, 'Learning restarted for every tenant')).toBeVisible();
    await expect(platform.getByTestId('global-kill-switch')).toHaveCount(0);
    await expect(platform.getByRole('button', { name: 'Stop learning for every tenant' })).toBeVisible();
  } finally {
    // Never leave the platform switched off for the specs that follow.
    await platformApi.post('/api/v1/learning/kill-switch', { data: { scope: 'global', enabled: true } });
  }
  const after = await ok<KillSwitchState>(platformApi.get('/api/v1/learning/kill-switch'));
  expect(after.globalKillSwitch).toBe(false);
  await expect.poll(async () => (await learners(adminApi)).some((l) => l.running)).toBe(true);
});
