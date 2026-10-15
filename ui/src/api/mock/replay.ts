/* Mock routes for Margin Replay (VITE_MOCK=1): POST /simulations/import and /simulations/import/sample create an `import`
   job that progresses over a few seconds and completes with a deterministic, plausible report in the real response shape. */
import type { SimulationJob } from '../types';
import type { ReplayJob, ReplayOptions, ReplayRegion, ReplayResults, ReplayRow, ReplaySummary } from '../replay';

type Handler = (ctx: { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> }) => unknown;
interface Helpers { on: (method: string, path: string, handler: Handler) => void; problem: (status: number, code: string, detail: string, extra?: Record<string, unknown>) => never; }

interface Profile { state: string; city: string; region: string; nearest: string; intent: string; changeRate: number; save: number; weight: number; }
const PROFILES: Profile[] = [
  { state: 'NY', city: 'New York', region: 'Northeast', nearest: 'DC-NJ', intent: 'DC-NJ', changeRate: 0.02, save: 0.4, weight: 20 },
  { state: 'CA', city: 'Los Angeles', region: 'California', nearest: 'ST-LA-02', intent: 'DC-NV', changeRate: 0.85, save: 2.4, weight: 17 },
  { state: 'CA', city: 'San Francisco', region: 'California', nearest: 'DC-NV', intent: 'DC-NV', changeRate: 0.05, save: 0.3, weight: 10 },
  { state: 'WA', city: 'Seattle', region: 'Pacific Northwest', nearest: 'DC-NV', intent: 'DC-NV', changeRate: 0.05, save: 0.3, weight: 7 },
  { state: 'MA', city: 'Boston', region: 'Northeast', nearest: 'DC-NJ', intent: 'DC-NJ', changeRate: 0.02, save: 0.2, weight: 6 },
  { state: 'TX', city: 'Austin', region: 'Texas & South Central', nearest: 'ST-AUS-03', intent: 'DC-TX', changeRate: 0.9, save: 3.1, weight: 6 },
  { state: 'TX', city: 'Houston', region: 'Texas & South Central', nearest: 'ST-AUS-03', intent: 'DC-TX', changeRate: 0.8, save: 2.2, weight: 4 },
  { state: 'IL', city: 'Chicago', region: 'Midwest', nearest: 'ST-CHI-01', intent: 'DC-NJ', changeRate: 0.55, save: 1.6, weight: 6 },
  { state: 'FL', city: 'Miami', region: 'Southeast', nearest: '3PL-ATL', intent: '3PL-ATL', changeRate: 0.04, save: 0.2, weight: 6 },
  { state: 'GA', city: 'Atlanta', region: 'Southeast', nearest: '3PL-ATL', intent: '3PL-ATL', changeRate: 0.03, save: 0.2, weight: 4 },
  { state: 'CO', city: 'Denver', region: 'Mountain West', nearest: 'DC-NV', intent: 'DC-TX', changeRate: 0.3, save: 0.9, weight: 3 },
  { state: 'PA', city: 'Philadelphia', region: 'Northeast', nearest: 'DC-NJ', intent: 'DC-NJ', changeRate: 0.02, save: 0.2, weight: 4 },
  { state: 'OR', city: 'Portland', region: 'Pacific Northwest', nearest: 'DC-NV', intent: 'DC-NV', changeRate: 0.05, save: 0.3, weight: 4 },
  { state: 'AZ', city: 'Phoenix', region: 'Mountain West', nearest: 'DC-NV', intent: 'DC-NV', changeRate: 0.1, save: 0.4, weight: 3 },
];
const INTENTS = ['together', 'urgent', 'gift', 'separate', 'pickup'];

