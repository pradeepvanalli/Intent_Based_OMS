/* Persona presets, destination cities and the capture-request builder for the Playground. Pure functions, no React. */
import type { CaptureRequestBody, CatalogItem } from '../../api/playground';
import { catalogFlag, catalogPrice } from '../../api/playground';
import type { Node } from '../../api/types';

export interface City { name: string; state: string; zip: string; lat: number; lon: number; line1: string; }
/** Ten US destinations with coordinates for the map (Honolulu renders in the Hawaii inset). */
export const CITIES: City[] = [
  { name: 'Seattle', state: 'WA', zip: '98101', lat: 47.61, lon: -122.33, line1: '400 Pine St' },
  { name: 'San Francisco', state: 'CA', zip: '94105', lat: 37.79, lon: -122.4, line1: '1 Market St' },
  { name: 'Los Angeles', state: 'CA', zip: '90012', lat: 34.05, lon: -118.24, line1: '200 N Spring St' },
  { name: 'Denver', state: 'CO', zip: '80202', lat: 39.75, lon: -104.99, line1: '1600 Glenarm Pl' },
  { name: 'Austin', state: 'TX', zip: '78701', lat: 30.27, lon: -97.74, line1: '301 Congress Ave' },
  { name: 'Chicago', state: 'IL', zip: '60606', lat: 41.88, lon: -87.63, line1: '233 S Wacker Dr' },
  { name: 'Atlanta', state: 'GA', zip: '30303', lat: 33.75, lon: -84.39, line1: '55 Trinity Ave SW' },
  { name: 'Miami', state: 'FL', zip: '33130', lat: 25.77, lon: -80.19, line1: '100 Biscayne Blvd' },
  { name: 'New York', state: 'NY', zip: '10007', lat: 40.71, lon: -74.01, line1: '1 Centre St' },
  { name: 'Honolulu', state: 'HI', zip: '96813', lat: 21.31, lon: -157.86, line1: '1 Aloha Tower Dr' },
];

export const SHIP_METHODS = [
  { id: 'ground', label: 'Ground' }, { id: '2day', label: '2-day' }, { id: 'overnight', label: 'Overnight (air)' }, { id: 'pickup', label: 'Store pickup' },
] as const;
export const TIERS = ['bronze', 'silver', 'gold', 'platinum'] as const;
export const ORDER_TYPES = ['standard_ship', 'bopis', 'ship_from_store', 'same_day_local', 'pre_order'] as const;

export interface FormLine { sku: string; qty: number; }
export interface PlaygroundForm {
  persona: string | null;
  note: string;
  lines: FormLine[];
  cityIdx: number;
  shipMethod: string;
  tier: string;
  orderType: string;
  channel: string;
  pickupNodeId: string;
  flaggedCustomer: boolean;
  agent: boolean;
  intentChoices: string[];
  urgentFirstLine: boolean;
}

export interface Persona {
  key: string; label: string; blurb: string; icon: string;
  note: string; skus: string[]; qty?: number[]; city: string; shipMethod: string; tier: string; orderType: string; channel?: string;
  flaggedCustomer?: boolean; agent?: boolean; intentChoices?: string[]; urgentFirstLine?: boolean; expect: string;
}

