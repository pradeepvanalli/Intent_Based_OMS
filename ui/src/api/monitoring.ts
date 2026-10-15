import { api } from './client';
import type { AtRiskOrder, ExceptionRecord, Page, PipelineView, Playbook } from './types';

export const controlTowerApi = {
  pipeline: (filters: { channel?: string; nodeId?: string; orderType?: string } = {}) => api.get<PipelineView>('/api/v1/control-tower/pipeline', { query: filters, silent: true }),
  atRisk: (limit = 50) => api.get<AtRiskOrder[]>('/api/v1/control-tower/at-risk', { query: { limit }, silent: true }),
};
export const exceptionsApi = {
  list: (q: { status?: string; type?: string; severity?: string; queue?: string; q?: string; offset?: number; limit?: number } = {}) => api.get<Page<ExceptionRecord>>('/api/v1/exceptions', { query: q }),
  get: (id: string) => api.get<ExceptionRecord>(`/api/v1/exceptions/${encodeURIComponent(id)}`),
  assign: (id: string, assignee: string) => api.post<ExceptionRecord>(`/api/v1/exceptions/${encodeURIComponent(id)}/assign`, { assignee }),
  resolve: (id: string, resolution: string, note?: string) => api.post<ExceptionRecord>(`/api/v1/exceptions/${encodeURIComponent(id)}/resolve`, { resolution, note }),
  playbooks: () => api.get<Playbook[]>('/api/v1/playbooks'),
};
