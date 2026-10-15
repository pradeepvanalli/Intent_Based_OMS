/* Mock routes for the Playground and public tracking (VITE_MOCK=1): catalogue, provider availability, order capture that
   produces decisions over a couple of seconds (so the trace animates), tracking links and the customer-safe view. */
import type { EntityStatus, FulfilmentGroup, LedgerEvent, Order, OrderLine, Shipment, TimelineEntry, WorkflowView } from '../types';
import type { CatalogItem, CaptureRequestBody, TrackingLink, TrackingView } from '../playground';
import { addDecisionRecord, config, nextId, nodes, orders, round, usd, type MockOrder } from './data';

type Handler = (ctx: { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> }) => unknown;
interface Helpers { on: (method: string, path: string, handler: Handler) => void; problem: (status: number, code: string, detail: string, extra?: Record<string, unknown>) => never; notFound: (what: string, id: string) => never; actor: () => string; }

/** Mirrors config/tenants/demo/catalog.yaml closely enough for every persona preset to resolve. */
export const MOCK_CATALOG: CatalogItem[] = [
  { sku: 'HOOD-GRY-M', name: 'Fleece hoodie grey M', attributes: { price: 68, cogs: 21, weightKg: 0.7 } },
  { sku: 'CAP-NVY', name: 'Wool cap navy', attributes: { price: 32, cogs: 9, weightKg: 0.15 } },
  { sku: 'SNK-RUN-10', name: 'Trail runner sneaker 10', attributes: { price: 145, cogs: 52, weightKg: 1.1 } },
  { sku: 'YOGA-MAT', name: 'Yoga mat', attributes: { price: 40, cogs: 11, weightKg: 1.0 } },
  { sku: 'TEE-BLK-M', name: 'Organic cotton tee black M', attributes: { price: 28, cogs: 7.5, weightKg: 0.25 } },
  { sku: 'MUG-CER', name: 'Ceramic mug', attributes: { price: 18, cogs: 4, weightKg: 0.4 } },
  { sku: 'PERF-50ML', name: 'Eau de parfum 50 ml', attributes: { price: 120, cogs: 22, hazmat: true, hazmatClass: '3 flammable liquid', weightKg: 0.3 } },
  { sku: 'PWRBNK-20K', name: '20,000 mAh power bank', attributes: { price: 59, cogs: 19, hazmat: true, lithium: true, hazmatClass: '9 lithium battery', weightKg: 0.45 } },
  { sku: 'DUMB-10KG', name: 'Dumbbell 10 kg pair', attributes: { price: 70, cogs: 28, oversized: true, weightKg: 20 } },
  { sku: 'BAG-TOTE', name: 'Canvas tote', attributes: { price: 45, cogs: 12, weightKg: 0.4 } },
  { sku: 'CNDL-SOY', name: 'Soy candle 8oz', attributes: { price: 34, cogs: 8, weightKg: 0.45 } },
  { sku: 'WATCH-GPS', name: 'GPS sports watch', attributes: { price: 349, cogs: 140, lithium: true, weightKg: 0.2 } },
  { sku: 'WINE-RED', name: 'Napa red wine 750 ml', attributes: { price: 42, cogs: 16, ageRestricted: true, category: 'alcohol', weightKg: 1.4 } },
  { sku: 'CHAIR-OFF', name: 'Ergonomic office chair', attributes: { price: 420, cogs: 160, oversized: true, weightKg: 18 } },
  { sku: 'JEAN-IND-32', name: 'Selvedge denim indigo 32', attributes: { price: 128, cogs: 38, weightKg: 0.8 } },
  { sku: 'HEAD-BT', name: 'Bluetooth headphones', attributes: { price: 199, cogs: 70, lithium: true, weightKg: 0.35 } },
];
const SKU = Object.fromEntries(MOCK_CATALOG.map((c) => [c.sku, c]));
const isAir = (m: string) => /air|overnight|express|next_day/i.test(m);
const num = (v: unknown) => (typeof v === 'number' ? v : Number(v ?? 0) || 0);

