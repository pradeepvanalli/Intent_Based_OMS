import { expect, request, type APIRequestContext, type APIResponse } from '@playwright/test';
import { createHash } from 'node:crypto';
import { BASE_URL } from '../playwright.config';
import { TENANT } from './roles';

/** Shapes the specs read; only the fields asserted on. */
export type Proposal = {
  proposalId: string; status: string; tier: string; createdBy: string; approvedBy?: string; rejectedReason?: string; rolledBackReason?: string;
  changeset: { target: { type: string; name: string }; changes: Array<Record<string, unknown>> };
  evaluationReport?: Record<string, unknown> | null;
};
export type Learner = {
  learnerKey: string; displayName: string; summary: string; requirement: string; autonomyLevel: string; enabled: boolean; paused: boolean;
  pausedReason?: string; running: boolean; gateReason: string; runnable: boolean; liveParameters: string[];
};
export type SeedStatus = { enabled: boolean; complete: boolean; actor: string; counts: Record<string, unknown>; ids: Record<string, string>; pausedLearners: Array<{ learnerKey: string; reason: string }> };
export type Experiment = { experimentId: string; proposalId: string; mode: string; status: string; trafficShare: number; createdBy?: string; targetRef?: string };
export type LiveParameter = { parameter: string; learnerKey: string; status: string; proposalId?: string; promotedBy?: string };

