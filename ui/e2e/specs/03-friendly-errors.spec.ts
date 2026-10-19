import { test, expect } from '../support/fixtures';

test('a failing screen explains the problem in plain words and keeps the raw text under Technical details', async ({ pageAs }) => {
  const page = await pageAs('strategy');
  await page.route('**/api/v1/learning/learners', (route) => route.fulfill({
    status: 400,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 400, title: 'Bad Request', detail: "Could not resolve type id 'ThresholdChange' as a subtype of `ai.intentoms.learning.model.PolicyChange`" }),
  }));
  await page.goto('/learning?tab=learners');
  const box = page.getByRole('alert').filter({ hasText: 'Some details need fixing' }).first();
  await expect(box).toBeVisible();
  await expect(box.getByText('Check the values you entered and try again.')).toBeVisible();
  await expect(box.getByText(/type id/)).toBeHidden();
  await box.getByText('Technical details').click();
  await expect(box.getByText(/type id/)).toBeVisible();
});
