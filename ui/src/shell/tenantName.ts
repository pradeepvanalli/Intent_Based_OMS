import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useSession } from '../auth/session';

interface TenantRow { tenant_id: string; name?: string | null; }

/** Resolves the signed-in tenant's display name (GET /admin/tenants/current; the id when unavailable). */
export function useTenantName(): { id: string | undefined; name: string } {
  const { principal } = useSession();
  const id = principal?.tenantId;
  const q = useQuery({ queryKey: ['tenant-current', id], enabled: !!id, staleTime: 5 * 60_000, retry: false,
    queryFn: () => api.get<TenantRow>('/api/v1/admin/tenants/current', { silent: true }).catch(() => null) });
  const row = q.data && q.data.tenant_id === id ? q.data : undefined;
  return { id, name: (row?.name && row.name.trim()) || id || '—' };
}
