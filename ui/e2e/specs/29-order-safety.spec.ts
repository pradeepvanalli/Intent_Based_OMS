import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { anonymous, createOrder, customerRefFor, deliverParcel, ok, shipLine, uid, type FullOrderView } from '../support/data';

type Atp = { sku: string; nodeId: string; onHand: number; reserved: number; allocated: number; available: number };
type Record_ = { id: string; decision: string; mode?: string; input?: { order?: { notes?: unknown } } };
type View = FullOrderView & { workflow?: { status?: string; lastError?: string | null } };

const view = async (api: Awaited<ReturnType<typeof apiAs>>, id: string) => ok<View>(api.get(`/api/v1/orders/${id}/view`));

/** Order-path fixes from Wave F, checked over the API (none of them has a screen of its own). */
test.describe('Order safety (API)', () => {
  test('A3: contact details typed into an order note are redacted before they reach a decision', async () => {
    const admin = await apiAs('admin');
    const ref = `e2e-${uid()}`;
    const raw = 'Call me on +1 415 555 0134 or write to jo.doe@example.com; card 4111 1111 1111 1111';
    const total = 60;
    const order = await ok<{ id: string }>(admin.post('/api/v1/orders', {
      data: {
        channel: 'web', channelOrderId: `E2E-${ref}`, orderType: 'standard_ship', customerRef: `cust-${ref}`,
        customer: { ref: `cust-${ref}`, name: 'E2E Shopper', email: `${ref}@example.com` },
        lines: [{ lineId: '1', sku: 'TEE-BLK-M', qty: 1, unitPrice: { amount: 28.0, currency: 'USD' } }],
        addresses: [{ type: 'ship_to', name: 'E2E Shopper', line1: `12 Market St, Unit ${ref}`, city: 'San Francisco', state: 'CA', zip: '94105', country: 'US' }],
        tenders: [{ type: 'card', token: 'tok_visa_ok', amount: { amount: total, currency: 'USD' } }],
        intentChoices: ['split_up_to_k'], shipMethod: 'ground',
        notes: [{ source: 'customer', author: 'shopper', text: raw, customerVisible: false }],
      },
    }), 200, 201);
    let notes: unknown;
    await expect.poll(async () => {
      const rows = await ok<Record_[]>(admin.get('/api/v1/decision-records', { params: { subjectType: 'order', subjectId: order.id } }));
      notes = rows.find((r) => r.decision === 'fulfilment_intent')?.input?.order?.notes;
      return Array.isArray(notes) && notes.length > 0;
    }, { timeout: 60_000, message: 'the fulfilment-intent decision with the order notes in its input' }).toBe(true);
    const text = JSON.stringify(notes);
    expect(text).toContain('[phone]');
    expect(text).toContain('[email]');
    expect(text).toContain('[card]');
    for (const secret of ['415 555 0134', 'jo.doe@example.com', '4111 1111 1111 1111']) expect(text, 'no raw contact detail in a decision input').not.toContain(secret);
  });

  test('A5: a carrier scan naming a parcel the order does not have is ignored and delivers nothing', async () => {
    const admin = await apiAs('admin');
    const orderId = await createOrder(admin);
    await expect.poll(async () => (await view(admin, orderId)).order.status, { timeout: 60_000, message: 'waiting for release' }).toMatch(/released|picking|packed/);
    const tracking = `1Z${uid()}K`;
    await shipLine(admin, orderId, '1', tracking);
    await expect.poll(async () => (await view(admin, orderId)).order.status, { timeout: 30_000 }).toBe('shipped');
    const hooks = await anonymous();
    const res = await ok<{ accepted: boolean; orders: string[]; ignored?: { orders: string[]; reason: string } }>(
      hooks.post('/webhooks/carrier', { data: { eventId: `car-unknown-${uid()}`, type: 'delivered', orderId, tracking: `1ZNOPE${uid()}` } }), 200, 201, 202);
    await hooks.dispose();
    expect(res.orders).toEqual([]);
    expect(res.ignored).toMatchObject({ orders: [orderId], reason: 'no parcel of the order matches the scan' });
    const v = await view(admin, orderId);
    expect(v.order.status).toBe('shipped');
    expect(v.ledger.filter((e) => e.type === 'order.delivered')).toHaveLength(0);
    // The real parcel's scan still delivers it.
    await deliverParcel(orderId, tracking);
    await expect.poll(async () => (await view(admin, orderId)).order.status, { timeout: 30_000 }).toBe('delivered');
  });

  test('FD-01: a new order\'s payment risk reads the learned fraud rate of each signal level, and a clean order is still cleared by its rule', async () => {
    const admin = await apiAs('admin');
    // A customer outside the persistent holdout: a holdout customer is served the standard values, so no learned rates.
    const { ref } = await customerRefFor(admin, false);
    const orderId = await createOrder(admin, { ref });
    type Payment = Record_ & { layer: string; value: unknown; input?: { payment?: { learned_fraud_rate?: number; learned_fraud_rates?: Record<string, number> } } };
    let record: Payment | undefined;
    await expect.poll(async () => {
      record = (await ok<Payment[]>(admin.get('/api/v1/decision-records', { params: { subjectType: 'order', subjectId: orderId } }))).find((r) => r.decision === 'payment_risk');
      return !!record;
    }, { timeout: 60_000 }).toBe(true);
    const rates = record!.input?.payment?.learned_fraud_rates ?? {};
    expect(Object.keys(rates).length, 'fraud.signal_risk is live with learned rates from the matured cohort').toBeGreaterThan(0);
    expect(record!.input!.payment!.learned_fraud_rate!).toBeLessThan(0.2);
    expect(record!.layer, 'the clean-order rule still decides, so no payment review hold').toBe('L0_RULE');
    expect(record!.value).toBe(false);
    const holds = (await ok<{ holds?: Array<{ type: string; releasedAt?: string }> }>(admin.get(`/api/v1/orders/${orderId}/view`))).holds ?? [];
    expect(holds.filter((h) => h.type === 'payment_review' && !h.releasedAt)).toEqual([]);
  });

  test('E-1: asking for sourcing options scores in shadow and adds no live decision', async () => {
    const admin = await apiAs('admin');
    const orderId = await createOrder(admin);
    const records = async () => ok<Record_[]>(admin.get('/api/v1/decision-records', { params: { subjectType: 'order', subjectId: orderId } }));
    await expect.poll(async () => (await records()).filter((r) => r.decision === 'sourcing_node_score' && r.mode === 'LIVE').length, { timeout: 60_000 }).toBeGreaterThan(0);
    const liveBefore = (await records()).filter((r) => r.decision === 'sourcing_node_score' && r.mode === 'LIVE').length;
    await ok(admin.get(`/api/v1/orders/${orderId}/sourcing/options`));
    await expect.poll(async () => (await records()).filter((r) => r.decision === 'sourcing_node_score' && r.mode === 'SHADOW').length, { timeout: 30_000 }).toBeGreaterThan(0);
    expect((await records()).filter((r) => r.decision === 'sourcing_node_score' && r.mode === 'LIVE').length, 'read-only sourcing writes no LIVE record').toBe(liveBefore);
  });

  test('FS-02: day parts follow the tenant clock (America/New_York), on live and seeded decisions alike', async () => {
    const admin = await apiAs('admin');
    const zone = await ok<Record<string, unknown>>(admin.get('/api/v1/admin/config', { params: { prefix: 'tenant.' } }));
    expect(zone['tenant.timezone']).toBe('America/New_York');
    const dayPart = (iso: string) => {
      const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hour: 'numeric', hourCycle: 'h23' }).format(new Date(iso)));
      return hour < 6 ? 'night' : hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening';
    };
    type Scored = Record_ & { createdAt: string; input?: { order?: { day_part?: string } } };
    const orderId = await createOrder(admin);
    let live: Scored | undefined;
    await expect.poll(async () => {
      live = (await ok<Scored[]>(admin.get('/api/v1/decision-records', { params: { subjectType: 'order', subjectId: orderId } }))).find((r) => r.decision === 'sourcing_node_score' && r.mode === 'LIVE');
      return !!live;
    }, { timeout: 60_000 }).toBe(true);
    expect(live!.input?.order?.day_part).toBe(dayPart(live!.createdAt));
    // The seeded history stamps the same definition, so learned day-part facts and live lookups agree.
    for (const n of ['0041', '0200', '0399']) {
      const seeded = await ok<Scored>(admin.get(`/api/v1/decision-records/dr-demo-sourcing_node_score-demo-hist-${n}`));
      expect(seeded.input?.order?.day_part, `demo-hist-${n}`).toBe(dayPart(seeded.createdAt));
    }
  });

  test('A1: orders racing for the last unit at a node are re-sourced, never compensated', async () => {
    const admin = await apiAs('admin');
    const sku = 'SNK-RUN-10';
    const node = 'DC-NV'; // the nearest warehouse to the San Francisco address createOrder uses
    const before = await ok<Atp>(admin.get(`/api/v1/inventory/${sku}/nodes/${node}`));
    const others = (await ok<Atp[]>(admin.get(`/api/v1/inventory/${sku}`))).filter((a) => a.nodeId !== node && a.available > 10);
    test.skip(others.length === 0, `${sku} needs stock at another node to re-source to`);
    // Leave exactly one unit at the node every order prefers, then place several orders for it at once.
    await ok(admin.post('/api/v1/inventory/adjust', { data: { sku, nodeId: node, delta: 1 - before.available, reason: 'e2e A1: last unit at the preferred node' } }));
    try {
      const ids = await Promise.all(Array.from({ length: 6 }, () => createOrder(admin, {
        lines: [{ lineId: '1', sku, qty: 1, unitPrice: { amount: 120.0, currency: 'USD' } }],
      })));
      // Settled means past reservation: waiting for fulfilment (or finished) on a live group, or backordered, or failed.
      // A group exists from the moment an order is planned, before its reservation runs, so a planned-but-not-yet-reserved
      // order is not counted as holding the unit.
      const settled = async (id: string) => {
        const v = await view(admin, id);
        const pastReservation = /WAITING_SIGNAL|COMPLETED/.test(v.workflow?.status ?? '') && v.fulfilmentGroups.some((g) => !/rejected|cancelled/i.test(g.status));
        return pastReservation || /cancelled|backorder/.test(v.order.status) || /COMPENSATED|FAILED/.test(v.workflow?.status ?? '');
      };
      await expect.poll(async () => (await Promise.all(ids.map(settled))).every(Boolean), { timeout: 90_000, message: 'every racing order sourced or settled' }).toBe(true);

      let resourced = 0;
      for (const id of ids) {
        const v = await view(admin, id);
        expect(v.order.status, `${id} is not cancelled`).not.toMatch(/cancel/);
        expect(v.workflow?.status ?? '', `${id} workflow not compensated or failed`).not.toMatch(/COMPENSATED|FAILED|CANCELLED/);
        expect(v.ledger.filter((e) => e.type === 'workflow.compensated'), `${id} has no compensation`).toHaveLength(0);
        const shortage = v.ledger.filter((e) => e.type === 'order.resourced' && e.data?.reason === 'insufficient_atp');
        if (shortage.length) {
          resourced++;
          expect(v.fulfilmentGroups.some((g) => g.nodeId !== node && !/rejected|cancelled/i.test(g.status)), `${id} moved to another node`).toBe(true);
        }
      }
      // At most one order can hold the last unit; the rest were sourced elsewhere first, or lost it at reservation and were re-sourced.
      const atNode = (await Promise.all(ids.map((id) => view(admin, id)))).filter((v) => v.fulfilmentGroups.some((g) => g.nodeId === node && !/rejected|cancelled/i.test(g.status))).length;
      expect(atNode).toBeLessThanOrEqual(1);
      // The invariant behind A1: the node never promises more than it holds.
      const after = await ok<Atp>(admin.get(`/api/v1/inventory/${sku}/nodes/${node}`));
      expect(after.reserved + after.allocated, `${node} reserved ${after.reserved} + allocated ${after.allocated} of ${after.onHand} on hand`).toBeLessThanOrEqual(after.onHand);
      expect(after.available).toBeGreaterThanOrEqual(0);
      test.info().annotations.push({ type: 'A1', description: `${resourced} of ${ids.length} orders lost the last unit at reservation and were re-sourced` });
    } finally {
      const now = await ok<Atp>(admin.get(`/api/v1/inventory/${sku}/nodes/${node}`));
      await admin.post('/api/v1/inventory/adjust', { data: { sku, nodeId: node, delta: before.onHand - now.onHand, reason: 'e2e A1: restore stock' } });
    }
  });
});
