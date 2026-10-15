import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { HI_BOUNDS, US_BOUNDS, arcPath, distanceKm, inBounds, outlinePath, place, project, US_OUTLINE } from '../pages/playground/map';
import { normalizeRecord, orderTrace, primaryRecord, traceStats, type RawRecord } from '../pages/playground/trace';
import { CITIES, PERSONAS, applyPersona, buildCapture, emptyForm, pickSkus } from '../pages/playground/presets';
import type { CatalogItem } from '../api/playground';
import { ordersApi } from '../api/orders';
import { trackingApi } from '../api/playground';
import { orders } from '../api/mock/data';
import Track from '../pages/track/Track';
import { loginAsAdmin, renderWithProviders } from './utils';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

const box = { x: 0, y: 0, w: 600, h: 300 };

describe('map projection', () => {
  it('maps the bounding box corners to the box corners (equirectangular, linear)', () => {
    expect(project(US_BOUNDS.maxLat, US_BOUNDS.minLon, box)).toEqual({ x: 0, y: 0 });
    expect(project(US_BOUNDS.minLat, US_BOUNDS.maxLon, box)).toEqual({ x: 600, y: 300 });
    const mid = project((US_BOUNDS.minLat + US_BOUNDS.maxLat) / 2, (US_BOUNDS.minLon + US_BOUNDS.maxLon) / 2, box);
    expect(mid.x).toBeCloseTo(300); expect(mid.y).toBeCloseTo(150);
  });
  it('keeps west of east and north above south', () => {
    const sea = project(47.61, -122.33, box); const nyc = project(40.71, -74.01, box); const mia = project(25.77, -80.19, box);
    expect(sea.x).toBeLessThan(nyc.x); expect(sea.y).toBeLessThan(mia.y); expect(nyc.y).toBeLessThan(mia.y);
  });
  it('places Honolulu in the Hawaii inset and leaves Toronto on the main map, Anchorage off map', () => {
    const inset = { x: 10, y: 200, w: 100, h: 60 };
    const hnl = place(21.31, -157.86, box, inset);
    expect(hnl?.inset).toBe(true); expect(hnl!.p.x).toBeGreaterThanOrEqual(10); expect(hnl!.p.x).toBeLessThanOrEqual(110);
    expect(inBounds(21.31, -157.86, HI_BOUNDS)).toBe(true);
    expect(place(43.65, -79.38, box, inset)?.inset).toBe(false);
    expect(place(61.2, -149.9, box, inset)).toBeNull();
  });
  it('builds closed SVG paths and arcs', () => {
    const d = outlinePath(US_OUTLINE, box, US_BOUNDS);
    expect(d.startsWith('M')).toBe(true); expect(d.endsWith(' Z')).toBe(true); expect(d.split('L').length).toBe(US_OUTLINE.length);
    expect(arcPath({ x: 0, y: 0 }, { x: 100, y: 0 })).toBe('M0.0 0.0 Q50.0 -22.0 100.0 0.0');
    expect(outlinePath([], box, US_BOUNDS)).toBe('');
  });
  it('computes great-circle distance', () => {
    expect(distanceKm({ lat: 40.71, lon: -74.01 }, { lat: 34.05, lon: -118.24 })).toBeCloseTo(3936, -2);
    expect(distanceKm({ lat: 1, lon: 1 }, { lat: 1, lon: 1 })).toBe(0);
  });
});

