/* Mock routes for Self-Optimizing Order Orchestration (oms-learning), mirroring the real endpoint shapes. */
import type {
  AutonomyLevel, ChangeCard, Digest, Experiment, GoldenCandidate, Hypothesis, KillSwitchState, LearnedFact, LearnerMetrics, LearnerView, LiftReport,
  LiveParameter, ObjectiveProfile, ObjectiveWeights, ParityReport, PolicyChange, Proposal, ProposalStatus, Review, RiskTier,
} from '../types';
import { getSession } from '../../auth/session';
import { daysAgo, hoursAgo, minutesAgo, nextId } from './data';

type Ctx = { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> };
type On = (method: string, path: string, handler: (ctx: Ctx) => unknown) => void;
type Problem = (status: number, code: string, detail: string, extra?: Record<string, unknown>) => never;

const SEED = 'system:demo-seed';
const me = () => { const s = getSession(); return s.principal ? `user:${s.principal.id}` : 'user:anonymous'; };
const now = () => new Date().toISOString();

type Desc = [key: string, name: string, summary: string, requirement: string, outcomes: string[], feeds: string[], level?: AutonomyLevel, lastRun?: string | null];
const DESCRIPTORS: Desc[] = [
  ['node_reliability', 'Node reliability', 'Learns how often each store or warehouse ships on time and rejects orders, by product category and time of day.', 'FS-02, FS-06', ['sourcing_node_score'], ['sourcing.node_reject_rate', 'sourcing.node_on_time_rate', 'sourcing.reject_penalty'], 'L2_ACT_WITH_APPROVAL', minutesAgo(4)],
  ['lane_transit', 'Carrier lane transit times', 'Learns how long each carrier lane really takes to each area and whether stated on-time odds hold up.', 'FS-03, CX-01', ['sourcing_node_score'], ['promise.lane_transit_days', 'promise.lane_calibration'], 'L2_ACT_WITH_APPROVAL', minutesAgo(9)],
  ['sourcing_policy_bandit', 'Sourcing policy explorer', 'Tries alternative sourcing policies on a small share of orders and learns which one gives the best outcome.', 'FS-01', ['sourcing_policy_bandit'], ['sourcing.policy_bandit'], 'L1_RECOMMEND', minutesAgo(2)],
  ['bandit_reward', 'Sourcing policy scores', 'Scores each sourcing policy per kind of order from real outcomes, correcting for how often each was tried.', 'FS-01', ['sourcing_policy_bandit'], ['sourcing.policy_bandit'], 'L1_RECOMMEND', hoursAgo(3)],
  ['sourcing_weights', 'Sourcing weights', 'Suggests how much cost, speed, node quality and fewer packages should count when choosing a plan.', 'FS-04', ['sourcing_node_score'], [], 'L1_RECOMMEND', hoursAgo(20)],
  ['node_backlog_breach', 'Backlog breach risk', "Learns how much each node's backlog raises the chance of missing the promised date.", 'FS-05', ['sourcing_node_score'], ['sourcing.node_backlog_breach'], 'L1_RECOMMEND', hoursAgo(5)],
  ['promise_buffer', 'Promise buffer', 'Finds how many days of padding on the delivery promise win the most shoppers while still being kept.', 'CX-02', [], ['promise.buffer_days'], 'L1_RECOMMEND', hoursAgo(22)],
  ['warn_early', 'Early delay warning', 'Learns how risky an order must look before telling the customer about a likely delay.', 'CX-03', ['sla_breach_risk'], ['monitoring.warn_early_threshold'], 'L1_RECOMMEND', hoursAgo(6)],
  ['appeasement_level', 'Appeasement level', 'Learns the goodwill credit that stops repeat complaints for each kind of customer, within budget. A person still approves every credit.', 'CX-04', ['appeasement'], ['cs.appeasement_level'], 'L1_RECOMMEND', daysAgo(1)],
  ['fraud_threshold_tuner', 'Fraud threshold tuner', 'Moves fraud thresholds toward your false-positive target, weighing fraud loss against blocking good orders.', 'FD-01, FD-02, FD-03', ['payment_risk', 'rto_risk', 'address_risk'], [], 'L1_RECOMMEND', daysAgo(1)],
  ['fraud_abuse_scanner', 'Abuse pattern finder', 'Looks for new fraud and abuse patterns, such as one address shared by many customers, and suggests rules for a person to review.', 'FD-04', ['payment_risk', 'return_risk', 'rto_risk'], ['fraud.address_reuse_count'], 'L1_RECOMMEND', daysAgo(1)],
  ['agent_reputation', 'Agent reputation', 'Scores each AI shopping agent on mandate compliance, prompt-injection hits and disputes. A person decides any trust change.', 'FD-05', [], ['fraud.agent_reputation', 'fraud.agent_dispute_rate'], 'L1_RECOMMEND', hoursAgo(4)],
  ['inventory_stock_age', 'Stock age', 'Tracks how long stock has sat at each location and flags stock that has stopped selling, so orders can be sent to use it up first.', 'IU-03', [], [], 'L1_RECOMMEND', hoursAgo(11)],
  ['inventory_safety_stock', 'Safety stock', 'Suggests how much buffer stock each location should hold, from how much its demand swings, within your limits.', 'IU-01', [], ['inventory.safety_stock'], 'L1_RECOMMEND', daysAgo(3)],
  ['inventory_reservation_ttl', 'Cart hold time', 'Learns how long to hold stock for a shopping cart, from how long real shoppers take to check out.', 'IU-02', [], ['inventory.reservation_ttl'], 'L1_RECOMMEND', daysAgo(2)],
  ['inventory_transfer', 'Stock transfer suggestions', 'Suggests moving stock from locations that have too much to ones about to run out. It only recommends; a planner decides.', 'IU-04', [], [], 'L1_RECOMMEND', daysAgo(4)],
  ['inventory_inbound_reliability', 'Supplier reliability', "Learns how much of each supplier's announced stock really arrives on time, so promises don't count on stock that won't come.", 'IU-05', [], ['inventory.inbound_reliability'], 'L1_RECOMMEND', daysAgo(1)],
  ['inventory_backorder', 'Backorder outcomes', 'Learns, per item, how often customers wait for a backorder instead of cancelling.', 'IU-06', [], [], 'L1_RECOMMEND', null],
  ['ci_preference', 'Customer preferences', "Learns each customer's split tolerance, speed vs cost, pickup preference and contact channel from what they do.", 'CI-01, CI-03, CI-04', [], ['customer.preferences'], 'L1_RECOMMEND', hoursAgo(1)],
  ['ci_segment_discovery', 'Customer segments', "Groups customers who behave alike (by splits, speed, pickup and how often they buy) so new customers start from their group's habits.", 'CI-02', [], [], 'L1_RECOMMEND', daysAgo(1)],
  ['band_threshold', 'Confidence band tuner', "Suggests where each decision's act threshold should sit for the best accuracy within your escalation budget.", 'DF-01', [], [], 'L1_RECOMMEND', hoursAgo(19)],
  ['confidence_calibrator', 'Confidence calibration', 'Checks whether a decision that says 90% sure is right 90% of the time, and corrects it when not.', 'DF-02', [], ['decision.calibration'], 'L1_RECOMMEND', hoursAgo(19)],
  ['golden_set_growth', 'Test case growth', 'Turns situations people keep resolving the same way into new test cases, after a reviewer signs off.', 'DF-03', [], [], 'L1_RECOMMEND', hoursAgo(18)],
  ['override_to_rule', 'Override-to-rule finder', 'Spots cases people override the same way again and again and suggests a rule for them.', 'DF-04', [], [], 'L1_RECOMMEND', hoursAgo(18)],
  ['provider_routing', 'AI provider routing', 'Finds the cheapest AI provider that is just as accurate for each decision.', 'DF-05', [], ['decision.provider'], 'L0_OBSERVE', hoursAgo(17)],
  ['rule_utilization', 'Unused rule finder', 'Finds rules that never fire, or that the model now beats, and suggests retiring them.', 'DF-07', [], [], 'L1_RECOMMEND', hoursAgo(17)],
];
const EVENT_DRIVEN = new Set(['node_reliability', 'lane_transit', 'inventory_backorder', 'sourcing_policy_bandit']);