const tokens = new Map<string, { orderId: string; expiresAt: string }>();
function mintToken(orderId: string): TrackingLink {
  const expiresAt = new Date(Date.now() + 30 * 86_400_000).toISOString();
  const token = `mock-${btoa(`demo|${orderId}|${Date.parse(expiresAt)}`).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')}.sig`;
  tokens.set(token, { orderId, expiresAt });
  return { url: `http://localhost:8080/track/${token}`, token, expiresAt };
}

const LINE_LABEL: Record<string, string> = { shipped: 'Shipped', delivered: 'Delivered', cancelled: 'Cancelled', backordered: 'Backordered', released: 'Being prepared', picking: 'Being prepared', packed: 'Being prepared', ready_for_pickup: 'Ready for pickup', picked_up: 'Picked up' };
const STAGE: Record<string, string> = { cancelled: 'cancelled', delivered: 'delivered', picked_up: 'delivered', shipped: 'shipped', partially_delivered: 'shipped', ready_for_pickup: 'shipped', released: 'confirmed', partially_shipped: 'confirmed', picking: 'confirmed', packed: 'confirmed' };
const LABEL: Record<string, string> = { cancelled: 'Cancelled', delivered: 'Delivered', picked_up: 'Picked up', ready_for_pickup: 'Ready for pickup', shipped: 'On its way', partially_shipped: 'Partly shipped', partially_delivered: 'Partly delivered', released: 'Being packed', picking: 'Being packed', packed: 'Being packed' };

/** Customer-safe projection of a mock order (same rules as the backend TrackingService). */
export function trackingViewOf(m: MockOrder, expiresAt: string): TrackingView {
  const o = m.order; const status = o.status;
  const pickup = o.orderType === 'bopis' || m.groups.some((g) => g.shipMethod === 'pickup');
  const held = m.holds.some((h) => !h.resolvedAt);
  const stage = STAGE[status] ?? 'placed';
  const statusLabel = (pickup && ['released', 'picking', 'packed'].includes(status) ? 'Being prepared for pickup' : LABEL[status]) ?? (held ? 'Being reviewed' : 'Order received');
  const dates = m.groups.map((g) => g.promiseDate).filter((d): d is string => !!d).sort();
  const promise = dates.length ? { earliest: dates[0], latest: dates[dates.length - 1], shipMethod: m.groups[0]?.shipMethod } : null;
  const items = o.lines.map((l) => { const st = m.lineStatuses.find((s) => s.entityId.endsWith(`:${l.lineId}`))?.state; return { name: l.description || l.sku, qty: l.qty, status: LINE_LABEL[st ?? ''] ?? 'Processing' }; });
  const shipments = m.shipments.map((s) => ({ carrier: s.carrier, service: s.service, tracking: s.tracking, trackingUrl: s.carrier === 'UPS' ? `https://www.ups.com/track?tracknum=${s.tracking}` : s.carrier === 'FedEx' ? `https://www.fedex.com/fedextrack/?trknbr=${s.tracking}` : null, status: s.status === 'delivered' ? 'Delivered' : 'In transit', shippedAt: s.shippedAt ?? null, deliveredAt: s.deliveredAt ?? null, eta: s.eta ?? null, items: s.lines.map((sl) => o.lines.find((l) => l.lineId === sl.lineId)?.description ?? sl.lineId) }));
  const store = pickup ? nodes.find((n) => n.id === m.groups[0]?.nodeId) : undefined;
  const pickupLocation = pickup ? { storeName: store?.name ?? 'Our store', city: o.addresses?.[0]?.city, state: store?.state ?? o.addresses?.[0]?.state } : null;
  const by = promise ? ` by ${new Date(promise.latest + 'T00:00:00').toLocaleDateString('en-US', { month: 'long', day: 'numeric' })}` : '';
  const narrative = stage === 'cancelled' ? `Order ${o.channelOrderId} has been cancelled. If you did not ask for this, please contact us and we will sort it out.`
    : stage === 'delivered' ? `Order ${o.channelOrderId} was ${pickup ? 'picked up' : 'delivered'}. We hope you enjoy it.`
    : stage === 'shipped' ? (pickup ? `Order ${o.channelOrderId} is ready for pickup at ${pickupLocation?.storeName}.` : `Order ${o.channelOrderId} is on its way${shipments[0]?.carrier ? ` with ${shipments[0].carrier}` : ''}${shipments[0]?.tracking ? ` (tracking ${shipments[0].tracking})` : ''}.${shipments.length > 1 ? ` It is travelling in ${shipments.length} packages.` : ''}`)
    : stage === 'confirmed' ? `Order ${o.channelOrderId} is confirmed and being ${pickup ? 'prepared' : 'packed'}. We expect it to ${pickup ? 'be ready' : 'arrive'}${by}.`
    : held ? `We received order ${o.channelOrderId}. A quick check is in progress before it ships; nothing is needed from you right now.` : `We received order ${o.channelOrderId} and are getting it ready.${by ? ` We expect it to arrive${by}.` : ''}`;
  const nextStep = stage === 'cancelled' ? 'Any payment taken will be refunded to the original payment method within a few days.' : stage === 'delivered' ? 'Something not right? Start a return or contact us from your account.' : stage === 'shipped' ? (pickup ? 'Bring your order number and a photo ID.' : 'Follow the carrier link for live updates.') : stage === 'confirmed' ? (pickup ? 'We will email you the moment your order is ready for pickup.' : 'We will email you the tracking number as soon as it ships.') : 'No action is needed. We will update this page when your order is confirmed.';
  return { orderNumber: o.channelOrderId, status, statusLabel, stage, pickup, placedAt: o.placedAt, promise, items, shipments, pickupLocation, narrative, narrativeGenerated: false, nextStep, expiresAt, generatedAt: new Date().toISOString() };
}

