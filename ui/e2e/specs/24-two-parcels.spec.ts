import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, deliverParcel, ok, shipLine, uid, type FullOrderView } from '../support/data';

const view = async (api: Awaited<ReturnType<typeof apiAs>>, id: string) => ok<FullOrderView>(api.get(`/api/v1/orders/${id}/view`));
const lineStatus = (v: FullOrderView, lineId: string) => v.order.lines.find((l) => l.lineId === lineId)?.status;

/**
 * Audit defect 7: a carrier scan delivers only the parcel it names. An order shipped in two parcels is partly
 * delivered after the first scan (one order.delivered, for that parcel's lines) and delivered after the second.
 */
test('an order shipped in two parcels is delivered one parcel at a time', async ({ pageAs }) => {
  const api = await apiAs('admin');
  const orderId = await createOrder(api, {
    lines: [
      { lineId: '1', sku: 'TEE-BLK-M', qty: 2, unitPrice: { amount: 28.0, currency: 'USD' } },
      { lineId: '2', sku: 'CAP-NVY', qty: 1, unitPrice: { amount: 22.0, currency: 'USD' } },
    ],
  });
  await expect.poll(async () => {
    const v = await view(api, orderId);
    const allocated = new Set(v.fulfilmentGroups.filter((g) => !/rejected|cancelled/i.test(g.status)).flatMap((g) => g.allocations.map((a) => a.lineId)));
    return allocated.has('1') && allocated.has('2') && /released|picking|packed|allocated|sourced/.test(v.order.status);
  }, { timeout: 60_000, message: 'waiting for both lines to be sourced and released' }).toBe(true);

  const first = `1Z${uid()}A`, second = `1Z${uid()}B`;
  await shipLine(api, orderId, '1', first);
  await expect.poll(async () => lineStatus(await view(api, orderId), '1'), { timeout: 30_000 }).toBe('shipped');
  await shipLine(api, orderId, '2', second);
  await expect.poll(async () => (await view(api, orderId)).order.status, { timeout: 30_000 }).toBe('shipped');
  expect((await view(api, orderId)).shipments.map((s) => s.tracking).sort()).toEqual([first, second].sort());

  // The first scan delivers line 1 only.
  await deliverParcel(orderId, first);
  await expect.poll(async () => (await view(api, orderId)).order.status, { timeout: 30_000 }).toBe('partially_delivered');
  let v = await view(api, orderId);
  expect(lineStatus(v, '1')).toBe('delivered');
  expect(lineStatus(v, '2')).toBe('shipped');
  const delivered = () => v.ledger.filter((e) => e.type === 'order.delivered');
  expect(delivered()).toHaveLength(1);
  expect(delivered()[0].data).toMatchObject({ lineIds: ['1'], allDelivered: false });

  const page = await pageAs('ops');
  await page.goto(`/orders/${orderId}`);
  await expect(page.getByLabel('status Partially delivered').first()).toBeVisible();
  await page.goto(`/orders/${orderId}?tab=shipments`);
  const rows = page.getByRole('row').filter({ hasText: /1Z/ });
  await expect(rows).toHaveCount(2);
  await expect(rows.filter({ hasText: first })).toContainText(/delivered/i);
  await expect(rows.filter({ hasText: second })).not.toContainText(/delivered/i);

  // The second scan delivers the rest, and the order is delivered.
  await deliverParcel(orderId, second);
  await expect.poll(async () => (await view(api, orderId)).order.status, { timeout: 30_000 }).toBe('delivered');
  v = await view(api, orderId);
  expect(lineStatus(v, '2')).toBe('delivered');
  expect(delivered()).toHaveLength(2);
  expect(delivered()[1].data).toMatchObject({ lineIds: ['2'], allDelivered: true });

  // B-2: each fulfilment group's last parcel carries groupDelivered=true (earlier parcels of the same group false), so
  // node and lane learners credit every group exactly once, whether the two lines shipped from one group or two.
  const byGroup = new Map<string, Array<Record<string, unknown>>>();
  for (const e of delivered()) {
    const group = String(e.data?.fulfilmentGroupId);
    byGroup.set(group, [...(byGroup.get(group) ?? []), e.data ?? {}]);
  }
  expect(byGroup.size, 'every delivery names its fulfilment group').toBeGreaterThan(0);
  for (const [group, events] of byGroup) {
    expect(events[events.length - 1].groupDelivered, `${group}: last parcel`).toBe(true);
    for (const earlier of events.slice(0, -1)) expect(earlier.groupDelivered, `${group}: an earlier parcel`).toBe(false);
  }
  const liveGroups = v.fulfilmentGroups.filter((g) => !/rejected|cancelled/i.test(g.status)).map((g) => g.id).sort();
  expect([...byGroup.keys()].sort()).toEqual(liveGroups);
  await page.reload();
  await expect(page.getByLabel('status Delivered').first()).toBeVisible();
  await expect(rows.filter({ hasText: second })).toContainText(/delivered/i);
});
