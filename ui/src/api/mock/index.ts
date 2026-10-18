/* In-memory mock API (VITE_MOCK=1). Routes mirror docs/ARCHITECTURE.md §3. */
import type {
  Band, BulkOperation, CalibrationBin, DecisionAnalytics, DecisionDefinition, DecisionRecord, DecisionResult, DecisionTrace, EvaluationResult, Feasibility, Hold, ImpactDiff, KpiDashboard, LintResult, OrderSummary, OrderView, Page, PipelineView, ProblemDetail, ReplayResult, SimulationJob, StateModel, TraceLayerStep, TraceQuestion, Layer, DecisionSummary, GoldenSet, ReturnRecord, Modification,
} from '../types';
import { ApiError } from '../client';
import { getSession } from '../../auth/session';
import { registerCopilotRoutes } from './copilot';
import { registerReplayRoutes } from './replay';
import { registerPlaygroundRoutes } from './playground';
import { registerLaunchRoutes } from './launch';
import { registerAutonomyRoutes } from './autonomy';
import { registerLearningRoutes } from './learning';
import {
  AGE_BUCKETS, PIPELINE_STATES, addDecisionRecord, ageBucket, agents, atRiskList, auditLog, config, daysAgo, decisionRecords, decisionSummaries, decisions, exceptions, hoursAgo, int, liveVersion, mandates, nextId, nodes, orders, parseDecisionYaml, parseStateModelYaml, queues, returns, rnd, round, stateModels, tasks, traces, usd, webhooks, NOW,
} from './data';

