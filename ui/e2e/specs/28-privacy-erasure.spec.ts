import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { ok } from '../support/data';

type Fact = { subjectType: string; subjectId: string; factKey: string; learner: string };
type Erasure = { pseudonym: string; firstTime: boolean; erased: boolean; erasedAt: string };

/**
 * GV-06 / N2 (API level): erasing a customer removes what learning knows about them, and a second request is
 * recognised as a repeat (firstTime false) rather than erasing again. The customer is one of the seeded demo history's
 * customers that still has learned preferences, so a re-run on the same database picks another one.
 */
test('erasing a customer twice: the second call is a repeat, and their learned facts are gone', async () => {
  const admin = await apiAs('admin');
  let customer = '';
  let before: Fact[] = [];
  for (let n = 300; n >= 1 && !customer; n--) {
    const ref = `cust-demo-${String(n).padStart(3, '0')}`;
    const facts = await ok<Fact[]>(admin.get('/api/v1/learning/facts', { params: { subjectType: 'CUSTOMER', subjectId: ref } }));
    if (facts.length > 0) { customer = ref; before = facts; }
  }
  expect(customer, 'a seeded customer with learned facts').not.toBe('');
  expect(before.every((f) => f.subjectId === customer)).toBe(true);

  // Only a privacy officer may erase (admin holds privacy.erase); a CS rep may not.
  expect((await (await apiAs('cs')).post(`/api/v1/admin/customers/${customer}/erase`)).status()).toBe(403);

  const first = await ok<Erasure>(admin.post(`/api/v1/admin/customers/${customer}/erase`));
  expect(first).toMatchObject({ firstTime: true, erased: true });
  expect(first.pseudonym).toMatch(/^cu_/);
  const second = await ok<Erasure>(admin.post(`/api/v1/admin/customers/${customer}/erase`));
  expect(second).toMatchObject({ firstTime: false, erased: true, pseudonym: first.pseudonym });

  // Learned facts about the customer are purged (the propagation runs after the shred event, so poll).
  await expect.poll(async () => (await ok<Fact[]>(admin.get('/api/v1/learning/facts', { params: { subjectType: 'CUSTOMER', subjectId: customer } }))).length,
    { timeout: 30_000, message: `learned facts for ${customer} after erasure` }).toBe(0);
  expect((await ok<Fact[]>(admin.get('/api/v1/learning/facts', { params: { subjectType: 'CUSTOMER', subjectId: first.pseudonym } }))).length, 'nor under the pseudonym').toBe(0);
});