const learners: LearnerView[] = DESCRIPTORS.map(([learnerKey, displayName, summary, requirement, outcomeDecisions, liveParameters, level, lastRunAt]) => ({
  learnerKey, displayName, summary, loop: 'FAST', requirement, outcomeDecisions, liveParameters, autonomyLevel: level ?? 'L1_RECOMMEND', enabled: true,
  paused: false, pausedReason: null, running: true, gateReason: 'ok', maxPromotionsPerWeek: 1, maxStep: null, lastRunAt: lastRunAt ?? null, updatedBy: SEED, updatedAt: daysAgo(30),
}));
// One learner paused by the coverage monitor, so the paused state is visible.
Object.assign(learners.find((l) => l.learnerKey === 'warn_early')!, { paused: true, pausedReason: 'outcome coverage for sla_breach_risk is 71% (floor 80%)', running: false, gateReason: 'paused: outcome coverage for sla_breach_risk is 71% (floor 80%)', updatedBy: 'system:coverage-monitor' });
let tenantPaused = false;

function regate() {
  for (const l of learners) {
    l.gateReason = tenantPaused ? 'tenant_kill_switch' : !l.enabled ? 'learner_kill_switch' : l.paused ? `paused: ${l.pausedReason}` : 'ok';
    l.running = l.gateReason === 'ok';
  }
}

const facts: Record<string, LearnedFact[]> = {
  'NODE:ST-CHI-01': [{ factId: 'fact_demo1', subjectType: 'NODE', subjectId: 'ST-CHI-01', factKey: 'NODE_ON_TIME_RATE', value: { type: 'NumberValue', value: 0.94 }, confidence: 0.8, sampleSize: 142, learner: 'node_reliability', version: 3, validFrom: hoursAgo(20), validTo: null, createdAt: hoursAgo(20) }],
};

const hypotheses: Hypothesis[] = [
  { hypothesisId: 'hyp_demo1', scope: { node: 'DC-NV' }, targetMetric: 'split_rate', expectedDirection: 'DECREASE', lever: { parameter: 'aged_stock_weight' }, priorConfidence: 0.7, evidence: ['35 orders sent from DC-NV split 18% more often than from similar nodes', 'DC-NV holds 2,400 units older than 90 days'], rationale: 'DC-NV orders split more than similar nodes; the sourcing score may under-weight nodes that can ship the whole order.', status: 'DRAFTED', createdAt: hoursAgo(26) },
  { hypothesisId: 'hyp_demo2', scope: { decision: 'payment_risk' }, targetMetric: 'false_positive_rate', expectedDirection: 'DECREASE', lever: { decision: 'payment_risk' }, priorConfidence: 0.6, evidence: ['41 of 380 held orders were later shown to be good (10.8%), above the 5% target', 'No confirmed fraud among orders scored 0.88–0.92'], rationale: 'The payment-risk act threshold blocks too many good orders; moving it up a little keeps fraud loss flat.', status: 'DRAFTED', createdAt: daysAgo(3) },
];