let seq = 1;
function rng(seed: number) { let s = seed >>> 0; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
const r2 = (v: number) => Math.round(v * 100) / 100;
const usd = (v: number) => `${v < 0 ? '−' : ''}$${Math.abs(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/** Counts orders in an uploaded CSV text (Shopify `Name` column or generic `order_id`); null when the layout is unknown. */
export function countOrders(text: string | null | undefined): { orders: number; format: string } | null {
  if (!text) return null;
  const lines = text.split(/\r?\n/).filter((l) => l.trim());
  if (lines.length < 2) return { orders: 0, format: 'unknown' };
  const headers = lines[0].split(',').map((h) => h.trim().replace(/^"|"$/g, '').toLowerCase());
  const idx = headers.indexOf('name') >= 0 && headers.some((h) => h.startsWith('lineitem')) ? headers.indexOf('name') : headers.indexOf('order_id');
  if (idx < 0) return null;
  const ids = new Set<string>();
  for (const l of lines.slice(1)) { const cells = l.split(','); const v = (cells[idx] ?? '').trim(); if (v) ids.add(v); }
  return { orders: ids.size, format: headers.includes('order_id') ? 'generic' : 'shopify' };
}

export function fakeResults(n: number, options: ReplayOptions, fileName: string, format: string): ReplayResults {
  const rnd = rng(n * 7919 + fileName.length);
  const total = PROFILES.reduce((a, p) => a + p.weight, 0);
  const pick = () => { let x = rnd() * total; for (const p of PROFILES) { x -= p.weight; if (x <= 0) return p; } return PROFILES[0]; };
  const single = options.baseline.startsWith('single_dc:') ? options.baseline.slice('single_dc:'.length) : null;
  const rows: ReplayRow[] = []; const regions = new Map<string, ReplayRegion>(); const intents: Record<string, number> = {};
  const s: ReplaySummary = { orders: n, parsed: n, planned: 0, skipped: 0, skippedReasons: {}, ordersChanged: 0, nodesShifted: 0, storeToDc: 0, revenue: 0, units: 0, baselineShippingCost: 0, intentShippingCost: 0, shippingSaved: 0, baselineFulfilmentCost: 0, intentFulfilmentCost: 0, fulfilmentSaved: 0, baselineMarginUsd: 0, intentMarginUsd: 0, marginRecoveredUsd: 0, marginRecoveredPerOrder: 0, baselineMarginRatio: 0, intentMarginRatio: 0, baselineSplits: 0, intentSplits: 0, splitsAvoided: 0, splitsAdded: 0, ordersBelowFloorFlagged: 0, baselineOrdersBelowFloor: 0, belowFloorMarginUsd: 0, intentsDetected: intents, intentsByBand: {}, ordersWithIntent: 0, ordersWithNotes: 0, avgOnTimeBefore: 0, avgOnTimeAfter: 0, unknownSkuLines: 0, ordersWithUnknownSku: 0, poBoxOrders: 0, hazmatOrders: 0, pickupOrders: 0, dateFrom: '2025-09-01T08:00:00Z', dateTo: '2025-09-30T20:00:00Z', annualisationDays: 30, estimatedAnnualisedSavings: 0, estimatedAnnualisedOrders: 0, estimateLabel: 'Estimate: scales the replayed window (30 days) to 365 days.' };
  let onB = 0, onA = 0, netRev = 0;
  for (let i = 0; i < n; i++) {
    const id = format === 'generic' ? `G-${2001 + i}` : `#${1001 + i}`;
    if (i === 41 || i === 150) { s.skipped++; s.skippedReasons[i === 41 ? 'non-US destination (CA)' : 'non-US destination (GB)'] = 1; rows.push({ orderId: id, skipped: true, error: i === 41 ? 'non-US destination (CA)' : 'non-US destination (GB)' }); continue; }
    const p = pick(); const units = 1 + (rnd() < 0.3 ? 1 : 0) + (rnd() < 0.1 ? 1 : 0); const revenue = r2(30 + rnd() * 160 * units);
    const baselineNode = single ?? p.nearest; const intentNode = p.intent;
    const changed = baselineNode !== intentNode && rnd() < (single ? 0.95 : p.changeRate);
    const nodesAfter = changed ? intentNode : baselineNode;
    const shipB = r2(6.2 + rnd() * 4 + (single && single !== p.nearest ? 2.4 : 0)); const shipA = changed ? r2(Math.max(5.9, shipB - 0.6 - rnd() * 2.2)) : shipB;
    const costB = r2(shipB + units * (baselineNode.startsWith('ST-') ? 3.9 : 2.1) + 0.65); const costA = changed ? r2(shipA + units * 2.1 + 0.65) : costB;
    const marginB = r2(revenue * 0.31 - costB - revenue * 0.079); const marginA = r2(marginB + (costB - costA));
    const hasNote = rnd() < 0.1; const intent = hasNote ? INTENTS[Math.floor(rnd() * INTENTS.length)] : 'none';
    const packagesB = 1; const packagesA = intent === 'urgent' && units > 1 ? 2 : 1;
    const onTimeB = r2(0.86 + rnd() * 0.09 - (baselineNode.startsWith('ST-') ? 0.05 : 0)); const onTimeA = changed ? r2(Math.min(0.97, onTimeB + 0.03 + rnd() * 0.03)) : onTimeB;
    const belowFloor = marginA / revenue < 0.12;
    const row: ReplayRow = { orderId: id, placedAt: new Date(Date.UTC(2025, 8, 1 + Math.floor(rnd() * 30), 8 + Math.floor(rnd() * 12))).toISOString(), city: p.city, state: p.state, region: p.region, geoPrecision: 'city', lines: units, units, revenue, shipMethod: 'ground',
      costBefore: costB, costAfter: costA, shippingBefore: shipB, shippingAfter: shipA, packagesBefore: packagesB, packagesAfter: packagesA, nodesBefore: [baselineNode], nodesAfter: [nodesAfter], onTimeBefore: onTimeB, onTimeAfter: onTimeA,
      marginBefore: r2(marginB / revenue * 100) / 100, marginAfter: r2(marginA / revenue * 100) / 100, marginBeforeUsd: marginB, marginAfterUsd: marginA, marginDeltaUsd: r2(marginA - marginB), flippedDecisions: [], blockedTransitions: 0,
      policy: intent === 'together' ? 'ship_complete' : intent === 'urgent' ? 'earliest_partial' : intent === 'pickup' ? 'pickup_consolidated' : 'ship_as_available', intent, intentSource: hasNote ? (options.useDecisions ? 'decision' : 'keywords') : 'none', intentBand: hasNote && options.useDecisions ? 'CONFIRM' : null,
      isGift: intent === 'gift', urgent: intent === 'urgent', belowFloor, baselineBelowFloor: marginB / revenue < 0.12, poBox: rnd() < 0.015, hazmat: rnd() < 0.05, unknownSkus: rnd() < 0.15 ? 1 : 0, changed: changed || packagesA !== packagesB,
      reason: changed ? `covers every line; node preference rank 1 (unit cost ${usd(costA / units)} vs ${usd(costB / units)} at ${baselineNode})` : 'no intent; channel web default ship_as_available',
      baselineReason: single ? `everything from ${single} (single-DC baseline)` : `nearest node with stock: ${baselineNode}`, note: hasNote ? `(${intent} note)` : undefined };
    rows.push(row);
    s.planned++; s.units += units; s.revenue += revenue; netRev += revenue; s.baselineShippingCost += shipB; s.intentShippingCost += shipA; s.baselineFulfilmentCost += costB; s.intentFulfilmentCost += costA; s.baselineMarginUsd += marginB; s.intentMarginUsd += marginA;
    onB += onTimeB; onA += onTimeA; if (row.changed) s.ordersChanged++; if (changed) { s.nodesShifted++; if (baselineNode.startsWith('ST-') && !intentNode.startsWith('ST-')) s.storeToDc++; }
    if (packagesA > packagesB) { s.splitsAdded++; s.intentSplits++; } if (belowFloor) { s.ordersBelowFloorFlagged++; s.belowFloorMarginUsd += marginB; } if (row.baselineBelowFloor) s.baselineOrdersBelowFloor++;
    if (hasNote) { s.ordersWithNotes++; if (intent !== 'none') { s.ordersWithIntent++; intents[intent] = (intents[intent] ?? 0) + 1; } s.intentsByBand[options.useDecisions ? 'CONFIRM' : 'KEYWORDS'] = (s.intentsByBand[options.useDecisions ? 'CONFIRM' : 'KEYWORDS'] ?? 0) + 1; }
    if (row.unknownSkus) { s.unknownSkuLines++; s.ordersWithUnknownSku++; } if (row.poBox) s.poBoxOrders++; if (row.hazmat) s.hazmatOrders++;
    const reg = regions.get(p.state) ?? { state: p.state, region: p.region, orders: 0, changed: 0, baselineCost: 0, intentCost: 0, baselineShipping: 0, intentShipping: 0, shippingSaved: 0, saved: 0, baselineSplits: 0, intentSplits: 0 };
    reg.orders++; if (row.changed) reg.changed++; reg.baselineCost = r2(reg.baselineCost + costB); reg.intentCost = r2(reg.intentCost + costA); reg.baselineShipping = r2(reg.baselineShipping + shipB); reg.intentShipping = r2(reg.intentShipping + shipA); reg.shippingSaved = r2(reg.baselineShipping - reg.intentShipping); reg.saved = r2(reg.saved + marginA - marginB); if (packagesA > 1) reg.intentSplits++;
    regions.set(p.state, reg);
  }
  for (const k of ['baselineShippingCost', 'intentShippingCost', 'baselineFulfilmentCost', 'intentFulfilmentCost', 'baselineMarginUsd', 'intentMarginUsd', 'revenue', 'belowFloorMarginUsd'] as const) s[k] = r2(s[k]);
  s.shippingSaved = r2(s.baselineShippingCost - s.intentShippingCost); s.fulfilmentSaved = r2(s.baselineFulfilmentCost - s.intentFulfilmentCost); s.marginRecoveredUsd = r2(s.intentMarginUsd - s.baselineMarginUsd);
  s.marginRecoveredPerOrder = s.planned ? r2(s.marginRecoveredUsd / s.planned) : 0; s.baselineMarginRatio = netRev ? r2(s.baselineMarginUsd / netRev * 100) / 100 : 0; s.intentMarginRatio = netRev ? r2(s.intentMarginUsd / netRev * 100) / 100 : 0;
  s.avgOnTimeBefore = s.planned ? Math.round(onB / s.planned * 1000) / 1000 : 0; s.avgOnTimeAfter = s.planned ? Math.round(onA / s.planned * 1000) / 1000 : 0;
  s.estimatedAnnualisedSavings = r2(s.marginRecoveredUsd * 365 / 30); s.estimatedAnnualisedOrders = Math.round(s.planned * 365 / 30);
  const byRegion = [...regions.values()].sort((a, b) => b.saved - a.saved);
  const top = byRegion[0];
  const findings = [
    top && top.saved > 0 ? `${top.changed} orders to ${top.region} shipped from ${PROFILES.find((p) => p.state === top.state)?.nearest}; routing them from ${PROFILES.find((p) => p.state === top.state)?.intent} recovers ${usd(top.saved)} (${usd(top.shippingSaved)} of it carrier cost).` : null,
    s.storeToDc > 0 ? `${s.storeToDc} orders went to a store because it was nearest; Intent OMS ships them from a distribution centre with lower pick cost and a better reject rate.` : null,
    s.ordersBelowFloorFlagged > 0 ? `${s.ordersBelowFloorFlagged} orders (${Math.round(s.ordersBelowFloorFlagged / Math.max(1, s.planned) * 100)}% of volume) would ship below the contribution-margin floor, carrying ${usd(s.belowFloorMarginUsd)} of margin under the baseline; Intent OMS holds them for approval instead of shipping blind.` : null,
    s.ordersWithIntent > 0 ? `${s.ordersWithIntent} of ${s.ordersWithNotes} orders with notes carried a fulfilment intent the baseline ignored: ${Object.entries(intents).map(([k, v]) => `${v} ${k}`).join(', ')}.` : null,
    `${s.planned} orders over 30 days: ${usd(s.marginRecoveredUsd)} of contribution margin recovered (${usd(s.marginRecoveredPerOrder)} per order, carrier spend ${usd(s.shippingSaved)}); at this run rate that is roughly ${usd(s.estimatedAnnualisedSavings)} a year (estimate).`,
  ].filter((f): f is string => !!f).slice(0, 5);
  const assumptions = [
    options.assumeStock ? 'Every active node is assumed to hold stock of every SKU (assumeStock=true), so the comparison isolates network geometry, node economics and policy; real ATP would change individual routings.' : 'Coverage comes from current ATP (assumeStock=false); orders with no stock anywhere were skipped.',
    single ? `Baseline = every order ships complete from ${single} (single-DC); overflow lines fall back to the nearest capable node.` : 'Baseline = the conventional OMS: ship-as-available from the nearest capable node by great-circle distance, no margin floor, no split penalty in the choice, no intent handling.',
    'Intent OMS = the tenant\'s live sourcing policy (rate card, node cost profiles, split penalty, margin floor, plan weights) with the fulfilment intent read from order notes; the node-health soft score is neutral in both arms.',
    options.useDecisions ? 'Intents come from the tenant\'s fulfilment_intent decision run in SIMULATION mode (no human tasks, no caching, records flagged SIMULATION) on orders with notes; keyword rules are the fallback.' : 'Intents come from deterministic keyword rules over order notes (useDecisions=false).',
    `SKUs missing from the catalogue use COGS = ${Math.round(options.unknownSkuCogsRatio * 100)}% of the selling price and 0.5 kg.`,
    'Both arms are costed identically: carrier rate card by distance zone and weight band, node labour/packaging/handling, landed cost and the tenant\'s split penalty per extra package.',
    'Nothing is persisted as an order, plan, reservation or task: only this job record.',
  ];
  const md = [`# Margin Replay — ${fileName}`, '', `Orders in file: ${n} · replayed: ${s.planned} · skipped: ${s.skipped} · window: 2025-09-01 → 2025-09-30 (30 days)`, '', '## Headline', '', '| Metric | Baseline | Intent OMS | Delta |', '|---|---:|---:|---:|',
    `| Carrier shipping cost | ${usd(s.baselineShippingCost)} | ${usd(s.intentShippingCost)} | ${usd(s.shippingSaved)} saved |`, `| Contribution margin | ${usd(s.baselineMarginUsd)} | ${usd(s.intentMarginUsd)} | ${usd(s.marginRecoveredUsd)} recovered |`,
    `| Split shipments | ${s.baselineSplits} | ${s.intentSplits} | ${s.splitsAvoided} avoided, ${s.splitsAdded} added for urgency |`, `| Orders below margin floor | ${s.baselineOrdersBelowFloor} shipped blind | ${s.ordersBelowFloorFlagged} flagged for approval | |`,
    `| Estimated annualised margin recovery | | | ${usd(s.estimatedAnnualisedSavings)} (estimate) |`, '', '## Findings', ...findings.map((f) => `- ${f}`), '', '## Assumptions', ...assumptions.map((a) => `- ${a}`), '', '_Generated by Intent OMS Margin Replay (mock). Nothing from the file was persisted as an order._', ''].join('\n');
  return { kind: 'import', options, file: { name: fileName, bytes: n * 620, format, rows: Math.round(n * 1.55), orders: n, warnings: [] }, summary: s, topFindings: findings, byRegion, assumptions, perOrder: rows, perOrderTruncated: false, markdownSummary: md, processed: n, partial: false, durationMs: 4200 };
}

