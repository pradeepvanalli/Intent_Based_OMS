import { expect, request, type APIRequestContext, type APIResponse } from '@playwright/test';
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
  await ok(admin.post(`/api/v1/learning/proposals/${p.proposalId}/approve`, { data: {} }));
  return ok<Proposal>(admin.post(`/api/v1/learning/proposals/${p.proposalId}/promote`, { data: {} }));
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
  const total = lines.reduce((s, l) => s + l.qty * l.unitPrice.amount, 0) + 12;
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
