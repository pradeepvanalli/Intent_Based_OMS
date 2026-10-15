import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { hasPermission, useSession } from '../auth/session';

interface TenantRow { tenant_id: string; name?: string | null; }

/** Resolves the tenant display name (GET /admin/tenants when permitted; the id otherwise). */
export function useTenantName(): { id: string | undefined; name: string } {
  const { principal } = useSession();
  const id = principal?.tenantId;
  const canList = hasPermission(principal, '*');
  const q = useQuery({ queryKey: ['tenants', id], enabled: !!id && canList, staleTime: 5 * 60_000, retry: false, queryFn: () => api.get<TenantRow[]>('/api/v1/admin/tenants', { silent: true }).catch(() => [] as TenantRow[]) });
  const row = Array.isArray(q.data) ? q.data.find((t) => t.tenant_id === id) : undefined;
  return { id, name: (row?.name && row.name.trim()) || id || '—' };
}
