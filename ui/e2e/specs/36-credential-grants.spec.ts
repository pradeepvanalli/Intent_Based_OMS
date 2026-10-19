import { request } from '@playwright/test';
import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { ok, uid } from '../support/data';
import { BASE_URL } from '../playwright.config';
import { toast } from '../support/ui';

type KillSwitchState = { canSetGlobal: boolean };

/**
 * D-1 (audit 3, the N3 bypass): a credential carries at most what its issuer holds. A tenant admin cannot mint a
 * platform_engineer key or user, an integration key of type SYSTEM stays inside its tenant (no tenant listing, no
 * global kill switch), a strategy lead cannot hand out admin, and an agent administrator cannot give an agent tools it
 * does not hold. The dev platform engineer still can.
 */
test.describe('Credentials carry at most what their issuer holds', () => {
  test('a tenant admin cannot mint a platform credential, and integration keys stay inside the tenant', async () => {
    const admin = await apiAs('admin');
    const key = await admin.post('/api/v1/auth/api-keys', { data: { subjectId: `e2e-escalate-${uid()}`, roles: ['platform_engineer'] } });
    expect(key.status()).toBe(403);
    expect(await key.text()).toContain('Only a platform operator may grant role platform_engineer');
    const user = await admin.post('/api/v1/auth/users', { data: { userId: `e2e-plat-${uid()}`, password: 'a-long-enough-password', roles: ['platform_engineer'] } });
    expect(user.status()).toBe(403);

    // A SYSTEM integration key with the admin role works inside the tenant but is not a platform operator.
    const sys = await ok<{ keyId: string; apiKey: string }>(admin.post('/api/v1/auth/api-keys', { data: { subjectId: `e2e-erp-${uid()}`, type: 'SYSTEM', roles: ['admin'] } }));
    const integration = await request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { 'X-Api-Key': sys.apiKey } });
    expect((await integration.get('/api/v1/admin/tenants')).status()).toBe(403);
    expect((await ok<KillSwitchState>(integration.get('/api/v1/learning/kill-switch'))).canSetGlobal).toBe(false);
    expect((await integration.post('/api/v1/learning/kill-switch', { data: { scope: 'global', enabled: false } })).status()).toBe(403);
    expect((await integration.get('/api/v1/admin/tenants/current')).status()).toBe(200);
    await ok(admin.delete(`/api/v1/auth/api-keys/${sys.keyId}`));
    expect((await integration.get('/api/v1/auth/me')).status()).toBe(401);

    // The platform engineer can still mint one, and it lists tenants.
    const platform = await apiAs('platform');
    const plat = await ok<{ keyId: string; apiKey: string }>(platform.post('/api/v1/auth/api-keys', { data: { subjectId: `e2e-platform-${uid()}`, roles: ['platform_engineer'] } }));
    const platKey = await request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { 'X-Api-Key': plat.apiKey } });
    expect((await platKey.get('/api/v1/admin/tenants')).status()).toBe(200);
    await ok(platform.delete(`/api/v1/auth/api-keys/${plat.keyId}`));
  });

  test('a strategy lead cannot hand out admin, and an agent administrator cannot give an agent tools it lacks', async () => {
    const strategy = await apiAs('strategy');
    const toAdmin = await strategy.post('/api/v1/auth/users', { data: { userId: `e2e-boss-${uid()}`, password: 'a-long-enough-password', roles: ['admin'] } });
    expect(toAdmin.status()).toBe(403);
    expect(await toAdmin.text()).toContain('role admin needs permission *');
    expect((await strategy.post('/api/v1/auth/api-keys', { data: { subjectId: `e2e-refunds-${uid()}`, permissions: ['refund.issue'] } })).status()).toBe(403);

    const risk = await apiAs('risk'); // agent.admin, but not order.cancel
    const agent = `e2e-agent-${uid()}`.toLowerCase();
    const cancels = await risk.post('/api/v1/agents', { data: { id: agent, owner: 'risk', protocol: 'mcp', trustTier: 1, limits: { allowedActions: ['order_get', 'order_cancel'] } } });
    expect(cancels.status()).toBe(403);
    expect(await cancels.text()).toContain('order.cancel');
    await ok(risk.post('/api/v1/agents', { data: { id: agent, owner: 'risk', protocol: 'mcp', trustTier: 1, limits: { allowedActions: ['order_get'] } } }), 201);
    await ok(risk.post(`/api/v1/agents/${agent}/suspend`, { data: { reason: 'e2e clean-up' } }));
  });

  test('issuing a platform key from Settings is refused on screen and no key is shown', async ({ pageAs }) => {
    const admin = await pageAs('admin');
    await admin.goto('/settings?tab=keys');
    await admin.getByLabel('Subject id').fill(`e2e-ui-${uid()}`);
    const roles = admin.getByLabel('Roles');
    await roles.fill('platform_engineer');
    await roles.press('Enter');
    await admin.getByRole('button', { name: 'Issue key' }).click();
    await expect(toast(admin, "You don't have access to this")).toBeVisible();
    await expect(admin.getByText('Store this key now; it is not retrievable later.')).toHaveCount(0);
  });
});