export const PERSONAS: Persona[] = [
  { key: 'gift', label: 'Gift buyer', icon: '🎁', blurb: '"birthday gift, send everything together"', note: 'This is a birthday gift for my dad, please send everything together in one box.', skus: ['HOOD-GRY-M', 'CAP-NVY', 'SNK-RUN-10'], city: 'Seattle', shipMethod: 'ground', tier: 'gold', orderType: 'standard_ship', expect: 'fulfilment_intent → ship_complete, one package even if it means a longer route' },
  { key: 'urgent', label: 'Urgent', icon: '⚡', blurb: '"need the mat before Friday, rest can come later"', note: 'I need the yoga mat before Friday, the rest can come whenever.', skus: ['YOGA-MAT', 'HOOD-GRY-M', 'CAP-NVY'], city: 'Seattle', shipMethod: 'ground', tier: 'gold', orderType: 'standard_ship', urgentFirstLine: true, expect: 'fulfilment_intent → earliest_partial; the mat leaves from the nearest node first' },
  { key: 'ambiguous', label: 'Ambiguous', icon: '🤷', blurb: '"ok"', note: 'ok', skus: ['TEE-BLK-M', 'MUG-CER'], city: 'Denver', shipMethod: 'ground', tier: 'silver', orderType: 'standard_ship', expect: 'low confidence → confirm band; a T1 decision applies the channel default deterministically' },
  { key: 'hazmat_air', label: 'Hazmat by air', icon: '🧯', blurb: 'perfume + power bank, overnight', note: 'Please rush this, I fly out Thursday.', skus: ['PERF-50ML', 'PWRBNK-20K'], city: 'Miami', shipMethod: 'overnight', tier: 'gold', orderType: 'standard_ship', expect: 'L0 compliance rule hazmat_no_air blocks capture before any model runs' },
  { key: 'below_floor', label: 'Below margin floor', icon: '📉', blurb: '2 × 20 kg dumbbells to Honolulu', note: '', skus: ['DUMB-10KG'], qty: [2], city: 'Honolulu', shipMethod: 'ground', tier: 'silver', orderType: 'standard_ship', intentChoices: ['ship_complete'], expect: 'shipping cost eats the margin → margin-floor hold and a finance approval task' },
  { key: 'bopis', label: 'BOPIS', icon: '🏬', blurb: 'buy online, pick up in store', note: 'I will collect it on my way home.', skus: ['BAG-TOTE', 'CNDL-SOY'], city: 'Austin', shipMethod: 'pickup', tier: 'gold', orderType: 'bopis', intentChoices: ['pickup_consolidated'], expect: 'pickup_consolidated; the store nearest the customer is chosen' },
  { key: 'flagged', label: 'Flagged customer', icon: '🚩', blurb: 'history flags + prior failed deliveries', note: 'Leave it with the concierge if nobody answers.', skus: ['WATCH-GPS'], city: 'Chicago', shipMethod: '2day', tier: 'bronze', orderType: 'standard_ship', flaggedCustomer: true, expect: 'address_risk and sla_breach_risk weigh the history; a T2 decision may escalate to a person' },
  { key: 'agent', label: 'Agent order', icon: '🤖', blurb: 'placed by shopbot-ucp under a $400 mandate', note: '', skus: ['HOOD-GRY-M', 'CAP-NVY'], city: 'Chicago', shipMethod: 'ground', tier: 'gold', orderType: 'standard_ship', channel: 'agent', agent: true, expect: 'provenance recorded; injection screen and mandate match run before sourcing' },
];

export const AGENT_INSTRUCTION = 'Buy the grey hoodie in M and a navy cap for Jordan, ship to the Chicago office, keep it under 400 dollars.';

export function emptyForm(): PlaygroundForm {
  return { persona: null, note: '', lines: [], cityIdx: 0, shipMethod: 'ground', tier: 'gold', orderType: 'standard_ship', channel: 'web', pickupNodeId: '', flaggedCustomer: false, agent: false, intentChoices: [], urgentFirstLine: false };
}

/** Preferred SKUs that exist in the catalogue; falls back to the first catalogue item so the form always has a line. */
export function pickSkus(preferred: string[], catalog: CatalogItem[], max = 3): string[] {
  const have = new Set(catalog.map((c) => c.sku));
  const out = preferred.filter((s) => have.has(s)).slice(0, max);
  if (!out.length && catalog.length) out.push(catalog[0].sku);
  return out;
}

export function applyPersona(p: Persona, catalog: CatalogItem[], nodes: Node[]): PlaygroundForm {
  const skus = pickSkus(p.skus, catalog);
  const cityIdx = Math.max(0, CITIES.findIndex((c) => c.name === p.city));
  const city = CITIES[cityIdx];
  return {
    persona: p.key, note: p.note, lines: skus.map((sku, i) => ({ sku, qty: p.qty?.[i] ?? 1 })), cityIdx,
    shipMethod: p.shipMethod, tier: p.tier, orderType: p.orderType, channel: p.channel ?? 'web',
    pickupNodeId: p.orderType === 'bopis' ? nearestStore(nodes, city)?.id ?? '' : '',
    flaggedCustomer: !!p.flaggedCustomer, agent: !!p.agent, intentChoices: p.intentChoices ?? [], urgentFirstLine: !!p.urgentFirstLine,
  };
}

export function nearestStore(nodes: Node[], city: City): Node | undefined {
  const stores = nodes.filter((n) => n.type === 'store' && n.active !== false && (n.capabilities ?? []).includes('bopis'));
  return stores.sort((a, b) => dist(a, city) - dist(b, city))[0];
}
const dist = (n: { lat: number; lon: number }, c: { lat: number; lon: number }) => Math.hypot(n.lat - c.lat, (n.lon - c.lon) * Math.cos((c.lat * Math.PI) / 180));

