import { api } from './client';
import type { EntityStatus, Hold, MigrationReport, PlaceHoldRequest, StateModel, StateModelSummary, TransitionDef, ValidationReport } from './types';

export const stateModelsApi = {
  list: () => api.get<StateModelSummary[]>('/api/v1/state-models'),
  latest: (id: string) => api.get<StateModel>(`/api/v1/state-models/${encodeURIComponent(id)}`),
  versions: (id: string) => api.get<StateModel[]>(`/api/v1/state-models/${encodeURIComponent(id)}/versions`),
  version: (id: string, v: number) => api.get<StateModel>(`/api/v1/state-models/${encodeURIComponent(id)}/versions/${v}`),
  validate: (yaml: string) => api.post<ValidationReport>('/api/v1/state-models/validate', { yaml }),
  publish: (yaml: string, reason: string) => api.post<StateModel>('/api/v1/state-models/publish', { yaml, reason }),
  migrate: (id: string, fromVersion: number, toVersion: number, dryRun: boolean) => api.post<MigrationReport>(`/api/v1/state-models/${encodeURIComponent(id)}/migrate`, { fromVersion, toVersion, dryRun }),
};

export const holdsApi = {
  place: (req: PlaceHoldRequest) => api.post<Hold>('/api/v1/holds', req),
  active: (scopeType: string, scopeId: string) => api.get<Hold[]>('/api/v1/holds', { query: { scopeType, scopeId } }),
  release: (holdId: string, resolution: string) => api.post<Hold>(`/api/v1/holds/${encodeURIComponent(holdId)}/release`, { resolution }),
};

export const entitiesApi = {
  status: (type: string, id: string) => api.get<EntityStatus>(`/api/v1/entities/${encodeURIComponent(type)}/${encodeURIComponent(id)}/status`),
  available: (type: string, id: string) => api.get<TransitionDef[]>(`/api/v1/entities/${encodeURIComponent(type)}/${encodeURIComponent(id)}/transitions`),
  fire: (type: string, id: string, trigger: string, qty?: number) => api.post<unknown>(`/api/v1/entities/${encodeURIComponent(type)}/${encodeURIComponent(id)}/transitions`, { trigger, qty }),
};
