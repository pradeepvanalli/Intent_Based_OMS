import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import Showcase from './Showcase';
import { CATALOGUE } from './catalogue';
import { loginAsAdmin, renderWithProviders } from '../../test/utils';

vi.mock('../../api/client', async (orig) => ({ ...(await orig<typeof import('../../api/client')>()), MOCK: true }));

describe('Showcase page', () => {
  it('renders every catalogue card with its command and deep links', () => {
    loginAsAdmin();
    renderWithProviders(<Showcase />, { route: '/showcase' });
    expect(screen.getByRole('heading', { level: 1, name: 'Showcase' })).toBeInTheDocument();
    for (const c of CATALOGUE) {
      const card = screen.getByTestId(`showcase-${c.id}`);
      expect(card).toHaveTextContent(c.title);
      if (c.command) expect(card).toHaveTextContent(c.command);
    }
    expect(screen.getAllByRole('link', { name: 'Orders UC1-*' })[0]).toHaveAttribute('href', '/orders?q=UC1-');
  });
  it('disables reference-tenant links until you switch tenant', () => {
    loginAsAdmin();   // tenant demo
    renderWithProviders(<Showcase />, { route: '/showcase' });
    const retail = screen.getByTestId('showcase-ref-retail');
    expect(retail).toHaveTextContent('Switch tenant');
    expect(retail.querySelectorAll('a.btn').length).toBe(0);
    expect(retail.querySelectorAll('[aria-disabled="true"]').length).toBe(4);
    const uc1 = screen.getByTestId('showcase-uc1');
    expect(uc1.querySelectorAll('a.btn').length).toBeGreaterThan(0);
  });
});
