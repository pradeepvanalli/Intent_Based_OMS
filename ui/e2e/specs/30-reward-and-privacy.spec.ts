import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { ok, uid, waitForDecision } from '../support/data';

type TermScale = { scale: number; method: string; source: string; reason: string };
type Baseline = { scales: Record<string, number>; terms: Record<string, TermScale> };
type Headline = { metric: string; unit?: string; definition?: string; orderLevel?: { metric: string } };
type Record_ = { id: string; decision: string; mode?: string; input?: Record<string, unknown>; contextFeatures?: Record<string, unknown> };

const TERMS = ['fill', 'otd', 'cost', 'split', 'fraud', 'cx', 'inv'];

/**
 * §7 normalisation is never off (audit 3, OBJ-normalisation): every reward term has an explicit scale with its method,
 * source and reason, the rate terms (fill, on time) the identity. §12 AC-2: the perfect-order headline is the §2
 * line-level rate, contacts and chargebacks excluded.
 */
test('every reward term has a stated scale, and the north star is the share of perfect lines', async () => {
  const api = await apiAs('admin');
  const baseline = await ok<Baseline>(api.get('/api/v1/learning/reward-baseline'));
  expect(Object.keys(baseline.terms).sort()).toEqual([...TERMS].sort());
  for (const t of TERMS) {
    expect(baseline.scales[t], `scale of ${t}`).toBeGreaterThan(0);
    expect(baseline.terms[t].reason, `reason for ${t}`).not.toBe('');
  }
  expect(baseline.terms.fill).toMatchObject({ scale: 1, method: 'identity' });
  expect(baseline.terms.otd).toMatchObject({ scale: 1, method: 'identity' });

  const lift = await ok<{ headline: Headline }>(api.get('/api/v1/learning/lift', { params: { days: 90 } }));
  expect(lift.headline).toMatchObject({ metric: 'perfect_order_rate', unit: 'share of lines' });
  expect(lift.headline.definition).toContain('contacts and chargebacks do not count');
  expect(lift.headline.orderLevel?.metric).toBe('perfect_order_share');
});

/**
 * NF-07 (audit 3): a customer's name and a street address typed into an order note never reach a decision's input;
 * the note still says what the customer wants.
 */
test('a note with the customer name and a street address reaches no decision input', async () => {
  const api = await apiAs('admin');
  const ref = `e2e-${uid()}`;
  const body = {
    channel: 'web', channelOrderId: `E2E-${ref}`, orderType: 'standard_ship', customerRef: `cust-${ref}`,
    customer: { ref: `cust-${ref}`, name: 'Jane Doe', email: `${ref}@example.com` },
    lines: [{ lineId: '1', sku: 'TEE-BLK-M', qty: 1, unitPrice: { amount: 28.0, currency: 'USD' } }],
    addresses: [{ type: 'ship_to', name: 'Jane Doe', line1: '400 Oak Ave', city: 'San Francisco', state: 'CA', zip: '94105', country: 'US' }],
    tenders: [{ type: 'card', token: 'tok_visa_ok', amount: { amount: 60, currency: 'USD' } }],
    notes: [{ source: 'customer', text: 'Call Jane Doe at 12 Elm Street before delivery', customerVisible: false }],
    shipMethod: 'ground',
  };
  const order = await ok<{ id: string }>(api.post('/api/v1/orders', { data: body }), 200, 201);
  const intent = await waitForDecision(api, order.id, 'fulfilment_intent');
  const rec = await ok<Record_>(api.get(`/api/v1/decision-records/${intent.id}`));
  const text = JSON.stringify(rec.input) + JSON.stringify(rec.contextFeatures ?? {});
  expect(text).not.toContain('Jane Doe');
  expect(text).not.toContain('12 Elm Street');
  expect(text).toContain('Call [name] at [address] before delivery');
});
