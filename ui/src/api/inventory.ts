import { api } from './client';
import type { Atp, Node } from './types';

export const inventoryApi = {
  atp: (sku: string) => api.get<Atp[]>(`/api/v1/inventory/${encodeURIComponent(sku)}`),
  network: (skus: string[]) => api.get<Record<string, number>>('/api/v1/inventory/network', { query: { skus: skus.join(',') } }),
};
export const nodesApi = {
  list: () => api.get<Node[]>('/api/v1/nodes'),
  get: (id: string) => api.get<Node>(`/api/v1/nodes/${encodeURIComponent(id)}`),
  upsert: (n: Node) => api.put<Node>(`/api/v1/nodes/${encodeURIComponent(n.id)}`, n),
};
