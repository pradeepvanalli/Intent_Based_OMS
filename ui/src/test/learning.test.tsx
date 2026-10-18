import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { loginAsAdmin, renderWithProviders } from './utils';
import LearningConsole from '../pages/learning/LearningConsole';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

const T = 15000;

describe('Learning Console', () => {
  it('shows the header, learners with their autonomy level, and the draft proposal queue', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />);
    await screen.findByText('Learning', {}, { timeout: T });
    expect(await screen.findByText('node reliability', {}, { timeout: T })).toBeInTheDocument();
    expect(await screen.findByText(/prop_demo2/, {}, { timeout: T })).toBeInTheDocument();
    expect(screen.getByText('Running experiments')).toBeInTheDocument();
    expect(screen.getByText('Tenant objective')).toBeInTheDocument();
  }, T);

  it('shows the replay lift and confidence interval on an evaluated proposal', async () => {
    loginAsAdmin();
    renderWithProviders(<LearningConsole />);
    await screen.findByText(/prop_demo2/, {}, { timeout: T });
    fireEvent.click(screen.getByRole('tab', { name: 'evaluated' }));
    expect(await screen.findByText(/prop_demo1/, {}, { timeout: T })).toBeInTheDocument();
    expect(await screen.findByText(/Replay lift/, {}, { timeout: T })).toBeInTheDocument();
    expect(screen.getByText(/95% CI/)).toBeInTheDocument();
  }, T);
});