/** A short unique suffix so specs never collide with each other or with an earlier run on the same database. */
export const uid = (): string => `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;

/** Body as JSON with a readable failure (status and text) when the status is not what the spec expects. */
export async function ok<T>(res: APIResponse | Promise<APIResponse>, ...expected: number[]): Promise<T> {
  const r = await res;
  const want = expected.length ? expected : [200];
  if (!want.includes(r.status())) throw new Error(`${r.url()} → HTTP ${r.status()} (expected ${want.join('/')}): ${(await r.text()).slice(0, 400)}`);
  const text = await r.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** An unauthenticated client, for 401 checks and the inbound webhooks (which authenticate by signature or tenant header). */
export async function anonymous(): Promise<APIRequestContext> {
  return request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { 'X-Tenant-Id': TENANT } });
}

export async function seedStatus(api: APIRequestContext): Promise<SeedStatus> {
  return ok<SeedStatus>(api.get('/api/v1/learning/seed-status'));
}

export async function learners(api: APIRequestContext): Promise<Learner[]> {
  return ok<Learner[]>(api.get('/api/v1/learning/learners'));
}

export async function proposals(api: APIRequestContext, status: string): Promise<Proposal[]> {
  return ok<Proposal[]>(api.get('/api/v1/learning/proposals', { params: { status } }));
}

export async function proposal(api: APIRequestContext, id: string): Promise<Proposal> {
  return ok<Proposal>(api.get(`/api/v1/learning/proposals/${encodeURIComponent(id)}`));
}

/** Drafts a live-use proposal through the real endpoint (the author is whoever `api` is signed in as). */
export async function proposeLiveUse(api: APIRequestContext, body: { family: string; learnerKey: string; min?: number; max?: number; maxStep?: number; reason?: string }): Promise<Proposal> {
  return ok<Proposal>(api.post('/api/v1/learning/live-parameters/proposals', { data: { enabled: true, ...body } }), 200, 201);
}

/** Drafted by strategy, approved and promoted by admin: the full governed path to a live learned value. */
export async function liveUseThroughTheGate(strategy: APIRequestContext, admin: APIRequestContext, family: string, learnerKey: string): Promise<Proposal> {
  const p = await proposeLiveUse(strategy, { family, learnerKey, min: 0, max: 10, maxStep: 1, reason: `e2e ${uid()}` });
  // A draft is tested before approval; live use has no counterfactual replay, so promotion carries a waiver reason.
  await ok(admin.post(`/api/v1/learning/proposals/${p.proposalId}/evaluate`));
  await ok(admin.post(`/api/v1/learning/proposals/${p.proposalId}/approve`, { data: {} }));
  return ok<Proposal>(admin.post(`/api/v1/learning/proposals/${p.proposalId}/promote`, { data: { overrideReason: 'Live-use family: bounded by range and max step; e2e suite' } }));
}

export async function liveParameters(api: APIRequestContext): Promise<LiveParameter[]> {
  return ok<LiveParameter[]>(api.get('/api/v1/learning/live-parameters'));
}

export async function experiments(api: APIRequestContext): Promise<Experiment[]> {
  return ok<Experiment[]>(api.get('/api/v1/learning/experiments', { params: { all: true } }));
}

type OrderLine = { lineId: string; sku: string; qty: number; unitPrice: { amount: number; currency: string } };

/** Captures a real order through the API, the same way the use-case scripts do. Each customer gets its own address. */
export async function createOrder(api: APIRequestContext, opts: { ref?: string; lines?: OrderLine[]; tender?: Record<string, unknown> } = {}): Promise<string> {
  const ref = opts.ref ?? `e2e-${uid()}`;
  const lines = opts.lines ?? [{ lineId: '1', sku: 'TEE-BLK-M', qty: 1, unitPrice: { amount: 28.0, currency: 'USD' } }];
  // Generous enough to cover shipping and tax; the order takes what it needs.
  const total = Math.round((lines.reduce((s, l) => s + l.qty * l.unitPrice.amount, 0) * 1.2 + 25) * 100) / 100;
  const body = {
    channel: 'web', channelOrderId: `E2E-${ref}`, orderType: 'standard_ship', customerRef: `cust-${ref}`,
    customer: { ref: `cust-${ref}`, name: 'E2E Shopper', email: `${ref}@example.com` }, lines,
    addresses: [{ type: 'ship_to', name: 'E2E Shopper', line1: `${100 + Math.floor(Math.random() * 800)} Market St, Unit ${ref}`, city: 'San Francisco', state: 'CA', zip: '94105', country: 'US' }],
    tenders: [opts.tender ?? { type: 'card', token: 'tok_visa_ok', amount: { amount: total, currency: 'USD' } }],
    intentChoices: ['split_up_to_k'], shipMethod: 'ground',
  };
  const order = await ok<{ id: string }>(api.post('/api/v1/orders', { data: body }), 200, 201);
  return order.id;
}

export type DecisionRecord = { id: string; decision: string; actor?: string; details?: Record<string, unknown> };

/** Waits (polling the API) until the order has a record for `decision`, and returns it. */
export async function waitForDecision(api: APIRequestContext, orderId: string, decision: string): Promise<DecisionRecord> {
  let found: DecisionRecord | undefined;
  await expect.poll(async () => {
    const rows = await ok<DecisionRecord[]>(api.get('/api/v1/decision-records', { params: { subjectType: 'order', subjectId: orderId } }));
    found = rows.find((r) => r.decision === decision);
    return !!found;
  }, { timeout: 60_000, message: `waiting for a ${decision} decision on ${orderId}` }).toBe(true);
  return found!;
}

export type OrderView = { order: { id: string; status: string }; fulfilmentGroups?: Array<{ nodeId?: string }> };

export async function orderView(api: APIRequestContext, orderId: string): Promise<OrderView> {
  return ok<OrderView>(api.get(`/api/v1/orders/${orderId}/view`));
}

/** WMS "shipped" then carrier "delivered" webhooks for every node the order was sourced to. */
export async function shipAndDeliver(api: APIRequestContext, orderId: string): Promise<void> {
  await expect.poll(async () => (await orderView(api, orderId)).fulfilmentGroups?.length ?? 0, { timeout: 60_000, message: 'waiting for sourcing' }).toBeGreaterThan(0);
  const view = await orderView(api, orderId);
  const nodes = [...new Set((view.fulfilmentGroups ?? []).map((g) => g.nodeId ?? 'DC-NJ'))];
  const hooks = await anonymous();
  for (const node of nodes) {
    const ts = uid();
    await ok(hooks.post(`/webhooks/wms/${node}`, { data: { eventId: `wms-${ts}-${orderId}`, type: 'shipped', orderId, carrier: 'UPS', service: 'ground', tracking: `1Z${ts}` } }), 200, 201, 202);
    await expect.poll(async () => (await orderView(api, orderId)).order.status, { timeout: 30_000 }).toMatch(/shipped|delivered/);
    await ok(hooks.post('/webhooks/carrier', { data: { eventId: `car-${ts}-${orderId}`, type: 'delivered', orderId, tracking: `1Z${ts}` } }), 200, 201, 202);
  }
  await hooks.dispose();
}

/** The ops agent's platform API key, printed once in the service log when the demo agents are seeded. */
export function opsAgentKey(logText: string): string | null {
  const m = /Registered demo agent ops-copilot \(tier 3, protocol mcp\) secret=\S+ apiKey=(\S+)/.exec(logText);
  return m ? m[1] : null;
}

/**
 * The server's sticky experiment split (ExperimentManager.stickyInTreatment): SHA-256 of "experimentId:key", first two
 * bytes as a bucket out of 10 000. Lets a spec pick a customer the persistent holdout will hold back, without guessing.
 */
export function stickyInTreatment(experimentId: string, key: string, share: number): boolean {
  const d = createHash('sha256').update(`${experimentId}:${key}`, 'utf8').digest();
  return ((d[0] << 8) | d[1]) % 10_000 < Math.round(share * 10_000);
}

/** A fresh order reference whose customer (`cust-<ref>`, as createOrder names it) is in, or out of, the running holdout. */
export async function customerRefFor(api: APIRequestContext, holdout: boolean): Promise<{ ref: string; experimentId: string }> {
  const running = (await experiments(api)).find((e) => e.mode === 'HOLDOUT' && e.status === 'RUNNING');
  if (!running) throw new Error('no running holdout experiment (the demo seed starts one)');
  for (let i = 0; i < 5000; i++) {
    const ref = `e2e-${uid()}-${i}`;
    if (stickyInTreatment(running.experimentId, `customer:cust-${ref}`, running.trafficShare) === holdout) return { ref, experimentId: running.experimentId };
  }
  throw new Error(`no ${holdout ? 'holdout' : 'treatment'} customer found for ${running.experimentId}`);
}

/** The order view with the parts the parcel specs read. */
export type FullOrderView = {
  order: { id: string; status: string; lines: Array<{ lineId: string; status: string }> };
  fulfilmentGroups: Array<{ id: string; nodeId: string; status: string; allocations: Array<{ lineId: string; qty: number }> }>;
  shipments: Array<{ id: string; tracking?: string; status: string; lines: Array<{ lineId: string; qty: number }> }>;
  ledger: Array<{ type: string; data?: Record<string, unknown> }>;
};

/** WMS "shipped" for one line of an order as its own parcel, from the node that line was sourced to. */
export async function shipLine(api: APIRequestContext, orderId: string, lineId: string, tracking: string): Promise<void> {
  const view = await ok<FullOrderView>(api.get(`/api/v1/orders/${orderId}/view`));
  const live = view.fulfilmentGroups.filter((g) => !/rejected|cancelled/i.test(g.status));
  const group = live.find((g) => g.allocations.some((a) => a.lineId === lineId));
  if (!group) throw new Error(`line ${lineId} of ${orderId} is not allocated to any fulfilment group`);
  const qty = group.allocations.filter((a) => a.lineId === lineId).reduce((s, a) => s + a.qty, 0);
  const hooks = await anonymous();
  await ok(hooks.post(`/webhooks/wms/${group.nodeId}`, {
    data: { eventId: `wms-${tracking}`, type: 'shipped', orderId, fulfilmentGroupId: group.id, carrier: 'UPS', service: 'ground', tracking, lines: [{ lineId, qty }] },
  }), 200, 201, 202);
  await hooks.dispose();
}

/** Carrier "delivered" scan for one parcel, by its tracking number. */
export async function deliverParcel(orderId: string, tracking: string): Promise<void> {
  const hooks = await anonymous();
  await ok(hooks.post('/webhooks/carrier', { data: { eventId: `car-${tracking}`, type: 'delivered', orderId, tracking } }), 200, 201, 202);
  await hooks.dispose();
}
