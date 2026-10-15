import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { AppShell } from '../shell/AppShell';
import { loginAsAdmin, renderWithProviders } from './utils';
import { setSession } from '../auth/session';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

describe('AppShell', () => {
  it('renders grouped navigation, tenant and user for an admin', async () => {
    loginAsAdmin();
    renderWithProviders(<Routes><Route element={<AppShell />}><Route path="/" element={<div>home</div>} /></Route></Routes>);
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
    for (const label of ['Control Tower', 'Orders', 'Tasks', 'Exceptions', 'Returns', 'Policy Studio', 'State Models', 'Agents', 'Simulation', 'Analytics', 'Settings']) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toBeInTheDocument();
    }
    expect(screen.getByText('demo', { exact: false })).toBeInTheDocument();
    expect(screen.getByLabelText('Global search')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /toggle theme/i })).toBeInTheDocument();
  });
  it('hides admin-only nav items for a CS rep', () => {
    setSession('t', { type: 'USER', id: 'cs', tenantId: 'demo', roles: ['cs_rep'], permissions: ['order.read', 'task.read', 'decision.read'], trustTier: 3 });
    renderWithProviders(<Routes><Route element={<AppShell />}><Route path="/" element={<div>home</div>} /></Route></Routes>);
    expect(screen.queryByRole('link', { name: /Settings/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Orders/ })).toBeInTheDocument();
  });
});
