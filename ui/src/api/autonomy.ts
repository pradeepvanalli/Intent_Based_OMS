import { api } from './client';
import type { Anomaly, AutonomousAction, AutonomyGuardrails, AutonomyScanResult, AutonomyStats, Page } from './types';

export const autonomyApi = {
  anomalies: (q: { offset?: number; limit?: number } = {}) => api.get<Page<Anomaly>>('/api/v1/autonomy/anomalies', { query: q, silent: true }),
  actions: (q: { from?: string; to?: string; actionType?: string; outcome?: string; offset?: number; limit?: number } = {}) =>
    api.get<Page<AutonomousAction>>('/api/v1/autonomy/actions', { query: q, silent: true }),
  undo: (id: string, reason?: string) => api.post<AutonomousAction>(`/api/v1/autonomy/actions/${encodeURIComponent(id)}/undo`, { reason }),
  guardrails: () => api.get<AutonomyGuardrails>('/api/v1/autonomy/guardrails', { silent: true }),
  updateGuardrails: (g: Partial<AutonomyGuardrails>) => api.put<AutonomyGuardrails>('/api/v1/autonomy/guardrails', g),
  killSwitch: (enabled: boolean) => api.post<AutonomyGuardrails>('/api/v1/autonomy/kill-switch', { enabled }),
  stats: () => api.get<AutonomyStats>('/api/v1/autonomy/stats', { silent: true }),
  scanNow: () => api.post<AutonomyScanResult>('/api/v1/autonomy/scan-now'),
};