type Handler = (ctx: { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> }) => unknown;
interface Route { method: string; pattern: RegExp; keys: string[]; handler: Handler; }
const routes: Route[] = [];
function on(method: string, path: string, handler: Handler) {
  const keys: string[] = [];
  const pattern = new RegExp('^' + path.replace(/\//g, '\\/').replace(/:(\w+)/g, (_, k: string) => { keys.push(k); return '([^/]+)'; }) + '$');
  routes.push({ method, pattern, keys, handler });
}
function problem(status: number, code: string, detail: string, extra: Record<string, unknown> = {}): never {
  throw new ApiError({ status, title: code, code, detail, ...extra } as ProblemDetail);
}
const notFound = (what: string, id: string) => problem(404, 'not_found', `${what} ${id} not found`);
function page<T>(items: T[], q: URLSearchParams): Page<T> {
  const offset = Number(q.get('offset') ?? 0); const limit = Math.min(200, Number(q.get('limit') ?? 25));
  return { items: items.slice(offset, offset + limit), total: items.length, offset, limit };
}
const actor = () => { const s = getSession(); return s.principal ? `user:${s.principal.id}` : 'user:anonymous'; };
const DEMO_USERS: Record<string, string[]> = { admin: ['admin'], ops: ['ops_analyst'], cs: ['cs_rep'], risk: ['risk_analyst'], finance: ['finance_controller'], strategy: ['strategy_lead'] };
const ROLE_PERMS: Record<string, string[]> = {
  admin: ['*'], strategy_lead: ['order.read', 'decision.*', 'statemodel.*', 'analytics.read', 'simulation.run', 'task.read', 'exception.read', 'config.admin', 'margin.approve', 'learning.*'],
  ops_analyst: ['order.read', 'order.modify', 'order.hold', 'order.release', 'order.bulk', 'task.*', 'exception.*', 'inventory.read', 'decision.read', 'decision.override', 'analytics.read'],
  cs_rep: ['order.read', 'order.modify', 'order.cancel', 'return.create', 'refund.issue', 'appeasement.issue', 'task.read', 'task.work', 'decision.read'],
  risk_analyst: ['order.read', 'return.decide', 'decision.read', 'decision.approve', 'decision.override', 'task.*', 'exception.read', 'agent.admin'],
  finance_controller: ['order.read', 'refund.approve', 'margin.approve', 'analytics.read', 'decision.read', 'decision.approve'],
};

/* ---- Auth ---- */
on('POST', '/api/v1/auth/login', ({ body }) => {
  const { tenantId, userId, password } = body as { tenantId: string; userId: string; password: string };
  const roles = DEMO_USERS[userId];
  if (tenantId !== 'demo' || !roles || password !== userId) problem(401, 'unauthorized', 'Invalid credentials');
  const perms = [...new Set(roles.flatMap((r) => ROLE_PERMS[r] ?? []))];
  const principal = { type: 'USER' as const, id: userId, tenantId, roles, permissions: perms, trustTier: 3 };
  return { token: `mock.${btoa(JSON.stringify({ sub: userId, tenant: tenantId }))}.sig`, principal };
});
on('GET', '/api/v1/auth/me', () => getSession().principal ?? problem(401, 'unauthorized', 'No session'));
on('POST', '/api/v1/auth/api-keys', ({ body }) => ({ keyId: nextId('key'), apiKey: `oms_live_${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`, note: 'Store this key now; it is not retrievable later.', label: body.label }));
on('DELETE', '/api/v1/auth/api-keys/:id', () => undefined);

/* ---- Launch visibility (registered before /decisions/:name so /decisions/providers is not shadowed) ---- */
registerLaunchRoutes({ on });

/* ---- Orders ---- */
function summary(m: typeof orders[number]): OrderSummary {
  const o = m.order;
  return { id: o.id, channel: o.channel, channelOrderId: o.channelOrderId, orderType: o.orderType, customerRef: o.customerRef, status: o.status, total: o.totals.grandTotal, lineCount: o.lines.length, placedAt: o.placedAt, nodeIds: m.groups.map((g) => g.nodeId), atRisk: !!m.risk && m.risk.score >= 3, riskScore: m.risk?.score, held: m.holds.some((h) => !h.resolvedAt), promiseDate: m.groups[0]?.promiseDate, agentId: o.provenance?.agentId ?? null };
}
on('GET', '/api/v1/orders', ({ query }) => {
  let list = orders.slice();
  const q = query.get('q')?.toLowerCase(); const status = query.get('status'); const channel = query.get('channel'); const orderType = query.get('orderType'); const nodeId = query.get('nodeId'); const from = query.get('placedFrom'); const to = query.get('placedTo'); const atRisk = query.get('atRisk'); const cust = query.get('customerRef');
  if (q) list = list.filter((m) => m.order.id.toLowerCase().includes(q) || m.order.channelOrderId.toLowerCase().includes(q) || (m.order.customer?.name ?? '').toLowerCase().includes(q) || (m.order.customer?.email ?? '').toLowerCase().includes(q) || m.order.lines.some((l) => l.sku.toLowerCase().includes(q)));
  if (status) list = list.filter((m) => status.split(',').includes(m.order.status) || m.lineStatuses.some((ls) => ls.quantities.some((qq) => status.split(',').includes(qq.state))));
  if (channel) list = list.filter((m) => m.order.channel === channel);
  if (orderType) list = list.filter((m) => m.order.orderType === orderType);
  if (nodeId) list = list.filter((m) => m.groups.some((g) => g.nodeId === nodeId));
  if (cust) list = list.filter((m) => m.order.customerRef === cust);
  if (from) list = list.filter((m) => m.order.placedAt >= from);
  if (to) list = list.filter((m) => m.order.placedAt <= to + 'T23:59:59Z');
  if (atRisk === 'true') list = list.filter((m) => m.risk && m.risk.score >= 3);
  const age = query.get('ageBucket'); if (age) list = list.filter((m) => ageBucket(NOW - Date.parse(m.order.placedAt)) === age);
  list.sort((a, b) => b.order.placedAt.localeCompare(a.order.placedAt));
  return page(list.map(summary), query);
});
const findOrder = (id: string) => orders.find((m) => m.order.id === id || m.order.channelOrderId === id) ?? notFound('Order', id);
on('GET', '/api/v1/orders/:id', ({ params }) => findOrder(params.id).order);
on('GET', '/api/v1/orders/:id/view', ({ params }): OrderView => {
  const m = findOrder(params.id);
  const recs = decisionRecords.filter((r) => r.subjectType === 'order' && r.subjectId === m.order.id).sort((a, b) => a.evaluatedAt.localeCompare(b.evaluatedAt));
  const rets = returns.filter((r) => r.orderId === m.order.id).map((r) => ({ rma: r.rma, orderId: r.orderId, status: r.status, createdAt: r.createdAt, channel: r.channel, lineCount: r.lines.length, refundAmount: r.refundAmount, riskScore: r.riskScore ?? undefined, refundTiming: r.refundTiming ?? undefined }));
  const waiting = m.workflow.status.startsWith('WAITING') ? { name: m.workflow.currentStep === 'await_fulfilment' ? 'event.shipped' : m.workflow.currentStep === 'await_delivery' ? 'event.delivered' : m.workflow.currentStep === 'source' ? 'task.completed' : 'event.order_validated', expectedBy: m.workflow.nextWakeAt ?? new Date(NOW + 4 * 3_600_000).toISOString() } : null;
  return { order: m.order, header: m.header, lineStatuses: m.lineStatuses, fulfilmentGroups: m.groups, holds: m.holds, shipments: m.shipments, returns: rets, payments: m.payments, refunds: m.refunds, decisionRecordIds: recs.map((r) => r.id), decisions: recs, workflow: m.workflow, timeline: m.timeline, ledger: m.ledger, communications: m.comms, risk: m.risk, nextExpectedEvent: waiting };
});
on('GET', '/api/v1/orders/:id/timeline', ({ params }) => findOrder(params.id).timeline);
on('GET', '/api/v1/admin/ledger/:type/:id', ({ params }) => (params.type === 'order' ? findOrder(params.id).ledger : []));
on('POST', '/api/v1/orders/:id/modifications/check', ({ params, body }): Feasibility => {
  const m = findOrder(params.id); const mod = body as unknown as Modification;
  const terminal = ['delivered', 'cancelled'].includes(m.order.status); const shipped = ['shipped', 'partially_shipped'].includes(m.order.status);
  if (terminal) return { allowed: false, reasons: [`Order is ${m.order.status}; no modifications allowed`], requiresApproval: false };
  if (mod.type === 'ChangeAddress') return shipped ? { allowed: true, reasons: ['Shipment in transit: carrier intercept available (UPS Delivery Change)'], costDelta: usd(14.5), requiresApproval: true, carrierIntercept: true } : { allowed: true, reasons: ['Lines not yet shipped; re-promise required'], costDelta: usd(m.order.addresses?.[0]?.state === 'TX' ? 0 : 3.2), requiresApproval: false, newPromise: { date: new Date(NOW + 3 * 86_400_000).toISOString().slice(0, 10), onTimeProbability: 0.91 } };
  if (mod.type === 'CancelOrder') return shipped ? { allowed: false, reasons: ['Some lines have shipped; cancel remaining lines individually or create a return'], requiresApproval: false } : { allowed: true, reasons: ['All lines cancellable from current state', 'Authorization will be voided'], costDelta: usd(-m.order.totals.grandTotal.amount), requiresApproval: m.order.totals.grandTotal.amount > 500 };
  if (mod.type === 'CancelLine') { const ls = m.lineStatuses.find((l) => l.entityId.endsWith(':' + mod.lineId)); const sh = ls?.quantities.some((q) => ['shipped', 'delivered'].includes(q.state)); return { allowed: !sh, reasons: sh ? ['Line already shipped'] : ['Line in ' + ls?.state + '; transition api.cancel_line available'], costDelta: usd(-(m.order.lines.find((l) => l.lineId === mod.lineId)?.unitPrice.amount ?? 0)), requiresApproval: false, affectedLines: [mod.lineId ?? ''] }; }
  if (mod.type === 'ChangeQty') return { allowed: !shipped, reasons: shipped ? ['Line already shipped'] : ['ATP available at ' + (m.groups[0]?.nodeId ?? 'DC-NJ')], costDelta: usd(((mod.qty ?? 1) - (m.order.lines.find((l) => l.lineId === mod.lineId)?.qty ?? 1)) * (m.order.lines.find((l) => l.lineId === mod.lineId)?.unitPrice.amount ?? 0)), requiresApproval: false, affectedLines: [mod.lineId ?? ''] };
  if (mod.type === 'ChangeShipMethod') return { allowed: !shipped, reasons: shipped ? ['Already shipped'] : ['Upgrade available before cutoff'], costDelta: usd(mod.shipMethod === '2day' ? 11.4 : mod.shipMethod === 'overnight' ? 26.9 : 0), requiresApproval: false, newPromise: { date: new Date(NOW + (mod.shipMethod === 'overnight' ? 1 : 2) * 86_400_000).toISOString().slice(0, 10), onTimeProbability: 0.95 } };
  return { allowed: !shipped, reasons: shipped ? ['Already shipped'] : ['Substitute item in stock; price difference applies'], costDelta: usd(12), requiresApproval: false };
});
on('POST', '/api/v1/orders/:id/modifications', ({ params, body }) => {
  const m = findOrder(params.id); const mod = (body as { modification: Modification }).modification; const reason = String(body.reason ?? '');
  if (mod.type === 'CancelLine' && mod.lineId) { const ls = m.lineStatuses.find((l) => l.entityId.endsWith(':' + mod.lineId)); if (ls) { ls.state = 'cancelled'; ls.quantities = [{ state: 'cancelled', qty: ls.quantities.reduce((s, q) => s + q.qty, 0), since: new Date().toISOString() }]; } }
  if (mod.type === 'ChangeQty' && mod.lineId) { const l = m.order.lines.find((x) => x.lineId === mod.lineId); if (l) l.qty = mod.qty ?? l.qty; }
  if (mod.type === 'ChangeAddress' && mod.address) { m.order.addresses = [{ ...(m.order.addresses?.[0] ?? { type: 'ship_to' }), ...mod.address, verified: true }]; }
  if (mod.type === 'ChangeShipMethod' && mod.shipMethod) m.groups.forEach((g) => { g.shipMethod = mod.shipMethod; });
  m.ledger.push({ id: nextId('evt'), type: 'order.modified', aggregateType: 'order', aggregateId: m.order.id, at: new Date().toISOString(), actor: actor(), data: { type: mod.type, reason, lineId: mod.lineId }, version: m.ledger.length + 1 });
  m.timeline.push({ at: new Date().toISOString(), kind: 'signal', name: 'order.modified', status: 'received', details: { type: mod.type } });
  m.order.version++;
  return m.order;
});
on('POST', '/api/v1/orders/:id/cancel', ({ params, body }) => {
  const m = findOrder(params.id);
  if (['shipped', 'delivered', 'cancelled'].includes(m.order.status)) problem(409, 'invalid_transition', `Cannot cancel an order in state ${m.order.status}`);
  m.order.status = 'cancelled'; m.header.state = 'cancelled'; m.workflow.status = 'CANCELLED';
  m.lineStatuses.forEach((ls) => { ls.state = 'cancelled'; ls.quantities = [{ state: 'cancelled', qty: ls.quantities.reduce((s, q) => s + q.qty, 0), since: new Date().toISOString() }]; });
  m.ledger.push({ id: nextId('evt'), type: 'order.cancelled', aggregateType: 'order', aggregateId: m.order.id, at: new Date().toISOString(), actor: actor(), data: { reason: body.reason }, version: m.ledger.length + 1 });
  m.risk = null;
  return m.order;
});
function placeHold(scopeType: string, scopeId: string, b: Record<string, unknown>): Hold {
  const m = findOrder(scopeId);
  const hold: Hold = { id: nextId('hold'), type: String(b.type ?? 'manual'), scopeType, scopeId: m.order.id, blocksTransitions: (b.blocks as string[]) ?? ['release'], reasonCode: String(b.reasonCode ?? 'manual'), note: b.note as string | undefined, expiresAt: b.ttl ? new Date(NOW + 24 * 3_600_000).toISOString() : null, placedBy: actor(), placedAt: new Date().toISOString(), resolvedAt: null };
  m.holds.push(hold);
  m.ledger.push({ id: nextId('evt'), type: 'hold.placed', aggregateType: 'order', aggregateId: m.order.id, at: hold.placedAt, actor: hold.placedBy, data: { type: hold.type, blocks: hold.blocksTransitions }, version: m.ledger.length + 1 });
  return hold;
}
on('POST', '/api/v1/orders/:id/holds', ({ params, body }) => placeHold('order', params.id, body));
on('POST', '/api/v1/holds', ({ body }) => placeHold(String(body.scopeType ?? 'order'), String(body.scopeId), body));
on('GET', '/api/v1/holds', ({ query }) => findOrder(query.get('scopeId') ?? '').holds.filter((h) => !h.resolvedAt));
on('POST', '/api/v1/holds/:id/release', ({ params, body }) => {
  for (const m of orders) { const h = m.holds.find((x) => x.id === params.id); if (h) { h.resolvedAt = new Date().toISOString(); h.resolvedBy = actor(); h.resolution = String(body.resolution ?? 'released'); m.ledger.push({ id: nextId('evt'), type: 'hold.released', aggregateType: 'order', aggregateId: m.order.id, at: h.resolvedAt, actor: h.resolvedBy, data: { holdId: h.id, resolution: h.resolution }, version: m.ledger.length + 1 }); return h; } }
  return notFound('Hold', params.id);
});
on('GET', '/api/v1/order-types', () => [
  { name: 'standard_ship', headerStateModel: 'order.standard_ship', lineStateModel: 'order_line.standard_ship', workflowType: 'order.standard_ship', decisionSet: ['fulfilment_intent', 'sourcing_node_score', 'sla_breach_risk'] },
  { name: 'bopis', headerStateModel: 'order.standard_ship', lineStateModel: 'order_line.bopis', workflowType: 'order.bopis', decisionSet: ['sourcing_node_score', 'sla_breach_risk'] },
  { name: 'preorder', headerStateModel: 'order.standard_ship', lineStateModel: 'order_line.standard_ship', workflowType: 'order.standard_ship', decisionSet: ['fulfilment_intent', 'backorder_next_action'] },
]);
on('GET', '/api/v1/orders/:id/sourcing/options', ({ params }) => { const m = findOrder(params.id); return ['ship_complete', 'ship_as_available', 'pickup_consolidated'].map((policy, k) => ({ id: nextId('plan'), policy, packages: m.groups.map((g) => ({ nodeId: g.nodeId, allocations: g.lineIds.map((lid) => ({ lineId: lid, sku: m.order.lines.find((l) => l.lineId === lid)?.sku ?? '', qty: 1 })), eta: g.promiseDate ?? '', onTimeProbability: g.onTimeProbability ?? 0.9, shippingCost: usd(8 + k * 2) })), totalCost: usd(12 + k * 3), score: round(0.9 - k * 0.1, 2), meetsMarginFloor: k < 2 })); });
on('POST', '/api/v1/orders/:id/sourcing', ({ params }) => ({ plan: null, requiresApproval: false, decisionRecordId: addDecisionRecord('sourcing_node_score', 'order', params.id, new Date().toISOString(), { node: { id: 'DC-NJ' } }, { forceValue: 3 }).id }));
on('POST', '/api/v1/orders/:id/resource', ({ params, body }) => { const m = findOrder(params.id); m.ledger.push({ id: nextId('evt'), type: 'order.resourced', aggregateType: 'order', aggregateId: m.order.id, at: new Date().toISOString(), actor: actor(), data: { reason: body.reason }, version: m.ledger.length + 1 }); return { plan: null, requiresApproval: false }; });
on('GET', '/api/v1/orders/:id/evidence-pack', ({ params }) => {
  const m = findOrder(params.id); const p = m.order.provenance;
  if (!p) problem(404, 'not_found', `Order ${params.id} has no agent provenance`);
  const trail = decisionRecords.filter((r) => r.subjectId === m.order.id);
  return { orderId: m.order.id, generatedAt: new Date().toISOString(), mandate: mandates.find((x) => x.id === p!.mandateId) ?? null, identity: agents.find((a) => a.id === p!.agentId) ?? null, instructionText: p!.instructionText, injectionScreen: { flagged: false, probability: 0.03, recordId: trail.find((r) => r.decision === 'injection_screen')?.id }, decisionTrail: trail, deliveryProof: m.shipments[0] ? { carrier: m.shipments[0].carrier, tracking: m.shipments[0].tracking, deliveredAt: m.shipments[0].deliveredAt ?? null, signature: m.shipments[0].deliveredAt ? 'front door photo' : null } : null, signatures: { mandate: p!.signature ?? '', pack: 'sha256:' + Math.random().toString(16).slice(2).padEnd(32, '0') }, hash: 'sha256:' + Math.random().toString(16).slice(2).padEnd(40, 'a') };
});

/* ---- Bulk ---- */
const bulkOps: BulkOperation[] = [];
on('POST', '/api/v1/bulk-operations', ({ body }) => {
  const { action, subjectIds, params, dryRun } = body as { action: string; subjectIds: string[]; params?: Record<string, unknown>; dryRun: boolean };
  const results = subjectIds.map((id) => {
    const m = orders.find((x) => x.order.id === id); if (!m) return { subjectId: id, status: 'failed', message: 'not found' };
    const terminal = ['delivered', 'cancelled'].includes(m.order.status); const shipped = m.order.status === 'shipped';
    if (terminal || (shipped && action !== 'hold')) return { subjectId: id, status: 'skipped', message: `order is ${m.order.status}` };
    if (action === 'release' && !m.holds.some((h) => !h.resolvedAt)) return { subjectId: id, status: 'skipped', message: 'no active hold' };
    if (!dryRun) {
      if (action === 'hold') placeHold('order', id, { type: params?.type ?? 'ops_review', reasonCode: params?.reasonCode ?? 'bulk', blocks: ['release'] });
      if (action === 'release') m.holds.filter((h) => !h.resolvedAt).forEach((h) => { h.resolvedAt = new Date().toISOString(); h.resolvedBy = actor(); h.resolution = 'bulk release'; });
      if (action === 'cancel') { m.order.status = 'cancelled'; m.header.state = 'cancelled'; m.risk = null; }
      if (action === 'resource' || action === 'repromise') m.ledger.push({ id: nextId('evt'), type: action === 'resource' ? 'order.resourced' : 'order.repromised', aggregateType: 'order', aggregateId: id, at: new Date().toISOString(), actor: actor(), data: { bulk: true, ...params }, version: m.ledger.length + 1 });
    }
    return { subjectId: id, status: dryRun ? 'planned' : 'ok', message: action === 'resource' ? `re-source from ${m.groups[0]?.nodeId ?? 'n/a'} → best alternative` : action === 'repromise' ? `new promise ${new Date(NOW + 3 * 86_400_000).toISOString().slice(0, 10)}` : undefined };
  });
  const op: BulkOperation = { id: nextId('bulk'), action, status: dryRun ? 'PLANNED' : 'COMPLETED', dryRun, total: subjectIds.length, succeeded: results.filter((r) => r.status === 'ok' || r.status === 'planned').length, failed: results.filter((r) => r.status === 'failed').length, perSecond: Number(body.perSecond ?? 20), params, results, submittedBy: actor(), submittedAt: new Date().toISOString(), completedAt: new Date().toISOString() };
  bulkOps.unshift(op); return op;
});
on('GET', '/api/v1/bulk-operations', ({ query }) => page(bulkOps, query));
on('GET', '/api/v1/bulk-operations/:id', ({ params }) => bulkOps.find((b) => b.id === params.id) ?? notFound('Bulk operation', params.id));
on('POST', '/api/v1/bulk-operations/:id/rollback', ({ params }) => { const b = bulkOps.find((x) => x.id === params.id) ?? notFound('Bulk operation', params.id); b.status = 'ROLLED_BACK'; return b; });
on('POST', '/api/v1/promise', ({ body }) => { const b = body as { destinationZip?: string }; const d = int(2, 5); return { date: new Date(NOW + d * 86_400_000).toISOString().slice(0, 10), onTimeProbability: round(0.85 + rnd() * 0.13, 2), fromNodeId: b.destinationZip?.startsWith('7') ? 'DC-TX' : 'DC-NJ', shipMethod: 'ground', fromCache: rnd() < 0.5, latencyMs: int(12, 180), alternatives: [{ date: new Date(NOW + 1 * 86_400_000).toISOString().slice(0, 10), onTimeProbability: 0.93, fromNodeId: 'DC-TX', shipMethod: 'overnight', cost: usd(26.9) }] }; });

/* ---- Entities ---- */
on('GET', '/api/v1/entities/:type/:id/status', ({ params }) => { if (params.type === 'order') return findOrder(params.id).header; for (const m of orders) { const ls = m.lineStatuses.find((l) => l.entityId === params.id); if (ls) return ls; } return notFound('Entity', params.id); });
on('GET', '/api/v1/entities/:type/:id/transitions', ({ params }) => { const m = findOrder(params.id.split(':')[0]); const model = stateModels[params.type === 'order' ? m.order.stateModelId : m.lineStatuses[0]?.modelId]?.at(-1); const st = params.type === 'order' ? m.header.state : m.lineStatuses.find((l) => l.entityId === params.id)?.state; return model?.transitions.filter((t) => t.from.includes(st ?? '')) ?? []; });
on('POST', '/api/v1/entities/:type/:id/transitions', () => ({ applied: true }));

/* ---- State models ---- */
on('GET', '/api/v1/state-models', () => Object.values(stateModels).map((vs) => { const m = vs[vs.length - 1]; return { id: m.id, version: m.version, entity: m.entity, quantityLevel: m.quantityLevel, stateCount: m.states.length, transitionCount: m.transitions.length, inFlight: m.entity === 'order_line' ? orders.filter((o) => o.lineStatuses.some((ls) => ls.modelId === m.id)).length : m.entity === 'order' ? orders.length : returns.length, publishedAt: m.publishedAt }; }));
on('GET', '/api/v1/state-models/:id', ({ params }) => stateModels[params.id]?.at(-1) ?? notFound('State model', params.id));
on('GET', '/api/v1/state-models/:id/versions', ({ params }) => stateModels[params.id] ?? notFound('State model', params.id));
on('GET', '/api/v1/state-models/:id/versions/:v', ({ params }) => stateModels[params.id]?.find((m) => m.version === Number(params.v)) ?? notFound('State model version', `${params.id}@${params.v}`));
function validateModel(text: string) {
  const issues: { severity: 'error' | 'warning'; code: string; message: string; ref?: string }[] = [];
  let model: StateModel | null = null;
  try { model = parseStateModelYaml(text); } catch (e) { issues.push({ severity: 'error', code: 'yaml_parse', message: e instanceof Error ? e.message : 'Invalid YAML' }); return { ok: false, issues, model: null }; }
  if (!model.id) issues.push({ severity: 'error', code: 'missing_id', message: 'stateModel id is required' });
  const ids = new Set(model.states.map((s) => s.id));
  if (!model.states.some((s) => s.meta === 'CREATED')) issues.push({ severity: 'error', code: 'no_initial', message: 'Exactly one state must have meta: Created' });
  if (!model.states.some((s) => s.meta === 'TERMINAL')) issues.push({ severity: 'warning', code: 'no_terminal', message: 'No terminal state defined' });
  for (const t of model.transitions) { for (const f of t.from) if (!ids.has(f)) issues.push({ severity: 'error', code: 'unknown_state', message: `Transition from unknown state '${f}'`, ref: t.id }); if (!ids.has(t.to)) issues.push({ severity: 'error', code: 'unknown_state', message: `Transition to unknown state '${t.to}'`, ref: t.id }); for (const g of t.guards ?? []) if (/[^!=<>]=[^=]/.test(g)) issues.push({ severity: 'error', code: 'cel_syntax', message: `Guard uses assignment '=' (did you mean '==')?`, ref: t.id }); }
  const reachable = new Set<string>(); const init = model.states.find((s) => s.meta === 'CREATED'); const stack = init ? [init.id] : [];
  while (stack.length) { const s = stack.pop()!; if (reachable.has(s)) continue; reachable.add(s); model.transitions.filter((t) => t.from.includes(s)).forEach((t) => stack.push(t.to)); }
  for (const s of model.states) { if (!reachable.has(s.id)) issues.push({ severity: 'warning', code: 'unreachable', message: `State '${s.id}' is unreachable from the initial state`, ref: s.id }); if (s.meta !== 'TERMINAL' && !model.transitions.some((t) => t.from.includes(s.id))) issues.push({ severity: 'error', code: 'dead_end', message: `Non-terminal state '${s.id}' has no outgoing transition`, ref: s.id }); if (s.sla && !/^P/.test(s.sla.maxAge)) issues.push({ severity: 'error', code: 'bad_duration', message: `SLA maxAge '${s.sla.maxAge}' is not an ISO-8601 duration`, ref: s.id }); }
  return { ok: !issues.some((i) => i.severity === 'error'), issues, model };
}
on('POST', '/api/v1/state-models/validate', ({ body }) => { const r = validateModel(String(body.yaml ?? '')); return { ok: r.ok, issues: r.issues }; });
on('POST', '/api/v1/state-models/publish', ({ body }) => {
  const r = validateModel(String(body.yaml ?? ''));
  if (!r.ok || !r.model) problem(422, 'validation_failed', 'State model has errors; fix them before publishing', { issues: r.issues });
  const existing = stateModels[r.model!.id] ?? [];
  if (existing.some((m) => m.version === r.model!.version)) problem(409, 'conflict', `Version ${r.model!.version} of ${r.model!.id} already exists; bump the version`);
  const m: StateModel = { ...r.model!, publishedAt: new Date().toISOString(), publishedBy: actor(), reason: String(body.reason ?? '') };
  (stateModels[m.id] ??= []).push(m); stateModels[m.id].sort((a, b) => a.version - b.version);
  auditLog.unshift({ id: auditLog.length + 1000, at: m.publishedAt!, actor: actor(), action: 'statemodel.publish', object_type: 'state_model', object_id: `${m.id}@${m.version}`, reason: m.reason ?? '' });
  return m;
});
on('POST', '/api/v1/state-models/:id/migrate', ({ params, body }) => {
  const { fromVersion, toVersion, dryRun } = body as { fromVersion: number; toVersion: number; dryRun: boolean };
  const from = stateModels[params.id]?.find((m) => m.version === fromVersion) ?? notFound('State model version', `${params.id}@${fromVersion}`);
  const to = stateModels[params.id]?.find((m) => m.version === toVersion) ?? notFound('State model version', `${params.id}@${toVersion}`);
  const ents = orders.flatMap((m) => m.lineStatuses.filter((ls) => ls.modelId === params.id && ls.modelVersion === fromVersion));
  const entities = ents.map((e) => { const exists = to.states.some((s) => s.id === e.state); if (!exists) return { entityType: e.entityType, entityId: e.entityId, fromState: e.state, toState: e.state, status: 'blocked', message: `State '${e.state}' does not exist in v${toVersion}` }; if (!dryRun) e.modelVersion = toVersion; return { entityType: e.entityType, entityId: e.entityId, fromState: e.state, toState: e.state, status: 'ok' }; });
  void from;
  return { modelId: params.id, fromVersion, toVersion, dryRun, total: entities.length, migrated: entities.filter((e) => e.status === 'ok').length, blocked: entities.filter((e) => e.status === 'blocked').length, entities };
});

/* ---- Workflows / tasks ---- */
on('GET', '/api/v1/workflows/:id', ({ params }) => orders.find((m) => m.workflow.id === params.id)?.workflow ?? notFound('Workflow', params.id));
on('GET', '/api/v1/workflows/:id/timeline', ({ params }) => orders.find((m) => m.workflow.id === params.id)?.timeline ?? notFound('Workflow', params.id));
on('GET', '/api/v1/workflows/by-key/:type/:key', ({ params }) => findOrder(params.key).workflow);
on('POST', '/api/v1/workflows/:id/signal', () => undefined);
on('GET', '/api/v1/queues', () => queues.map((q) => ({ ...q, openCount: tasks.filter((t) => t.queue === q.name && t.status === 'OPEN').length, claimedCount: tasks.filter((t) => t.queue === q.name && t.status === 'CLAIMED').length, overdueCount: tasks.filter((t) => t.queue === q.name && ['OPEN', 'CLAIMED'].includes(t.status) && t.slaDueAt && Date.parse(t.slaDueAt) < Date.now()).length })));
on('GET', '/api/v1/tasks', ({ query }) => { let list = tasks.slice(); const queue = query.get('queue'); const status = query.get('status'); const assignee = query.get('assignee'); const st = query.get('subjectType'); const sid = query.get('subjectId'); if (queue) list = list.filter((t) => t.queue === queue); if (status) list = list.filter((t) => status.split(',').includes(t.status)); if (assignee) list = list.filter((t) => t.assignee === assignee); if (st) list = list.filter((t) => t.subjectType === st); if (sid) list = list.filter((t) => t.subjectId === sid); list.sort((a, b) => (a.slaDueAt ?? 'z').localeCompare(b.slaDueAt ?? 'z')); return page(list, query); });
const findTask = (id: string) => tasks.find((t) => t.id === id) ?? notFound('Task', id);
on('GET', '/api/v1/tasks/:id', ({ params }) => findTask(params.id));
on('POST', '/api/v1/tasks/:id/claim', ({ params, body }) => { const t = findTask(params.id); if (t.status !== 'OPEN') problem(409, 'conflict', `Task is ${t.status}`); t.status = 'CLAIMED'; t.assignee = String(body.assignee ?? actor().split(':')[1]); return t; });
on('POST', '/api/v1/tasks/:id/complete', ({ params, body }) => { const t = findTask(params.id); if (!['OPEN', 'CLAIMED'].includes(t.status)) problem(409, 'conflict', `Task is ${t.status}`); if (!body.reason) problem(422, 'validation_failed', 'Reason is required', { fields: { reason: 'required' } }); t.status = 'COMPLETED'; t.outcome = String(body.outcome); t.outcomeReason = String(body.reason); t.completedBy = actor().split(':')[1]; t.completedAt = new Date().toISOString(); if (t.type === 'margin_floor_approval' && t.outcome === 'approved') { const m = orders.find((o) => o.order.id === t.subjectId); if (m?.order.margin) { m.order.margin.approvalPresent = true; m.order.margin.approvalTaskId = t.id; } } return t; });
on('POST', '/api/v1/tasks/:id/cancel', ({ params, body }) => { const t = findTask(params.id); t.status = 'CANCELLED'; t.outcomeReason = String(body.reason ?? ''); return t; });

/* ---- Decisions ---- */
on('GET', '/api/v1/decisions', (): DecisionSummary[] => decisionSummaries());
on('GET', '/api/v1/decisions/:name', ({ params }) => decisions[params.name] ?? notFound('Decision', params.name));
on('GET', '/api/v1/decisions/:name/versions/:v', ({ params }) => decisions[params.name]?.find((d) => d.version === Number(params.v)) ?? notFound('Decision version', `${params.name}@${params.v}`));
on('POST', '/api/v1/decisions/:name/drafts', ({ params, body }) => {
  const vs = decisions[params.name]; if (!vs) notFound('Decision', params.name);
  let parsed: ReturnType<typeof parseDecisionYaml>;
  try { parsed = parseDecisionYaml(String(body.yaml)); } catch (e) { return problem(422, 'validation_failed', `YAML parse error: ${e instanceof Error ? e.message : e}`); }
  const existingDraft = vs.find((d) => d.status === 'draft');
  const version = existingDraft ? existingDraft.version : Math.max(...vs.map((d) => d.version)) + 1;
  const yamlText = String(body.yaml).replace(/^version: \d+/m, `version: ${version}`);
  const def: DecisionDefinition = { ...(parsed as DecisionDefinition), yaml: yamlText, name: params.name, version, tier: parsed.tier ?? vs[vs.length - 1].tier, owner: parsed.owner ?? vs[vs.length - 1].owner, status: 'draft', canaryPercent: 0, createdAt: new Date().toISOString(), createdBy: actor().split(':')[1], approvals: [], killSwitch: false, changeReason: existingDraft?.changeReason, ticket: existingDraft?.ticket };
  if (existingDraft) Object.assign(existingDraft, def); else vs.push(def);
  return existingDraft ?? def;
});
const findVersion = (name: string, v: string) => decisions[name]?.find((d) => d.version === Number(v)) ?? notFound('Decision version', `${name}@${v}`);
function lintDef(def: DecisionDefinition): LintResult[] {
  const out: LintResult[] = [];
  for (const q of Object.values(def.questions)) {
    if (q.type === 'choice') {
      const keys = Object.keys(q.choiceCriteria ?? {});
      if (!keys.some((k) => /unclear|none|not_enough|escalate|other|unknown/.test(k))) out.push({ severity: 'error', code: 'missing_escape_option', message: `Choice '${q.key}' has no escape option (GR-03). Add e.g. 'unclear: Not enough information to tell'.`, path: `questions.${q.key}.criteria` });
      const descs = Object.values(q.choiceCriteria ?? {});
      for (let i = 0; i < descs.length; i++) for (let j = i + 1; j < descs.length; j++) { const a = new Set(descs[i].toLowerCase().split(/\W+/).filter((w) => w.length > 4)); const b = descs[j].toLowerCase().split(/\W+/).filter((w) => w.length > 4); const overlap = b.filter((w) => a.has(w)).length; if (overlap >= 3 && overlap / Math.max(1, Math.min(a.size, b.length)) > 0.6) out.push({ severity: 'warning', code: 'overlapping_options', message: `Options '${keys[i]}' and '${keys[j]}' have overlapping descriptions`, path: `questions.${q.key}.criteria` }); }
      for (const [k, d] of Object.entries(q.choiceCriteria ?? {})) if (d.trim().split(/\s+/).length < 3) out.push({ severity: 'warning', code: 'vague_option', message: `Option '${k}' description is too short to discriminate`, path: `questions.${q.key}.criteria.${k}` });
    }
    if (q.type === 'score') { for (const [i, l] of (q.scoreLevels ?? []).entries()) if (/\b(soon|some|few|normal|high|low)\b/i.test(l) && l.split(/\s+/).length < 6) out.push({ severity: 'warning', code: 'vague_rubric', message: `Score level ${i} ('${l}') uses a relative term without a measurable anchor`, path: `questions.${q.key}.levels[${i}]` }); if ((q.scoreLevels ?? []).length < 3) out.push({ severity: 'error', code: 'rubric_too_small', message: `Score '${q.key}' needs at least 3 levels`, path: `questions.${q.key}.levels` }); }
    const text = `${q.instructions ?? ''} ${q.statement ?? ''} ${Object.values(q.choiceCriteria ?? {}).join(' ')}`;
    if (/\b(email|phone|ssn|address|name)\b/i.test(text) && !/order notes/i.test(text)) out.push({ severity: 'error', code: 'pii_in_prompt', message: `Question '${q.key}' references PII fields in prompt text (GR-09)`, path: `questions.${q.key}` });
  }
  for (const p of def.stateInclude) if (/\b(age|gender|race|religion|zip)\b/i.test(p) && /return|risk|fraud/.test(def.name)) out.push({ severity: 'error', code: 'protected_attribute', message: `State path '${p}' is a protected attribute or close proxy (GR-16)`, path: 'state.include' });
  for (const p of def.stateInclude) if (!/^(order|customer|plan_options|node|candidate|event|carrier|return|inventory|vendor|line|input|payment|agent|mandate)\b/.test(p)) out.push({ severity: 'warning', code: 'unknown_state_path', message: `State path '${p}' is not in the state schema`, path: 'state.include' });
  for (const r of def.rules ?? []) { if (!r.when) out.push({ severity: 'error', code: 'rule_missing_when', message: `Rule '${r.id}' has no when expression`, path: `rules.${r.id}` }); const q = Object.values(def.questions).find((x) => x.type === 'choice'); if (q && r.then && !['block', 'escalate'].includes(r.then) && !r.then.includes('.') && !Object.keys(q.choiceCriteria ?? {}).includes(r.then)) out.push({ severity: 'error', code: 'rule_contradicts_options', message: `Rule '${r.id}' returns '${r.then}' which is not an option of '${q.key}'`, path: `rules.${r.id}.then` }); }
  const tierMin: Record<string, number> = { T0: 0.5, T1: 0.8, T2: 0.9, T3: 1.01 };
  if (def.bands.actGte < tierMin[def.tier]) out.push({ severity: 'error', code: 'band_below_tier_floor', message: `act threshold ${def.bands.actGte} is below the ${def.tier} starting gate (${tierMin[def.tier]})`, path: 'bands.act' });
  if (def.bands.confirmGte > def.bands.actGte) out.push({ severity: 'error', code: 'band_order', message: 'confirm threshold must be below act threshold', path: 'bands' });
  if (!def.evaluation && def.tier !== 'T0') out.push({ severity: 'warning', code: 'no_golden_set', message: 'No evaluation.goldenSet; publish gate cannot run (GR-11)', path: 'evaluation' });
  if (!out.length) out.push({ severity: 'info', code: 'clean', message: 'No lint findings' });
  return out;
}
on('POST', '/api/v1/decisions/:name/versions/:v/lint', ({ params, body }) => { let def = findVersion(params.name, params.v); if (body.yaml) { try { def = { ...def, ...(parseDecisionYaml(String(body.yaml)) as DecisionDefinition) }; } catch (e) { return [{ severity: 'error', code: 'yaml_parse', message: e instanceof Error ? e.message : 'Invalid YAML' }]; } } return lintDef(def); });
on('POST', '/api/v1/decisions/:name/versions/:v/impact', ({ params, body }): ImpactDiff => {
  const def = findVersion(params.name, params.v); const live = liveVersion(params.name); const days = Number(body.days ?? 30);
  const recs = decisionRecords.filter((r) => r.decision === params.name && r.mode === 'LIVE');
  const evaluated = Math.max(recs.length, days * int(18, 60));
  const flipRate = round(0.02 + rnd() * 0.08, 3); const flips = Math.round(evaluated * flipRate);
  const before: Record<Band, number> = { ACT: 0, CONFIRM: 0, ESCALATE: 0 }; recs.forEach((r) => { before[r.band]++; });
  const total = Math.max(1, recs.length); const mixB = { ACT: round(before.ACT / total, 3), CONFIRM: round(before.CONFIRM / total, 3), ESCALATE: round(before.ESCALATE / total, 3) };
  const shift = def.bands.actGte > (live?.bands.actGte ?? 0) ? -0.06 : 0.03;
  const mixA = { ACT: round(Math.max(0, mixB.ACT + shift), 3), CONFIRM: round(Math.max(0, mixB.CONFIRM - shift * 0.6), 3), ESCALATE: round(Math.max(0, mixB.ESCALATE - shift * 0.4), 3) };
  const q = Object.values(def.questions).find((x) => x.type === 'choice'); const opts = q ? Object.keys(q.choiceCriteria ?? {}) : ['0', '1', '2', '3'];
  const samples = recs.slice(0, Math.min(8, flips)).map((r) => { const after = opts.filter((o) => o !== String(r.value))[int(0, Math.max(0, opts.length - 2))]; const confA = round(Math.min(0.99, Math.max(0.3, r.confidence + (rnd() - 0.5) * 0.3)), 3); const bandA: Band = confA >= def.bands.actGte ? 'ACT' : confA >= def.bands.confirmGte ? 'CONFIRM' : 'ESCALATE'; return { recordId: r.id, subjectType: r.subjectType, subjectId: r.subjectId, before: r.value, after, bandBefore: r.band, bandAfter: bandA, confidenceBefore: r.confidence, confidenceAfter: confA }; });
  return { days, baseVersion: live?.version ?? def.version - 1, draftVersion: def.version, evaluated, flips, flipRate, bandMixBefore: mixB, bandMixAfter: mixA, layerMixBefore: { L0_RULE: 0.18, L1_SYSTEM_ONE: 0.71, L2_LLM: 0.07, L3_HUMAN: 0.04 }, layerMixAfter: { L0_RULE: 0.18, L1_SYSTEM_ONE: round(0.71 + shift, 3), L2_LLM: round(0.07 - shift * 0.6, 3), L3_HUMAN: round(0.04 - shift * 0.4, 3) }, costDeltaUsd: round(-shift * evaluated * 0.006, 2), latencyDeltaMs: Math.round(-shift * 900), escalationDelta: Math.round(-shift * 0.4 * evaluated), marginDeltaUsd: round((rnd() - 0.4) * 800, 2), slaBreachDelta: int(-6, 3), samples };
});
function bins(n: number, calibErr: number): CalibrationBin[] { return Array.from({ length: 10 }, (_, i) => { const lower = i / 10; const mean = round(lower + 0.05, 3); const count = Math.round(n * (i < 4 ? 0.03 : i < 7 ? 0.08 : 0.2)); return { bin: i, lower, upper: lower + 0.1, count, meanConfidence: mean, accuracy: round(Math.min(1, Math.max(0, mean + (rnd() - 0.5) * calibErr * 4 + (i > 7 ? -calibErr : 0))), 3) }; }); }
on('POST', '/api/v1/decisions/:name/versions/:v/evaluate', ({ params }): EvaluationResult => {
  const def = findVersion(params.name, params.v); const spec = def.evaluation ?? { goldenSet: `${params.name}_gold`, minAccuracyInActBand: 0.85, maxCalibrationError: 0.05 };
  const good = def.status !== 'draft' || rnd() < 0.6; const acc = round(good ? spec.minAccuracyInActBand + 0.02 + rnd() * 0.05 : spec.minAccuracyInActBand - 0.03 - rnd() * 0.05, 3); const cal = round(good ? 0.02 + rnd() * 0.02 : 0.055 + rnd() * 0.03, 3);
  const cases = params.name === 'fulfilment_intent' ? 240 : int(180, 420);
  return { goldenSet: spec.goldenSet, cases, accuracyInActBand: acc, minAccuracyInActBand: spec.minAccuracyInActBand, calibrationError: cal, maxCalibrationError: spec.maxCalibrationError, passed: acc >= spec.minAccuracyInActBand && cal <= spec.maxCalibrationError, actBandShare: round(0.6 + rnd() * 0.3, 2), bins: bins(cases, cal), failures: Array.from({ length: 4 }, (_, i) => ({ caseId: `gc_${(1200 + i * 17).toString(36)}`, expected: 'ship_complete', got: i % 2 ? 'ship_as_available' : 'unclear', confidence: round(0.6 + rnd() * 0.3, 2) })), evaluatedAt: new Date().toISOString() };
});
on('POST', '/api/v1/decisions/:name/versions/:v/submit', ({ params, body }) => { const d = findVersion(params.name, params.v); if (d.status !== 'draft') problem(409, 'conflict', `Version is ${d.status}; only drafts can be submitted`); if (!body.reason || !body.ticket) problem(422, 'validation_failed', 'Change reason and ticket are required (FR-PRM-06)', { fields: { reason: body.reason ? '' : 'required', ticket: body.ticket ? '' : 'required' } }); d.status = 'pending_approval'; d.changeReason = String(body.reason); d.ticket = String(body.ticket); d.submittedBy = actor().split(':')[1]; d.approvals = []; auditLog.unshift({ id: auditLog.length + 1000, at: new Date().toISOString(), actor: actor(), action: 'decision.submit', object_type: 'decision', object_id: `${d.name}@${d.version}`, reason: d.changeReason }); return d; });
on('POST', '/api/v1/decisions/:name/versions/:v/approve', ({ params, body }) => { const d = findVersion(params.name, params.v); const me = actor().split(':')[1]; if (d.status !== 'pending_approval') problem(409, 'conflict', `Version is ${d.status}; nothing to approve`); if (d.submittedBy === me) problem(403, 'separation_of_duties', 'The submitter cannot approve their own change (GR-14)'); if (d.approvals?.some((a) => a.approver === me)) problem(409, 'conflict', 'You have already approved this version'); d.approvals = [...(d.approvals ?? []), { approver: me, at: new Date().toISOString(), comment: body.comment as string | undefined }]; return d; });
on('POST', '/api/v1/decisions/:name/versions/:v/publish', ({ params, body }) => {
  const d = findVersion(params.name, params.v); const rollout = String(body.rollout ?? 'live'); const need = d.tier === 'T2' || d.tier === 'T3' ? 2 : 1;
  if (d.status === 'draft') problem(409, 'conflict', 'Submit the draft for approval first');
  if ((d.approvals?.length ?? 0) < need && d.status === 'pending_approval') problem(403, 'approvals_required', `${d.tier} changes need ${need} approver${need > 1 ? 's' : ''}; ${d.approvals?.length ?? 0} so far`);
  if (rollout === 'live') { decisions[params.name].forEach((x) => { if (x.status === 'live') x.status = 'retired'; }); d.status = 'live'; d.canaryPercent = 0; }
  else if (rollout.startsWith('canary')) { d.status = 'canary'; d.canaryPercent = Number(rollout.split(':')[1] ?? 10); }
  else { d.status = 'shadow'; }
  auditLog.unshift({ id: auditLog.length + 1000, at: new Date().toISOString(), actor: actor(), action: 'decision.publish', object_type: 'decision', object_id: `${d.name}@${d.version}`, reason: rollout });
  return d;
});
on('POST', '/api/v1/decisions/:name/rollback', ({ params, body }) => { const vs = decisions[params.name] ?? notFound('Decision', params.name); const live = vs.find((d) => d.status === 'live'); const prev = vs.filter((d) => d.status === 'retired' && (!live || d.version < live.version)).at(-1); if (!prev) problem(409, 'conflict', 'No previous version to roll back to'); if (live) live.status = 'retired'; prev!.status = 'live'; vs.filter((d) => d.status === 'canary').forEach((d) => { d.status = 'retired'; }); auditLog.unshift({ id: auditLog.length + 1000, at: new Date().toISOString(), actor: actor(), action: 'decision.rollback', object_type: 'decision', object_id: `${params.name}@${prev!.version}`, reason: String(body.reason ?? '') }); return prev; });
on('POST', '/api/v1/decisions/:name/kill-switch', ({ params, body }) => { const vs = decisions[params.name] ?? notFound('Decision', params.name); vs.forEach((d) => { d.killSwitch = !!body.on; }); config[`decision.kill_switch.${params.name}`] = !!body.on; return { name: params.name, killSwitch: !!body.on }; });
on('POST', '/api/v1/decisions/:name/evaluate-now', ({ params, body }): DecisionResult => {
  const vs = decisions[params.name] ?? notFound('Decision', params.name);
  const def = body.version ? vs.find((d) => d.version === Number(body.version)) ?? vs.at(-1)! : liveVersion(params.name) ?? vs.at(-1)!;
  const input = (body.input as Record<string, unknown>) ?? {};
  const rule = (def.rules ?? []).find((r) => { const inp = JSON.stringify(input); return r.when.includes('intent_choices') ? /"intent_choices":\s*\["[^"]+"\]/.test(inp) : r.when.includes('has_hazmat') ? /"has_hazmat":\s*true/.test(inp) : false; });
  const rec = addDecisionRecord(params.name, 'adhoc', `studio:${actor().split(':')[1]}`, new Date().toISOString(), input, { mode: (body.mode as DecisionRecord['mode']) ?? 'SIMULATION', rulesFired: rule ? [rule.id] : [], forceValue: rule ? (rule.then.includes('intent_choices') ? ((input as { order?: { intent_choices?: string[] } }).order?.intent_choices?.[0]) : rule.then) : undefined });
  rec.version = def.version;
  const t = traces[rec.id];
  return { recordId: rec.id, decision: rec.decision, version: rec.version, layer: rec.layer, band: rec.band, value: rec.value, answers: Object.fromEntries(Object.entries(t.answers).map(([k, a]) => [k, { type: a.type as 'choice', value: a.value, probabilities: a.probabilities, confidence: a.confidence }])), confidence: rec.confidence, action: rec.action, requiresHuman: rec.band === 'ESCALATE', escalationId: null, rulesFired: t.rulesFired, reasonCodes: rec.reasonCodes, latencyMs: rec.latencyMs, costUsd: rec.costUsd, provider: rec.provider, modelVersion: rec.modelVersion, details: { mode: rec.mode } };
});

/* ---- Decision records ---- */
on('GET', '/api/v1/decision-records', ({ query }) => { const st = query.get('subjectType'); const sid = query.get('subjectId'); const dec = query.get('decision'); let list = decisionRecords.slice().sort((a, b) => b.evaluatedAt.localeCompare(a.evaluatedAt)); if (st) list = list.filter((r) => r.subjectType === st); if (sid) list = list.filter((r) => r.subjectId === sid); if (dec) list = list.filter((r) => r.decision === dec); return st && sid ? list : page(list, query); });
const findRecord = (id: string) => decisionRecords.find((r) => r.id === id) ?? notFound('Decision record', id);
on('GET', '/api/v1/decision-records/:id', ({ params }) => findRecord(params.id));
on('GET', '/api/v1/decision-records/:id/why', ({ params, query }): DecisionTrace => {
  const r = findRecord(params.id); const def = decisions[r.decision]?.find((d) => d.version === r.version) ?? decisions[r.decision]?.at(-1); const t = traces[r.id];
  const audience = query.get('audience') === 'customer' ? 'customer' : 'internal';
  const questions: TraceQuestion[] = Object.entries(t?.answers ?? {}).map(([key, a]) => { const q = def?.questions[key]; const keys = Object.keys(q?.choiceCriteria ?? {}); return { key, type: (q?.type ?? a.type) as TraceQuestion['type'], instructions: q?.instructions, criteria: q?.choiceCriteria, levels: q?.scoreLevels, statement: q?.statement, answer: { type: a.type as TraceQuestion['type'], value: a.value, probabilities: a.probabilities, confidence: a.confidence }, escape: a.type === 'choice' && keys.length > 0 && String(a.value) === keys[keys.length - 1] && /unclear|escalate|other|none/.test(String(a.value)) }; });
  const ranL0 = true; const ranL1 = r.layer !== 'L0_RULE'; const ranL2 = r.layer === 'L2_LLM' || (r.layer === 'L3_HUMAN' && rnd() < 0.5); const ranL3 = r.layer === 'L3_HUMAN';
  const layers: TraceLayerStep[] = [
    { layer: 'L0_RULE', ran: ranL0, outcome: r.layer === 'L0_RULE' ? `rule ${t?.rulesFired[0] ?? ''} matched` : 'no rule matched', latencyMs: 2 },
    { layer: 'L1_SYSTEM_ONE', ran: ranL1, outcome: ranL1 ? `${r.provider} answered, confidence ${r.confidence}` : 'skipped (L0 short-circuit)', latencyMs: ranL1 ? Math.min(r.latencyMs, 480) : undefined },
    { layer: 'L2_LLM', ran: ranL2, outcome: ranL2 ? (r.layer === 'L2_LLM' ? `claude-sonnet-5 proposed '${r.value}' from the enumerated options (read-only tools)` : 'LLM could not confirm; escalated') : 'not required', latencyMs: ranL2 ? r.latencyMs : undefined },
    { layer: 'L3_HUMAN', ran: ranL3, outcome: ranL3 ? (t?.escalationTaskId ? `task ${t.escalationTaskId} in decision_escalations` : 'escalation stored') : 'not required' },
  ];
  const rules = (def?.rules ?? []).map((rule) => ({ id: rule.id, when: rule.when, then: rule.then, reason: rule.reason, matched: (t?.rulesFired ?? []).includes(rule.id) }));
  const inputs = audience === 'customer' ? redact(t?.inputs ?? {}) : t?.inputs ?? {};
  const task = t?.escalationTaskId ? tasks.find((x) => x.id === t.escalationTaskId) : undefined;
  const label = (def && Object.values(def.questions)[0]?.type === 'score') ? `${Object.values(def.questions)[0].scoreLevels?.[Number(r.value)] ?? r.value}` : String(r.value);
  return {
    record: r, definition: { name: r.decision, version: r.version, tier: def?.tier ?? 'T1', owner: def?.owner ?? '', purpose: def?.purpose, bands: def?.bands ?? { actGte: 0.8, confirmGte: 0.5 }, status: def?.status },
    provider: r.provider, modelVersion: r.modelVersion, latencyMs: r.latencyMs, costUsd: r.costUsd, inputTokens: r.layer === 'L0_RULE' ? 0 : int(400, 2600), outputTokens: r.layer === 'L0_RULE' ? 0 : int(20, 90),
    layers, inputs, inputHash: 'sha256:' + r.id.replace(/\W/g, '').padEnd(16, '0').slice(0, 16) + '…', tokenisedFields: ['customer.name', 'customer.email', 'ship_to.address'].filter(() => audience === 'internal'),
    questions, rules, checks: (t?.checks ?? []).map((c) => ({ ...c })), band: r.band, action: r.action, actor: r.actor ?? 'system:workflow',
    escalation: ranL3 ? { id: `esc_${r.id.slice(3)}`, queue: 'decision_escalations', taskId: task?.id, status: task?.status ?? 'OPEN' } : null,
    override: r.overridden ? { by: 'user:ops', at: r.evaluatedAt, value: r.overrideValue, reason: r.overrideReason ?? '' } : null, label: r.label ? { label: r.label, at: new Date().toISOString(), by: 'user:ops' } : null,
    explanation: audience === 'customer' ? undefined : `${r.decision.replace(/_/g, ' ')} v${r.version} ${r.layer === 'L0_RULE' ? `was settled by rule '${t?.rulesFired[0]}' without a model call` : `was answered by ${r.provider} with confidence ${r.confidence}, which falls in the ${r.band} band for a ${def?.tier} decision`}. Outcome: ${label}. (Generated from the trace; not a source of truth.)`,
    customerSafeExplanation: audience === 'customer' ? `We chose "${label}" for this order based on the delivery options available and your order details. Internal cost and risk information is not shown.` : undefined, audience,
  };
});
function redact(obj: Record<string, unknown>): Record<string, unknown> { const out: Record<string, unknown> = {}; for (const [k, v] of Object.entries(obj)) { if (/margin|cost|risk|history|penalty|health|cogs|fraud|not_received|return_rate/i.test(k)) continue; out[k] = v && typeof v === 'object' && !Array.isArray(v) ? redact(v as Record<string, unknown>) : v; } return out; }
on('POST', '/api/v1/decision-records/:id/label', ({ params, body }) => { const r = findRecord(params.id); r.label = String(body.label); return undefined; });
on('POST', '/api/v1/decision-records/:id/override', ({ params, body }) => { const r = findRecord(params.id); if (!body.reason) problem(422, 'validation_failed', 'Override reason is required (GR-13)', { fields: { reason: 'required' } }); r.overridden = true; r.overrideValue = body.newValue; r.overrideReason = String(body.reason); auditLog.unshift({ id: auditLog.length + 1000, at: new Date().toISOString(), actor: actor(), action: 'decision.override', object_type: 'decision_record', object_id: r.id, reason: r.overrideReason }); return r; });
on('GET', '/api/v1/golden-sets/:name', ({ params }): GoldenSet => ({ name: params.name, updatedAt: daysAgo(4), cases: Array.from({ length: 12 }, (_, i) => ({ id: `gc_${(1200 + i * 17).toString(36)}`, input: { order: { channel: ['web', 'amazon', 'shopify'][i % 3], notes: i % 2 ? ['Ship together please'] : [] }, customer: { tier: 'gold' } }, expected: i % 2 ? 'ship_complete' : 'ship_as_available', label: 'outcome_confirmed' })) }));
on('POST', '/api/v1/golden-sets/:name', ({ params, body }) => ({ name: params.name, cases: body.cases ?? [], updatedAt: new Date().toISOString() }));

/* ---- Inventory / nodes ---- */
on('GET', '/api/v1/nodes', () => nodes);
on('GET', '/api/v1/nodes/:id', ({ params }) => nodes.find((n) => n.id === params.id) ?? notFound('Node', params.id));
on('PUT', '/api/v1/nodes/:id', ({ params, body }) => { const i = nodes.findIndex((n) => n.id === params.id); const n = { ...(nodes[i] ?? {}), ...(body as object), id: params.id } as typeof nodes[number]; if (i >= 0) nodes[i] = n; else nodes.push(n); return n; });
on('GET', '/api/v1/inventory/:sku', ({ params }) => nodes.map((n) => { const onHand = int(0, 400); const reserved = int(0, Math.min(40, onHand)); return { sku: params.sku, nodeId: n.id, onHand, inbound: int(0, 200), reserved, allocated: int(0, 10), safety: n.type === 'store' ? 2 : 10, available: Math.max(0, onHand - reserved - 10), confidence: round(0.85 + rnd() * 0.15, 2) }; }));
on('GET', '/api/v1/inventory/network', ({ query }) => Object.fromEntries((query.get('skus') ?? '').split(',').filter(Boolean).map((s) => [s, int(0, 900)])));

/* ---- Control tower ---- */
on('GET', '/api/v1/control-tower/pipeline', ({ query }): PipelineView => {
  const channel = query.get('channel'); const nodeId = query.get('nodeId'); const orderType = query.get('orderType');
  const open = orders.filter((m) => !['delivered', 'cancelled'].includes(m.order.status) && (!channel || m.order.channel === channel) && (!orderType || m.order.orderType === orderType) && (!nodeId || m.groups.some((g) => g.nodeId === nodeId)));
  const cells = new Map<string, number>();
  for (const m of open) { const b = ageBucket(NOW - Date.parse(m.order.placedAt)); const seen = new Set<string>(); for (const ls of m.lineStatuses) for (const q of ls.quantities) { if (PIPELINE_STATES.includes(q.state) && !seen.has(q.state)) { seen.add(q.state); cells.set(`${q.state}|${b}`, (cells.get(`${q.state}|${b}`) ?? 0) + 1); } } }
  const byChannel: Record<string, number> = {}; const byNode: Record<string, number> = {}; const byOrderType: Record<string, number> = {};
  for (const m of open) { byChannel[m.order.channel] = (byChannel[m.order.channel] ?? 0) + 1; byOrderType[m.order.orderType] = (byOrderType[m.order.orderType] ?? 0) + 1; for (const g of m.groups) byNode[g.nodeId] = (byNode[g.nodeId] ?? 0) + 1; }
  return { states: PIPELINE_STATES, ageBuckets: AGE_BUCKETS, cells: [...cells.entries()].map(([k, count]) => { const [state, ageBucket] = k.split('|'); return { state, ageBucket, count }; }), byChannel, byNode, byOrderType, openOrders: open.length, atRisk: open.filter((m) => m.risk && m.risk.score >= 3).length, held: open.filter((m) => m.holds.some((h) => !h.resolvedAt)).length, generatedAt: new Date().toISOString() };
});
on('GET', '/api/v1/control-tower/at-risk', ({ query }) => atRiskList().slice(0, Number(query.get('limit') ?? 50)));

/* ---- Exceptions ---- */
on('GET', '/api/v1/exceptions', ({ query }) => { let list = exceptions.slice(); const status = query.get('status'); const type = query.get('type'); const sev = query.get('severity'); const queue = query.get('queue'); const q = query.get('q')?.toLowerCase(); if (status) list = list.filter((e) => status.split(',').includes(e.status)); if (type) list = list.filter((e) => e.type === type); if (sev) list = list.filter((e) => e.severity === sev); if (queue) list = list.filter((e) => e.queue === queue); if (q) list = list.filter((e) => e.title.toLowerCase().includes(q) || e.subjectId.toLowerCase().includes(q)); const sevRank: Record<string, number> = { critical: 0, high: 1, medium: 2, low: 3 }; list.sort((a, b) => (sevRank[a.severity] ?? 9) - (sevRank[b.severity] ?? 9) || b.raisedAt.localeCompare(a.raisedAt)); return page(list, query); });
const findExc = (id: string) => exceptions.find((e) => e.id === id) ?? notFound('Exception', id);
on('GET', '/api/v1/exceptions/:id', ({ params }) => findExc(params.id));
on('POST', '/api/v1/exceptions/:id/assign', ({ params, body }) => { const e = findExc(params.id); e.assignee = String(body.assignee); e.status = 'ASSIGNED'; return e; });
on('POST', '/api/v1/exceptions/:id/resolve', ({ params, body }) => { const e = findExc(params.id); e.status = 'RESOLVED'; e.resolution = String(body.resolution); e.resolvedAt = new Date().toISOString(); e.playbookSteps?.forEach((s) => { if (s.status !== 'done') s.status = 'skipped'; }); return e; });
on('GET', '/api/v1/playbooks', () => [
  { name: 'store_reject_resource', exceptionType: 'store_reject', steps: [{ action: 're_source', minConfidence: 0.8 }, { action: 'notify' }], enabled: true },
  { name: 'short_pick', exceptionType: 'inventory_short', steps: [{ action: 're_source', minConfidence: 0.8 }, { action: 'contact_customer', minConfidence: 0.9 }, { action: 'reship', maxSpendUsd: 150 }], enabled: true },
  { name: 'carrier_delay', exceptionType: 'carrier_delay', steps: [{ action: 'contact_customer', minConfidence: 0.9 }, { action: 'reship', maxSpendUsd: 150, minConfidence: 0.95 }], enabled: true },
  { name: 'address_fix', exceptionType: 'address', steps: [{ action: 'hold' }, { action: 'contact_customer' }], enabled: true },
  { name: 'reauthorize', exceptionType: 'payment', steps: [{ action: 'hold' }, { action: 'notify' }], enabled: true },
  { name: 'retry_integration', exceptionType: 'system', steps: [{ action: 'notify' }], enabled: false },
]);

/* ---- Returns ---- */
on('GET', '/api/v1/returns', ({ query }) => { let list = returns.slice(); const status = query.get('status'); const q = query.get('q')?.toLowerCase(); if (status) list = list.filter((r) => status.split(',').includes(r.status)); if (q) list = list.filter((r) => r.rma.includes(q) || r.orderId.toLowerCase().includes(q)); list.sort((a, b) => b.createdAt.localeCompare(a.createdAt)); return page(list.map((r) => ({ rma: r.rma, orderId: r.orderId, status: r.status, createdAt: r.createdAt, channel: r.channel, lineCount: r.lines.length, refundAmount: r.refundAmount, riskScore: r.riskScore ?? undefined, refundTiming: r.refundTiming ?? undefined })), query); });
const findReturn = (rma: string) => returns.find((r) => r.rma === rma) ?? notFound('Return', rma);
on('GET', '/api/v1/returns/:rma', ({ params }) => findReturn(params.rma));
on('POST', '/api/v1/returns', ({ body }) => { const b = body as { orderId: string; channel: string; lines: { lineId: string; qty: number; reason: string }[]; reason: string }; const m = findOrder(b.orderId); const rma = `rma_${nextId('').slice(1)}`; const lines = b.lines.map((l) => { const ol = m.order.lines.find((x) => x.lineId === l.lineId); return { lineId: l.lineId, sku: ol?.sku ?? '', description: ol?.description, qty: l.qty, reason: l.reason, condition: null, disposition: null }; }); const rec: ReturnRecord = { rma, orderId: b.orderId, channel: b.channel, status: 'requested', lines, reason: b.reason, createdAt: new Date().toISOString(), createdBy: actor(), riskScore: null, reasonCodes: [], refundTiming: null, refundAmount: usd(lines.reduce((s, l) => s + (m.order.lines.find((x) => x.lineId === l.lineId)?.unitPrice.amount ?? 0) * l.qty, 0)), timeline: [{ at: new Date().toISOString(), kind: 'step', name: 'return_created', status: 'completed' }] }; returns.unshift(rec); return rec; });
on('POST', '/api/v1/returns/:rma/decide', ({ params }) => { const r = findReturn(params.rma); const rec = addDecisionRecord('return_risk', 'return', r.rma, new Date().toISOString(), { return: { reason: r.reason, lines: r.lines.map((l) => ({ sku: l.sku })) }, customer: { history: { returns_90d: 1 } } }, { forceValue: 1, sharp: 3 }); r.riskScore = Number(rec.value); r.riskLevel = ['very_low', 'low', 'medium', 'high', 'very_high'][Number(rec.value)]; r.riskDecisionRecordId = rec.id; r.refundTimingDecisionRecordId = rec.id; r.refundTiming = String(Object.values(traces[rec.id].answers).find((a) => a.type === 'choice')?.value ?? 'on_inspection'); r.reasonCodes = Object.entries(traces[rec.id].answers).filter(([, a]) => a.type === 'noul').map(([code, a]) => ({ code, probability: Number(a.value) })); r.eligibility = { eligible: r.riskScore < 4, reasons: ['Within 30-day window', 'Receipt present'], windowDays: 30 }; r.status = rec.band === 'ESCALATE' ? 'eligibility_review' : r.riskScore >= 4 ? 'denied' : 'approved'; r.timeline?.push({ at: rec.evaluatedAt, kind: 'activity', name: 'decision:return_risk', status: 'completed', details: { recordId: rec.id, timing: r.refundTiming } }); return r; });
on('POST', '/api/v1/returns/:rma/scan', ({ params }) => { const r = findReturn(params.rma); r.scannedAt = new Date().toISOString(); if (r.status === 'approved') r.status = 'in_transit'; r.timeline?.push({ at: r.scannedAt, kind: 'signal', name: 'carrier_scan', status: 'received' }); if (r.refundTiming === 'on_carrier_scan') { r.status = 'refunded'; r.refund = { amount: r.refundAmount!, status: 'issued', issuedAt: r.scannedAt }; } return r; });
on('POST', '/api/v1/returns/:rma/inspect', ({ params, body }) => { const r = findReturn(params.rma); r.inspectedAt = new Date().toISOString(); r.lines.forEach((l) => { l.condition = String(body.condition ?? 'like_new'); }); if (r.status === 'in_transit' || r.status === 'received') r.status = 'inspected'; r.timeline?.push({ at: r.inspectedAt, kind: 'signal', name: 'inspected', status: 'received', details: { condition: body.condition } }); return r; });
on('POST', '/api/v1/returns/:rma/disposition', ({ params }) => { const r = findReturn(params.rma); const rec = addDecisionRecord('return_disposition', 'return', r.rma, new Date().toISOString(), { return: { lines: r.lines.map((l) => ({ sku: l.sku, condition: l.condition })) } }, { forceValue: r.lines[0]?.condition === 'damaged' ? 'liquidate' : 'restock', sharp: 3 }); r.dispositionDecisionRecordId = rec.id; r.lines.forEach((l) => { l.disposition = String(rec.value); }); return r; });
on('POST', '/api/v1/returns/:rma/refund', ({ params }) => { const r = findReturn(params.rma); if (r.status === 'denied') problem(409, 'invalid_transition', 'Return was denied'); if (r.status === 'refunded') problem(409, 'conflict', 'Already refunded'); r.status = 'refunded'; r.refund = { amount: r.refundAmount!, restockingFee: usd(0), status: 'issued', issuedAt: new Date().toISOString() }; const m = orders.find((o) => o.order.id === r.orderId); m?.refunds.push({ id: nextId('rf'), orderId: r.orderId, amount: r.refundAmount!, status: 'issued', reason: `return ${r.rma}`, returnId: r.rma, issuedAt: r.refund.issuedAt! }); return r; });

/* ---- Agents ---- */
on('GET', '/api/v1/agents', () => agents);
on('GET', '/api/v1/agents/:id', ({ params }) => agents.find((a) => a.id === params.id) ?? notFound('Agent', params.id));
on('POST', '/api/v1/agents', ({ body }) => { const b = body as Partial<typeof agents[number]>; if (!b.id) problem(422, 'validation_failed', 'Agent id is required', { fields: { id: 'required' } }); if (agents.some((a) => a.id === b.id)) problem(409, 'conflict', `Agent ${b.id} already exists`); const a = { id: b.id!, owner: b.owner ?? '', protocol: b.protocol ?? 'mcp', verification: b.verification ?? 'api_key', trustTier: b.trustTier ?? 1, limits: b.limits ?? { allowedActions: ['order_get'] }, active: b.active ?? true, createdAt: new Date().toISOString(), lastSeenAt: null, callbackUrl: b.callbackUrl ?? null, orders30d: 0 }; agents.push(a); return a; });
on('PUT', '/api/v1/agents/:id', ({ params, body }) => { const a = agents.find((x) => x.id === params.id) ?? notFound('Agent', params.id); Object.assign(a, body, { id: a.id }); return a; });
on('POST', '/api/v1/agents/:id/rotate-secret', ({ params }) => ({ agentId: params.id, secret: `whsec_${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`, note: 'Share this secret with the agent owner now; it is not retrievable later.' }));
on('GET', '/api/v1/mandates', ({ query }) => { const a = query.get('agentId'); return a ? mandates.filter((m) => m.agentId === a) : mandates; });
on('GET', '/api/v1/mandates/:id', ({ params }) => mandates.find((m) => m.id === params.id) ?? notFound('Mandate', params.id));

/* ---- Simulation ---- */
const jobs: SimulationJob[] = [{ id: 'sim_01hzq8', status: 'COMPLETED', from: daysAgo(14).slice(0, 10), to: daysAgo(0).slice(0, 10), overrides: { closedNodes: ['ST-CHI-114'], carrierOutage: [], tariffDelta: 0 }, progress: 1, submittedAt: hoursAgo(26), completedAt: hoursAgo(25.4), results: { orders: 1842, costPerOrder: 9.84, baselineCostPerOrder: 9.31, splitRate: 0.142, baselineSplitRate: 0.137, onTimeRate: 0.946, baselineOnTimeRate: 0.951, marginRatio: 0.187, baselineMarginRatio: 0.191, touchlessRate: 0.903, baselineTouchlessRate: 0.912, escalations: 61, baselineEscalations: 54 } }];
on('POST', '/api/v1/simulations/replay/:orderId', ({ params, body }): ReplayResult => { const m = findOrder(params.orderId); const ov = ((body as { decisionVersionOverrides?: Record<string, number> }).decisionVersionOverrides) ?? {}; const recs = decisionRecords.filter((r) => r.subjectId === m.order.id && r.mode === 'LIVE'); const diffs = recs.map((r) => { const def = decisions[r.decision]; const target = ov[r.decision] ?? liveVersion(r.decision)?.version ?? r.version; const changed = target !== r.version && rnd() < 0.45; const q = def && Object.values(def[0].questions).find((x) => x.type === 'choice'); const opts = q ? Object.keys(q.choiceCriteria ?? {}) : null; const afterVal = changed ? (opts ? opts.filter((o) => o !== String(r.value))[0] : typeof r.value === 'number' ? Math.max(0, Number(r.value) - 1) : r.value) : r.value; const confA = changed ? round(Math.min(0.99, Math.max(0.3, r.confidence + (rnd() - 0.5) * 0.3)), 3) : r.confidence; const bands = def?.find((d) => d.version === target)?.bands ?? { actGte: 0.8, confirmGte: 0.5 }; const bandA: Band = r.layer === 'L0_RULE' ? 'ACT' : confA >= bands.actGte ? 'ACT' : confA >= bands.confirmGte ? 'CONFIRM' : 'ESCALATE'; return { recordId: r.id, decision: r.decision, version: target, subjectId: r.subjectId, before: { value: r.value, band: r.band, confidence: r.confidence, layer: r.layer }, after: { value: afterVal, band: bandA, confidence: confA, layer: (bandA === 'ESCALATE' ? 'L3_HUMAN' : r.layer) as Layer }, changed: changed || bandA !== r.band }; }); return { orderId: m.order.id, eventsReplayed: m.ledger.length, decisions: diffs, stateBefore: m.order.status, stateAfter: m.order.status }; });
on('POST', '/api/v1/simulations/batch', ({ body }) => { const b = body as { from: string; to: string; overrides: SimulationJob['overrides'] }; const job: SimulationJob = { id: nextId('sim'), status: 'QUEUED', from: b.from, to: b.to, overrides: b.overrides ?? {}, progress: 0, submittedAt: new Date().toISOString(), completedAt: null, results: null }; jobs.unshift(job); const start = Date.now(); const tick = () => { const p = Math.min(1, (Date.now() - start) / 6000); job.progress = round(p, 2); job.status = p < 1 ? 'RUNNING' : 'COMPLETED'; if (p >= 1) { const closed = (job.overrides.closedNodes ?? []).length; const outage = (job.overrides.carrierOutage ?? []).length; const tariff = job.overrides.tariffDelta ?? 0; job.completedAt = new Date().toISOString(); job.results = { orders: int(900, 2600), costPerOrder: round(9.31 + closed * 0.42 + outage * 0.9 + tariff * 0.12, 2), baselineCostPerOrder: 9.31, splitRate: round(0.137 + closed * 0.011, 3), baselineSplitRate: 0.137, onTimeRate: round(0.951 - closed * 0.006 - outage * 0.03, 3), baselineOnTimeRate: 0.951, marginRatio: round(0.191 - closed * 0.004 - tariff * 0.003, 3), baselineMarginRatio: 0.191, touchlessRate: round(0.912 - closed * 0.01 - outage * 0.025, 3), baselineTouchlessRate: 0.912, escalations: Math.round(54 + closed * 7 + outage * 15), baselineEscalations: 54 }; } else setTimeout(tick, 700); }; setTimeout(tick, 500); return job; });
on('GET', '/api/v1/simulations', () => jobs);
on('GET', '/api/v1/simulations/:id', ({ params }) => jobs.find((j) => j.id === params.id) ?? notFound('Simulation', params.id));

/* ---- Analytics ---- */
const trend = (base: number, spread: number, n = 14) => Array.from({ length: n }, (_, i) => round(base + Math.sin(i / 2.3) * spread + (rnd() - 0.5) * spread, 3));
on('GET', '/api/v1/analytics/kpis', (): KpiDashboard => ({ range: { from: daysAgo(30).slice(0, 10), to: new Date().toISOString().slice(0, 10) }, generatedAt: new Date().toISOString(), kpis: [
  { key: 'touchless_rate', label: 'Touchless order rate', value: 0.912, unit: 'pct', target: 0.9, targetDirection: 'gte', trend: trend(0.9, 0.02), delta: 0.014, definition: 'Orders reaching a terminal state with no human action' },
  { key: 'promise_accuracy', label: 'Promise accuracy', value: 0.951, unit: 'pct', target: 0.95, targetDirection: 'gte', trend: trend(0.945, 0.01), delta: 0.004, definition: 'Orders delivered on or before the promised date' },
  { key: 'split_rate', label: 'Split-shipment rate', value: 0.137, unit: 'pct', target: 0.15, targetDirection: 'lte', trend: trend(0.15, 0.015), delta: -0.021, definition: 'Orders shipped in more than one package' },
  { key: 'cost_to_serve', label: 'Cost to serve', value: 9.31, unit: 'usd', target: 9.6, targetDirection: 'lte', trend: trend(9.8, 0.4), delta: -0.47, definition: 'Shipping, labour and packaging per order' },
  { key: 'margin_floor_compliance', label: 'Margin-floor compliance', value: 1, unit: 'pct', target: 1, targetDirection: 'gte', trend: trend(0.995, 0.004), delta: 0, definition: 'Below-floor orders with a recorded approval' },
  { key: 'store_reject_rate', label: 'Store reject rate', value: 0.048, unit: 'pct', target: 0.05, targetDirection: 'lte', trend: trend(0.06, 0.01), delta: -0.012, definition: 'Store-sourced lines rejected or timed out' },
  { key: 'exception_resolution_median', label: 'Exception resolution (median)', value: 41, unit: 'minutes', target: 45, targetDirection: 'lte', trend: trend(55, 8), delta: -14, definition: 'Median minutes from exception to resolution' },
  { key: 'return_abuse_loss', label: 'Return abuse loss per $1k', value: 11.2, unit: 'usd', target: 12, targetDirection: 'lte', trend: trend(13, 1.5), delta: -1.9, definition: 'Refunds later judged abusive, per $1,000 returned' },
  { key: 'wismo_per_100', label: 'WISMO contacts per 100 orders', value: 3.4, unit: 'count', target: 3.5, targetDirection: 'lte', trend: trend(4.2, 0.5), delta: -0.8, definition: 'Where-is-my-order contacts per 100 orders' },
  { key: 'policy_lead_time', label: 'Policy change lead time (T0–T1)', value: 0.6, unit: 'count', target: 1, targetDirection: 'lte', trend: trend(0.9, 0.3), delta: -0.3, definition: 'Business days from idea to production' },
  { key: 'decision_accuracy', label: 'Decision accuracy (weighted)', value: 0.934, unit: 'pct', target: 0.9, targetDirection: 'gte', trend: trend(0.93, 0.01), delta: 0.004, definition: 'Correct decisions against labelled outcomes' },
  { key: 'calibration_error', label: 'Calibration error (mean)', value: 0.031, unit: 'ratio', target: 0.05, targetDirection: 'lte', trend: trend(0.035, 0.008), delta: -0.004, definition: 'Gap between stated confidence and observed accuracy' },
  { key: 'override_rate', label: 'Override rate', value: 0.027, unit: 'pct', target: 0.05, targetDirection: 'lte', trend: trend(0.03, 0.006), delta: -0.003, definition: 'Autonomous decisions reversed by a person' },
  { key: 'ai_cost_per_order', label: 'AI cost per order', value: 0.0042, unit: 'usd', target: 0.01, targetDirection: 'lte', trend: trend(0.0045, 0.0006), delta: -0.0003, definition: 'Provider spend divided by orders captured (GR-17)' },
  { key: 'open_orders', label: 'Open orders', value: orders.filter((m) => !['delivered', 'cancelled'].includes(m.order.status)).length, unit: 'count', trend: trend(40, 6), definition: 'Orders not in a terminal state' },
  { key: 'exceptions_open', label: 'Exceptions open', value: exceptions.filter((e) => e.status !== 'RESOLVED' && e.status !== 'CLOSED').length, unit: 'count', trend: trend(11, 3), definition: 'Unresolved exceptions' },
  { key: 'touchless_today', label: 'Touchless rate today', value: 0.897, unit: 'pct', target: 0.9, targetDirection: 'gte', trend: trend(0.9, 0.02), definition: 'Touchless rate for orders captured today' },
] }));
on('GET', '/api/v1/analytics/decisions/:name', ({ params }): DecisionAnalytics => { const vs = decisions[params.name] ?? notFound('Decision', params.name); return { name: params.name, tier: vs[0].tier, versions: vs.filter((d) => d.status !== 'draft').map((d) => { const vol = d.status === 'live' ? int(3000, 12000) : d.status === 'canary' ? int(200, 900) : d.status === 'shadow' ? int(1000, 4000) : int(800, 5000); const act = round(0.6 + rnd() * 0.3, 3); const conf = round((1 - act) * 0.6, 3); return { version: d.version, status: d.status, volume: vol, bandMix: { ACT: act, CONFIRM: conf, ESCALATE: round(1 - act - conf, 3) }, layerMix: { L0_RULE: round(0.1 + rnd() * 0.15, 3), L1_SYSTEM_ONE: round(0.6 + rnd() * 0.15, 3), L2_LLM: round(0.04 + rnd() * 0.05, 3), L3_HUMAN: round(0.02 + rnd() * 0.04, 3) }, overrideRate: round(rnd() * 0.04, 3), escalationRate: round(1 - act - conf, 3), latencyP50Ms: int(90, 260), latencyP95Ms: int(380, 760), costUsd: round(vol * 0.00008, 2), costPerDecisionUsd: 0.00008, accuracy: round(0.88 + rnd() * 0.1, 3), calibration: bins(vol, 0.03), dailyVolume: Array.from({ length: 30 }, () => Math.round(vol / 30 * (0.6 + rnd() * 0.8))) }; }) }; });

/* ---- Admin ---- */
on('GET', '/api/v1/admin/config', ({ query }) => { const p = query.get('prefix') ?? ''; return Object.fromEntries(Object.entries(config).filter(([k]) => k.startsWith(p))); });
on('PUT', '/api/v1/admin/config/:key', ({ params, body }) => { if (!body.reason) problem(422, 'validation_failed', 'A change reason is required', { fields: { reason: 'required' } }); config[params.key] = body.value; auditLog.unshift({ id: auditLog.length + 1000, at: new Date().toISOString(), actor: actor(), action: 'config.set', object_type: 'tenant_config', object_id: params.key, reason: String(body.reason) }); return { key: params.key, value: body.value }; });
on('DELETE', '/api/v1/admin/config/:key', ({ params }) => { delete config[params.key]; return undefined; });
on('GET', '/api/v1/admin/webhooks', () => webhooks);
on('POST', '/api/v1/admin/webhooks', ({ body }) => { const w = { id: nextId('wh'), url: String(body.url), event_filter: (body.eventFilter as string[]) ?? ['*'], active: true, created_at: new Date().toISOString() }; webhooks.push(w); return { id: w.id, secret: `whsec_${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}` }; });
on('GET', '/api/v1/admin/audit', ({ query }) => auditLog.slice(0, Number(query.get('limit') ?? 100)));
on('GET', '/api/v1/admin/outbox', () => ({ pending: int(0, 4), failing: [] }));

/* ---- Copilot (oms-copilot) ---- */
registerCopilotRoutes({ on, problem, notFound, actor, validateModel });
registerReplayRoutes({ on, problem }, jobs);
registerPlaygroundRoutes({ on, problem, notFound, actor });

/* ---- Agentic Control Tower (oms-orchestration autonomy) ---- */
registerAutonomyRoutes({ on, problem });

/* ---- Self-Optimizing Order Orchestration (oms-learning) ---- */
registerLearningRoutes({ on, problem });

/* ---- Dispatcher ---- */
export async function mockRequest<T>(method: string, url: string, body?: unknown): Promise<T> {
  const u = new URL(url, 'http://mock.local');
  await new Promise((r) => setTimeout(r, 60 + Math.random() * 160));
  for (const r of routes) {
    if (r.method !== method) continue;
    const m = r.pattern.exec(u.pathname);
    if (!m) continue;
    const params: Record<string, string> = {}; r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
    const result = r.handler({ params, query: u.searchParams, body: (body as Record<string, unknown>) ?? {} });
    return JSON.parse(JSON.stringify(result ?? null)) as T;
  }
  return problem(404, 'not_found', `No mock route for ${method} ${u.pathname}`);
}
