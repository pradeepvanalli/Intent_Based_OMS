import { api } from './client';
import type { AuditEntry, Webhook } from './types';

export const adminApi = {
  config: (prefix = '') => api.get<Record<string, unknown>>('/api/v1/admin/config', { query: { prefix } }),
  setConfig: (key: string, value: unknown, reason: string) => api.put<{ key: string; value: unknown }>(`/api/v1/admin/config/${encodeURIComponent(key)}`, { value, reason }),
  deleteConfig: (key: string, reason: string) => api.del<void>(`/api/v1/admin/config/${encodeURIComponent(key)}`, { query: { reason } }),
  webhooks: () => api.get<Webhook[]>('/api/v1/admin/webhooks'),
  createWebhook: (url: string, eventFilter: string[]) => api.post<{ id: string; secret: string }>('/api/v1/admin/webhooks', { url, eventFilter }),
  audit: (opts: { objectType?: string; objectId?: string; limit?: number } = {}) => api.get<AuditEntry[]>('/api/v1/admin/audit', { query: opts }),
  outbox: () => api.get<{ pending: number; failing: unknown[] }>('/api/v1/admin/outbox'),
};
