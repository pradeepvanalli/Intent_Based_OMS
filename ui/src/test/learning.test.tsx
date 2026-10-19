import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { loginAsAdmin, renderWithProviders } from './utils';
import LearningConsole from '../pages/learning/LearningConsole';
import { setSession } from '../auth/session';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

const T = 15000;
const tab = (name: string) => screen.getByRole('tab', { name: new RegExp(`^${name}`) });

describe('Learning Console', () => {
  it('opens on an overview that answers "is it helping" and "what needs me" in plain words', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />);
    expect(await screen.findByRole('heading', { level: 1, name: /Learning/ }, { timeout: T })).toBeInTheDocument();
    expect(screen.getByText('Is learning helping?', { selector: '.label' })).toBeInTheDocument();
    const lift = await screen.findByRole('table', { name: 'Lift by measure' }, { timeout: T });
    expect(within(lift).getByText('On-time delivery')).toBeInTheDocument();
    expect(within(lift).getAllByText('Better with learning').length).toBeGreaterThan(0);
    expect(await screen.findByRole('button', { name: /changes to review/ }, { timeout: T })).toBeInTheDocument();
    expect(await screen.findByRole('meter', { name: 'SLA breach risk outcome coverage' }, { timeout: T })).toBeInTheDocument();
    // jargon stays hidden until asked for
    expect(screen.queryByText(/ips_doubly_robust/)).not.toBeInTheDocument();
  }, T);

  it('lists learners by area with their autonomy in words and shows a paused learner', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=learners' });
    expect(await screen.findByRole('heading', { level: 3, name: 'Fraud and abuse' }, { timeout: T })).toBeInTheDocument();
    const row = await screen.findByTestId('learner-node_reliability', {}, { timeout: T });
    expect(within(row).getByText('Node reliability')).toBeInTheDocument();
    expect((within(row).getByRole('combobox', { name: /How far Node reliability may go/ }) as HTMLSelectElement).value).toBe('L2_ACT_WITH_APPROVAL');
    expect(within(row).getByRole('option', { name: 'Acts with your approval' })).toBeInTheDocument();
    const paused = screen.getByTestId('learner-warn_early');
    expect(within(paused).getByText(/Paused: outcome coverage/)).toBeInTheDocument();
    expect(within(paused).getByRole('button', { name: 'Resume' })).toBeInTheDocument();
  }, T);

  it('pauses all learning only after a confirmation inside the page', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=learners' });
    fireEvent.click(await screen.findByRole('button', { name: 'Pause all learning' }, { timeout: T }));
    const dialog = await screen.findByRole('dialog', { name: 'Pause all learning?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Pause all learning' }));
    expect(await screen.findByText('All learning is paused.', {}, { timeout: T })).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: /^Resume/ })[0]);
    await screen.findByRole('button', { name: 'Pause all learning' }, { timeout: T });
  }, T);

  it('opens a change card with replay result, second opinion and cumulative diff, and explains author-cannot-approve', async () => {
    setSession('test-token', { type: 'USER', id: 'strategy', tenantId: 'demo', roles: ['strategy_lead'], permissions: ['*'], trustTier: 3 });
    renderWithProviders(<LearningConsole />, { route: '/?tab=changes' });
    fireEvent.click(await screen.findByTestId('change-prop_demo1', {}, { timeout: T }));
    const drawer = await screen.findByRole('dialog', {}, { timeout: T });
    expect(await within(drawer).findByText(/Looks better/, {}, { timeout: T })).toBeInTheDocument();
    expect(within(drawer).getByText('Looks safe')).toBeInTheDocument();
    expect(within(drawer).getByRole('table', { name: 'If this goes live' })).toBeInTheDocument();
    expect(within(drawer).getByText(/Last approved by/)).toBeInTheDocument();
    fireEvent.click(within(drawer).getByRole('button', { name: 'Close' }));

    fireEvent.click(await screen.findByTestId('change-prop_demo2', {}, { timeout: T }));
    expect(await screen.findByText(/You suggested this change, so someone else needs to approve it/, {}, { timeout: T })).toBeInTheDocument();
  }, T * 2);

  it('asks for a postal reason code before approving a postal-code change', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=changes&change=prop_demo10' });
    const approve = await screen.findByRole('button', { name: 'Approve' }, { timeout: T });
    fireEvent.click(approve);
    const dialog = await screen.findByRole('dialog', { name: 'Approve a postal-code change' });
    const confirm = within(dialog).getByRole('button', { name: 'Approve' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason code/ }), { target: { value: 'carrier_service_gap' } });
    expect(confirm).not.toBeDisabled();
  }, T);

  it('shows live learned values with a switch-off action and lets you propose live use', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=changes' });
    expect(await screen.findByText('Store and warehouse reject rates', {}, { timeout: T })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Switch off Store and warehouse reject rates' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Propose live use' }));
    const dialog = await screen.findByRole('dialog', { name: 'Propose using a learned value live' });
    expect(await within(dialog).findByRole('option', { name: /from Cart hold time/ }, { timeout: T })).toBeInTheDocument();
  }, T);

  it('shows experiments in words and the objective draft that needs someone else to approve', async () => {
    setSession('test-token', { type: 'USER', id: 'strategy', tenantId: 'demo', roles: ['strategy_lead'], permissions: ['*'], trustTier: 3 });
    renderWithProviders(<LearningConsole />, { route: '/?tab=experiments' });
    const table = await screen.findByRole('table', { name: 'Experiments' }, { timeout: T });
    expect(within(table).getAllByText('Trial on some customers').length).toBeGreaterThan(0);
    expect(within(table).getByText('Everything learning changes (overall effect)')).toBeInTheDocument();

    fireEvent.click(tab('Settings'));
    const draft = await screen.findByTestId('objective-draft-3', {}, { timeout: T });
    expect(within(draft).getByRole('button', { name: 'Approve and use' })).toBeDisabled();
    expect(within(draft).getByText(/someone else must approve/)).toBeInTheDocument();
  }, T);

  it('only offers what the change status allows: a draft is tested before approval, a live change can only be rolled back', async () => {
    loginAsAdmin();
    const view = renderWithProviders(<LearningConsole />, { route: '/?tab=changes&change=prop_demo3' });
    let drawer = await screen.findByRole('dialog', {}, { timeout: T });
    const approve = await within(drawer).findByRole('button', { name: 'Approve' }, { timeout: T });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAttribute('title', 'Test it on past decisions before approving');
    expect(within(drawer).getByRole('button', { name: 'Test on past decisions' })).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: /Go live/ })).not.toBeInTheDocument();
    view.unmount();

    renderWithProviders(<LearningConsole />, { route: '/?tab=changes&change=prop_demo7' });
    drawer = await screen.findByRole('dialog', {}, { timeout: T });
    expect(await within(drawer).findByRole('button', { name: 'Roll back' }, { timeout: T })).toBeInTheDocument();
    for (const name of ['Approve', 'Reject', 'Test again', 'Test on past decisions', 'Go live', 'Start shadow test']) {
      expect(within(drawer).queryByRole('button', { name })).not.toBeInTheDocument();
    }
  }, T * 2);

  it('asks for a written reason before a change with weak evidence goes live, and records it', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=changes&change=prop_demo13' });
    const drawer = await screen.findByRole('dialog', {}, { timeout: T });
    expect(await within(drawer).findByTestId('promotion-concerns', {}, { timeout: T })).toHaveTextContent(/no shadow run has passed/);
    fireEvent.click(within(drawer).getByRole('button', { name: 'Go live anyway…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Go live without full evidence?' });
    expect(within(dialog).getByText(/no shadow run has passed/)).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Go live anyway' });
    const reason = within(dialog).getByRole('textbox', { name: /Why go live anyway/ });
    fireEvent.change(reason, { target: { value: 'too short' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(reason, { target: { value: 'Pilot agreed with the operations lead for one week' } });
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);
    expect(await screen.findByTestId('promotion-note', {}, { timeout: T })).toHaveTextContent(/Pilot agreed with the operations lead/);
  }, T * 2);

  it('lets a configuration admin change a learner’s limits', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=learners' });
    const budget = await screen.findByTestId('budget-node_reliability', {}, { timeout: T });
    fireEvent.click(within(budget).getByRole('button', { name: 'Change limits for Node reliability' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change limits for Node reliability' });
    fireEvent.change(within(dialog).getByRole('spinbutton', { name: /Largest move per change/ }), { target: { value: '0.02' } });
    fireEvent.change(within(dialog).getByRole('spinbutton', { name: /Changes per policy per week/ }), { target: { value: '2' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save limits' }));
    expect(await within(await screen.findByTestId('budget-node_reliability')).findByText(/Moves at most 0.02 · 2 changes a week per target/, {}, { timeout: T })).toBeInTheDocument();
  }, T);

  it('lets only a platform administrator stop learning for every tenant', async () => {
    setSession('test-token', { type: 'USER', id: 'ops', tenantId: 'demo', roles: ['admin'], permissions: ['learning.read', 'learning.admin', 'config.admin'], trustTier: 3 });
    const view = renderWithProviders(<LearningConsole />, { route: '/?tab=learners' });
    await screen.findByRole('button', { name: 'Pause all learning' }, { timeout: T });
    expect(screen.queryByRole('button', { name: 'Stop learning for every tenant' })).not.toBeInTheDocument();
    view.unmount();

    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=learners' });
    fireEvent.click(await screen.findByRole('button', { name: 'Stop learning for every tenant' }, { timeout: T }));
    const dialog = await screen.findByRole('dialog', { name: 'Stop learning for every tenant?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stop learning everywhere' }));
    const banner = await screen.findByTestId('global-kill-switch', {}, { timeout: T });
    expect(banner).toHaveTextContent(/Learning is stopped for every tenant/);
    fireEvent.click(within(banner).getByRole('button', { name: 'Restart learning' }));
    await screen.findByRole('button', { name: 'Stop learning for every tenant' }, { timeout: T });
  }, T * 2);
});
