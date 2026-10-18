/* Mock routes for Self-Optimizing Order Orchestration (oms-learning). Mirrors docs/API.md "Learning". */
import type {
  AutonomyLevel, Experiment, Hypothesis, LearnedFact, LearnerState, ObjectiveProfile, ObjectiveWeights, ParityReport, Proposal, Review,
} from '../types';
import { hoursAgo, minutesAgo, nextId } from './data';

type Ctx = { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> };
type On = (method: string, path: string, handler: (ctx: Ctx) => unknown) => void;
type Problem = (status: number, code: string, detail: string, extra?: Record<string, unknown>) => never;

const learners: LearnerState[] = [
  { learnerKey: 'node_reliability', autonomyLevel: 'L1_RECOMMEND', enabled: true, maxPromotionsPerWeek: 1, maxStep: 0.05, lastRunAt: minutesAgo(8), updatedBy: 'system', updatedAt: minutesAgo(8) },
  { learnerKey: 'sourcing_policy_bandit', autonomyLevel: 'L1_RECOMMEND', enabled: true, maxPromotionsPerWeek: 1, maxStep: null, lastRunAt: minutesAgo(3), updatedBy: 'system', updatedAt: minutesAgo(3) },
  { learnerKey: 'fraud_threshold_tuner', autonomyLevel: 'L0_OBSERVE', enabled: true, maxPromotionsPerWeek: 1, maxStep: 0.02, lastRunAt: hoursAgo(1), updatedBy: 'system', updatedAt: hoursAgo(1) },
  { learnerKey: 'band_threshold_learner', autonomyLevel: 'L1_RECOMMEND', enabled: true, maxPromotionsPerWeek: 1, maxStep: 0.05, lastRunAt: hoursAgo(20), updatedBy: 'system', updatedAt: hoursAgo(20) },
];

const facts: Record<string, LearnedFact[]> = {
  'NODE:ST-CHI-01': [{ factId: 'fact_demo1', subjectType: 'NODE', subjectId: 'ST-CHI-01', factKey: 'NODE_ON_TIME_RATE', value: { type: 'NumberValue', value: 0.94 }, confidence: 0.8, sampleSize: 142, learner: 'node_reliability', version: 3, validFrom: hoursAgo(20), validTo: null, createdAt: hoursAgo(20) }],
};

const hypotheses: Hypothesis[] = [
  { hypothesisId: 'hyp_demo1', scope: { node: 'DC-NV' }, targetMetric: 'split_rate', expectedDirection: 'DECREASE', lever: { parameter: 'aged_stock_weight' }, priorConfidence: 0.7, evidence: ['35 episodes, DC-NV splits 18% above network average'], rationale: 'DC-NV orders split more than similar nodes; aged-stock preference may be under-weighted.', status: 'DRAFTED', createdAt: hoursAgo(26) },
];

const proposals: Proposal[] = [
  {
    proposalId: 'prop_demo1', hypothesisId: 'hyp_demo1', tier: 'T1', status: 'EVALUATED',
    changeset: { target: { type: 'decision', name: 'sourcing_node_score' }, baseVersion: 3, tier: 'T1', motivatedBy: 'hyp_demo1', changes: [{ type: 'ThresholdChange', decision: 'sourcing_node_score', band: 'act', from: 0.82, to: 0.78 }] },
    criticReview: { verdict: 'LOOKS_SAFE', risks: ['Small sample for the DC-NV cohort (35 episodes); recommend a short canary before full promotion.'] },
    evaluationReport: { method: 'ips_doubly_robust', sampleSize: 412, baselineReward: 0.61, proposedRewardEstimate: 0.67, liftEstimate: 0.06, confidenceInterval: { lower: 0.01, upper: 0.11 }, clippedRecords: 3 },
    createdBy: 'system:author', createdAt: hoursAgo(24), updatedAt: hoursAgo(2), evaluatedAt: hoursAgo(2),
    approvedBy: null, approvedAt: null, promotedAt: null, rejectedReason: null, rejectedAt: null, rolledBackReason: null, rolledBackAt: null,
  },
  {
    proposalId: 'prop_demo2', hypothesisId: null, tier: 'T0', status: 'DRAFT',
    changeset: { target: { type: 'parameter', name: 'reservation_ttl_minutes' }, baseVersion: 1, tier: 'T0', motivatedBy: null, changes: [{ type: 'ParameterChange', parameter: 'reservation_ttl_minutes', from: 15, to: 12 }] },
    criticReview: null, evaluationReport: null,
    createdBy: 'system:author', createdAt: hoursAgo(1), updatedAt: hoursAgo(1), evaluatedAt: null,
    approvedBy: null, approvedAt: null, promotedAt: null, rejectedReason: null, rejectedAt: null, rolledBackReason: null, rolledBackAt: null,
  },
];