export function lineTotal(form: PlaygroundForm, catalog: CatalogItem[]): number {
  return form.lines.reduce((s, l) => { const c = catalog.find((x) => x.sku === l.sku); return s + (c ? catalogPrice(c) * l.qty : 0); }, 0);
}

export function hasHazmat(form: PlaygroundForm, catalog: CatalogItem[]): boolean {
  return form.lines.some((l) => { const c = catalog.find((x) => x.sku === l.sku); return !!c && catalogFlag(c, 'hazmat'); });
}

/** Builds the capture request. Channel order id is unique per attempt (`PLAY-<timestamp>`). */
export function buildCapture(form: PlaygroundForm, catalog: CatalogItem[], nodes: Node[], now = Date.now()): CaptureRequestBody {
  const city = CITIES[form.cityIdx] ?? CITIES[0];
  const ref = `PLAY-${now}`;
  const customerRef = form.agent ? 'cust-agent-1' : form.flaggedCustomer ? `cust-flagged-${now}` : `cust-play-${now}`;
  const name = form.agent ? 'Jordan Lee' : form.flaggedCustomer ? 'Casey Morgan' : 'Priya Raman';
  const total = Math.round(lineTotal(form, catalog) * 1.2 * 100) / 100 + 10;
  const history: Record<string, unknown> = form.flaggedCustomer
    ? { split_acceptance_rate: 0.2, orders_12m: 2, flags: ['flagged_customer'], not_received_claims: 2, prior_failed_deliveries: 2 }
    : { split_acceptance_rate: form.persona === 'gift' ? 0.3 : 0.8, orders_12m: 7 };
  const lines = form.lines.map((l, i) => {
    const c = catalog.find((x) => x.sku === l.sku);
    const attributes: Record<string, unknown> = {};
    if (i === 0 && form.urgentFirstLine) attributes.urgent = true;
    return { lineId: String(i + 1), sku: l.sku, qty: Math.max(1, l.qty), unitPrice: { amount: c ? catalogPrice(c) : 0, currency: 'USD' }, ...(Object.keys(attributes).length ? { attributes } : {}) };
  });
  const isPickup = form.orderType === 'bopis' || form.shipMethod === 'pickup';
  const store = isPickup ? nodes.find((n) => n.id === form.pickupNodeId) ?? nearestStore(nodes, city) : undefined;
  const addresses: CaptureRequestBody['addresses'] = isPickup && store
    ? [{ type: 'pickup', name, line1: store.id, city: cityOf(store, city), state: store.state ?? city.state, zip: city.zip, country: 'US', addressType: 'commercial' },
       { type: 'ship_to', name, line1: `Store pickup ${store.id}`, city: cityOf(store, city), state: store.state ?? city.state, zip: city.zip, country: 'US', addressType: 'commercial' }]
    : [{ type: 'ship_to', name, line1: form.flaggedCustomer ? `${city.line1}, PO Box 1182` : city.line1, city: city.name, state: city.state, zip: city.zip, country: 'US', addressType: form.flaggedCustomer ? 'po_box' : 'residential' }];
  return {
    channel: form.channel || 'web', channelOrderId: ref, orderType: form.orderType, customerRef,
    customer: { ref: customerRef, name, email: `${name.toLowerCase().replace(/\s+/g, '.')}@example.com`, tier: form.tier, ageVerified: false, history },
    lines, addresses, charges: [], tenders: [{ type: 'card', token: 'tok_visa_ok', amount: { amount: total, currency: 'USD' } }],
    notes: form.note.trim() ? [{ source: 'customer', author: 'customer', text: form.note.trim() }] : [],
    intentChoices: form.intentChoices, shipMethod: isPickup ? 'pickup' : form.shipMethod, currency: 'USD', extensions: { playground: true },
    provenance: form.agent ? { agentId: 'shopbot-ucp', protocol: 'ucp', instructionText: AGENT_INSTRUCTION, spendLimit: { amount: 400, currency: 'USD' }, buyerRef: customerRef } : null,
  };
}
const cityOf = (n: Node, fallback: City): string => (n.name?.split(/[ ,(]/)[0] && /^[A-Z]/.test(n.name) ? n.name.split(/[ ,(]/)[0] : fallback.name);
