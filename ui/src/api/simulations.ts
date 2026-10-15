import { api } from './client';
import type { ReplayResult, SimulationJob, SimulationOverrides } from './types';

export const simulationsApi = {
  replay: (orderId: string, opts: { decisionVersionOverrides?: Record<string, number>; stateModelVersion?: number } = {}) => api.post<ReplayResult>(`/api/v1/simulations/replay/${encodeURIComponent(orderId)}`, opts),
  batch: (from: string, to: string, overrides: SimulationOverrides) => api.post<SimulationJob>('/api/v1/simulations/batch', { from, to, overrides }),
  get: (id: string) => api.get<SimulationJob>(`/api/v1/simulations/${encodeURIComponent(id)}`, { silent: true }),
  list: () => api.get<SimulationJob[]>('/api/v1/simulations'),
};
