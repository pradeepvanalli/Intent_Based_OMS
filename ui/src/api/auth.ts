import { api } from './client';
import type { IssuedKey, LoginRequest, LoginResponse, Principal, PrincipalType } from './types';

/** One seeded tenant as `GET /api/v1/public/tenants` lists it (unauthenticated; only when the demo seed is on). */
export interface PublicTenant { tenantId: string; name: string; vertical?: string | null; description?: string | null; }

export const authApi = {
  login: (req: LoginRequest) => api.post<LoginResponse>('/api/v1/auth/login', req, { silent: true }),
  me: () => api.get<Principal>('/api/v1/auth/me'),
  /** Tenants the login page can offer; empty when the deployment does not expose a list (production). */
  publicTenants: () => api.get<PublicTenant[]>('/api/v1/public/tenants', { silent: true }),
  issueKey: (req: { subjectId: string; type?: PrincipalType; roles?: string[]; permissions?: string[]; trustTier?: number; label?: string }) => api.post<IssuedKey>('/api/v1/auth/api-keys', req),
  revokeKey: (keyId: string) => api.del<void>(`/api/v1/auth/api-keys/${encodeURIComponent(keyId)}`),
};