/** Creates a MockOrder from a capture body and schedules the decisions the real workflow would make. */
function capture(body: CaptureRequestBody, h: Helpers): Order {
  if (!body.lines?.length) h.problem(422, 'validation_failed', 'At least one line is required');
  const lines: OrderLine[] = body.lines.map((l, i) => { const c = SKU[l.sku]; const price = num(l.unitPrice?.amount) || (c ? num(c.attributes.price) : 0); return { lineId: l.lineId ?? String(i + 1), sku: l.sku, description: c?.name ?? l.sku, qty: l.qty, unitPrice: usd(price), discount: usd(0), tax: usd(price * l.qty * 0.0825), fulfilmentGroupId: null, attributes: { ...(c?.attributes ?? {}), hazmat: !!c?.attributes.hazmat, ...(l.attributes ?? {}) } }; });
  const issues: { severity: string; code: string; field: string; message: string; step: string }[] = [];
  for (const l of lines) {
    if (l.attributes?.hazmat && isAir(body.shipMethod ?? '')) issues.push({ severity: 'block', code: 'HAZMAT_AIR', field: `lines[${l.lineId}].sku`, message: `${l.sku} is hazardous material and cannot ship via ${body.shipMethod}; choose a ground service`, step: 'compliance' });
    if (l.attributes?.ageRestricted && !body.customer?.ageVerified) issues.push({ severity: 'warn', code: 'AGE_VERIFICATION_REQUIRED', field: `lines[${l.lineId}].sku`, message: `${l.sku} is age-restricted; the customer must complete age verification before release`, step: 'compliance' });
  }
  if (issues.some((i) => i.severity === 'block')) h.problem(422, 'validation_failed', `Order ${body.channelOrderId} failed validation`, { issues, blocking: issues.filter((i) => i.severity === 'block').length });

  const id = `ord_${nextId('').slice(1)}`; const now = new Date(); const iso = now.toISOString();
  const subtotal = lines.reduce((s, l) => s + l.unitPrice.amount * l.qty, 0); const tax = round(subtotal * 0.0825); const shipping = body.shipMethod === 'pickup' ? 0 : subtotal > 150 ? 0 : 7.95;
  const dest = body.addresses?.find((a) => a.type === 'ship_to') ?? body.addresses?.[0];
  const pickup = body.orderType === 'bopis' || body.shipMethod === 'pickup';
  const weight = lines.reduce((s, l) => s + num(l.attributes?.weightKg) * l.qty, 0);
  const far = dest?.state === 'HI' || dest?.state === 'AK';
  const cogs = lines.reduce((s, l) => s + num(l.attributes?.cogs) * l.qty, 0);
  const shipCost = pickup ? 2.5 : round(6 + weight * (far ? 4.2 : 0.9) + lines.length * 1.5);
  const cm = round(subtotal - cogs - shipCost - lines.length * 1.9 - subtotal * 0.029);
  const marginRatio = round(cm / Math.max(1, subtotal), 3); const floor = Number(config['sourcing.margin_floor'] ?? 0.12) || 0.12;
  const order: Order = {
    id, channel: body.channel ?? 'web', channelOrderId: body.channelOrderId, orderType: body.orderType ?? 'standard_ship', customerRef: body.customerRef, customer: { ...body.customer },
    lines, addresses: (body.addresses ?? []).map((a) => ({ ...a, verified: true })), charges: shipping ? [{ type: 'shipping', amount: usd(shipping), description: 'Standard shipping' }] : [],
    tenders: (body.tenders ?? []).map((t) => ({ type: t.type, amount: t.amount, token: 'tok_••••4242', status: 'authorized' })), notes: (body.notes ?? []).map((n) => ({ at: iso, by: n.author ?? 'customer', text: n.text, source: n.source })),
    intent: null, provenance: body.provenance ? { agentId: body.provenance.agentId, protocol: body.provenance.protocol, mandateId: body.provenance.mandateId, spendLimit: body.provenance.spendLimit, instructionText: body.provenance.instructionText } : null,
    totals: { merchandise: usd(subtotal), discounts: usd(0), shipping: usd(shipping), tax: usd(tax), grandTotal: usd(subtotal + shipping + tax) },
    margin: { revenue: round(subtotal), contributionMargin: cm, marginRatio, floor, belowFloor: marginRatio < floor, approvalPresent: false, computedAt: iso },
    stateModelId: pickup ? 'order.bopis' : 'order.standard_ship', stateModelVersion: 1, status: 'created', extensions: body.extensions ?? {}, placedAt: iso, capturedAt: iso, version: 1,
  };
  const header: EntityStatus = { entityType: 'order', entityId: id, modelId: order.stateModelId, modelVersion: 1, state: 'created', quantities: [{ state: 'created', qty: 1, since: iso }] };
  const lineStatuses: EntityStatus[] = lines.map((l) => ({ entityType: 'order_line', entityId: `${id}:${l.lineId}`, modelId: pickup ? 'order_line.bopis' : 'order_line.standard_ship', modelVersion: 3, state: 'created', quantities: [{ state: 'created', qty: l.qty, since: iso }], attributes: { sku: l.sku } }));
  const workflow: WorkflowView = { id: `wf_${id.slice(4)}`, type: pickup ? 'order.bopis' : 'order.standard_ship', version: 1, businessKey: id, status: 'RUNNING', currentStep: 'validate', startedAt: iso, updatedAt: iso };
  const ledger: LedgerEvent[] = [{ id: nextId('evt'), type: 'order.captured', aggregateType: 'order', aggregateId: id, at: iso, actor: `channel:${order.channel}`, data: { channel: order.channel, channelOrderId: order.channelOrderId, lines: lines.length }, version: 1, correlationId: id }];
  const timeline: TimelineEntry[] = [{ at: iso, kind: 'step', name: 'validate', status: 'completed', details: { steps: ['schema', 'address', 'item_price_sanity', 'payment_status', 'compliance', 'agent_provenance'], issues: issues.length } }];
  const m: MockOrder = { order, header, lineStatuses, groups: [], holds: [], shipments: [] as Shipment[], payments: [], refunds: [], workflow, timeline, ledger, comms: [], risk: null };
  orders.unshift(m);
  for (const w of issues.filter((i) => i.severity === 'warn')) if (w.code === 'AGE_VERIFICATION_REQUIRED') m.holds.push({ id: nextId('hold'), type: 'compliance', scopeType: 'order', scopeId: id, blocksTransitions: ['decision.sourcing_plan', 'release', 'event.shipped'], reasonCode: w.code, note: w.message, placedBy: 'system:compliance', placedAt: iso });
  scheduleDecisions(m, body, { pickup, far, dest });
  return order;
}

