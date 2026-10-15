import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render } from '@testing-library/react';
import { setSession } from '../auth/session';

export function loginAsAdmin() {
  setSession('test-token', { type: 'USER', id: 'admin', tenantId: 'demo', roles: ['admin'], permissions: ['*'], trustTier: 3 });
}
export function renderWithProviders(ui: ReactNode, { route = '/' }: { route?: string } = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={qc}><MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter></QueryClientProvider>);
}
