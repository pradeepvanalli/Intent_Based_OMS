import { api } from './client';
import type { BootstrapRequest, MarginDigest, ChangeRequest, ChangeSet, CopilotExplanation, CopilotStatus, CsDraft, DraftArtifact, OpsAnswer, OpsTemplate } from './types';

const base = '/api/v1/copilot';
const cs = (id: string) => `${base}/changes/${encodeURIComponent(id)}`;

export const copilotApi = {
  status: () => api.get<CopilotStatus>(`${base}/status`, { silent: true }),
  propose: (req: ChangeRequest) => api.post<ChangeSet>(`${base}/changes`, req),
  changes: (opts: { offset?: number; limit?: number } = {}) => api.get<{ items: ChangeSet[]; total: number }>(`${base}/changes`, { query: opts }),
  change: (id: string) => api.get<ChangeSet>(cs(id)),
  stage: (id: string, changeIds: string[]) => api.post<ChangeSet>(`${cs(id)}/stage`, { changeIds }),
  reject: (id: string, reason: string) => api.post<ChangeSet>(`${cs(id)}/reject`, { reason }),
  explainChange: (id: string, changeId: string) => api.get<{ text: string }>(`${cs(id)}/changes/${encodeURIComponent(changeId)}/explain`),
  addTests: (id: string, changeId: string) => api.post<{ added: number; goldenSet: string }>(`${cs(id)}/changes/${encodeURIComponent(changeId)}/tests`, {}),
  artifacts: (status?: string) => api.get<{ items: DraftArtifact[] } | DraftArtifact[]>(`${base}/artifacts`, { query: { status } }).then((r) => (Array.isArray(r) ? { items: r } : { items: r.items ?? [] })),
  applyArtifact: (id: string, reason: string) => api.post<DraftArtifact>(`${base}/artifacts/${encodeURIComponent(id)}/apply`, { reason }),
  rejectArtifact: (id: string, reason: string) => api.post<DraftArtifact>(`${base}/artifacts/${encodeURIComponent(id)}/reject`, { reason }),
  bootstrap: (req: BootstrapRequest) => api.post<ChangeSet>(`${base}/bootstrap`, req),
  opsTemplates: () => api.get<{ templates: OpsTemplate[] } | OpsTemplate[]>(`${base}/ops/templates`, { silent: true }).then((r) => (Array.isArray(r) ? { templates: r } : { templates: r.templates ?? [] })),
  opsAsk: (question: string) => api.post<OpsAnswer>(`${base}/ops/ask`, { question }),
  explain: (recordId: string, audience: 'internal' | 'customer') => api.get<CopilotExplanation>(`${base}/explain/${encodeURIComponent(recordId)}`, { query: { audience } }),
  digest: (days = 7) => api.get<MarginDigest>(`${base}/digest`, { query: { days } }),
  digestRun: (days = 7) => api.post<MarginDigest>(`${base}/digest/run?days=${days}`, {}),
  digestLatest: () => api.get<MarginDigest | null>(`${base}/digest/latest`, { silent: true }).catch(() => null),
  digestHistory: (limit = 10) => api.get<{ items: MarginDigest[] } | MarginDigest[]>(`${base}/digest/history`, { query: { limit } }).then((r) => (Array.isArray(r) ? r : r.items ?? [])),
  csDraft: (orderId: string, message: string) => api.post<CsDraft>(`${base}/cs/draft`, { orderId, message }),
};
