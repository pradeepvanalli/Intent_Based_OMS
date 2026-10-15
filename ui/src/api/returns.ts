import { api, idem } from './client';
import type { Page, ReturnRecord, ReturnSummary } from './types';

const r = (rma: string) => `/api/v1/returns/${encodeURIComponent(rma)}`;
export const returnsApi = {
  list: (q: { status?: string; q?: string; offset?: number; limit?: number } = {}) => api.get<Page<ReturnSummary>>('/api/v1/returns', { query: q }),
  get: (rma: string) => api.get<ReturnRecord>(r(rma)),
  create: (body: { orderId: string; channel: string; lines: { lineId: string; qty: number; reason: string }[]; reason: string }) => api.post<ReturnRecord>('/api/v1/returns', body, { idempotencyKey: idem() }),
  decide: (rma: string) => api.post<ReturnRecord>(`${r(rma)}/decide`, {}),
  scan: (rma: string) => api.post<ReturnRecord>(`${r(rma)}/scan`, {}),
  inspect: (rma: string, condition: string, note?: string) => api.post<ReturnRecord>(`${r(rma)}/inspect`, { condition, note }),
  disposition: (rma: string) => api.post<ReturnRecord>(`${r(rma)}/disposition`, {}),
  refund: (rma: string) => api.post<ReturnRecord>(`${r(rma)}/refund`, {}, { idempotencyKey: idem() }),
};
