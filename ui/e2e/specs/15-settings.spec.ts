import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { ok, uid } from '../support/data';
import { toast } from '../support/ui';

/** How the UI names an actor (mirrors copy.ts personName). */
const person = (ref: string): string => ref === 'system:demo-seed' ? 'demo data'
  : ref.startsWith('system:demo-seed.') ? `${ref.slice('system:demo-seed.'.length) === 'strategy' ? 'strategy lead' : ref.slice('system:demo-seed.'.length)} (demo)`
  : ref.replace(/^(user|system):/, '');

type Objective = { version: number; status: string; weights: Record<string, number>; constraints: Record<string, unknown>; createdBy: string; approvedBy?: string; sourceText?: string; draftMethod?: string };

/**
 * Settings: the objective profile (draft by one person, approved by another), suggested test cases, fairness, weekly
 * summaries and retention. The objective test ends by approving a new version with exactly the weights and constraints
 * that were in use before it ran, so later specs and re-runs see the same objective content (with a higher version).
 */
test.describe('Settings', () => {
  test('the objective in use is shown with its approver and freeze window', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const live = await ok<Objective>(api.get('/api/v1/learning/objective'));
    const history = await ok<Objective[]>(api.get('/api/v1/learning/objective/history', { params: { limit: 50 } }));
    const v1 = history.find((h) => h.version === 1);
    expect(v1?.createdBy, 'seeded v1 drafted by the demo strategy lead').toBe('system:demo-seed.strategy');
    expect(v1?.approvedBy, 'and approved by a different (demo) person').toBe('system:demo-seed.admin');

    const page = await pageAs('strategy');
    await page.goto('/learning?tab=settings');
    await expect(page.getByText(`In use: version ${live.version}`)).toBeVisible();
    await expect(page.getByText(`approved by ${person(live.approvedBy!)}`, { exact: false }).first()).toBeVisible();
    await expect(page.getByText('No automatic changes during:')).toBeVisible();
    await expect(page.getByRole('meter', { name: 'Deliver on time weight' })).toBeVisible();
  });

  test('a draft needs a different person to approve it; plain-English and slider drafts both work', async ({ pageAs }) => {
    const strategyApi = await apiAs('strategy');
    const adminApi = await apiAs('admin');
    const before = await ok<Objective>(strategyApi.get('/api/v1/learning/objective'));
    const text = `Peak season ${uid()}: on-time delivery matters most, cost matters less, never accept more fraud`;
    try {
      const strategy = await pageAs('strategy');
      await strategy.goto('/learning?tab=settings');
      await strategy.getByRole('textbox', { name: 'Describe what matters' }).fill(text);
      await strategy.getByRole('button', { name: 'Draft from description' }).click();
      await expect(toast(strategy, /Draft version \d+ created/)).toBeVisible();
      const fromText = (await ok<Objective[]>(strategyApi.get('/api/v1/learning/objective/drafts'))).find((d) => d.sourceText === text)!;
      expect(fromText, 'the plain-English draft is stored with its source text').toBeTruthy();
      expect(['llm', 'template']).toContain(fromText.draftMethod);
      const card = strategy.getByTestId(`objective-draft-${fromText.version}`);
      await expect(card).toContainText(text);
      await expect(card.getByRole('button', { name: 'Approve and use' })).toBeDisabled();
      await expect(card).toContainText('You drafted this; someone else must approve');
      const self = await strategyApi.post(`/api/v1/learning/objective/${fromText.version}/approve`);
      expect(self.status()).toBe(422);

      await strategy.getByText('Or adjust the weights yourself').click();
      await strategy.getByRole('button', { name: 'Speed first' }).click();
      await strategy.getByRole('button', { name: 'Save as draft' }).click();
      let slider: Objective | undefined;
      await expect.poll(async () => {
        const drafts = await ok<Objective[]>(strategyApi.get('/api/v1/learning/objective/drafts'));
        slider = drafts.filter((d) => d.version > fromText.version && d.createdBy === 'user:strategy' && d.weights.otd === 1.6 && d.weights.split === 0.5).sort((a, b) => b.version - a.version)[0];
        return !!slider;
      }, { message: 'the slider draft carries the speed-first weights' }).toBe(true);
      await expect(toast(strategy, `Draft version ${slider!.version} created`)).toBeVisible();

      const admin = await pageAs('admin');
      await admin.goto('/learning?tab=settings');
      await admin.getByTestId(`objective-draft-${slider!.version}`).getByRole('button', { name: 'Approve and use' }).click();
      await expect(toast(admin, `Version ${slider!.version} is now in use`)).toBeVisible();
      await expect(admin.getByText(`In use: version ${slider!.version}`)).toBeVisible();
      expect((await ok<Objective>(adminApi.get('/api/v1/learning/objective'))).approvedBy).toBe('user:admin');
    } finally {
      // Restore the content that was in use before: admin drafts it, strategy approves it.
      const restore = await ok<Objective>(adminApi.put('/api/v1/learning/objective', { data: { weights: before.weights, constraints: before.constraints } }));
      await ok(strategyApi.post(`/api/v1/learning/objective/${restore.version}/approve`));
      const now = await ok<Objective>(adminApi.get('/api/v1/learning/objective'));
      expect(now.weights).toEqual(before.weights);
    }
  });

  test('suggested test cases can be signed off or dismissed', async ({ pageAs }) => {
    const api = await apiAs('admin');
    type Candidate = { hypothesisId: string; decision: string; expectedValue: string };
    const list = await ok<Candidate[]>(api.get('/api/v1/learning/golden-candidates'));
    test.skip(list.length < 2, 'needs two seeded golden-case candidates (a fresh e2e database has three)');

    const page = await pageAs('admin');
    await page.goto('/learning?tab=settings');
    const section = page.locator('section', { has: page.getByRole('heading', { name: /Suggested test cases/ }) });
    await expect(section).toContainText(`should answer ${list[0].expectedValue}`);
    await section.locator('.subtle-list > div').first().getByRole('button', { name: 'Sign off' }).click();
    await expect(toast(page, 'Added to the test cases')).toBeVisible();
    await expect.poll(async () => (await ok<Candidate[]>(api.get('/api/v1/learning/golden-candidates'))).length).toBe(list.length - 1);
    await section.locator('.subtle-list > div').first().getByRole('button', { name: 'Dismiss' }).click();
    await expect(toast(page, 'Dismissed')).toBeVisible();
    await expect.poll(async () => (await ok<Candidate[]>(api.get('/api/v1/learning/golden-candidates'))).length).toBe(list.length - 2);
  });

  test('fairness report, weekly summary and retention dry run', async ({ pageAs }) => {
    const api = await apiAs('admin');
    const digestsBefore = (await ok<unknown[]>(api.get('/api/v1/learning/digests', { params: { limit: 50 } }))).length;
    const page = await pageAs('admin');
    await page.goto('/learning?tab=settings');

    await page.getByRole('combobox', { name: 'Decision to check' }).selectOption({ label: 'Payment risk' });
    const fairness = page.locator('section', { has: page.getByRole('heading', { name: /Fairness check/ }) });
    await expect(fairness.getByRole('table', { name: 'Fairness by customer group' }).or(fairness.getByText('Not enough decisions yet'))).toBeVisible();

    await page.getByRole('button', { name: 'Write one now' }).click();
    await expect(toast(page, 'Weekly summary written')).toBeVisible();
    await expect.poll(async () => (await ok<unknown[]>(api.get('/api/v1/learning/digests', { params: { limit: 50 } }))).length).toBe(digestsBefore + 1);

    await page.getByRole('button', { name: 'Check what would be removed' }).click();
    await expect(page.getByText(/Keeping 400 days/)).toBeVisible();
  });

  test('read-only roles see the objective but no editing, retention or summary controls', async ({ pageAs }) => {
    const page = await pageAs('ops');
    await page.goto('/learning?tab=settings');
    await expect(page.getByText(/In use: version \d+/)).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Describe what matters' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Write one now' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Check what would be removed' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Approve and use' })).toHaveCount(0);
  });
});
