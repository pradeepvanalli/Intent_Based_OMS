import { api } from './client';
import type { HumanTask, Page, TaskQuery, TaskQueue, TimelineEntry, WorkflowView } from './types';

export const workflowsApi = {
  get: (id: string) => api.get<WorkflowView>(`/api/v1/workflows/${encodeURIComponent(id)}`),
  timeline: (id: string) => api.get<TimelineEntry[]>(`/api/v1/workflows/${encodeURIComponent(id)}/timeline`),
  byKey: (type: string, key: string) => api.get<WorkflowView>(`/api/v1/workflows/by-key/${encodeURIComponent(type)}/${encodeURIComponent(key)}`),
  signal: (id: string, name: string, payload: Record<string, unknown>) => api.post<void>(`/api/v1/workflows/${encodeURIComponent(id)}/signal`, { name, payload }),
};

export const tasksApi = {
  list: (q: TaskQuery) => api.get<Page<HumanTask>>('/api/v1/tasks', { query: { ...q } }),
  get: (id: string) => api.get<HumanTask>(`/api/v1/tasks/${encodeURIComponent(id)}`),
  claim: (id: string, assignee: string) => api.post<HumanTask>(`/api/v1/tasks/${encodeURIComponent(id)}/claim`, { assignee }),
  complete: (id: string, outcome: string, reason: string, details?: Record<string, unknown>) => api.post<HumanTask>(`/api/v1/tasks/${encodeURIComponent(id)}/complete`, { outcome, reason, details: details ?? {} }),
  cancel: (id: string, reason: string) => api.post<HumanTask>(`/api/v1/tasks/${encodeURIComponent(id)}/cancel`, { reason }),
  queues: () => api.get<TaskQueue[]>('/api/v1/queues'),
};
