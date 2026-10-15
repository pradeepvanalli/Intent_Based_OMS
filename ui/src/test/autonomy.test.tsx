import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within, waitFor } from '@testing-library/react';
import { loginAsAdmin, renderWithProviders } from './utils';
import { AgenticOps } from '../pages/controlTower/AgenticOps';
import { Toasts } from '../components/Toasts';
import { mockRequest } from '../api/mock';
import type { Page, AutonomousAction } from '../api/types';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

const T = 15000;

describe('Agentic Control Tower', () => {
  it('shows the header stats and the seeded live feed with both an executed and an escalated card', async () => {
    loginAsAdmin();
    renderWithProviders(<AgenticOps />);
    await screen.findByText('Autonomous operations', {}, { timeout: T });
    expect((await screen.findAllByText(/Acted automatically/, {}, { timeout: T })).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Escalated to/).length).toBeGreaterThan(0);
    expect(screen.getByText('Autonomous actions today')).toBeInTheDocument();
    expect(screen.getByText('Automation rate')).toBeInTheDocument();
  }, T);

  it('shows an Undo button on an executed, undoable action and clicking it reverses it', async () => {
    loginAsAdmin();
    renderWithProviders(<><AgenticOps /><Toasts /></>);
    await screen.findAllByText(/Acted automatically/, {}, { timeout: T });
    const undoButtons = await screen.findAllByRole('button', { name: 'Undo' }, { timeout: T });
    expect(undoButtons.length).toBeGreaterThan(0);
    fireEvent.click(undoButtons[0]);
    await screen.findByText(/^undone/, {}, { timeout: T });
  }, T);

  it('run a scan now adds a fresh anomaly to the feed', async () => {
    loginAsAdmin();
    const before = await mockRequest<Page<unknown>>('GET', 'http://mock.local/api/v1/autonomy/anomalies');
    renderWithProviders(<><AgenticOps /><Toasts /></>);
    await screen.findByText('Autonomous operations', {}, { timeout: T });
    fireEvent.click(screen.getByRole('button', { name: 'Run a scan now' }));
    await screen.findByText(/Scan complete/, {}, { timeout: T });
    await waitFor(async () => {
      const after = await mockRequest<Page<unknown>>('GET', 'http://mock.local/api/v1/autonomy/anomalies');
      expect(after.total).toBeGreaterThan(before.total);
    }, { timeout: T });
  }, T);

  it('the kill switch asks for confirmation and, once engaged, shows as engaged', async () => {
    loginAsAdmin();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderWithProviders(<><AgenticOps /><Toasts /></>);
    await screen.findByText('Autonomous operations', {}, { timeout: T });
    const group = await screen.findByRole('group', { name: 'Autonomy kill switch' }, { timeout: T });
    const switchInput = within(group).getByRole('switch');
    expect(switchInput).not.toBeChecked();
    fireEvent.click(switchInput);
    expect(confirmSpy).toHaveBeenCalled();
    await screen.findByText('engaged', {}, { timeout: T });
    confirmSpy.mockRestore();
  }, T);

  it('toggling an allowed-action chip updates the guardrails', async () => {
    loginAsAdmin();
    renderWithProviders(<><AgenticOps /><Toasts /></>);
    await screen.findByText('Autonomous operations', {}, { timeout: T });
    const chip = await screen.findByRole('button', { name: /notify customer/i, pressed: true }, { timeout: T });
    fireEvent.click(chip);
    await screen.findByText(/Guardrails updated/, {}, { timeout: T });
    const g = await mockRequest<{ allowedActions: string[] }>('GET', 'http://mock.local/api/v1/autonomy/guardrails');
    expect(g.allowedActions).not.toContain('notify_customer');
  }, T);

  it('undo is refused for an action that is not reversible', async () => {
    // aut_demo2 is notify_customer, executed but never undoable
    await expect(mockRequest<AutonomousAction>('POST', 'http://mock.local/api/v1/autonomy/actions/aut_demo2/undo', { reason: 'test' })).rejects.toMatchObject({ problem: { status: 422 } });
  });
});
