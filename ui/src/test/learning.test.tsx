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
});
