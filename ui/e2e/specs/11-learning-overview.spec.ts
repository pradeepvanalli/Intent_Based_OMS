import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { learners, ok, proposals } from '../support/data';
import { setSwitch } from '../support/ui';

/** "Is learning helping, what changed, what needs me": the Overview a first-time visitor lands on. */
test.describe('Learning overview', () => {
  test('headline tiles match what the API reports', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const all = await learners(api);
    const running = all.filter((l) => l.running).length;
    const waiting = (await proposals(api, 'DRAFT')).length + (await proposals(api, 'EVALUATED')).length + (await proposals(api, 'APPROVED')).length;
    const coverage = await ok<Record<string, number>>(api.get('/api/v1/learning/coverage'));
    const lowest = Math.min(...Object.values(coverage));

    const page = await pageAs('strategy');
    await page.goto('/learning');
    await expect(page.getByRole('heading', { level: 1, name: /Learning/ })).toBeVisible();
    const tabs = page.getByRole('tablist', { name: 'Learning sections' });
    for (const t of ['Overview', 'Learners', 'Changes', 'Experiments', 'Settings']) await expect(tabs.getByRole('tab', { name: new RegExp(`^${t}`) })).toBeVisible();

    await expect(page.getByText('Is learning helping?').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Changes waiting for you/ })).toContainText(String(waiting));
    await expect(page.getByRole('button', { name: /Learners running/ })).toContainText(`${running} of ${all.length}`);
    await expect(page.getByText('Outcomes coming back').first()).toBeVisible();
    await expect(page.locator('.learn-tiles')).toContainText(`${(lowest * 100).toFixed(0)}%`);
  });

  test('the lift table compares learning with the holdout in plain words', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const lift = await ok<{ windowDays: number; holdout?: { trafficShare: number }; metrics: Array<{ metric: string }> }>(api.get('/api/v1/learning/lift', { params: { days: 90 } }));
    expect(lift.windowDays).toBe(90);
    expect(lift.holdout, 'the demo seed starts a holdout').toBeTruthy();

    const page = await pageAs('strategy');
    await page.goto('/learning');
    const table = page.getByRole('table', { name: 'Lift by measure' });
    await expect(table).toBeVisible();
    await expect(page.getByText(/of customers have been in the holdout since/)).toBeVisible();
    const rows = table.locator('tbody tr');
    await expect(rows).toHaveCount(Math.max(1, lift.metrics.length));
    for (const row of await rows.all()) {
      await expect(row).toContainText(/Better with learning|Worse with learning|No clear difference yet|Not enough data yet|No outcomes have matured/);
    }
    // Raw metric keys and confidence intervals stay behind the technical toggle.
    await expect(table).not.toContainText(/CI \[/);
    await setSwitch(page, 'Show technical details', true);
    if (lift.metrics.length) await expect(table.locator('.mono').first()).toBeVisible();
    await setSwitch(page, 'Show technical details', false);
    await expect(table.locator('.mono')).toHaveCount(0);
  });

  test('"Needs you" counts lead to the right tab', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const paused = (await learners(api)).filter((l) => !l.running).length;
    const review = (await proposals(api, 'DRAFT')).length + (await proposals(api, 'EVALUATED')).length;

    const page = await pageAs('strategy');
    await page.goto('/learning');
    await expect(page.getByRole('heading', { name: 'Needs you' })).toBeVisible();
    await expect(page.getByRole('button', { name: `${review} changes to review` })).toBeVisible();
    await page.getByRole('button', { name: `${paused} learners paused or switched off` }).click();
    await expect(page).toHaveURL(/tab=learners/);
    await expect(page.getByRole('tab', { name: /^Learners/ })).toHaveAttribute('aria-selected', 'true');

    await page.getByRole('tab', { name: /^Overview/ }).click();
    await page.getByRole('button', { name: `${review} changes to review` }).click();
    await expect(page).toHaveURL(/tab=changes/);
  });

  test('coverage meters flag the decision below the floor, and its learner shows why it paused', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const coverage = await ok<Record<string, number>>(api.get('/api/v1/learning/coverage'));
    expect(coverage.address_risk, 'the demo seed leaves address_risk short of outcomes on purpose').toBeLessThan(0.8);

    const page = await pageAs('strategy');
    await page.goto('/learning');
    const low = page.getByRole('meter', { name: 'Address risk outcome coverage' });
    await expect(low).toBeVisible();
    expect(Number(await low.getAttribute('aria-valuenow'))).toBeCloseTo(coverage.address_risk, 2);
    await expect(page.getByRole('meter', { name: 'SLA breach risk outcome coverage' })).toHaveAttribute('aria-valuenow', String(Number(coverage.sla_breach_risk.toFixed(3))));

    await page.getByRole('tab', { name: /^Learners/ }).click();
    const tuner = page.getByTestId('learner-fraud_threshold_tuner');
    await expect(tuner).toContainText('Paused: outcome coverage for address_risk');
    await expect(tuner).toContainText('floor 80%');
  });

  test('"This week" summarises what went live and what changed', async ({ pageAs }) => {
    const api = await apiAs('strategy');
    const latest = await ok<{ digestId: string; summary: { headline?: string } }>(api.get('/api/v1/learning/digests/latest'));

    const page = await pageAs('strategy');
    await page.goto('/learning');
    const week = page.locator('section', { has: page.getByRole('heading', { name: 'This week' }) });
    await expect(week).toBeVisible();
    if (latest.summary.headline) await expect(week).toContainText(latest.summary.headline);
    for (const t of ['Went live', 'Rolled back', 'Learner changes']) await expect(week).toContainText(t);
    await expect(week).toContainText(/Covers .* to .* written/);
  });
});