type P = Partial<Proposal> & { proposalId: string; tier: RiskTier; status: ProposalStatus; target: [string, string]; changes: PolicyChange[] };
function mk(p: P): Proposal {
  return {
    proposalId: p.proposalId, hypothesisId: p.hypothesisId ?? null, tier: p.tier, status: p.status,
    changeset: { target: { type: p.target[0], name: p.target[1] }, baseVersion: 3, tier: p.tier, motivatedBy: p.hypothesisId ?? null, changes: p.changes },
    criticReview: p.criticReview ?? null, evaluationReport: p.evaluationReport ?? null, createdBy: p.createdBy ?? SEED, createdAt: p.createdAt ?? daysAgo(2), updatedAt: p.updatedAt ?? hoursAgo(2),
    evaluatedAt: p.evaluatedAt ?? null, approvedBy: p.approvedBy ?? null, approvedAt: p.approvedAt ?? null, promotedAt: p.promotedAt ?? null,
    rejectedReason: p.rejectedReason ?? null, rejectedAt: p.rejectedAt ?? null, rolledBackReason: p.rolledBackReason ?? null, rolledBackAt: p.rolledBackAt ?? null,
  };
}
const good = { method: 'ips_doubly_robust', sampleSize: 412, baselineReward: 0.61, proposedRewardEstimate: 0.67, liftEstimate: 0.06, confidenceInterval: { lower: 0.012, upper: 0.108 }, clippedRecords: 3 };
const proposals: Proposal[] = [
  mk({ proposalId: 'prop_demo1', hypothesisId: 'hyp_demo1', tier: 'T1', status: 'EVALUATED', target: ['decision', 'sourcing_node_score'], changes: [{ type: 'ThresholdChange', decision: 'sourcing_node_score', band: 'act', from: 0.82, to: 0.78 }],
    criticReview: { verdict: 'LOOKS_SAFE', risks: ['The DC-NV group is small (35 orders); a short trial before going live everywhere is sensible.'] }, evaluationReport: good, createdBy: 'system:band_threshold', createdAt: hoursAgo(24), evaluatedAt: hoursAgo(2) }),
  mk({ proposalId: 'prop_demo2', tier: 'T0', status: 'DRAFT', target: ['parameter', 'live:inventory.reservation_ttl'], changes: [{ type: 'ParameterChange', parameter: 'live:inventory.reservation_ttl', from: { enabled: false }, to: { enabled: true, min: 5, max: 60, maxStep: 10, learner: 'inventory_reservation_ttl' } }],
    createdBy: 'user:strategy', createdAt: hoursAgo(1), updatedAt: hoursAgo(1) }),
  mk({ proposalId: 'prop_demo3', hypothesisId: 'hyp_demo2', tier: 'T2', status: 'DRAFT', target: ['decision', 'payment_risk'], changes: [{ type: 'ThresholdChange', decision: 'payment_risk', band: 'act', from: 0.9, to: 0.93 }],
    createdBy: 'system:fraud_threshold_tuner', createdAt: daysAgo(3), updatedAt: daysAgo(3) }),
  mk({ proposalId: 'prop_demo4', tier: 'T1', status: 'APPROVED', target: ['config', 'sourcing.weights'], changes: [{ type: 'WeightChange', target: 'config:sourcing.weights', weightKey: 'packages', from: 0.15, to: 0.2 }],
    evaluationReport: { method: 'observational_cohort', sampleSize: 960, liftEstimate: 0.02, confidenceInterval: { lower: 0.004, upper: 0.036 } }, createdBy: 'system:sourcing_weights', approvedBy: 'user:strategy', approvedAt: hoursAgo(5), evaluatedAt: hoursAgo(8) }),
  mk({ proposalId: 'prop_demo5', tier: 'T1', status: 'CANARY', target: ['decision', 'fulfilment_intent'], changes: [{ type: 'ThresholdChange', decision: 'fulfilment_intent', band: 'confirm', from: 0.5, to: 0.55 }],
    evaluationReport: { ...good, liftEstimate: 0.031, confidenceInterval: { lower: 0.006, upper: 0.056 } }, criticReview: { verdict: 'LOOKS_SAFE', risks: [] }, createdBy: 'system:band_threshold', approvedBy: 'user:admin', approvedAt: daysAgo(2), evaluatedAt: daysAgo(2) }),
  mk({ proposalId: 'prop_demo6', tier: 'T1', status: 'PROMOTED', target: ['parameter', 'live:sourcing.node_reject_rate'], changes: [{ type: 'ParameterChange', parameter: 'live:sourcing.node_reject_rate', from: { enabled: false }, to: { enabled: true, min: 0, max: 0.5, maxStep: 0.1, learner: 'node_reliability' } }],
    evaluationReport: { method: 'no_counterfactual', shadow: { passed: true, reason: 'learned values were available for enough live decisions', decisionsAffected: 1840 } }, createdBy: 'user:strategy', approvedBy: 'user:admin', approvedAt: daysAgo(12), promotedAt: daysAgo(12), evaluatedAt: daysAgo(13) }),
  mk({ proposalId: 'prop_demo7', tier: 'T1', status: 'PROMOTED', target: ['decision', 'split_acceptability'], changes: [{ type: 'RuleRetire', decision: 'split_acceptability', ruleId: 'legacy_holiday_split', reason: 'never fired in 90 days' }],
    evaluationReport: { method: 'resimulation+ips_doubly_robust', sampleSize: 2310, liftEstimate: 0.0, confidenceInterval: { lower: -0.004, upper: 0.004 } }, createdBy: 'system:rule_utilization', approvedBy: 'user:admin', approvedAt: daysAgo(6), promotedAt: daysAgo(6), evaluatedAt: daysAgo(7) }),
  mk({ proposalId: 'prop_demo8', tier: 'T2', status: 'REJECTED', target: ['decision', 'return_risk'], changes: [{ type: 'RuleAdd', decision: 'return_risk', expression: 'customer.returns_90d > 6', thenValue: 'review', reason: 'serial returners' }],
    evaluationReport: { ...good, liftEstimate: -0.01, confidenceInterval: { lower: -0.03, upper: 0.01 } }, createdBy: 'system:author', rejectedReason: 'Would flag our best loyalty-tier customers; needs a value threshold too.', rejectedAt: daysAgo(4) }),
  mk({ proposalId: 'prop_demo9', tier: 'T1', status: 'ROLLED_BACK', target: ['decision', 'sla_breach_risk'], changes: [{ type: 'ThresholdChange', decision: 'sla_breach_risk', band: 'act', from: 0.7, to: 0.62 }],
    evaluationReport: { ...good, liftEstimate: 0.02, confidenceInterval: { lower: 0.001, upper: 0.04 } }, createdBy: 'system:band_threshold', approvedBy: 'user:admin', approvedAt: daysAgo(20), promotedAt: daysAgo(19), rolledBackAt: daysAgo(17), rolledBackReason: 'Escalations to people went up 30% after going live.' }),
  mk({ proposalId: 'prop_demo10', tier: 'T1', status: 'EVALUATED', evaluatedAt: hoursAgo(3), evaluationReport: { method: 'no_counterfactual', sampleSize: 0, reason: 'sourcing.blocked_zip3 has only ever run at its current value' }, target: ['parameter', 'sourcing.blocked_zip3'], changes: [{ type: 'ParameterChange', parameter: 'sourcing.blocked_zip3', from: [], to: ['941'] }],
    createdBy: 'system:author', createdAt: hoursAgo(6), updatedAt: hoursAgo(6) }),
  mk({ proposalId: 'prop_demo13', tier: 'T1', status: 'APPROVED', target: ['parameter', 'live:promise.lane_calibration'], changes: [{ type: 'ParameterChange', parameter: 'live:promise.lane_calibration', from: { enabled: false }, to: { enabled: true, min: 0.5, max: 1, maxStep: 0.1, learner: 'lane_transit' } }],
    evaluationReport: { method: 'no_counterfactual', sampleSize: 0, reason: 'only 4 decisions that used a learned value and 210 decisions on static values with a reward (need 30 each)', next: 'run a shadow experiment, then promote within the learner max step; the promotion gate will ask for a person' },
    createdBy: 'system:lane_transit', approvedBy: 'user:strategy', approvedAt: hoursAgo(4), evaluatedAt: hoursAgo(9), createdAt: hoursAgo(10), updatedAt: hoursAgo(4) }),
];

const liveParameters: LiveParameter[] = [
  { parameter: 'sourcing.node_reject_rate', learnerKey: 'node_reliability', min: 0, max: 0.5, maxStep: 0.1, status: 'LIVE', proposalId: 'prop_demo6', promotedBy: SEED, promotedAt: daysAgo(12) },
  { parameter: 'sourcing.node_on_time_rate', learnerKey: 'node_reliability', min: 0.5, max: 1, maxStep: 0.15, status: 'LIVE', proposalId: 'prop_demo6', promotedBy: SEED, promotedAt: daysAgo(12) },
  { parameter: 'promise.lane_transit_days', learnerKey: 'lane_transit', min: 1, max: 10, maxStep: 1, status: 'LIVE', proposalId: 'prop_demo11', promotedBy: SEED, promotedAt: daysAgo(9) },
  { parameter: 'promise.buffer_days', learnerKey: 'promise_buffer', min: 0, max: 3, maxStep: 1, status: 'REVOKED', proposalId: 'prop_demo12', promotedBy: SEED, promotedAt: daysAgo(25), revokedBy: 'user:admin', revokedAt: daysAgo(18) },
];

