import { describe, expect, it, vi, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import LoginPage from '../pages/Login';
import { renderWithProviders } from './utils';
import { authApi } from '../api/auth';
import { ApiError } from '../api/client';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

const TENANTS = [
  { tenantId: 'demo', name: 'Intent OMS demo', vertical: 'Omnichannel demo', description: 'The original demo tenant.' },
  { tenantId: 'retail', name: 'Maple & Main General Store', vertical: 'General retail', description: 'Two DCs, six stores, one drop-ship vendor.' },
];

describe('LoginPage tenant picker', () => {
  beforeEach(() => { vi.restoreAllMocks(); });

  it('offers the seeded tenants as a select with name and vertical, and keeps the demo-user hint', async () => {
    vi.spyOn(authApi, 'publicTenants').mockResolvedValue(TENANTS);
    renderWithProviders(<Routes><Route path="/" element={<LoginPage />} /></Routes>);
    const select = await screen.findByRole('combobox', { name: 'Tenant' }) as HTMLSelectElement;
    expect(select.tagName).toBe('SELECT');
    expect(screen.getByRole('option', { name: /Maple & Main General Store · General retail \(retail\)/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /Intent OMS demo · Omnichannel demo \(demo\)/ })).toBeInTheDocument();
    expect(select.value).toBe('demo');
    fireEvent.change(select, { target: { value: 'retail' } });
    expect(select.value).toBe('retail');
    expect(screen.getByText('Two DCs, six stores, one drop-ship vendor.')).toBeInTheDocument();
    expect(screen.getByText(/password equals user name/)).toBeInTheDocument();
    expect(screen.getByText('retail', { selector: 'code' })).toBeInTheDocument();
    for (const u of ['admin', 'ops', 'cs', 'risk', 'finance', 'strategy']) expect(screen.getByRole('button', { name: new RegExp(`^${u}`) })).toBeInTheDocument();
  });

  it('falls back to the free-text tenant input when the public list fails', async () => {
    vi.spyOn(authApi, 'publicTenants').mockRejectedValue(new ApiError({ status: 404, title: 'not_found' }));
    renderWithProviders(<Routes><Route path="/" element={<LoginPage />} /></Routes>);
    await waitFor(() => expect(authApi.publicTenants).toHaveBeenCalled());
    const input = screen.getByLabelText('Tenant') as HTMLInputElement;
    expect(input.tagName).toBe('INPUT');
    expect(input.value).toBe('demo');
  });

  it('falls back to the free-text tenant input when the list is empty', async () => {
    vi.spyOn(authApi, 'publicTenants').mockResolvedValue([]);
    renderWithProviders(<Routes><Route path="/" element={<LoginPage />} /></Routes>);
    await waitFor(() => expect(authApi.publicTenants).toHaveBeenCalled());
    expect((screen.getByLabelText('Tenant') as HTMLInputElement).tagName).toBe('INPUT');
  });

  it('logs in against the selected tenant', async () => {
    vi.spyOn(authApi, 'publicTenants').mockResolvedValue(TENANTS);
    const login = vi.spyOn(authApi, 'login').mockResolvedValue({ token: 't', principal: { type: 'USER', id: 'ops', tenantId: 'retail', roles: ['ops_analyst'], permissions: ['order.read'], trustTier: 3 } });
    renderWithProviders(<Routes><Route path="/" element={<LoginPage />} /><Route path="/control-tower" element={<div>tower</div>} /></Routes>);
    const select = await screen.findByRole('combobox', { name: 'Tenant' });
    fireEvent.change(select, { target: { value: 'retail' } });
    fireEvent.click(screen.getByRole('button', { name: /^ops/ }));
    await waitFor(() => expect(login).toHaveBeenCalledWith({ tenantId: 'retail', userId: 'ops', password: 'ops' }));
    expect(await screen.findByText('tower')).toBeInTheDocument();
  });
});
