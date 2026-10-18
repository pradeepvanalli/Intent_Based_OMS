/* Self-Optimizing Order Orchestration: learners, facts, proposals, experiments, objective, governance. */
import { api } from './client';
import type {
  AutonomyLevel, Experiment, ExperimentMode, Hypothesis, LearnedFact, LearnerState, ObjectiveProfile, ObjectiveWeights,
  ParityReport, Proposal, Review, SubjectType,
} from './types';

export const learningApi = {
  learners: () => api.get<LearnerState[]>('/api/v1/learning/learners', { silent: true }),
  setAutonomy: (learnerKey: string, autonomyLevel: AutonomyLevel) =>
    api.put<LearnerState>(`/api/v1/learning/learners/${encodeURIComponent(learnerKey)}/autonomy`, { autonomyLevel }),
  killSwitch: (learnerKey: string, enabled: boolean) => api.post<LearnerState>('/api/v1/learning/kill-switch', { learnerKey, enabled }),

  facts: (subjectType: SubjectType, subjectId: string) =>
    api.get<LearnedFact[]>('/api/v1/learning/facts', { query: { subjectType, subjectId }, silent: true }),

  proposals: (status: string = 'DRAFT') => api.get<Proposal[]>('/api/v1/learning/proposals', { query: { status }, silent: true }),
  proposal: (id: string) => api.get<Proposal>(`/api/v1/learning/proposals/${encodeURIComponent(id)}`, { silent: true }),
  approve: (id: string) => api.post<Proposal>(`/api/v1/learning/proposals/${encodeURIComponent(id)}/approve`),
  reject: (id: string, reason?: string) => api.post<Proposal>(`/api/v1/learning/proposals/${encodeURIComponent(id)}/reject`, { reason }),
  evaluate: (id: string) => api.post<Proposal>(`/api/v1/learning/proposals/${encodeURIComponent(id)}/evaluate`),
  promote: (id: string) => api.post<Proposal>(`/api/v1/learning/proposals/${encodeURIComponent(id)}/promote`),

  objective: () => api.get<ObjectiveProfile>('/api/v1/learning/objective', { silent: true }),
  setObjective: (weights: ObjectiveWeights, constraints: Record<string, unknown> = {}) =>
    api.put<ObjectiveProfile>('/api/v1/learning/objective', { weights, constraints }),

  experiments: () => api.get<Experiment[]>('/api/v1/learning/experiments', { silent: true }),
  startExperiment: (proposalId: string, mode: ExperimentMode, trafficShare: number, stopRule: Record<string, unknown> = {}) =>
    api.post<Experiment>('/api/v1/learning/experiments', { proposalId, mode, trafficShare, stopRule }),
  stopExperiment: (id: string, status: 'STOPPED' | 'COMPLETED' = 'STOPPED') =>
    api.post<void>(`/api/v1/learning/experiments/${encodeURIComponent(id)}/stop`, { status }),
  ensureHoldout: (share = 0.05) => api.post<Experiment>('/api/v1/learning/experiments/holdout', undefined, { query: { share } }),

  fairness: (decision: string) => api.get<ParityReport>(`/api/v1/learning/fairness/${encodeURIComponent(decision)}`, { silent: true }),

  runAnalyst: (decision?: string) => api.post<Hypothesis[]>('/api/v1/learning/llm/analyst/run', undefined, { query: decision ? { decision } : {} }),
  runAuthor: (hypothesisId: string) => api.post<Proposal>(`/api/v1/learning/llm/author/${encodeURIComponent(hypothesisId)}`),
  runCritic: (proposalId: string) => api.post<Review>(`/api/v1/learning/llm/critic/${encodeURIComponent(proposalId)}`),
};
