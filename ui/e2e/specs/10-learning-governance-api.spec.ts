import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';

type Learner = { learnerKey: string; displayName: string; autonomyLevel: string; enabled: boolean; paused: boolean; running: boolean; gateReason: string };

test.describe('Learning governance (API)', () => {
  test('every learner is registered with a plain-English name', async () => {
    const api = await apiAs('strategy');
    const res = await api.get('/api/v1/learning/learners');
    expect(res.status()).toBe(200);
    const learners = (await res.json()) as Learner[];
    expect(learners.length).toBeGreaterThanOrEqual(14);
    for (const l of learners) expect(l.displayName).not.toMatch(/_/);
  });

  test('proposals of every status read back', async () => {
    const api = await apiAs('strategy');
    for (const status of ['DRAFT', 'EVALUATED', 'APPROVED', 'CANARY', 'PROMOTED', 'REJECTED', 'ROLLED_BACK']) {
      const res = await api.get('/api/v1/learning/proposals', { params: { status } });
      expect(res.status(), status).toBe(200);
    }
  });

  test('the tenant kill switch pauses every learner and resumes them', async () => {
    const api = await apiAs('strategy');
    try {
      const off = await api.post('/api/v1/learning/kill-switch', { data: { learnerKey: '*', enabled: false } });
      expect(off.status()).toBe(200);
      expect((await off.json()).tenantPaused).toBe(true);
      const learners = (await (await api.get('/api/v1/learning/learners')).json()) as Learner[];
      for (const l of learners) {
        expect(l.running).toBe(false);
        expect(l.gateReason).toBe('tenant_kill_switch');
      }
    } finally {
      const on = await api.post('/api/v1/learning/kill-switch', { data: { learnerKey: '*', enabled: true } });
      expect((await on.json()).tenantPaused).toBe(false);
    }
  });

  test('a single learner can be switched off and back on', async () => {
    const api = await apiAs('strategy');
    try {
      await api.post('/api/v1/learning/kill-switch', { data: { learnerKey: 'node_reliability', enabled: false } });
      const l = (await (await api.get('/api/v1/learning/learners/node_reliability')).json()) as Learner;
      expect(l.enabled).toBe(false);
      expect(l.gateReason).toBe('learner_kill_switch');
    } finally {
      await api.post('/api/v1/learning/kill-switch', { data: { learnerKey: 'node_reliability', enabled: true } });
    }
  });

  test('changing autonomy needs config admin as well as learning admin', async () => {
    const ops = await apiAs('ops');
    expect((await ops.put('/api/v1/learning/learners/band_threshold/autonomy', { data: { autonomyLevel: 'L2_ACT_WITH_APPROVAL' } })).status()).toBe(403);
    const strategy = await apiAs('strategy');
    const before = (await (await strategy.get('/api/v1/learning/learners/band_threshold')).json()) as Learner;
    const res = await strategy.put('/api/v1/learning/learners/band_threshold/autonomy', { data: { autonomyLevel: 'L2_ACT_WITH_APPROVAL' } });
    expect(res.status()).toBe(200);
    expect(((await res.json()) as Learner).autonomyLevel).toBe('L2_ACT_WITH_APPROVAL');
    await strategy.put('/api/v1/learning/learners/band_threshold/autonomy', { data: { autonomyLevel: before.autonomyLevel } });
  });

  test('read-only roles can see learning but not change it', async () => {
    const ops = await apiAs('ops');
    expect((await ops.get('/api/v1/learning/learners')).status()).toBe(200);
    expect((await ops.post('/api/v1/learning/kill-switch', { data: { learnerKey: '*', enabled: false } })).status()).toBe(403);
    const cs = await apiAs('cs');
    expect((await cs.get('/api/v1/learning/learners')).status()).toBe(403);
  });
});
