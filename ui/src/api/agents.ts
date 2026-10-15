import { api } from './client';
import type { AgentIdentity, Mandate } from './types';

export const agentsApi = {
  list: () => api.get<AgentIdentity[]>('/api/v1/agents'),
  get: (id: string) => api.get<AgentIdentity>(`/api/v1/agents/${encodeURIComponent(id)}`),
  create: (a: Partial<AgentIdentity>) => api.post<AgentIdentity>('/api/v1/agents', a),
  update: (id: string, a: Partial<AgentIdentity>) => api.put<AgentIdentity>(`/api/v1/agents/${encodeURIComponent(id)}`, a),
  rotateSecret: (id: string) => api.post<{ agentId: string; secret: string; note?: string }>(`/api/v1/agents/${encodeURIComponent(id)}/rotate-secret`, {}),
  mandates: (agentId?: string) => api.get<Mandate[]>('/api/v1/mandates', { query: { agentId } }),
  mandate: (id: string) => api.get<Mandate>(`/api/v1/mandates/${encodeURIComponent(id)}`),
};