const experiments: Experiment[] = [
  { experimentId: 'exp_holdout', proposalId: 'holdout', mode: 'HOLDOUT', trafficShare: 0.05, status: 'RUNNING', startAt: daysAgo(60), endAt: null, stopRule: {}, result: null, createdAt: daysAgo(60), targetRef: 'tenant', decisionName: null, candidateVersion: null, baselineVersion: null, createdBy: SEED },
  { experimentId: 'exp_canary1', proposalId: 'prop_demo5', mode: 'CANARY', trafficShare: 0.1, status: 'RUNNING', startAt: daysAgo(2), endAt: daysAgo(-12), stopRule: { alpha: 0.05 }, result: null, createdAt: daysAgo(2), targetRef: 'decision:fulfilment_intent', decisionName: 'fulfilment_intent', candidateVersion: 4, baselineVersion: 3, createdBy: SEED },
  { experimentId: 'exp_shadow1', proposalId: 'prop_demo1', mode: 'SHADOW', trafficShare: 1, status: 'RUNNING', startAt: hoursAgo(20), endAt: daysAgo(-7), stopRule: {}, result: null, createdAt: hoursAgo(20), targetRef: 'decision:sourcing_node_score', decisionName: 'sourcing_node_score', candidateVersion: 5, baselineVersion: 4, createdBy: 'user:strategy' },
  { experimentId: 'exp_shadow0', proposalId: 'prop_demo6', mode: 'SHADOW', trafficShare: 1, status: 'COMPLETED', startAt: daysAgo(16), endAt: daysAgo(13), stopRule: {}, result: { passed: true, reason: 'learned values were available for enough live decisions', decisionsAffected: 1840, factsHeldBack: 5120, meanShift: 0.031 }, createdAt: daysAgo(16), targetRef: 'parameter:live:sourcing.node_reject_rate', decisionName: null, candidateVersion: null, baselineVersion: null, createdBy: SEED },
];
const measurement: Record<string, Record<string, unknown>> = {
  exp_canary1: { candidateVersion: 4, baselineVersion: 3, trafficShare: 0.1, msprt: { n: 912, likelihoodRatio: 6.4, stop: false }, liftEstimate: 0.028, measuredAt: now() },
  exp_shadow1: { passed: true, reason: 'shadow matched live within guardrails', shadowVersion: 5, baselineVersion: 4, shadowRecords: 640, paired: 612, agreementRate: 0.93, shadowEscalationRate: 0.041, liveEscalationRate: 0.052, postCheckFailures: 0, shadowLatencyP95Ms: 118, liveLatencyP95Ms: 112, measuredAt: now() },
};

const metric = (m: string, better: 'higher' | 'lower', t: number, h: number, ci: number, n = 2400): LiftReport['metrics'][number] => {
  const lift = +(t - h).toFixed(4); const sig = Math.abs(lift) > ci;
  return { metric: m, betterWhen: better, nTreated: n, nHoldout: Math.round(n * 0.05), treatedMean: t, holdoutMean: h, lift, ciLower: +(lift - ci).toFixed(4), ciUpper: +(lift + ci).toFixed(4), significant: sig, improvement: sig && (better === 'higher' ? lift > 0 : lift < 0), enoughData: true };
};
const lift: LiftReport = {
  windowDays: 90, since: daysAgo(90), holdout: { experimentId: 'exp_holdout', trafficShare: 0.05, startAt: daysAgo(60) }, baselines: {},
  metrics: [metric('reward', 'higher', 0.642, 0.611, 0.014), metric('otd', 'higher', 0.931, 0.902, 0.018), metric('split', 'lower', 0.118, 0.142, 0.02), metric('cost', 'lower', 0.093, 0.095, 0.006), metric('cx', 'lower', 0.041, 0.044, 0.01), metric('fraud', 'lower', 0.004, 0.004, 0.003)],
  byDecision: {}, minPerArm: 30,
};

const coverage: Record<string, number> = { sourcing_node_score: 0.91, sourcing_policy_bandit: 0.88, payment_risk: 0.84, sla_breach_risk: 0.71 };

const metricsOf = (): LearnerMetrics[] => learners.map((l, i) => ({
  learnerKey: l.learnerKey, displayName: l.displayName, lastRunAt: l.lastRunAt, updateLagSeconds: l.lastRunAt ? Math.round((Date.now() - Date.parse(l.lastRunAt)) / 1000) : null,
  coverage: l.outcomeDecisions.length ? coverage[l.outcomeDecisions[0]] ?? null : null, proposalsDrafted: i % 3 === 0 ? 4 : i % 3, proposalsPromoted: i % 4 === 0 ? 1 : 0, proposalsRolledBack: l.learnerKey === 'band_threshold' ? 1 : 0,
  proposalYieldPerWeek: 0.4, promotionRate: 0.25, rollbackRate: l.learnerKey === 'band_threshold' ? 0.2 : 0, running: l.running, gateReason: l.gateReason,
}));

let objective: ObjectiveProfile = {
  version: 2, status: 'APPROVED', weights: { fill: 1, otd: 1.2, cost: 1, split: 0.9, fraud: 1.1, cx: 1, inv: 0.8 }, constraints: { freezeWindows: [{ from: '2026-11-24', to: '2026-12-02' }] },
  approvedBy: 'user:admin', approvedAt: daysAgo(30), createdBy: SEED, createdAt: daysAgo(31), sourceText: null, draftMethod: 'manual', rationale: {},
};
const objectiveDrafts: ObjectiveProfile[] = [
  { version: 3, status: 'DRAFT', weights: { fill: 1, otd: 1.5, cost: 0.8, split: 0.9, fraud: 1.2, cx: 1, inv: 0.8 }, constraints: objective.constraints, approvedBy: null, approvedAt: null, createdBy: 'user:strategy', createdAt: hoursAgo(3),
    sourceText: 'Peak season: delivering on time matters most, cost a bit less, never accept more fraud', draftMethod: 'template', rationale: { otd: 'you said on-time delivery matters most', cost: 'you said cost matters a bit less', fraud: 'you said never accept more fraud' } },
];
const objectiveHistory: ObjectiveProfile[] = [objective, { ...objective, version: 1, status: 'RETIRED', weights: { fill: 1, otd: 1, cost: 1, split: 1, fraud: 1, cx: 1, inv: 1 }, approvedAt: daysAgo(90), createdAt: daysAgo(91) }];