function scheduleDecisions(m: MockOrder, body: CaptureRequestBody, ctx: { pickup: boolean; far: boolean; dest?: CaptureRequestBody['addresses'][number] }) {
  const o = m.order; const id = o.id;
  const note = (body.notes ?? []).map((n) => n.text).join(' ').toLowerCase();
  const explicit = body.intentChoices?.[0];
  const intent = explicit ?? (ctx.pickup ? 'pickup_consolidated' : /together|one box|gift/.test(note) ? 'ship_complete' : /before|urgent|asap|rest can/.test(note) ? 'earliest_partial' : note.trim().length < 6 ? 'unclear' : 'ship_as_available');
  const sharp = explicit ? 6 : intent === 'unclear' ? 0.6 : /gift|before|together/.test(note) ? 4 : 2;
  const at = (ms: number) => new Date(Date.now() + ms).toISOString();
  const step = (name: string, status = 'completed', details: Record<string, unknown> = {}) => m.timeline.push({ at: new Date().toISOString(), kind: 'step', name, status, details });
  const ev = (type: string, data: Record<string, unknown> = {}) => m.ledger.push({ id: nextId('evt'), type, aggregateType: 'order', aggregateId: id, at: new Date().toISOString(), actor: 'system:workflow', data, version: m.ledger.length + 1, correlationId: id });
  const t0 = Date.now();
  const later = (ms: number, fn: () => void) => { window.setTimeout(fn, ms); };

  if (o.provenance) later(250, () => { const r = addDecisionRecord('injection_screen', 'order', id, at(0), { text: { source: 'agent_instruction', content: o.provenance!.instructionText } }, { sharp: 5 }); r.value = false; r.band = 'ACT'; r.action = 'act'; r.confidence = 0.96; r.layer = 'L1_SYSTEM_ONE'; step('screen_agent_instruction', 'completed', { recordId: r.id }); });
  later(650, () => {
    o.status = 'sourcing'; m.header.state = 'sourcing'; m.workflow.currentStep = 'infer_intent';
    const rec = addDecisionRecord('fulfilment_intent', 'order', id, at(0), { order: { channel: o.channel, notes: (body.notes ?? []).map((n) => n.text), intent_choices: body.intentChoices ?? [], has_hazmat: o.lines.some((l) => l.attributes?.hazmat) }, customer: { tier: o.customer?.tier, history: o.customer?.history } }, { forceValue: intent, sharp, rulesFired: explicit ? ['explicit_choice'] : [] });
    if (intent === 'unclear' && rec.band !== 'ACT') { rec.layer = 'DEFAULT'; rec.band = 'CONFIRM'; rec.action = 'confirm'; rec.provider = 'default'; rec.value = 'ship_as_available'; }
    o.intent = { policy: String(rec.value), confidence: rec.confidence, source: explicit ? 'explicit' : rec.layer === 'DEFAULT' ? 'channel_default' : 'inferred', explicitChoices: body.intentChoices ?? [], decisionRecordId: rec.id };
    step('infer_intent', 'completed', { recordId: rec.id, value: rec.value }); ev('order.intent_resolved', { policy: rec.value, recordId: rec.id });
  });
  later(1450, () => {
    const belowFloor = !!o.margin?.belowFloor;
    const dcs = nodes.filter((n) => n.type === 'dc');
    const dest = ctx.dest; const dlat = Number((dest as { lat?: number } | undefined)?.lat) || cityLat(dest?.state); const dlon = cityLon(dest?.state);
    const nearest = (ctx.pickup ? nodes.filter((n) => n.type === 'store') : dcs).slice().sort((a, b) => Math.hypot(a.lat - dlat, a.lon - dlon) - Math.hypot(b.lat - dlat, b.lon - dlon));
    const pickupNode = ctx.pickup ? nodes.find((n) => n.id === body.addresses?.find((a) => a.type === 'pickup')?.line1) ?? nearest[0] : undefined;
    const primary = pickupNode ?? nearest[0];
    const split = !ctx.pickup && o.intent?.policy === 'earliest_partial' && o.lines.length > 1;
    const rec = addDecisionRecord('sourcing_node_score', 'order', id, at(0), { order: { destination_state: dest?.state, ship_method: o.lines.length, line_count: o.lines.length }, candidates: nearest.slice(0, 4).map((n, i) => ({ id: n.id, type: n.type, backlog_units: n.health?.backlog, reject_rate: n.health?.reject_rate, distance_km: 120 + i * 640 })) }, { forceValue: 3, sharp: 3.5 });
    step('source', 'completed', { recordId: rec.id, node: primary.id });
    if (belowFloor) {
      m.holds.push({ id: nextId('hold'), type: 'margin_floor', scopeType: 'order', scopeId: id, blocksTransitions: ['release'], reasonCode: 'BELOW_MARGIN_FLOOR', note: `Contribution margin ${(o.margin!.marginRatio * 100).toFixed(1)}% is below the floor ${(o.margin!.floor * 100).toFixed(0)}%; awaiting finance approval`, placedBy: 'system:workflow', placedAt: new Date().toISOString() });
      m.workflow.status = 'WAITING_TASK'; m.workflow.currentStep = 'margin_floor_approval'; step('margin_floor_approval', 'open', { marginRatio: o.margin!.marginRatio, floor: o.margin!.floor }); ev('order.sourcing_blocked_margin_floor', { marginRatio: o.margin!.marginRatio });
      m.groups = [{ id: 'FG1', nodeId: primary.id, lineIds: o.lines.map((l) => l.lineId), shipMethod: body.shipMethod, promiseDate: new Date(t0 + 5 * 86_400_000).toISOString().slice(0, 10), onTimeProbability: 0.81, ...( { allocations: o.lines.map((l) => ({ lineId: l.lineId, sku: l.sku, qty: l.qty })), estimatedCost: usd(Math.max(0, round((o.margin!.revenue ?? 0) - o.margin!.contributionMargin - 20))), status: 'planned', policy: o.intent?.policy } as object) } as FulfilmentGroup];
      return;
    }
    if (m.holds.some((h) => !h.resolvedAt)) { m.workflow.status = 'WAITING_SIGNAL'; m.workflow.currentStep = 'await_hold_release'; return; }
    const g1: FulfilmentGroup = { id: 'FG1', nodeId: primary.id, lineIds: split ? [o.lines[0].lineId] : o.lines.map((l) => l.lineId), shipMethod: body.shipMethod, promiseDate: new Date(t0 + (ctx.pickup ? 1 : primary.type === 'dc' && nearest[0].id === primary.id ? 2 : 4) * 86_400_000).toISOString().slice(0, 10), onTimeProbability: round(0.86 + Math.random() * 0.1, 2) };
    Object.assign(g1, { allocations: o.lines.filter((l) => g1.lineIds.includes(l.lineId)).map((l) => ({ lineId: l.lineId, sku: l.sku, qty: l.qty })), estimatedCost: usd(ctx.pickup ? 2.5 : 6.4 + g1.lineIds.length * 1.8), status: 'released', policy: o.intent?.policy });
    m.groups = [g1];
    if (split) { const n2 = dcs.find((n) => n.id !== primary.id) ?? primary; const g2: FulfilmentGroup = { id: 'FG2', nodeId: n2.id, lineIds: o.lines.slice(1).map((l) => l.lineId), shipMethod: body.shipMethod, promiseDate: new Date(t0 + 4 * 86_400_000).toISOString().slice(0, 10), onTimeProbability: 0.9 }; Object.assign(g2, { allocations: o.lines.slice(1).map((l) => ({ lineId: l.lineId, sku: l.sku, qty: l.qty })), estimatedCost: usd(5.9 + g2.lineIds.length * 1.8), status: 'released', policy: o.intent?.policy }); m.groups.push(g2); }
    o.lines.forEach((l) => { l.fulfilmentGroupId = m.groups.find((g) => g.lineIds.includes(l.lineId))?.id ?? null; });
    o.status = 'released'; m.header.state = 'released'; m.lineStatuses.forEach((ls) => { ls.state = 'released'; ls.quantities = [{ state: 'released', qty: ls.quantities[0].qty, since: new Date().toISOString() }]; });
    m.workflow.status = 'WAITING_SIGNAL'; m.workflow.currentStep = 'await_fulfilment';
    ev('order.sourced', { plan: m.groups.map((g) => ({ node: g.nodeId, lines: g.lineIds })), policy: o.intent?.policy }); step('release', 'completed', { groups: m.groups.length });
  });
  later(2300, () => {
    const flagged = Array.isArray(o.customer?.history?.flags) && (o.customer!.history!.flags as string[]).length > 0;
    const rec = addDecisionRecord('sla_breach_risk', 'order', id, at(0), { order: { state: o.status, hours_in_state: 0, promise_hours_remaining: ctx.far ? 60 : 96, ship_method: body.shipMethod }, node: { backlog_units: 900, reject_rate: 0.01 }, carrier: { on_time_rate_7d: 0.95 } }, { forceValue: flagged || ctx.far ? 3 : 1, sharp: 3 });
    m.risk = { score: Number(rec.value), level: Number(rec.value) >= 3 ? 'high' : 'low', reasons: flagged ? ['customer history flagged', 'prior failed deliveries at this address'] : ctx.far ? ['long transit to destination'] : [], decisionRecordId: rec.id };
  });
}
const cityLat = (st?: string) => ({ WA: 47.6, CA: 36.5, CO: 39.7, TX: 30.3, IL: 41.9, GA: 33.7, FL: 25.8, NY: 40.7, HI: 21.3 } as Record<string, number>)[st ?? ''] ?? 39;
const cityLon = (st?: string) => ({ WA: -122.3, CA: -120, CO: -105, TX: -97.7, IL: -87.6, GA: -84.4, FL: -80.2, NY: -74, HI: -157.9 } as Record<string, number>)[st ?? ''] ?? -98;

