import { api, idem } from './client';
import type { TrackingLink } from './playground';
import type { Address, BulkOperation, BulkRequest, Feasibility, Hold, LedgerEvent, Modification, Order, OrderQuery, OrderSummary, OrderType, OrderView, Page, SourcingPlan, SourcingResult, TimelineEntry, EvidencePack, PromiseRequestBody, PromiseResponse } from './types';

export const ordersApi = {
  search: (q: OrderQuery) => api.get<Page<OrderSummary>>('/api/v1/orders', { query: { ...q, atRisk: q.atRisk ? 'true' : undefined } }),
  get: (id: string) => api.get<Order>(`/api/v1/orders/${encodeURIComponent(id)}`),
  view: (id: string) => api.get<OrderView>(`/api/v1/orders/${encodeURIComponent(id)}/view`),
  timeline: (id: string) => api.get<TimelineEntry[]>(`/api/v1/orders/${encodeURIComponent(id)}/timeline`),
  ledger: (id: string) => api.get<LedgerEvent[]>(`/api/v1/admin/ledger/order/${encodeURIComponent(id)}`),
  checkModification: (id: string, m: Modification) => api.post<Feasibility>(`/api/v1/orders/${encodeURIComponent(id)}/modifications/check`, m),
  applyModification: (id: string, m: Modification, reason: string) => api.post<Order>(`/api/v1/orders/${encodeURIComponent(id)}/modifications`, { modification: m, reason }, { idempotencyKey: idem() }),
  cancel: (id: string, reason: string) => api.post<Order>(`/api/v1/orders/${encodeURIComponent(id)}/cancel`, { reason }, { idempotencyKey: idem() }),
  placeHold: (id: string, body: { type: string; blocks: string[]; reasonCode: string; note?: string; ttl?: string }) => api.post<Hold>(`/api/v1/orders/${encodeURIComponent(id)}/holds`, body, { idempotencyKey: idem() }),
  types: () => api.get<OrderType[]>('/api/v1/order-types'),
  sourcingOptions: (id: string) => api.get<SourcingPlan[]>(`/api/v1/orders/${encodeURIComponent(id)}/sourcing/options`),
  source: (id: string) => api.post<SourcingResult>(`/api/v1/orders/${encodeURIComponent(id)}/sourcing`, {}, { idempotencyKey: idem() }),
  resource: (id: string, reason: string) => api.post<SourcingResult>(`/api/v1/orders/${encodeURIComponent(id)}/resource`, { reason }, { idempotencyKey: idem() }),
  evidencePack: (id: string) => api.get<EvidencePack>(`/api/v1/orders/${encodeURIComponent(id)}/evidence-pack`),
  bulk: (req: BulkRequest) => api.post<BulkOperation>('/api/v1/bulk-operations', req, { idempotencyKey: idem() }),
  bulkGet: (id: string) => api.get<BulkOperation>(`/api/v1/bulk-operations/${encodeURIComponent(id)}`),
  bulkRollback: (id: string) => api.post<BulkOperation>(`/api/v1/bulk-operations/${encodeURIComponent(id)}/rollback`, {}),
  bulkList: (offset = 0, limit = 20) => api.get<Page<BulkOperation>>('/api/v1/bulk-operations', { query: { offset, limit } }),
  promise: (body: PromiseRequestBody) => api.post<PromiseResponse>('/api/v1/promise', body),
  /** Mint a 30-day customer tracking link (order.read). The UI builds the customer URL from `token` on its own origin. */
  trackingLink: (id: string) => api.post<TrackingLink>(`/api/v1/orders/${encodeURIComponent(id)}/tracking-link`, {}),
};
export type { Address };