const golden: GoldenCandidate[] = [
  { hypothesisId: 'hyp_gold1', decision: 'return_disposition', expectedValue: 'refurbish', sourceRecordIds: ['dr_a1', 'dr_a2', 'dr_a3'], agreeingResolutions: 7, rationale: 'Opened-box electronics with intact seals are always sent to refurbish', createdAt: daysAgo(2) },
  { hypothesisId: 'hyp_gold2', decision: 'exception_classify', expectedValue: 'address', sourceRecordIds: ['dr_b1'], agreeingResolutions: 5, rationale: null, createdAt: daysAgo(5) },
];

const digest: Digest = {
  digestId: 'dig_1', periodStart: daysAgo(7), periodEnd: now(), deliveredTo: [], delivery: 'stored_only', createdBy: 'system:digest', createdAt: hoursAgo(30),
  summary: {
    headline: '2 changes went live and 1 was rolled back. Overall outcome score up 0.031 against the holdout; 3 changes need your review.',
    changed: {
      promoted: [{ proposal_id: 'prop_demo6', target_ref: 'parameter:live:sourcing.node_reject_rate', tier: 'T1' }, { proposal_id: 'prop_demo7', target_ref: 'decision:split_acceptability', tier: 'T1' }],
      rolledBack: [{ proposal_id: 'prop_demo9', target_ref: 'decision:sla_breach_risk', rolled_back_reason: 'Escalations up 30%' }],
      learnerChanges: [{ type: 'learner.paused', learner: 'warn_early', reason: 'outcome coverage' }],
      factsLearned: [{ fact_key: 'NODE_ON_TIME_RATE', versions: 84, subjects: 12 }],
    },
    impact: { rewardByDecision: [{ decision: 'sourcing_node_score', maturedEpisodes: 1840, meanReward: 0.648, previousMeanReward: 0.621, change: 0.027 }, { decision: 'payment_risk', maturedEpisodes: 610, meanReward: 0.702, previousMeanReward: 0.709, change: -0.007 }], coverage },
    pending: { proposalsByStatus: [{ status: 'DRAFT', proposals: 3 }], objectiveDrafts: 1, openHypotheses: 2, pausedLearners: [{ learner_key: 'warn_early', paused_reason: 'outcome coverage' }] },
  },
};

/** Mirrors ProposalLifecycle (defect 2): the moves allowed from each status. */
const LIFECYCLE: Record<ProposalStatus, ProposalStatus[]> = {
  DRAFT: ['EVALUATED', 'REJECTED'], EVALUATED: ['EVALUATED', 'APPROVED', 'REJECTED', 'CANARY', 'PROMOTED'], APPROVED: ['PROMOTED', 'CANARY', 'REJECTED'],
  CANARY: ['APPROVED', 'EVALUATED', 'REJECTED', 'PROMOTED', 'ROLLED_BACK'], PROMOTED: ['ROLLED_BACK'], REJECTED: [], ROLLED_BACK: [],
};
const STATUS_PHRASE: Record<ProposalStatus, string> = {
  DRAFT: 'a draft that has not been tested yet', EVALUATED: 'tested and waiting for approval', APPROVED: 'approved and waiting to go live', CANARY: 'being tried on some customers',
  PROMOTED: 'live', REJECTED: 'rejected', ROLLED_BACK: 'rolled back',
};
const NEXT_STEP: Record<ProposalStatus, string> = {
  DRAFT: 'Test it on past decisions first.', EVALUATED: 'Approve, reject or try it first.', APPROVED: 'It can go live, be tried on some customers, or be rejected.',
  CANARY: 'Wait for the trial to finish, or roll it back.', PROMOTED: 'The only thing left is to roll it back.', REJECTED: 'It is closed; draft a new change to try again.', ROLLED_BACK: 'It is closed; draft a new change to try again.',
};
const allowed = (p: Proposal, to: ProposalStatus) => LIFECYCLE[p.status].includes(to);
const promotionNotes: Record<string, { by: string; reason: string; waived: string[]; at: string }> = {};
let globalOff: { by: string; at: string } | null = null;

/** EX-07: what a person would waive by promoting now (mirrors PromotionGuard.concerns for the demo data). */
function concernsOf(p: Proposal): string[] {
  const r = p.evaluationReport;
  const out: string[] = [];
  const ci = r?.confidenceInterval;
  if (!ci) out.push(p.changeset.target.type === 'decision' ? 'it has no replay evaluation on past decisions' : 'this kind of change cannot be replayed and no shadow run has passed');
  else if (ci.upper < 0) out.push('replay shows it makes outcomes worse');
  else if (ci.lower <= 0) out.push('replay shows no clear improvement (the confidence interval includes zero)');
  if (r?.shadow && r.shadow.passed === false) out.push('the shadow run did not pass');
  if (p.criticReview?.verdict === 'RISKY') out.push('the critic review flagged it as risky');
  return out;
}
const blockerOf = (): string | null => (globalOff ? 'learning is switched off (global kill switch); nothing is promoted until it is back on'
  : tenantPaused ? 'learning is switched off (tenant kill switch); nothing is promoted until it is back on' : null);

function card(p: Proposal): ChangeCard {
  const h = hypotheses.find((x) => x.hypothesisId === p.hypothesisId) ?? null;
  const viewer = me();
  const diff = p.changeset.changes.flatMap((c) => c.type === 'ThresholdChange' ? [{ key: `bands.${c.band}`, type: 'threshold', from: c.from, to: c.to, steps: 1 }]
    : c.type === 'WeightChange' ? [{ key: `${c.target.replace(/^config:/, '')}.${c.weightKey}`, type: 'weight', from: c.from, to: c.to, steps: 1 }]
    : c.type === 'ParameterChange' ? [{ key: c.parameter, type: 'parameter', from: c.from, to: c.to, steps: 1 }] : []);
  const r = p.evaluationReport;
  return {
    proposal: p, hypothesis: h, evidence: h?.evidence ?? [], criticReview: p.criticReview,
    replay: r ? { evaluated: true, method: r.method, liftEstimate: r.liftEstimate, confidenceInterval: r.confidenceInterval, sampleSize: r.sampleSize, shadow: r.shadow } : { evaluated: false },
    diff,
    cumulative: { target: `${p.changeset.target.type}:${p.changeset.target.name}`, baseline: p.changeset.target.name === 'sourcing_node_score' ? { proposalId: 'prop_old', approvedBy: 'user:admin', approvedAt: daysAgo(40), promotedAt: daysAgo(40) } : null,
      automaticPromotionsSinceBaseline: p.changeset.target.name === 'sourcing_node_score' ? 2 : 0,
      sinceBaseline: p.changeset.target.name === 'sourcing_node_score' ? [{ key: 'bands.act', type: 'threshold', from: 0.86, to: 0.82, steps: 2 }] : [],
      withPending: p.changeset.target.name === 'sourcing_node_score' ? [{ key: 'bands.act', type: 'threshold', from: 0.86, to: 0.78, steps: 3 }] : diff, promotedProposalIds: [] },
    actions: {
      canApprove: allowed(p, 'APPROVED') && viewer !== p.createdBy, canReject: allowed(p, 'REJECTED'),
      reason: !allowed(p, 'APPROVED') ? (p.status === 'DRAFT' ? 'test it on past decisions before approving' : `proposal is ${p.status}`) : viewer === p.createdBy ? 'the author of a proposal cannot approve it' : null,
      canEvaluate: allowed(p, 'EVALUATED'), canPromote: p.status === 'APPROVED' && !blockerOf(), canRollback: p.status === 'PROMOTED' || p.status === 'CANARY',
      canExperiment: p.status === 'DRAFT' || p.status === 'EVALUATED' || p.status === 'APPROVED',
      promotionConcerns: p.status === 'APPROVED' ? concernsOf(p) : [], promotionBlocker: p.status === 'APPROVED' ? blockerOf() : null,
    },
    promotionNote: promotionNotes[p.proposalId] ?? null,
  };
}