export function registerReplayRoutes({ on, problem }: Helpers, jobs: SimulationJob[]) {
  const start = (fileName: string, orders: number, format: string, options: ReplayOptions): ReplayJob => {
    const now = new Date().toISOString();
    const job: ReplayJob = { id: `sim_imp${(seq++).toString(36).padStart(4, '0')}`, kind: 'import', label: `Margin Replay · ${fileName} · ${orders} orders`, status: 'QUEUED', from: '2025-09-01', to: '2025-09-30', overrides: {}, progress: 0, submittedAt: now, completedAt: null, results: null };
    jobs.unshift(job as unknown as SimulationJob);
    const startedAt = Date.now();
    const tick = () => {
      const p = Math.min(1, (Date.now() - startedAt) / 4500);
      job.progress = Math.round(p * 100) / 100; job.status = p < 1 ? 'RUNNING' : 'COMPLETED';
      if (p >= 1) { job.completedAt = new Date().toISOString(); job.results = fakeResults(orders, options, fileName, format); } else setTimeout(tick, 600);
    };
    setTimeout(tick, 400);
    return job;
  };
  on('POST', '/api/v1/simulations/import', ({ body }) => {
    const b = body as { fileName?: string; size?: number; text?: string; options?: Partial<ReplayOptions> };
    if ((b.size ?? 0) > 10 * 1024 * 1024) problem(422, 'validation_failed', 'The file is larger than 10 MB');
    const counted = countOrders(b.text);
    if (b.text && !counted) problem(422, 'validation_failed', 'Could not recognise the CSV layout: expected Shopify columns (Name, Lineitem sku, ...) or generic columns (order_id, sku, qty, ...)');
    if (counted && counted.orders === 0) problem(422, 'validation_failed', 'No orders could be read from the file');
    if (counted && counted.orders > 5000) problem(422, 'validation_failed', `The file has ${counted.orders} orders; at most 5000 are accepted per replay`);
    const options = { format: 'auto', assumeStock: true, unknownSkuCogsRatio: 0.6, baseline: 'nearest_node', useDecisions: true, ...(b.options ?? {}) } as ReplayOptions;
    return start(b.fileName ?? 'orders.csv', counted?.orders ?? 300, counted?.format ?? 'shopify', options);
  });
  on('POST', '/api/v1/simulations/import/sample', ({ body }) => {
    const options = { format: 'auto', assumeStock: true, unknownSkuCogsRatio: 0.6, baseline: 'nearest_node', useDecisions: true, ...(body as Partial<ReplayOptions>) } as ReplayOptions;
    return start('shopify-orders-sample.csv', 300, 'shopify', options);
  });
}