export function registerPlaygroundRoutes(h: Helpers) {
  h.on('GET', '/api/v1/catalog', () => MOCK_CATALOG);
  h.on('GET', '/api/v1/catalog/:sku', ({ params }) => SKU[params.sku] ?? h.notFound('Catalog item', params.sku));
  h.on('GET', '/api/v1/decisions/providers', () => { const off = !!config['ai.kill_switch']; return { heuristic: true, jev: !off && !config['provider.kill_switch.jev'], 'llm.anthropic': !off && !config['provider.kill_switch.llm.anthropic'], 'llm.openai': false }; });
  h.on('POST', '/api/v1/orders', ({ body }) => capture(body as unknown as CaptureRequestBody, h));
  h.on('POST', '/api/v1/orders/:id/tracking-link', ({ params }) => { const m = orders.find((x) => x.order.id === params.id || x.order.channelOrderId === params.id) ?? h.notFound('Order', params.id); return mintToken(m.order.id); });
  h.on('GET', '/public/track/:token', ({ params }) => {
    const t = tokens.get(params.token);
    if (!t || Date.parse(t.expiresAt) < Date.now()) h.problem(404, 'not_found', 'This tracking link is invalid or has expired', { code: 'tracking_link_invalid' });
    const m = orders.find((x) => x.order.id === t!.orderId) ?? h.notFound('Order', t!.orderId);
    return trackingViewOf(m, t!.expiresAt);
  });
}