const experiments: Experiment[] = [
  { experimentId: 'exp_demo1', proposalId: 'holdout', mode: 'HOLDOUT', trafficShare: 0.05, status: 'RUNNING', startAt: hoursAgo(400), endAt: null, stopRule: {}, result: null, createdAt: hoursAgo(400) },
];

let objective: ObjectiveProfile = {
  version: 1, status: 'APPROVED', weights: { fill: 1, otd: 1, cost: 1, split: 1, fraud: 1, cx: 1, inv: 1 }, constraints: {},
  approvedBy: 'user:admin', approvedAt: hoursAgo(400), createdBy: 'user:admin', createdAt: hoursAgo(400),
};

export function registerLearningRoutes({ on, problem }: { on: On; problem: Problem }) {
  on('GET', '/api/v1/learning/learners', () => learners);

  on('PUT', '/api/v1/learning/learners/:id/autonomy', ({ params, body }) => {
    const l = learners.find((x) => x.learnerKey === params.id);
    if (!l) return problem(404, 'not_found', `Learner ${params.id} not found`);
    l.autonomyLevel = body.autonomyLevel as AutonomyLevel;
    l.updatedAt = new Date().toISOString();
    l.updatedBy = 'user:admin';
    return l;
  });

  on('POST', '/api/v1/learning/kill-switch', ({ body }) => {
    const l = learners.find((x) => x.learnerKey === body.learnerKey);
    if (!l) return problem(404, 'not_found', `Learner ${body.learnerKey} not found`);
    l.enabled = Boolean(body.enabled);
    l.updatedAt = new Date().toISOString();
    return l;
  });

  on('GET', '/api/v1/learning/facts', ({ query }) => facts[`${query.get('subjectType')}:${query.get('subjectId')}`] ?? []);

  on('GET', '/api/v1/learning/proposals', ({ query }) => {
    const status = query.get('status') ?? 'DRAFT';
    return proposals.filter((p) => p.status === status);
  });
  on('GET', '/api/v1/learning/proposals/:id', ({ params }) => proposals.find((p) => p.proposalId === params.id) ?? problem(404, 'not_found', `Proposal ${params.id} not found`));
  on('POST', '/api/v1/learning/proposals/:id/approve', ({ params }) => {
    const p = proposals.find((x) => x.proposalId === params.id);
    if (!p) return problem(404, 'not_found', `Proposal ${params.id} not found`);
    if (p.createdBy === 'user:admin') return problem(422, 'validation_failed', 'The author of a proposal cannot approve it themselves');
    p.status = 'APPROVED'; p.approvedBy = 'user:admin'; p.approvedAt = new Date().toISOString(); p.updatedAt = p.approvedAt;
    return p;
  });
  on('POST', '/api/v1/learning/proposals/:id/reject', ({ params, body }) => {
    const p = proposals.find((x) => x.proposalId === params.id);
    if (!p) return problem(404, 'not_found', `Proposal ${params.id} not found`);
    p.status = 'REJECTED'; p.rejectedReason = (body.reason as string) ?? null; p.rejectedAt = new Date().toISOString(); p.updatedAt = p.rejectedAt;
    return p;
  });
  on('POST', '/api/v1/learning/proposals/:id/evaluate', ({ params }) => {
    const p = proposals.find((x) => x.proposalId === params.id);
    if (!p) return problem(404, 'not_found', `Proposal ${params.id} not found`);
    p.evaluationReport = { method: 'ips_doubly_robust', sampleSize: 128, baselineReward: 0.58, proposedRewardEstimate: 0.6, liftEstimate: 0.02, confidenceInterval: { lower: -0.01, upper: 0.05 }, clippedRecords: 1 };
    p.status = 'EVALUATED'; p.evaluatedAt = new Date().toISOString(); p.updatedAt = p.evaluatedAt;
    return p;
  });
  on('POST', '/api/v1/learning/proposals/:id/promote', ({ params }) => {
    const p = proposals.find((x) => x.proposalId === params.id);
    if (!p) return problem(404, 'not_found', `Proposal ${params.id} not found`);
    if (p.status !== 'APPROVED' && p.tier !== 'T0') return problem(409, 'conflict', `Proposal ${params.id} must be approved before it can promote`);
    p.status = 'PROMOTED'; p.promotedAt = new Date().toISOString(); p.updatedAt = p.promotedAt;
    return p;
  });

  on('GET', '/api/v1/learning/objective', () => objective);
  on('PUT', '/api/v1/learning/objective', ({ body }) => {
    objective = { version: objective.version + 1, status: 'APPROVED', weights: body.weights as ObjectiveWeights, constraints: (body.constraints as Record<string, unknown>) ?? {}, approvedBy: 'user:admin', approvedAt: new Date().toISOString(), createdBy: 'user:admin', createdAt: new Date().toISOString() };
    return objective;
  });

  on('GET', '/api/v1/learning/experiments', () => experiments.filter((e) => e.status === 'RUNNING'));
  on('POST', '/api/v1/learning/experiments', ({ body }) => {
    const e: Experiment = { experimentId: nextId('exp'), proposalId: body.proposalId as string, mode: body.mode as Experiment['mode'], trafficShare: Number(body.trafficShare), status: 'RUNNING', startAt: new Date().toISOString(), endAt: null, stopRule: (body.stopRule as Record<string, unknown>) ?? {}, result: null, createdAt: new Date().toISOString() };
    experiments.push(e);
    return e;
  });
  on('POST', '/api/v1/learning/experiments/:id/stop', ({ params, body }) => {
    const e = experiments.find((x) => x.experimentId === params.id);
    if (!e) return problem(404, 'not_found', `Experiment ${params.id} not found`);
    e.status = (body.status as Experiment['status']) ?? 'STOPPED'; e.endAt = new Date().toISOString();
    return undefined;
  });
  on('POST', '/api/v1/learning/experiments/holdout', ({ query }) => {
    const existing = experiments.find((e) => e.mode === 'HOLDOUT' && e.status === 'RUNNING');
    if (existing) return existing;
    const e: Experiment = { experimentId: nextId('exp'), proposalId: 'holdout', mode: 'HOLDOUT', trafficShare: Number(query.get('share') ?? 0.05), status: 'RUNNING', startAt: new Date().toISOString(), endAt: null, stopRule: {}, result: null, createdAt: new Date().toISOString() };
    experiments.push(e);
    return e;
  });

  on('GET', '/api/v1/learning/fairness/:decision', ({ params }) => {
    const r: ParityReport = {
      decision: params.decision,
      groups: [
        { group: 'split_tolerance:low', customers: 212, actBandRate: 0.71, overrideRate: 0.04 },
        { group: 'split_tolerance:mid', customers: 340, actBandRate: 0.74, overrideRate: 0.03 },
        { group: 'split_tolerance:high', customers: 188, actBandRate: 0.69, overrideRate: 0.05 },
      ],
      tenantActBandRate: 0.72, tenantOverrideRate: 0.04, flags: [],
    };
    return r;
  });

  on('POST', '/api/v1/learning/llm/analyst/run', () => hypotheses);
  on('POST', '/api/v1/learning/llm/author/:id', ({ params }) => proposals.find((p) => p.hypothesisId === params.id) ?? problem(404, 'not_found', `No proposal drafted for hypothesis ${params.id}`));
  on('POST', '/api/v1/learning/llm/critic/:id', ({ params }) => {
    const p = proposals.find((x) => x.proposalId === params.id);
    if (!p?.evaluationReport) return problem(404, 'not_found', `No evaluation report yet for proposal ${params.id}`);
    const review: Review = { verdict: 'LOOKS_SAFE', risks: [] };
    return review;
  });
}