describe('trace ordering and totals', () => {
  const recs: RawRecord[] = [
    { id: 'c', decision: 'sla_breach_risk', createdAt: '2026-10-08T10:00:03Z', layer: 'L1_SYSTEM_ONE', band: 'CONFIRM', action: 'confirm', confidence: 0.6, latencyMs: 500, costUsd: 0.00002, provider: 'jev' },
    { id: 'a', decision: 'fulfilment_intent', createdAt: '2026-10-08T10:00:01Z', layer: 'L1_SYSTEM_ONE', band: 'ACT', action: 'act', confidence: 0.91, latencyMs: 420, costUsd: 0.00002, provider: 'jev', value: 'ship_complete' },
    { id: 'b', decision: 'sourcing_node_score', evaluatedAt: '2026-10-08T10:00:02Z', layer: 'L3_HUMAN', band: 'ESCALATE', action: 'escalate', confidence: 0.4, latencyMs: 300, costUsd: 0, provider: 'jev', requiresHuman: true },
    { id: 'a', decision: 'fulfilment_intent', createdAt: '2026-10-08T10:00:01Z' }, // duplicate from the second source
    { id: 'z', decision: 'injection_screen', layer: 'L0_RULE', band: 'ACT', action: 'act', confidence: 1, latencyMs: 2, costUsd: 0, rulesFired: ['clean_text'] },
  ];
  it('sorts chronologically across createdAt/evaluatedAt, de-duplicates and keeps untimed records last', () => {
    const t = orderTrace(recs);
    expect(t.map((r) => r.id)).toEqual(['a', 'b', 'c', 'z']);
    expect(t[1].at).toBe('2026-10-08T10:00:02Z');
    expect(t[3].rulesFired).toEqual(['clean_text']);
  });
  it('normalises missing fields defensively', () => {
    const n = normalizeRecord({ id: 'x', decision: 'd' });
    expect(n.layer).toBe('DEFAULT'); expect(n.band).toBe('ESCALATE'); expect(n.costUsd).toBe(0); expect(n.at).toBe(''); expect(n.requiresHuman).toBe(false);
  });
  it('totals cost, latency and human decisions avoided', () => {
    const s = traceStats(orderTrace(recs));
    expect(s.decisions).toBe(4);
    expect(s.escalated).toBe(1);
    expect(s.humanAvoided).toBe(3);
    expect(s.latencyMs).toBe(1222);
    expect(s.costUsd).toBeCloseTo(0.00004, 6);
    expect(s.aiCalls).toBe(2);
  });
  it('prefers the intent decision as primary', () => {
    expect(primaryRecord(orderTrace(recs))?.decision).toBe('fulfilment_intent');
    expect(primaryRecord(orderTrace([recs[0]]))?.decision).toBe('sla_breach_risk');
    expect(primaryRecord([])).toBeUndefined();
  });
});

