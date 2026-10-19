import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { loginAsAdmin, renderWithProviders } from './utils';
import LearningConsole from '../pages/learning/LearningConsole';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

const T = 15000;

describe('Learning Console: north star, guardrails, critic and live-use domains', () => {
  it('leads with the perfect-order rate and judges every guardrail over four weeks', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />);
    const headline = await screen.findByTestId('perfect-order-headline', {}, { timeout: T });
    expect(headline).toHaveTextContent(/87\.4%.*with learning vs.*84\.2%.*in the holdout/);
    expect(headline).toHaveTextContent(/95% interval/);
    const report = await screen.findByRole('table', { name: 'Guardrails with learning vs without' }, { timeout: T });
    for (const label of ['Override rate', 'Decision latency (p95)', 'Customer contact rate', 'Fraud false-positive rate', 'Perfect-order rate']) {
      expect(within(report).getByText(label)).toBeInTheDocument();
    }
    expect(within(report).getAllByText('Not worse').length).toBe(4);
    expect(within(report).getByText('Too little data')).toBeInTheDocument();
  }, T);

  it('shows the automatic critic review, the governing learner and the lift in reward, not percent', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=changes' });
    fireEvent.click(await screen.findByTestId('change-prop_demo1', {}, { timeout: T }));
    const drawer = await screen.findByRole('dialog', {}, { timeout: T });
    expect(await within(drawer).findByText(/Looks better: \+0\.060 reward/, {}, { timeout: T })).toBeInTheDocument();
    expect(within(drawer).queryByText(/Looks better: \+6/)).not.toBeInTheDocument();
    expect(within(drawer).getByTestId('critic-source')).toHaveTextContent(/AI critic.*automatically after testing/);
    expect(within(drawer).getByTestId('governing-learner')).toHaveTextContent('governed by Confidence band tuner');
  }, T);

  it('refuses a live-use range outside the family domain before sending it', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />, { route: '/?tab=changes' });
    fireEvent.click(await screen.findByRole('button', { name: 'Propose live use' }, { timeout: T }));
    const dialog = await screen.findByRole('dialog', { name: 'Propose using a learned value live' });
    await within(dialog).findByRole('option', { name: /from Early delay warning/ }, { timeout: T });
    fireEvent.change(within(dialog).getByLabelText('Learned value'), { target: { value: 'warn_early|monitoring.warn_early_threshold' } });
    expect(await within(dialog).findByTestId('live-domain', {}, { timeout: T })).toHaveTextContent('SLA breach risk score (1 some risk, 4 certain), from 1 to 4');
    fireEvent.change(within(dialog).getByLabelText('Highest allowed'), { target: { value: '10' } });
    expect(within(dialog).getByTestId('live-range-problems')).toHaveTextContent('max 10 is outside the values monitoring.warn_early_threshold can take');
    expect(within(dialog).getByRole('button', { name: 'Draft proposal' })).toBeDisabled();
    // B-1: a probability-scale minimum is refused, not just values above the top of the scale.
    fireEvent.change(within(dialog).getByLabelText('Lowest allowed'), { target: { value: '0.3' } });
    expect(within(dialog).getByTestId('live-range-problems')).toHaveTextContent('min 0.3 is outside the values monitoring.warn_early_threshold can take');
    fireEvent.change(within(dialog).getByLabelText('Highest allowed'), { target: { value: '3.5' } });
    fireEvent.change(within(dialog).getByLabelText('Lowest allowed'), { target: { value: '1.5' } });
    expect(within(dialog).queryByTestId('live-range-problems')).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Draft proposal' })).toBeEnabled();
  }, T);
});
