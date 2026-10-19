import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, ok, orderView, shipAndDeliver } from '../support/data';
import { toast } from '../support/ui';

type Rma = { rma: string; status: string; refundTiming?: string };
type Record_ = { id: string; decision: string; labels?: { fraud_outcome?: { confirmed: boolean; kind: string; reasonCode?: string; provisional?: boolean } } };

const CONDITIONS = ['new', 'like_new', 'damaged', 'missing_parts', 'wrong_item', 'not_as_described'];

/**
 * FD-06 / C1: the inspection of a returned item offers every graded condition, and an inspection that finds the
 * wrong item labels the return's risk decision as confirmed return fraud (the label the fraud learners read).
 */
test('an inspection that finds the wrong item labels the return as fraud', async ({ pageAs }) => {
  const admin = await apiAs('admin');
  const orderId = await createOrder(admin);
  await shipAndDeliver(admin, orderId);
  await expect.poll(async () => (await orderView(admin, orderId)).order.status, { timeout: 30_000 }).toBe('delivered');

  // No receipt: the risk score is not confident enough to refund at once, so the return waits for a person.
  const created = await ok<Rma>(admin.post('/api/v1/returns', {
    data: { orderId, channel: 'web', receiptPresent: false, lines: [{ lineId: '1', qty: 1, reasonCode: 'wrong_size', customerReasonText: 'Too small' }] },
  }), 200, 201);
  const decided = await ok<Rma>(admin.post(`/api/v1/returns/${created.rma}/decide`, { data: {} }));
  if (decided.status === 'requested') {
    await ok(admin.post(`/api/v1/returns/${created.rma}/review`, { data: { refundTiming: 'on_inspection', reason: 'e2e: inspect before refunding' } }));
  }
  const view = await orderView(admin, orderId);
  const received = await ok<Rma>(admin.post(`/api/v1/returns/${created.rma}/receive`, { data: { nodeId: view.fulfilmentGroups?.[0]?.nodeId ?? 'DC-NJ' } }));
  expect(received.status).toBe('received');

  const page = await pageAs('admin');
  await page.goto(`/returns/${created.rma}`);
  const condition = page.getByRole('combobox', { name: 'Condition' });
  await expect(condition).toBeVisible();
  expect(await condition.locator('option').evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value))).toEqual(CONDITIONS);
  await condition.selectOption({ label: 'wrong item' });
  await page.getByRole('button', { name: 'Record inspection' }).click();
  await expect(toast(page, 'Inspection recorded')).toBeVisible();

  // The return's risk decision now carries a confirmed fraud label from the grading.
  let label: NonNullable<Record_['labels']>['fraud_outcome'];
  await expect.poll(async () => {
    const records = await ok<Record_[]>(admin.get('/api/v1/decision-records', { params: { subjectType: 'return', subjectId: created.rma } }));
    label = records.find((r) => r.decision === 'return_risk')?.labels?.fraud_outcome;
    return label?.confirmed;
  }, { timeout: 30_000, message: 'fraud_outcome on the return_risk record' }).toBe(true);
  expect(label).toMatchObject({ confirmed: true, kind: 'return_grading', reasonCode: 'wrong_item' });
  expect(label!.provisional ?? false).toBe(false);
});