describe('presets and capture request', () => {
  const catalog: CatalogItem[] = [
    { sku: 'HOOD-GRY-M', name: 'Fleece hoodie grey M', attributes: { price: 68 } }, { sku: 'CAP-NVY', name: 'Wool cap navy', attributes: { price: 32 } },
    { sku: 'DUMB-10KG', name: 'Dumbbell pair', attributes: { price: 70, oversized: true } }, { sku: 'BAG-TOTE', name: 'Canvas tote', attributes: { price: 45 } },
  ];
  const nodes = [{ id: 'ST-AUS-03', name: 'Austin store', type: 'store', lat: 30.4, lon: -97.7, country: 'US', state: 'TX', capabilities: ['bopis'], dailyCapacity: 100, active: true }, { id: 'DC-NJ', name: 'Edison DC', type: 'dc', lat: 40.5, lon: -74.4, country: 'US', state: 'NJ', capabilities: ['ship'], dailyCapacity: 1000, active: true }];
  it('fills preferred SKUs that exist and tops up from the catalogue', () => {
    expect(pickSkus(['HOOD-GRY-M', 'NOPE', 'CAP-NVY'], catalog)).toEqual(['HOOD-GRY-M', 'CAP-NVY']);
    expect(pickSkus(['NOPE'], catalog)).toEqual(['HOOD-GRY-M']);
  });
  it('builds a unique PLAY- order for the gift persona', () => {
    const form = applyPersona(PERSONAS[0], catalog, nodes);
    const body = buildCapture(form, catalog, nodes, 1700000000000);
    expect(body.channelOrderId).toBe('PLAY-1700000000000');
    expect(body.channel).toBe('web');
    expect(body.lines.map((l) => l.sku)).toEqual(['HOOD-GRY-M', 'CAP-NVY']);
    expect(body.lines[0].unitPrice).toEqual({ amount: 68, currency: 'USD' });
    expect(body.notes[0].text).toMatch(/gift/i);
    expect(body.addresses[0]).toMatchObject({ type: 'ship_to', city: 'Seattle', state: 'WA' });
    expect(body.provenance).toBeNull();
  });
  it('builds pickup addresses for BOPIS and provenance for agent orders', () => {
    const bopis = buildCapture(applyPersona(PERSONAS.find((p) => p.key === 'bopis')!, catalog, nodes), catalog, nodes, 1);
    expect(bopis.orderType).toBe('bopis'); expect(bopis.shipMethod).toBe('pickup');
    expect(bopis.addresses.map((a) => a.type)).toEqual(['pickup', 'ship_to']);
    expect(bopis.addresses[0].line1).toBe('ST-AUS-03');
    const agent = buildCapture(applyPersona(PERSONAS.find((p) => p.key === 'agent')!, catalog, nodes), catalog, nodes, 2);
    expect(agent.channel).toBe('agent'); expect(agent.provenance?.agentId).toBe('shopbot-ucp'); expect(agent.provenance?.spendLimit?.amount).toBe(400);
    const flagged = buildCapture(applyPersona(PERSONAS.find((p) => p.key === 'flagged')!, catalog, nodes), catalog, nodes, 3);
    expect(flagged.customer.history.flags).toEqual(['flagged_customer']);
    expect(emptyForm().lines).toEqual([]);
    expect(CITIES).toHaveLength(10);
  });
});

describe('public tracking page (mock API)', () => {
  it('renders the customer view for a minted token and an error for a bad one', async () => {
    loginAsAdmin();
    const sample = orders.find((m) => m.shipments.length > 0) ?? orders[0];
    const link = await ordersApi.trackingLink(sample.order.id);
    expect(link.token).toBeTruthy();
    const view = await trackingApi.view(link.token);
    expect(view.orderNumber).toBe(sample.order.channelOrderId);
    expect(JSON.stringify(view)).not.toMatch(/margin|cogs|nodeId|decision/);
    renderWithProviders(<Routes><Route path="/track/:token" element={<Track />} /></Routes>, { route: `/track/${link.token}` });
    await waitFor(() => expect(screen.getByText(sample.order.channelOrderId)).toBeInTheDocument());
    expect(screen.getByRole('list', { name: /order progress/i })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /items/i })).toBeInTheDocument();
  });
  it('shows a friendly error for an invalid token', async () => {
    renderWithProviders(<Routes><Route path="/track/:token" element={<Track />} /></Routes>, { route: '/track/not-a-real-token' });
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument(), { timeout: 4000 });
    expect(screen.getByText(/invalid or has expired/i)).toBeInTheDocument();
  });
});

describe('Playground page (mock API)', () => {
  it('renders presets, fills the form from a persona and offers to place the order', async () => {
    loginAsAdmin();
    const { default: Playground } = await import('../pages/playground/Playground');
    const user = (await import('@testing-library/user-event')).default.setup();
    renderWithProviders(<Routes><Route path="/playground" element={<Playground />} /></Routes>, { route: '/playground' });
    expect(screen.getByRole('heading', { name: /playground/i })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /place order/i })).toBeEnabled(), { timeout: 4000 });
    await user.click(screen.getByRole('button', { name: /urgent/i }));
    expect((screen.getByLabelText(/customer note/i) as HTMLTextAreaElement).value).toMatch(/yoga mat/i);
    expect(screen.getByRole('status', { name: /ai status/i })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /network map/i })).toBeInTheDocument();
    expect(screen.getByText(/no order yet/i)).toBeInTheDocument();
  });
});
