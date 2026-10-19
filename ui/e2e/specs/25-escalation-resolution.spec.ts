import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { createOrder, ok } from '../support/data';
import { dialog, toast } from '../support/ui';

type Task = { id: string; type: string; status: string; title: string; payload: { decision?: string; recordId?: string; recommendedValue?: unknown; options?: Record<string, string> } };
type Record_ = { id: string; decision: string; value: unknown; labels?: { human_resolution?: Record<string, string> }; overriddenValue?: unknown };

/**
 * OC-06 / C2: a decision escalation opens in the Tasks drawer with the choices the server sent ({key: description},
 * read by escalationChoices), shows the recommendation, and resolving it with another value records that value as
 * the answer (label human_resolution.value) on the decision record the learners read.
 */
test('resolving a decision escalation with another value records the value chosen', async ({ pageAs }) => {
  const admin = await apiAs('admin');
  // The e2e profile has no AI keys, so a new order's fulfilment intent falls to the "unclear" escape option and escalates.
  const orderId = await createOrder(admin);
  let task: Task | undefined;
  await expect.poll(async () => {
    const page = await ok<{ items: Task[] }>(admin.get('/api/v1/tasks', { params: { subjectType: 'order', subjectId: orderId, status: 'OPEN,CLAIMED', limit: 20 } }));
    task = page.items.find((t) => t.type === 'decision_escalation' && t.payload.decision === 'fulfilment_intent') ?? page.items.find((t) => t.type === 'decision_escalation');
    return !!task;
  }, { timeout: 60_000, message: `waiting for a decision escalation on ${orderId}` }).toBe(true);
  const { recordId, recommendedValue, options } = task!.payload;
  expect(recordId, 'the escalation names its decision record').toBeTruthy();
  expect(options, 'choices arrive as {key: description}').toMatchObject({ accept: expect.any(String), override: expect.any(String) });

  const page = await pageAs('admin');
  await page.goto(`/tasks?task=${task!.id}`);
  const drawer = dialog(page, 'Task detail');
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('heading', { level: 2 })).toHaveText(task!.title);
  await expect(drawer).toContainText(`Recommended: ${String(recommendedValue)}`);
  await drawer.getByRole('button', { name: 'Claim' }).click();
  await expect(toast(page, 'Task claimed')).toBeVisible();

  const outcome = drawer.getByRole('combobox', { name: 'Outcome' });
  await expect(outcome).toBeEnabled();
  // Every choice the server offered, in its own words.
  for (const label of Object.values(options!)) await expect(outcome.locator('option', { hasText: label })).toHaveCount(1);
  await outcome.selectOption('override');
  const value = drawer.getByRole('textbox', { name: 'Value to use instead' });
  await expect(value).toBeVisible();
  const complete = drawer.getByRole('button', { name: 'Complete task' });
  await drawer.getByRole('textbox', { name: 'Reason (required, audited)' }).fill('Customer asked on the phone for everything in one box');
  await expect(complete, 'overriding needs the value chosen').toBeDisabled();
  await value.fill('ship_complete');
  await complete.click();
  await expect(toast(page, 'Task completed')).toBeVisible();

  // The chosen value lands on the decision record as the person's answer (through the outbox, so poll).
  let record: Record_ | undefined;
  await expect.poll(async () => {
    record = await ok<Record_>(admin.get(`/api/v1/decision-records/${recordId}`));
    return record.labels?.human_resolution?.value;
  }, { timeout: 30_000, message: 'human_resolution.value on the escalated record' }).toBe('ship_complete');
  expect(record!.labels!.human_resolution).toMatchObject({ outcome: 'override', recommendedValue: String(recommendedValue), resolvedBy: 'user:admin' });
  const done = await ok<Task>(admin.get(`/api/v1/tasks/${task!.id}`));
  expect(done.status).toBe('COMPLETED');
});