export function registerLearningRoutes({ on, problem }: { on: On; problem: Problem }) {
  const findLearner = (key: string) => learners.find((x) => x.learnerKey === key) ?? problem(404, 'not_found', `No learner '${key}'`);
  const findProposal = (id: string) => proposals.find((x) => x.proposalId === id) ?? problem(404, 'not_found', `Proposal ${id} not found`);
  const ks = (): KillSwitchState => ({
    globalKillSwitch: !!globalOff, globalSource: globalOff ? 'runtime' : null, globalSetBy: globalOff?.by ?? null, globalSetAt: globalOff?.at ?? null,
    tenantPaused, pausedLearners: learners.filter((l) => !l.enabled || l.paused).map((l) => l.learnerKey),
    canSetGlobal: (getSession().principal?.permissions ?? []).includes('*'),
  });
  const refuse = (p: Proposal, action: string): never => problem(409, 'conflict', `Cannot ${action} this change: it is ${STATUS_PHRASE[p.status]}. ${NEXT_STEP[p.status]}`, { proposalId: p.proposalId, status: p.status, action });

  on('GET', '/api/v1/learning/learners', () => learners);
  on('GET', '/api/v1/learning/learners/:id', ({ params }) => findLearner(params.id));
  on('PUT', '/api/v1/learning/learners/:id/autonomy', ({ params, body }) => { const l = findLearner(params.id); l.autonomyLevel = body.autonomyLevel as AutonomyLevel; l.updatedAt = now(); l.updatedBy = me(); return l; });
  on('POST', '/api/v1/learning/learners/:id/resume', ({ params }) => { const l = findLearner(params.id); l.paused = false; l.pausedReason = null; regate(); return l; });
  on('POST', '/api/v1/learning/learners/:id/run', ({ params }) => {
    const l = findLearner(params.id);
    if (EVENT_DRIVEN.has(l.learnerKey)) return problem(422, 'validation_failed', `${l.displayName} learns from events as they happen and has no on-demand run`);
    l.lastRunAt = now(); return l;
  });
  on('POST', '/api/v1/learning/jobs/:job/run', ({ params }) => ({ job: params.job, result: 0 }));
  on('GET', '/api/v1/learning/kill-switch', () => ks());
  on('POST', '/api/v1/learning/kill-switch', ({ body }) => {
    const key = body.learnerKey as string | undefined;
    if (body.scope === 'global') {
      if (!ks().canSetGlobal) return problem(403, 'forbidden', 'Only a platform administrator can switch learning off or on for every tenant');
      globalOff = body.enabled ? null : { by: me(), at: now() };
    } else if (!key || key === '*') tenantPaused = !body.enabled; else findLearner(key).enabled = Boolean(body.enabled);
    regate(); return ks();
  });
  on('PUT', '/api/v1/learning/learners/:id/budget', ({ params, body }) => {
    const l = findLearner(params.id);
    const maxStep = body.maxStep as number | null | undefined; const perWeek = body.maxPromotionsPerWeek as number | null | undefined;
    if (maxStep != null && !(maxStep > 0)) return problem(422, 'validation_failed', 'maxStep must be a positive number');
    if (perWeek != null && perWeek < 0) return problem(422, 'validation_failed', 'maxPromotionsPerWeek must be zero or more');
    if (body.clearMaxStep) l.maxStep = null; else if (maxStep != null) l.maxStep = maxStep;
    if (perWeek != null) l.maxPromotionsPerWeek = perWeek;
    l.updatedAt = now(); l.updatedBy = me(); return l;
  });
  on('GET', '/api/v1/learning/metrics', () => metricsOf());
  on('GET', '/api/v1/learning/coverage', () => coverage);

  on('GET', '/api/v1/learning/live-parameters', () => liveParameters);
  on('POST', '/api/v1/learning/live-parameters/proposals', ({ body }) => {
    const family = String(body.family ?? ''); const learnerKey = String(body.learnerKey ?? '');
    const l = findLearner(learnerKey);
    if (!l.liveParameters.includes(family)) return problem(422, 'validation_failed', `${l.displayName} does not feed '${family}'; it feeds [${l.liveParameters.join(', ')}]`);
    const p = mk({ proposalId: nextId('prop'), tier: 'T1', status: 'DRAFT', target: ['parameter', `live:${family}`], createdBy: me(), createdAt: now(), updatedAt: now(),
      changes: [{ type: 'ParameterChange', parameter: `live:${family}`, from: { enabled: false }, to: { enabled: body.enabled !== false, min: body.min, max: body.max, maxStep: body.maxStep, learner: learnerKey } }] });
    proposals.unshift(p); return p;
  });
  on('POST', '/api/v1/learning/live-parameters/:family/revoke', ({ params }) => {
    const lp = liveParameters.find((x) => x.parameter === params.family && x.status === 'LIVE');
    if (lp) { lp.status = 'REVOKED'; lp.revokedBy = me(); lp.revokedAt = now(); }
    // Defect 27: switching live use off closes the proposal that switched it on.
    const p = lp?.proposalId ? proposals.find((x) => x.proposalId === lp.proposalId && x.status === 'PROMOTED') : undefined;
    if (p) { p.status = 'ROLLED_BACK'; p.rolledBackAt = now(); p.rolledBackReason = `live use of ${params.family} switched off by ${me()}`; p.updatedAt = now(); }
    return { family: params.family, revoked: !!lp };
  });

  on('GET', '/api/v1/learning/facts', ({ query }) => facts[`${query.get('subjectType')}:${query.get('subjectId')}`] ?? []);

  on('GET', '/api/v1/learning/proposals', ({ query }) => proposals.filter((p) => p.status === (query.get('status') ?? 'DRAFT')));
  on('GET', '/api/v1/learning/proposals/:id', ({ params }) => findProposal(params.id));
  on('GET', '/api/v1/learning/proposals/:id/card', ({ params }) => card(findProposal(params.id)));
  on('GET', '/api/v1/learning/diff', ({ query }) => ({ target: query.get('target') ?? '', baseline: null, automaticPromotionsSinceBaseline: 0, sinceBaseline: [], withPending: [], promotedProposalIds: [] }));
  on('POST', '/api/v1/learning/proposals/:id/approve', ({ params, body }) => {
    const p = findProposal(params.id);
    if (!allowed(p, 'APPROVED')) return refuse(p, 'approve');
    if (p.createdBy === me()) return problem(422, 'validation_failed', 'The author of a proposal cannot approve it themselves');
    const postal = /zip|postal/i.test(JSON.stringify(p.changeset));
    if (postal && !body?.reasonCode) return problem(400, 'validation_failed', 'This change is scoped to postal codes, so approving it needs a reason code: one of [carrier_service_gap, confirmed_fraud_ring, regulatory_restriction, natural_disaster]');
    p.status = 'APPROVED'; p.approvedBy = me(); p.approvedAt = now(); p.updatedAt = p.approvedAt;
    return p;
  });
  on('POST', '/api/v1/learning/proposals/:id/reject', ({ params, body }) => { const p = findProposal(params.id); if (!allowed(p, 'REJECTED')) return refuse(p, 'reject'); p.status = 'REJECTED'; p.rejectedReason = (body.reason as string) ?? null; p.rejectedAt = now(); p.updatedAt = now(); return p; });
  on('POST', '/api/v1/learning/proposals/:id/evaluate', ({ params }) => {
    const p = findProposal(params.id);
    if (!allowed(p, 'EVALUATED')) return refuse(p, 're-test');
    // Re-testing replaces the replay but keeps a shadow or canary verdict already on the report (defect 3).
    const prev = p.evaluationReport;
    const kept = { ...(prev?.shadow ? { shadow: prev.shadow } : {}), ...(prev?.canary ? { canary: prev.canary } : {}) };
    p.evaluationReport = { ...(p.changeset.target.type === 'decision'
      ? { ...good, sampleSize: 318, liftEstimate: 0.024, confidenceInterval: { lower: 0.003, upper: 0.045 }, ips: { proposedRewardEstimate: 0.66, liftEstimate: 0.021, confidenceInterval: { lower: -0.004, upper: 0.046 } }, rewards: { mature: 240, provisional: 78, matureShare: 0.755 } }
      : { method: 'no_counterfactual', liftEstimate: null, confidenceInterval: null }), ...kept };
    p.status = 'EVALUATED'; p.evaluatedAt = now(); p.updatedAt = now(); return p;
  });
  on('POST', '/api/v1/learning/proposals/:id/promote', ({ params, body }) => {
    const p = findProposal(params.id);
    if (!allowed(p, 'PROMOTED')) return refuse(p, 'promote');
    const blocker = blockerOf();
    if (blocker) return problem(409, 'conflict', `Cannot promote: ${blocker}`);
    if (p.status !== 'APPROVED') return problem(409, 'conflict', `Cannot promote: proposal must be APPROVED before a person can promote it (it is ${p.status})`);
    const concerns = concernsOf(p);
    const reason = String(body?.overrideReason ?? '').trim();
    if (concerns.length > 0 && reason.length < 10) {
      return problem(409, 'promotion_needs_override', `Promoting this change needs a written reason, because ${concerns.join('; ')}`, { proposalId: p.proposalId, waivable: concerns });
    }
    if (concerns.length > 0) promotionNotes[p.proposalId] = { by: me(), reason, waived: concerns, at: now() };
    p.status = 'PROMOTED'; p.promotedAt = now(); p.updatedAt = now(); return p;
  });
  on('POST', '/api/v1/learning/promotions/:id/rollback', ({ params, body }) => {
    const p = findProposal(params.id);
    if (p.status !== 'PROMOTED' && p.status !== 'CANARY') return refuse(p, 'roll back');
    // Rolling back a trial stops it (EX-05 during rollout); the candidate never went live.
    if (p.status === 'CANARY') experiments.filter((e) => e.proposalId === p.proposalId && e.mode === 'CANARY' && e.status === 'RUNNING').forEach((e) => { e.status = 'STOPPED'; e.endAt = now(); e.result = { rolledBack: true, reason: (body.reason as string) ?? null }; });
    p.status = 'ROLLED_BACK'; p.rolledBackAt = now(); p.rolledBackReason = (body.reason as string) ?? null; p.updatedAt = now(); return p;
  });

  on('GET', '/api/v1/learning/objective', () => objective);
  on('PUT', '/api/v1/learning/objective', ({ body }) => {
    const d: ObjectiveProfile = { version: Math.max(objective.version, ...objectiveDrafts.map((x) => x.version)) + 1, status: 'DRAFT', weights: body.weights as ObjectiveWeights, constraints: (body.constraints as Record<string, unknown>) ?? {}, approvedBy: null, approvedAt: null, createdBy: me(), createdAt: now(), sourceText: null, draftMethod: 'manual', rationale: {} };
    objectiveDrafts.unshift(d); return d;
  });
  on('POST', '/api/v1/learning/objective/draft', ({ body }) => {
    const text = String(body.text ?? '');
    const w = { ...objective.weights, otd: /on.?time|fast|speed/i.test(text) ? 1.6 : objective.weights.otd, cost: /cost|cheap|margin/i.test(text) ? 1.5 : objective.weights.cost };
    const d: ObjectiveProfile = { version: Math.max(objective.version, ...objectiveDrafts.map((x) => x.version)) + 1, status: 'DRAFT', weights: w, constraints: objective.constraints, approvedBy: null, approvedAt: null, createdBy: me(), createdAt: now(), sourceText: text, draftMethod: 'template', rationale: { otd: 'from your description' } };
    objectiveDrafts.unshift(d); return d;
  });
  on('GET', '/api/v1/learning/objective/drafts', () => objectiveDrafts);
  on('GET', '/api/v1/learning/objective/history', () => objectiveHistory);
  on('POST', '/api/v1/learning/objective/:version/approve', ({ params }) => {
    const i = objectiveDrafts.findIndex((d) => d.version === Number(params.version));
    if (i < 0) return problem(404, 'not_found', `No draft version ${params.version}`);
    if (objectiveDrafts[i].createdBy === me()) return problem(422, 'validation_failed', 'The person who drafted an objective cannot approve it');
    objective = { ...objectiveDrafts[i], status: 'APPROVED', approvedBy: me(), approvedAt: now() };
    objectiveDrafts.splice(i, 1); objectiveHistory.unshift(objective); return objective;
  });

  on('GET', '/api/v1/learning/experiments', ({ query }) => (query.get('all') === 'true' ? experiments : experiments.filter((e) => e.status === 'RUNNING')));
  on('GET', '/api/v1/learning/experiments/:id', ({ params }) => {
    const e = experiments.find((x) => x.experimentId === params.id) ?? problem(404, 'not_found', `Experiment ${params.id} not found`);
    const p = proposals.find((x) => x.proposalId === e.proposalId);
    return { experiment: e, proposal: p ? { proposalId: p.proposalId, target: `${p.changeset.target.type}:${p.changeset.target.name}`, status: p.status, tier: p.tier, changeTypes: p.changeset.changes.map((c) => c.type) } : undefined, measurement: e.status === 'RUNNING' ? measurement[e.experimentId] ?? {} : e.result };
  });
  on('POST', '/api/v1/learning/experiments', ({ body }) => {
    const p = findProposal(String(body.proposalId));
    const mode = body.mode as Experiment['mode'];
    if (experiments.some((e) => e.status === 'RUNNING' && e.mode === mode && e.targetRef === `${p.changeset.target.type}:${p.changeset.target.name}`)) return problem(409, 'conflict', `An experiment already runs ${mode} on this change; only one per change at a time`);
    const e: Experiment = { experimentId: nextId('exp'), proposalId: p.proposalId, mode, trafficShare: mode === 'SHADOW' ? 1 : Number(body.trafficShare), status: 'RUNNING', startAt: now(), endAt: null, stopRule: (body.stopRule as Record<string, unknown>) ?? {}, result: null, createdAt: now(), targetRef: `${p.changeset.target.type}:${p.changeset.target.name}`, decisionName: p.changeset.target.type === 'decision' ? p.changeset.target.name : null, candidateVersion: 9, baselineVersion: 8, createdBy: me() };
    experiments.unshift(e); if (mode === 'CANARY') p.status = 'CANARY'; return e;
  });
  on('POST', '/api/v1/learning/experiments/:id/complete', ({ params }) => {
    const e = experiments.find((x) => x.experimentId === params.id) ?? problem(404, 'not_found', 'not found');
    const minDays = typeof e.stopRule?.minDurationDays === 'number' ? e.stopRule.minDurationDays : 7;
    if (e.mode === 'CANARY' && Date.now() < new Date(e.startAt).getTime() + minDays * 86_400_000) {
      return problem(409, 'conflict', `This trial has run ${((Date.now() - new Date(e.startAt).getTime()) / 86_400_000).toFixed(1)} of its minimum ${minDays} days (EX-03); stop it instead if it has to end now`);
    }
    e.status = 'COMPLETED'; e.endAt = now(); e.result = measurement[e.experimentId] ?? { passed: true }; return e; });
  on('POST', '/api/v1/learning/experiments/:id/stop', ({ params }) => { const e = experiments.find((x) => x.experimentId === params.id) ?? problem(404, 'not_found', 'not found'); e.status = 'STOPPED'; e.endAt = now(); return undefined; });
  on('POST', '/api/v1/learning/experiments/holdout', ({ query }) => {
    const existing = experiments.find((e) => e.mode === 'HOLDOUT' && e.status === 'RUNNING');
    if (existing) return existing;
    const e: Experiment = { experimentId: nextId('exp'), proposalId: 'holdout', mode: 'HOLDOUT', trafficShare: Number(query.get('share') ?? 0.05), status: 'RUNNING', startAt: now(), endAt: null, stopRule: {}, result: null, createdAt: now(), targetRef: 'tenant', createdBy: me() };
    experiments.push(e); return e;
  });
  on('GET', '/api/v1/learning/lift', () => lift);

  on('GET', '/api/v1/learning/fairness/features', () => learners.map((l) => ({ learner: l.learnerKey, declaredFeatures: [], protectedFeatures: [] })));
  on('GET', '/api/v1/learning/fairness/:decision', ({ params }) => {
    const r: ParityReport = {
      decision: params.decision, grouping: 'segment',
      groups: [
        { group: 'seg_value_shoppers', label: 'Value shoppers', customers: 212, decisions: 1430, actBandRate: 0.71, overrideRate: 0.04 },
        { group: 'seg_speed_seekers', label: 'Speed seekers', customers: 340, decisions: 2210, actBandRate: 0.74, overrideRate: 0.03 },
        { group: 'seg_pickup_regulars', label: 'Pickup regulars', customers: 188, decisions: 960, actBandRate: 0.69, overrideRate: 0.05 },
      ],
      tenantActBandRate: 0.72, tenantOverrideRate: 0.04, featureAudit: [], flags: [],
    };
    return r;
  });
  on('GET', '/api/v1/learning/digests', () => [digest]);
  on('GET', '/api/v1/learning/digests/latest', () => digest);
  on('POST', '/api/v1/learning/digests/run', () => ({ ...digest, digestId: nextId('dig'), createdAt: now() }));
  on('POST', '/api/v1/learning/retention/run', () => ({ retentionDays: 400, cutoff: daysAgo(400), dryRun: true, outcomes: 0, episodes: 0, supersededFacts: 1240, digests: 0 }));

  on('GET', '/api/v1/learning/golden-candidates', () => golden);
  on('POST', '/api/v1/learning/golden-candidates/:id/sign-off', ({ params }) => { const i = golden.findIndex((g) => g.hypothesisId === params.id); if (i < 0) return problem(404, 'not_found', 'not found'); const g = golden.splice(i, 1)[0]; return { id: nextId('gc'), input: {}, expected: g.expectedValue }; });
  on('POST', '/api/v1/learning/golden-candidates/:id/reject', ({ params }) => { const i = golden.findIndex((g) => g.hypothesisId === params.id); if (i >= 0) golden.splice(i, 1); return { hypothesisId: params.id, status: 'REJECTED' }; });

  on('POST', '/api/v1/learning/llm/analyst/run', () => hypotheses);
  on('POST', '/api/v1/learning/llm/author/:id', ({ params }) => proposals.find((p) => p.hypothesisId === params.id) ?? problem(404, 'not_found', `No proposal drafted for hypothesis ${params.id}`));
  on('POST', '/api/v1/learning/llm/critic/:id', ({ params }) => {
    const p = findProposal(params.id);
    if (!p.evaluationReport) return problem(404, 'not_found', `No evaluation report yet for proposal ${params.id}`);
    const review: Review = { verdict: 'LOOKS_SAFE', risks: [] };
    return review;
  });
}
