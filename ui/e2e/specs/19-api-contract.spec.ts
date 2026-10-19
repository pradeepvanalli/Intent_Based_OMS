import fs from 'node:fs';
import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { anonymous, ok, opsAgentKey, proposeLiveUse, seedStatus, uid, type Proposal } from '../support/data';
import { BASE_URL } from '../playwright.config';

const L = '/api/v1/learning';
const keysOf = (v: unknown) => (Array.isArray(v) ? (v[0] ? Object.keys(v[0] as object) : []) : v && typeof v === 'object' ? Object.keys(v) : []);

/** Read endpoints of the SOO REST surface (§9.3) plus the Wave B additions, with the fields each must carry. */
function reads(ids: Record<string, string>): Array<{ path: string; keys: string[]; array?: boolean }> {
  return [
    { path: `${L}/learners`, array: true, keys: ['learnerKey', 'displayName', 'summary', 'loop', 'requirement', 'outcomeDecisions', 'liveParameters', 'autonomyLevel', 'enabled', 'paused', 'running', 'gateReason', 'maxPromotionsPerWeek', 'runnable'] },
    { path: `${L}/learners/node_reliability`, keys: ['learnerKey', 'displayName', 'autonomyLevel', 'running', 'gateReason', 'runnable'] },
    { path: `${L}/kill-switch`, keys: ['globalKillSwitch', 'tenantPaused', 'pausedLearners'] },
    { path: `${L}/facts?subjectType=NODE&subjectId=ST-LA-02`, array: true, keys: ['factId', 'subjectType', 'subjectId', 'factKey', 'value', 'confidence', 'sampleSize', 'learner', 'version', 'validFrom'] },
    { path: `${L}/facts/NODE/ST-LA-02/NODE_ON_TIME_RATE`, keys: ['factKey', 'value', 'version', 'learner', 'sampleSize'] },
    { path: `${L}/proposals?status=PROMOTED`, array: true, keys: ['proposalId', 'changeset', 'tier', 'status', 'createdBy'] },
    { path: `${L}/proposals/${ids.promotedDecision}`, keys: ['proposalId', 'changeset', 'tier', 'status', 'evaluationReport', 'approvedBy', 'promotedAt'] },
    { path: `${L}/proposals/${ids.promotedDecision}/card`, keys: ['proposal', 'evidence', 'replay', 'diff', 'cumulative', 'actions'] },
    { path: `${L}/diff?target=decision:sourcing_node_score`, keys: ['target', 'baseline', 'automaticPromotionsSinceBaseline', 'sinceBaseline', 'withPending'] },
    { path: `${L}/experiments`, array: true, keys: ['experimentId', 'proposalId', 'mode', 'trafficShare', 'status', 'startAt'] },
    { path: `${L}/experiments?all=true`, array: true, keys: ['experimentId', 'mode', 'status', 'createdBy'] },
    { path: `${L}/experiments/${ids.canaryExperiment}`, keys: ['experiment', 'proposal', 'measurement'] },
    { path: `${L}/lift?days=90`, keys: ['windowDays', 'since', 'metrics'] },
    { path: `${L}/objective`, keys: ['version', 'status', 'weights', 'constraints'] },
    { path: `${L}/objective/drafts`, keys: [] },
    { path: `${L}/objective/history?limit=5`, array: true, keys: ['version', 'status', 'weights'] },
    { path: `${L}/fairness/payment_risk`, keys: ['decision', 'grouping', 'groups'] },
    { path: `${L}/fairness/features`, array: true, keys: ['learner', 'declaredFeatures', 'protectedFeatures'] },
    { path: `${L}/metrics`, array: true, keys: ['learnerKey', 'displayName', 'running', 'gateReason', 'proposalsDrafted'] },
    { path: `${L}/digests`, array: true, keys: ['digestId', 'periodStart', 'periodEnd', 'summary'] },
    { path: `${L}/digests/latest`, keys: ['digestId', 'periodStart', 'periodEnd', 'summary', 'delivery'] },
    { path: `${L}/live-parameters`, array: true, keys: ['parameter', 'learnerKey', 'status'] },
    { path: `${L}/coverage`, keys: ['address_risk', 'sourcing_node_score'] },
    { path: `${L}/seed-status`, keys: ['enabled', 'complete', 'counts', 'ids', 'actor'] },
    { path: `${L}/golden-candidates`, keys: [] },
    { path: `${L}/llm/budget`, keys: ['spentThisMonthUsd', 'exhausted'] },
    { path: `${L}/llm/calls?limit=5`, keys: [] },
    { path: `${L}/llm/author-quality?days=30`, keys: ['drafted', 'windowDays'] },
    { path: `${L}/segments`, keys: [] },
  ];
}

