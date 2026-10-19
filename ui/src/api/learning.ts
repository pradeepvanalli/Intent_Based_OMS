/* Self-Optimizing Order Orchestration: learners, facts, proposals, experiments, objective, governance, explainability. */
import { api } from './client';
import type {
  AutonomyLevel, BudgetUpdate, ChangeCard, Digest, Experiment, ExperimentDetail, ExperimentMode, GoldenCandidate, Hypothesis, KillSwitchState, LearnedFact,
  LearnerMetrics, LearnerView, LiftReport, LiveParameter, LiveUseRequest, ObjectiveProfile, ObjectiveWeights, ParityReport, Proposal,
  RetentionResult, Review, SubjectType, CumulativeDiff,
} from './types';

const enc = encodeURIComponent;
const L = '/api/v1/learning';

export type LearningJob = 'reward-maturity' | 'coverage' | 'auto-promote' | 'guardrails';

export const learningApi = {
  /* ---- learners and governance (GV-01..03) ---- */
  learners: () => api.get<LearnerView[]>(`${L}/learners`, { silent: true }),
  learner: (key: string) => api.get<LearnerView>(`${L}/learners/${enc(key)}`, { silent: true }),
  setAutonomy: (learnerKey: string, autonomyLevel: AutonomyLevel) => api.put<LearnerView>(`${L}/learners/${enc(learnerKey)}/autonomy`, { autonomyLevel }),
  /** Switch one learner on or off. */
  setLearnerEnabled: (learnerKey: string, enabled: boolean) => api.post<KillSwitchState>(`${L}/kill-switch`, { learnerKey, enabled }),
  /** Pause (enabled=false) or resume all learning for this tenant. */
  setAllLearning: (enabled: boolean) => api.post<KillSwitchState>(`${L}/kill-switch`, { learnerKey: '*', enabled }),
  killSwitch: () => api.get<KillSwitchState>(`${L}/kill-switch`, { silent: true }),
  /** Stop (enabled=false) or restart learning for every tenant; platform engineers only (the platform_engineer role). */
  setGlobalLearning: (enabled: boolean) => api.post<KillSwitchState>(`${L}/kill-switch`, { scope: 'global', enabled }),
  /** GV-02: how far one promotion may move a number and how often one target may be promoted per week. */
  setBudget: (learnerKey: string, body: BudgetUpdate) => api.put<LearnerView>(`${L}/learners/${enc(learnerKey)}/budget`, body),
  resume: (learnerKey: string) => api.post<LearnerView>(`${L}/learners/${enc(learnerKey)}/resume`),
  runLearner: (learnerKey: string) => api.post<LearnerView>(`${L}/learners/${enc(learnerKey)}/run`),
  runJob: (job: LearningJob) => api.post<{ job: string; result: unknown }>(`${L}/jobs/${enc(job)}/run`),
  metrics: () => api.get<LearnerMetrics[]>(`${L}/metrics`, { silent: true }),
  coverage: () => api.get<Record<string, number>>(`${L}/coverage`, { silent: true }),

  /* ---- live use of learned values (EX-07) ---- */
  liveParameters: () => api.get<LiveParameter[]>(`${L}/live-parameters`, { silent: true }),
  proposeLiveUse: (req: LiveUseRequest) => api.post<Proposal>(`${L}/live-parameters/proposals`, req),
  revokeLiveUse: (family: string) => api.post<{ family: string; revoked: boolean }>(`${L}/live-parameters/${enc(family)}/revoke`),

  facts: (subjectType: SubjectType, subjectId: string) => api.get<LearnedFact[]>(`${L}/facts`, { query: { subjectType, subjectId }, silent: true }),

  /* ---- proposals (XP-02, EX-01, EX-07) ---- */
  proposals: (status: string = 'DRAFT') => api.get<Proposal[]>(`${L}/proposals`, { query: { status }, silent: true }),
  proposal: (id: string) => api.get<Proposal>(`${L}/proposals/${enc(id)}`, { silent: true }),
  card: (id: string) => api.get<ChangeCard>(`${L}/proposals/${enc(id)}/card`, { silent: true }),
  diff: (target: string) => api.get<CumulativeDiff>(`${L}/diff`, { query: { target }, silent: true }),
  approve: (id: string, reasonCode?: string) => api.post<Proposal>(`${L}/proposals/${enc(id)}/approve`, reasonCode ? { reasonCode } : undefined),
  reject: (id: string, reason?: string) => api.post<Proposal>(`${L}/proposals/${enc(id)}/reject`, { reason }),
  evaluate: (id: string) => api.post<Proposal>(`${L}/proposals/${enc(id)}/evaluate`),
  /**
   * Weak evidence or a used-up weekly budget makes the server answer 409 with code "promotion_needs_override" and the
   * list of what would be waived ("waivable"); repeating the call with a written overrideReason promotes it and records
   * the waiver. Silent, so the caller can turn that 409 into a dialog instead of an error toast.
   */
  promote: (id: string, overrideReason?: string) =>
    api.post<Proposal>(`${L}/proposals/${enc(id)}/promote`, overrideReason ? { overrideReason } : undefined, { silent: true }),
  rollback: (id: string, reason: string) => api.post<Proposal>(`${L}/promotions/${enc(id)}/rollback`, { reason }),

  /* ---- objective (XP-07) ---- */
  objective: () => api.get<ObjectiveProfile>(`${L}/objective`, { silent: true }),
  /** Creates a DRAFT; someone other than the drafter approves it. */
  draftObjective: (weights: ObjectiveWeights, constraints: Record<string, unknown> = {}) => api.put<ObjectiveProfile>(`${L}/objective`, { weights, constraints }),
  draftObjectiveFromText: (text: string) => api.post<ObjectiveProfile>(`${L}/objective/draft`, { text }),
  objectiveDrafts: () => api.get<ObjectiveProfile[]>(`${L}/objective/drafts`, { silent: true }),
  objectiveHistory: (limit = 20) => api.get<ObjectiveProfile[]>(`${L}/objective/history`, { query: { limit }, silent: true }),
  approveObjective: (version: number) => api.post<ObjectiveProfile>(`${L}/objective/${version}/approve`),

  /* ---- experiments and lift (EX-02..06, XP-01) ---- */
  experiments: (all = false) => api.get<Experiment[]>(`${L}/experiments`, { query: all ? { all: true } : {}, silent: true }),
  experiment: (id: string) => api.get<ExperimentDetail>(`${L}/experiments/${enc(id)}`, { silent: true }),
  startExperiment: (proposalId: string, mode: ExperimentMode, trafficShare: number, stopRule: Record<string, unknown> = {}) =>
    api.post<Experiment>(`${L}/experiments`, { proposalId, mode, trafficShare, stopRule }),
  completeExperiment: (id: string) => api.post<Experiment>(`${L}/experiments/${enc(id)}/complete`),
  stopExperiment: (id: string) => api.post<void>(`${L}/experiments/${enc(id)}/stop`, { status: 'STOPPED' }),
  ensureHoldout: (share = 0.05) => api.post<Experiment>(`${L}/experiments/holdout`, undefined, { query: { share } }),
  lift: (days = 90) => api.get<LiftReport>(`${L}/lift`, { query: { days }, silent: true }),

  /* ---- explainability, fairness, digests, retention (XP-04, GV-07, NF-10) ---- */
  fairness: (decision: string) => api.get<ParityReport>(`${L}/fairness/${enc(decision)}`, { silent: true }),
  digests: (limit = 10) => api.get<Digest[]>(`${L}/digests`, { query: { limit }, silent: true }),
  latestDigest: () => api.get<Digest>(`${L}/digests/latest`, { silent: true }),
  runDigest: (days = 7) => api.post<Digest>(`${L}/digests/run`, undefined, { query: { days } }),
  retention: (dryRun = true) => api.post<RetentionResult>(`${L}/retention/run`, undefined, { query: { dryRun } }),

  /* ---- golden-case candidates (DF-03) ---- */
  goldenCandidates: () => api.get<GoldenCandidate[]>(`${L}/golden-candidates`, { silent: true }),
  signOffGolden: (id: string, expectedValue?: string, notes?: string) => api.post<unknown>(`${L}/golden-candidates/${enc(id)}/sign-off`, { expectedValue, notes }),
  rejectGolden: (id: string) => api.post<unknown>(`${L}/golden-candidates/${enc(id)}/reject`),

  /* ---- slow loop (§6.1) ---- */
  runAnalyst: (decision?: string) => api.post<Hypothesis[]>(`${L}/llm/analyst/run`, undefined, { query: decision ? { decision } : {} }),
  runAuthor: (hypothesisId: string) => api.post<Proposal>(`${L}/llm/author/${enc(hypothesisId)}`),
  runCritic: (proposalId: string) => api.post<Review>(`${L}/llm/critic/${enc(proposalId)}`),
};
