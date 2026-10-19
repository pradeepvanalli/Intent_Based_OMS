import { test, expect } from '../support/fixtures';
import { apiAs } from '../support/api';
import { experiments, ok, seedStatus, type Experiment } from '../support/data';
import { toast } from '../support/ui';

test.describe('Experiments', () => {
  test('the seeded canary and holdout are listed in words, labelled as demo data', async ({ pageAs }) => {
    const ids = (await seedStatus(await apiAs('strategy'))).ids;
    const page = await pageAs('strategy');
    await page.goto('/learning?tab=experiments');
    const table = page.getByRole('table', { name: 'Experiments' });
    const canary = table.getByRole('row').filter({ hasText: 'SLA breach risk, new version' });
    await expect(canary).toContainText('Trial on some customers');
    await expect(canary).toContainText('10%');
    await expect(canary).toContainText(/running/i);
    await expect(canary.getByTestId('demo-badge')).toBeVisible();
    const holdout = table.getByRole('row').filter({ hasText: 'Everything learning changes (overall effect)' });
    await expect(holdout).toContainText('Holdout');
    await expect(holdout).toContainText('5%');
    await expect(holdout.getByTestId('demo-badge')).toBeVisible();

    // Detail drawer by deep link.
    await page.goto(`/learning?tab=experiments&experiment=${ids.canaryExperiment}`);
    const drawer = page.getByRole('dialog', { name: /SLA breach risk, new version/ });
    await expect(drawer).toContainText('A fixed share of customers sees the change');
    await expect(drawer).toContainText('So far');
    await expect(drawer).toContainText(/Compared with live version \d+; testing version \d+/);
    await expect(drawer).toContainText('The change being tested is: being tried on some customers');
    await expect(drawer.getByRole('button', { name: 'Complete and save result' })).toBeVisible();

    await page.goto(`/learning?tab=experiments&experiment=${ids.holdoutExperiment}`);
    const holdoutDrawer = page.getByRole('dialog', { name: 'Everything learning changes (overall effect)' });
    await expect(holdoutDrawer).toContainText('A small share of customers stays on the original behaviour');
    await expect(holdoutDrawer.getByRole('button', { name: 'Complete and save result' })).toHaveCount(0);
    await expect(holdoutDrawer.getByRole('button', { name: 'Stop' })).toHaveCount(0);
  });

  test('a shadow test started from a change, a second one refused in words, then completed; another stopped', async ({ pageAs }) => {
    const api = await apiAs('admin');
    const draft = (await seedStatus(api)).ids.draft;
    const running = async () => (await experiments(api)).find((e) => e.proposalId === draft && e.mode === 'SHADOW' && e.status === 'RUNNING');
    // Leave nothing running from an earlier attempt on the same database.
    for (const e of (await experiments(api)).filter((x) => x.proposalId === draft && x.status === 'RUNNING')) await api.post(`/api/v1/learning/experiments/${e.experimentId}/stop`, { data: { status: 'STOPPED' } });

    const page = await pageAs('admin');
    await page.goto(`/learning?tab=changes&change=${draft}`);
    const drawer = page.getByRole('dialog').filter({ hasText: 'What would change' });
    await drawer.getByRole('button', { name: 'Start shadow test' }).click();
    await expect(toast(page, 'Shadow test started')).toBeVisible();
    await expect.poll(async () => !!(await running())).toBe(true);

    await drawer.getByRole('button', { name: 'Start shadow test' }).click();
    const refusal = toast(page, 'This could not be done right now');
    await expect(refusal).toBeVisible();
    await expect(refusal).toContainText('only one per artifact');
    await expect(refusal).not.toContainText(/exception|java\./i);

    const shadow = (await running())!;
    await page.goto(`/learning?tab=experiments&experiment=${shadow.experimentId}`);
    const exp = page.getByRole('dialog', { name: /Fulfilment intent/ });
    await expect(exp).toContainText('Shadow test');
    await expect(exp).toContainText('Runs alongside the live decision');
    await expect(exp.getByTestId('demo-badge')).toHaveCount(0);
    await exp.getByRole('button', { name: 'Complete and save result' }).click();
    await expect(toast(page, 'Experiment completed')).toBeVisible();
    await expect.poll(async () => (await ok<{ experiment: Experiment }>(api.get(`/api/v1/learning/experiments/${shadow.experimentId}`))).experiment.status).toBe('COMPLETED');

    const second = await ok<Experiment>(api.post('/api/v1/learning/experiments', { data: { proposalId: draft, mode: 'SHADOW', trafficShare: 1, stopRule: {} } }));
    await page.goto(`/learning?tab=experiments&experiment=${second.experimentId}`);
    const exp2 = page.getByRole('dialog', { name: /Fulfilment intent/ });
    await exp2.getByRole('button', { name: 'Stop' }).click();
    await expect(toast(page, 'Experiment stopped')).toBeVisible();
    await expect.poll(async () => (await ok<{ experiment: Experiment }>(api.get(`/api/v1/learning/experiments/${second.experimentId}`))).experiment.status).toBe('STOPPED');
  });

  test('read-only roles see experiments but cannot start or stop them', async ({ pageAs }) => {
    const ids = (await seedStatus(await apiAs('ops'))).ids;
    const page = await pageAs('ops');
    await page.goto(`/learning?tab=experiments&experiment=${ids.canaryExperiment}`);
    const drawer = page.getByRole('dialog', { name: /SLA breach risk/ });
    await expect(drawer).toContainText('So far');
    await expect(drawer.getByRole('button', { name: 'Stop' })).toHaveCount(0);
    expect((await (await apiAs('ops')).post('/api/v1/learning/experiments', { data: { proposalId: ids.draft, mode: 'SHADOW', trafficShare: 1, stopRule: {} } })).status()).toBe(403);
  });
});