test.describe('Learning REST contract', () => {
  test('every read endpoint returns its documented shape to an authorised role', async () => {
    const api = await apiAs('admin');
    const ids = (await seedStatus(api)).ids;
    for (const r of reads(ids)) {
      const body = await ok<unknown>(api.get(r.path));
      if (r.array) expect(Array.isArray(body), `${r.path} is a list`).toBe(true);
      if (r.keys.length) expect(keysOf(body), r.path).toEqual(expect.arrayContaining(r.keys));
    }
  });

  test('without a session every endpoint answers 401; a role without learning access gets 403', async () => {
    const anon = await anonymous();
    const cs = await apiAs('cs');
    const ids = (await seedStatus(await apiAs('admin'))).ids;
    for (const r of reads(ids).filter((x) => !x.path.endsWith('/seed-status'))) {
      expect((await anon.get(r.path)).status(), `anonymous ${r.path}`).toBe(401);
      expect((await cs.get(r.path)).status(), `cs ${r.path}`).toBe(403);
    }
    for (const [m, p] of [['post', `${L}/kill-switch`], ['post', `${L}/proposals/${ids.approved}/approve`], ['post', `${L}/proposals/${ids.approved}/promote`], ['post', `${L}/promotions/${ids.promotedDecision}/rollback`], ['post', `${L}/experiments`], ['put', `${L}/objective`], ['post', `${L}/live-parameters/proposals`]] as const) {
      expect((await anon[m](p, { data: {} })).status(), `anonymous ${m} ${p}`).toBe(401);
      expect((await (await apiAs('ops'))[m](p, { data: {} })).status(), `ops ${m} ${p}`).toBe(403);
    }
    expect((await anon.get(`${L}/seed-status`)).status()).toBe(401);
  });

  test('write endpoints: learner run, resume, autonomy, kill switch, jobs, retention and digests', async () => {
    const api = await apiAs('admin');
    expect(keysOf(await ok(api.post(`${L}/learners/rule_utilization/run`)))).toEqual(expect.arrayContaining(['learnerKey', 'running', 'lastRunAt']));
    const eventDriven = await api.post(`${L}/learners/node_reliability/run`);
    expect([400, 422]).toContain(eventDriven.status());
    expect(await eventDriven.text()).toContain('learns from events');
    expect((await ok<{ running: boolean }>(api.post(`${L}/learners/inventory_transfer/resume`))).running).toBe(true);
    expect((await ok<{ autonomyLevel: string }>(api.put(`${L}/learners/inventory_transfer/autonomy`, { data: { autonomyLevel: 'L1_RECOMMEND' } }))).autonomyLevel).toBe('L1_RECOMMEND');
    expect(keysOf(await ok(api.post(`${L}/kill-switch`, { data: { learnerKey: 'inventory_transfer', enabled: true } })))).toEqual(expect.arrayContaining(['globalKillSwitch', 'tenantPaused', 'pausedLearners']));
    for (const job of ['reward-maturity', 'coverage', 'guardrails']) {
      expect((await ok<{ job: string }>(api.post(`${L}/jobs/${job}/run`))).job).toBe(job);
    }
    expect((await api.post(`${L}/jobs/nonsense/run`)).status()).toBe(422);
    expect(keysOf(await ok(api.post(`${L}/retention/run?dryRun=true`)))).toEqual(expect.arrayContaining(['retentionDays', 'cutoff', 'dryRun']));
    expect(keysOf(await ok(api.post(`${L}/digests/run?days=7`)))).toEqual(expect.arrayContaining(['digestId', 'summary']));
    expect((await ok<{ mode: string; status: string }>(api.post(`${L}/experiments/holdout?share=0.05`)))).toMatchObject({ mode: 'HOLDOUT', status: 'RUNNING' });
  });

  test('proposal lifecycle over the API: draft, evaluate, approve by another, promote, roll back; reject; revoke', async () => {
    const strategy = await apiAs('strategy');
    const admin = await apiAs('admin');
    // A family with no live use yet, so rolling back its promotion switches live use off again.
    const p = await proposeLiveUse(strategy, { family: 'monitoring.warn_early_threshold', learnerKey: 'warn_early', min: 0.3, max: 0.9, maxStep: 0.1, reason: `e2e contract ${uid()}` });
    expect(p).toMatchObject({ status: 'DRAFT', tier: 'T1', createdBy: 'user:strategy' });
    const evaluated = await ok<Proposal>(admin.post(`${L}/proposals/${p.proposalId}/evaluate`));
    expect(evaluated.evaluationReport?.method).toBeTruthy();
    expect((await strategy.post(`${L}/proposals/${p.proposalId}/approve`, { data: {} })).status()).toBe(422);
    expect((await ok<Proposal>(admin.post(`${L}/proposals/${p.proposalId}/approve`, { data: {} }))).status).toBe('APPROVED');
    expect((await ok<Proposal>(admin.post(`${L}/proposals/${p.proposalId}/promote`, { data: { overrideReason: 'live-use families cannot be replayed; e2e contract test' } }))).status).toBe('PROMOTED');
    const liveNow = await ok<Array<{ parameter: string; status: string }>>(admin.get(`${L}/live-parameters`));
    expect(liveNow.find((x) => x.parameter === 'monitoring.warn_early_threshold')?.status).toBe('LIVE');
    const rolled = await ok<Proposal>(admin.post(`${L}/promotions/${p.proposalId}/rollback`, { data: { reason: 'e2e contract rollback' } }));
    expect(rolled).toMatchObject({ status: 'ROLLED_BACK', rolledBackReason: 'e2e contract rollback' });
    const live = await ok<Array<{ parameter: string; status: string }>>(admin.get(`${L}/live-parameters`));
    expect(live.find((x) => x.parameter === 'monitoring.warn_early_threshold')?.status).toBe('REVOKED');

    const q = await proposeLiveUse(strategy, { family: 'promise.buffer_days', learnerKey: 'promise_buffer', reason: `e2e contract reject ${uid()}` });
    expect((await ok<Proposal>(admin.post(`${L}/proposals/${q.proposalId}/reject`, { data: { reason: 'not now' } })))).toMatchObject({ status: 'REJECTED', rejectedReason: 'not now' });

    expect(await ok(admin.post(`${L}/live-parameters/sourcing.no_such_family/revoke`))).toEqual({ family: 'sourcing.no_such_family', revoked: false });
    const notDeclared = await strategy.post(`${L}/live-parameters/proposals`, { data: { family: 'promise.buffer_days', learnerKey: 'node_reliability' } });
    expect(notDeclared.status()).toBe(422);
    expect(await notDeclared.text()).toContain('does not feed');
  });

  test('objective: a plain-English draft comes back as a draft, never in use until someone else approves', async () => {
    const strategy = await apiAs('strategy');
    const before = await ok<{ version: number }>(strategy.get(`${L}/objective`));
    const draft = await ok<{ version: number; status: string; draftMethod: string }>(strategy.post(`${L}/objective/draft`, { data: { text: `e2e ${uid()}: fewer split shipments, cost matters a little more` } }));
    expect(draft.status).toBe('DRAFT');
    expect(['llm', 'template']).toContain(draft.draftMethod);
    expect((await ok<{ version: number }>(strategy.get(`${L}/objective`))).version).toBe(before.version);
  });

  test('MCP: learning_status and explain_policy_change for the ops agent; no tool can approve or promote', async () => {
    const logPath = process.env.E2E_SERVICE_LOG;
    test.skip(!logPath || !fs.existsSync(logPath), 'the service log (with the ops agent key printed at seed time) is only available when scripts/e2e.sh started the service');
    const key = opsAgentKey(fs.readFileSync(logPath!, 'utf8'));
    test.skip(!key, 'no ops agent key in the service log (agents already existed in this database)');
    const ids = (await seedStatus(await apiAs('admin'))).ids;
    const mcp = await (await import('@playwright/test')).request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { 'X-Api-Key': key! } });
    const call = async (method: string, params: Record<string, unknown>) =>
      ok<{ result: { tools?: Array<{ name: string }>; content?: Array<{ text: string }>; isError?: boolean } }>(mcp.post('/mcp', { data: { jsonrpc: '2.0', id: 1, method, params } }));

    const tools = (await call('tools/list', {})).result.tools!.map((t) => t.name);
    expect(tools).toEqual(expect.arrayContaining(['learning_status', 'explain_policy_change']));
    expect(tools.filter((t) => /approve|promote/.test(t))).toEqual([]);

    const status = await call('tools/call', { name: 'learning_status', arguments: {} });
    expect(status.result.isError ?? false).toBe(false);
    const statusBody = JSON.parse(status.result.content![0].text) as { learners: Array<{ key: string }> };
    expect(statusBody.learners.map((l) => l.key)).toEqual(expect.arrayContaining(['node_reliability', 'sourcing_policy_bandit']));

    const explained = await call('tools/call', { name: 'explain_policy_change', arguments: { proposalId: ids.promotedDecision } });
    expect(explained.result.isError ?? false).toBe(false);
    expect(explained.result.content![0].text).toContain(ids.promotedDecision);
    await mcp.dispose();
  });
});
