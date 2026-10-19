import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, ok, orderView, shipAndDeliver } from '../support/data';

type Rma = { rma: string; status: string; refundTiming?: string };
type Label = { value?: string; outcome?: string; reasonCode?: string; recommendedValue?: string; resolvedBy?: string; resolvedByType?: string };
type Record_ = { id: string; decision: string; layer: string; value: unknown; labels?: { human_resolution?: Label } };
type Task = { id: string; type: string; status: string; payload: { recordId?: string; decision?: string } };

/**
 * OC-06 (audit C-N5): a risky return escalates its return_risk decision and opens a refund-timing review. Answering
 * the review is the answer to that decision: its decision-escalation task is closed and the record is labelled with the
 * person's answer, the outcome, the reason, the recommendation and who resolved it (a person). Before, the escalation
 * stayed open and unlabelled after the return was received, inspected and refunded.
 */
test('answering a return review resolves and labels the return_risk escalation', async () => {
  const admin = await apiAs('admin');
  const orderId = await createOrder(admin);
  await shipAndDeliver(admin, orderId);
  await expect.poll(async () => (await orderView(admin, orderId)).order.status, { timeout: 30_000 }).toBe('delivered');

  // No receipt: the risk score is not confident enough to refund at once, so the return waits for a person.
  const created = await ok<Rma>(admin.post('/api/v1/returns', {
    data: { orderId, channel: 'web', receiptPresent: false, lines: [{ lineId: '1', qty: 1, reasonCode: 'wrong_size', customerReasonText: 'Too small' }] },
  }), 200, 201);
  const decided = await ok<Rma>(admin.post(`/api/v1/returns/${created.rma}/decide`, { data: {} }));
  expect(decided.status, 'the return waits for a person').toBe('requested');

  const risk = (await ok<Record_[]>(admin.get('/api/v1/decision-records', { params: { subjectType: 'return', subjectId: created.rma } }))).find((r) => r.decision === 'return_risk');
  expect(risk, 'the return_risk decision of this return').toBeTruthy();
  expect(risk!.layer, 'it escalated to a person').toBe('L3_HUMAN');
  let escalation: Task | undefined;
  await expect.poll(async () => {
    const open = await ok<{ items: Task[] }>(admin.get('/api/v1/tasks', { params: { subjectType: 'return', subjectId: created.rma, status: 'OPEN,CLAIMED', limit: 20 } }));
    escalation = open.items.find((t) => t.type === 'decision_escalation' && t.payload.decision === 'return_risk');
    return !!escalation;
  }, { timeout: 30_000, message: 'the return_risk decision-escalation task is open' }).toBe(true);

  await ok(admin.post(`/api/v1/returns/${created.rma}/review`, { data: { refundTiming: 'on_inspection', reason: 'e2e: inspect before refunding' } }));

  let label: Label | undefined;
  await expect.poll(async () => {
    label = (await ok<Record_>(admin.get(`/api/v1/decision-records/${risk!.id}`))).labels?.human_resolution;
    return label?.resolvedByType;
  }, { timeout: 30_000, message: 'human_resolution on the return_risk record' }).toBe('person');
  expect(label).toMatchObject({ reasonCode: 'e2e: inspect before refunding', resolvedBy: 'user:admin' });
  expect(Number(label!.recommendedValue), 'the model\'s score is the recommendation').toBe(Number(risk!.value));
  expect(['accept', 'override']).toContain(label!.outcome);
  expect(label!.value, 'the answer, in return_risk\'s own terms').toBeTruthy();
  const done = await ok<Task>(admin.get(`/api/v1/tasks/${escalation!.id}`));
  expect(done.status, 'the escalation task is closed by the review').toBe('COMPLETED');
});
