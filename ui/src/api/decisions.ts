import { api } from './client';
import { normalizeTrace } from './traceNormalizer';
import type { DecisionDefinition, DecisionMode, DecisionRecord, DecisionResult, DecisionSummary, DecisionTrace, EvaluationResult, GoldenCase, GoldenSet, ImpactDiff, LintResult, Rollout } from './types';

const d = (name: string) => `/api/v1/decisions/${encodeURIComponent(name)}`;
const v = (name: string, version: number) => `${d(name)}/versions/${version}`;

export const decisionsApi = {
  list: () => api.get<DecisionSummary[]>('/api/v1/decisions'),
  versions: (name: string) => api.get<DecisionDefinition[]>(d(name)),
  version: (name: string, version: number) => api.get<DecisionDefinition>(v(name, version)),
  createDraft: (name: string, yaml: string, fromVersion?: number) => api.post<DecisionDefinition>(`${d(name)}/drafts`, { yaml, fromVersion }),
  lint: (name: string, version: number, yaml?: string) => api.post<LintResult[]>(`${v(name, version)}/lint`, yaml ? { yaml } : {}),
  impact: (name: string, version: number, days: number) => api.post<ImpactDiff>(`${v(name, version)}/impact`, { days }),
  evaluate: (name: string, version: number) => api.post<EvaluationResult>(`${v(name, version)}/evaluate`, {}),
  submit: (name: string, version: number, reason: string, ticket: string) => api.post<DecisionDefinition>(`${v(name, version)}/submit`, { reason, ticket }),
  approve: (name: string, version: number, comment?: string) => api.post<DecisionDefinition>(`${v(name, version)}/approve`, { comment }),
  publish: (name: string, version: number, rollout: Rollout) => api.post<DecisionDefinition>(`${v(name, version)}/publish`, { rollout }),
  rollback: (name: string, reason: string) => api.post<DecisionDefinition>(`${d(name)}/rollback`, { reason }),
  killSwitch: (name: string, on: boolean, reason?: string) => api.post<{ name: string; killSwitch: boolean }>(`${d(name)}/kill-switch`, { on, reason }),
  evaluateNow: (name: string, input: Record<string, unknown>, mode: DecisionMode = 'SIMULATION', version?: number) => api.post<DecisionResult>(`${d(name)}/evaluate-now`, { input, mode, version }),
};

export const decisionRecordsApi = {
  forSubject: (subjectType: string, subjectId: string) => api.get<DecisionRecord[]>('/api/v1/decision-records', { query: { subjectType, subjectId } }),
  recent: (opts: { decision?: string; limit?: number; offset?: number } = {}) => api.get<{ items: DecisionRecord[]; total: number; offset: number; limit: number }>('/api/v1/decision-records', { query: { ...opts } }),
  get: (id: string) => api.get<DecisionRecord>(`/api/v1/decision-records/${encodeURIComponent(id)}`),
  why: (id: string, audience: 'internal' | 'customer' = 'internal') => api.get<unknown>(`/api/v1/decision-records/${encodeURIComponent(id)}/why`, { query: { audience } }).then((raw) => normalizeTrace(raw, audience) as DecisionTrace),
  label: (id: string, label: string, details?: Record<string, unknown>) => api.post<void>(`/api/v1/decision-records/${encodeURIComponent(id)}/label`, { label, details: details ?? {} }),
  override: (id: string, newValue: unknown, reason: string) => api.post<DecisionRecord>(`/api/v1/decision-records/${encodeURIComponent(id)}/override`, { newValue, reason }),
};

export const goldenSetsApi = {
  get: (name: string) => api.get<GoldenSet>(`/api/v1/golden-sets/${encodeURIComponent(name)}`),
  upload: (name: string, cases: GoldenCase[]) => api.post<GoldenSet>(`/api/v1/golden-sets/${encodeURIComponent(name)}`, { cases }),
};
