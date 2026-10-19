/* Plain-language wording for the self-optimizing layer, shared by every learning screen and the Why drawer. */
import type { AutonomyLevel, ExperimentMode, LiftMetric, PolicyChange, Proposal, ProposalStatus, RiskTier } from '../../api/types';
import { humanize } from '../../lib/format';

export const AUTONOMY_LEVELS: AutonomyLevel[] = ['L0_OBSERVE', 'L1_RECOMMEND', 'L2_ACT_WITH_APPROVAL', 'L3_AUTONOMOUS'];
export const AUTONOMY: Record<AutonomyLevel, { label: string; short: string; explain: string }> = {
  L0_OBSERVE: { label: 'Watching only', short: 'L0', explain: 'Measures and learns, but never suggests or changes anything.' },
  L1_RECOMMEND: { label: 'Suggests changes', short: 'L1', explain: 'Drafts changes for a person to review. Only the smallest, tested changes go live by themselves.' },
  L2_ACT_WITH_APPROVAL: { label: 'Acts with your approval', short: 'L2', explain: 'Low-risk changes go live after testing; anything bigger waits for your approval.' },
  L3_AUTONOMOUS: { label: 'Acts on its own within limits', short: 'L3', explain: 'Applies low and medium-risk changes itself, inside its weekly budget and step limits.' },
};

export const TIER_WORDS: Record<RiskTier, string> = {
  T0: 'Very low risk', T1: 'Low risk, reversible', T2: 'Affects customers', T3: 'Money-moving, never automatic',
};

/** What a proposal status means to a reader, and the filter group it belongs to. */
export const STATUS_WORDS: Record<ProposalStatus, string> = {
  DRAFT: 'Waiting for review', EVALUATED: 'Tested, waiting for review', APPROVED: 'Approved, not live yet', CANARY: 'Being tried on some customers',
  PROMOTED: 'Live', REJECTED: 'Rejected', ROLLED_BACK: 'Rolled back',
};
export const STATUS_TONE: Record<ProposalStatus, string> = {
  DRAFT: 'neutral', EVALUATED: 'info', APPROVED: 'accent', CANARY: 'ai', PROMOTED: 'ok', REJECTED: 'danger', ROLLED_BACK: 'warn',
};

export const MODE_WORDS: Record<ExperimentMode, { label: string; explain: string }> = {
  SHADOW: { label: 'Shadow test', explain: 'Runs alongside the live decision and is compared with it. It never acts.' },
  CANARY: { label: 'Trial on some customers', explain: 'A fixed share of customers sees the change; the same customer always sees the same version.' },
  HOLDOUT: { label: 'Holdout', explain: 'A small share of customers stays on the original behaviour, so the effect of learning can be measured honestly.' },
};

/** Why a learner is or isn't running, from the governance gate. */
export function gateWords(reason: string | null | undefined): string {
  if (!reason || reason === 'ok') return 'Running';
  if (reason === 'global_kill_switch') return 'Stopped for every tenant by the operator';
  if (reason === 'tenant_kill_switch') return 'All learning is paused';
  if (reason === 'tenant_learning_disabled') return 'Learning is turned off for this tenant';
  if (reason === 'learner_kill_switch') return 'Switched off';
  if (reason.startsWith('paused: ')) return `Paused: ${reason.slice('paused: '.length)}`;
  return humanize(reason);
}

export const LEARNER_AREAS: { prefix: string[]; title: string; caption: string }[] = [
  { prefix: ['FS', 'CX'], title: 'Fulfilment and delivery promises', caption: 'Which store or warehouse ships, and how honest the promised date is.' },
  { prefix: ['FD'], title: 'Fraud and abuse', caption: 'Catching bad orders without blocking good customers.' },
  { prefix: ['IU'], title: 'Inventory', caption: 'Safety stock, cart holds, transfers and aged stock.' },
  { prefix: ['CI'], title: 'Customers', caption: 'Preferences and groups of customers who behave alike.' },
  { prefix: ['DF'], title: 'Decision quality', caption: 'Thresholds, confidence, test cases, rules and AI providers.' },
];
export function areaOf(requirement: string | null | undefined): string {
  const first = (requirement ?? '').trim().slice(0, 2).toUpperCase();
  return LEARNER_AREAS.find((a) => a.prefix.includes(first))?.title ?? 'Other';
}

/** Learners that update on every event instead of a batch run, so "Run now" does not apply. */
export const EVENT_DRIVEN = new Set(['node_reliability', 'lane_transit', 'inventory_backorder', 'sourcing_policy_bandit']);

const METRIC: Record<string, string> = {
  reward: 'Overall outcome score', fill: 'Units shipped', otd: 'On-time delivery', cost: 'Cost to serve', split: 'Split shipments',
  fraud: 'Fraud loss', cx: 'Customer contacts', inv: 'Aged stock sold', perfect: 'Perfect orders', perfect_order_rate: 'Perfect-order rate',
};
export const metricName = (m: string): string => METRIC[m] ?? humanize(m);

/** One sentence for a lift result, in words a first-timer can act on. */
export function liftVerdict(m: LiftMetric): { text: string; tone: 'ok' | 'warn' | 'danger' | 'neutral' } {
  if (m.lift === null || m.lift === undefined || m.nHoldout < 1 || m.nTreated < 1 || m.enoughData === false) return { text: 'Not enough data yet', tone: 'neutral' };
  if (!m.significant) return { text: 'No clear difference yet', tone: 'neutral' };
  return m.improvement ? { text: 'Better with learning', tone: 'ok' } : { text: 'Worse with learning', tone: 'danger' };
}

/** A decision or config key as a person would write it: "Sourcing node score". */
export function decisionLabel(name: string | null | undefined): string {
  const s = humanize((name ?? '').replace(/\./g, ' ')).replace(/\b(sla|rto|ai|cs|ttl|cod|psp|zip3)\b/gi, (m) => m.toUpperCase());
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : '';
}

/** An artifact reference ("decision:x", "parameter:live:f", "config:k") as a short readable name. */
export function targetName(ref: string | null | undefined): string {
  const r = ref ?? '';
  if (r.startsWith('parameter:live:')) return `${familyName(r.slice('parameter:live:'.length))} (used live)`;
  if (r.startsWith('live:')) return `${familyName(r.slice('live:'.length))} (used live)`;
  return decisionLabel(r.replace(/^(decision|parameter|config):/, ''));
}

/** Relative change against a baseline, e.g. +5.1%, or null when the baseline is zero or missing. */
export function relativeChange(value: number | null | undefined, baseline: number | null | undefined): number | null {
  if (value == null || baseline == null || baseline === 0) return null;
  return (value - baseline) / Math.abs(baseline);
}

const FAMILY: Record<string, string> = {
  'sourcing.node_reject_rate': 'Store and warehouse reject rates', 'sourcing.node_on_time_rate': 'Store and warehouse on-time rates',
  'sourcing.reject_penalty': 'Penalty for nodes that reject orders', 'sourcing.node_backlog_breach': 'Backlog-to-late-delivery curve',
  'sourcing.policy_bandit': 'Trying alternative sourcing policies', 'sourcing.aged_stock_tiebreak': 'Prefer aged stock when costs are close',
  'promise.lane_transit_days': 'Carrier transit times', 'promise.lane_calibration': 'Promise accuracy by carrier lane',
  'promise.buffer_days': 'Promise date buffer', 'monitoring.warn_early_threshold': 'When to warn customers about delays',
  'cs.appeasement_level': 'Goodwill credit amount', 'decision.calibration': 'Confidence calibration', 'decision.provider': 'Which AI provider to ask first',
  'inventory.safety_stock': 'Safety stock', 'inventory.inbound_reliability': 'Supplier delivery reliability',
  'inventory.reservation_ttl': 'How long carts hold stock', 'customer.preferences': 'Customer preferences',
  'fraud.agent_reputation': 'AI agent reputation', 'fraud.agent_dispute_rate': 'AI agent dispute rate', 'fraud.address_reuse_count': 'Shared delivery addresses',
};
export const familyName = (f: string): string => FAMILY[f] ?? humanize(f.replace(/\./g, ' · '));

const FACT: Record<string, string> = {
  NODE_ON_TIME_RATE: 'on-time rate', NODE_REJECT_RATE: 'reject rate', NODE_REJECT_PENALTY: 'reject penalty', NODE_BACKLOG_BREACH_CURVE: 'backlog risk curve',
  LANE_TRANSIT_DAYS_P50: 'typical transit days', LANE_TRANSIT_DAYS_P90: 'slow-case transit days', LANE_ON_TIME_RATE: 'lane on-time rate',
  LANE_CALIBRATION_OFFSET: 'promise correction', SPLIT_TOLERANCE: 'tolerance for split shipments', SPEED_VS_COST: 'speed vs cost preference',
  PICKUP_PREFERENCE: 'prefers pickup', PREFERRED_CONTACT_CHANNEL: 'preferred contact channel', AGENT_REPUTATION: 'agent reputation',
  DISPUTE_RATE: 'dispute rate', ADDRESS_REUSE_COUNT: 'customers sharing this address', STOCK_AGE_DAYS: 'stock age (days)',
  SAFETY_STOCK_UNITS: 'safety stock', INBOUND_RELIABILITY: 'delivery reliability', CALIBRATION_CURVE: 'calibration curve',
  RESERVATION_TTL_MINUTES: 'cart hold time (minutes)', PROMISE_BUFFER_DAYS: 'promise buffer (days)', WARN_EARLY_THRESHOLD: 'early-warning threshold',
  APPEASEMENT_LEVEL: 'goodwill credit', SEGMENT_MEMBERSHIP: 'customer group', RTO_RISK: 'refused-delivery risk', FALSE_POSITIVE_RATE: 'false-alarm rate',
};
export const factName = (k: string): string => FACT[k] ?? humanize(k.toLowerCase());

const SUBJECT: Record<string, string> = {
  NODE: 'Node', LANE: 'Carrier lane', CUSTOMER: 'Customer', SEGMENT: 'Customer group', SKU: 'Product', SKU_NODE: 'Product at node',
  AGENT: 'AI agent', DECISION: 'Decision', SUPPLIER: 'Supplier', ADDRESS: 'Address', CARRIER: 'Carrier',
};
export const subjectName = (t: string): string => SUBJECT[t?.toUpperCase()] ?? humanize(t);

/** Why a learned value was or wasn't used, in plain words. */
export function servedWhy(reason: string | null | undefined, source: string | null | undefined): string {
  const r = (reason ?? '').toLowerCase();
  if (source === 'LEARNED') {
    if (r.includes('max step')) return 'used, limited to the allowed step';
    if (r.includes('minimum') || r.includes('maximum')) return 'used, kept inside the allowed range';
    return 'used';
  }
  if (r.includes('holdout')) return 'not used: this customer is in the comparison group that keeps the standard values';
  if (r.includes('not promoted')) return 'not used: not switched on for live decisions yet';
  if (r.includes('l0') || r.includes('observe')) return 'not used: this learner is only watching';
  if (r.includes('no learned value')) return 'not used: nothing learned yet';
  if (r.includes('paused') || r.includes('kill') || r.includes('switch')) return 'not used: learning is paused';
  return reason ? `not used: ${reason}` : 'not used';
}

export const METHOD_WORDS: Record<string, string> = {
  ips_doubly_robust: 'Replayed against past decisions',
  'resimulation+ips_doubly_robust': 'Re-ran past decisions with the change',
  observational_cohort: 'Compared similar past orders (no true replay possible)',
  no_counterfactual: 'Cannot be replayed; needs a person to judge',
};

export const VERDICT: Record<string, { label: string; tone: string }> = {
  LOOKS_SAFE: { label: 'Looks safe', tone: 'ok' }, NEEDS_CHANGES: { label: 'Needs changes', tone: 'warn' }, RISKY: { label: 'Risky', tone: 'danger' },
};

const num = (v: unknown): string => (typeof v === 'number' ? (Number.isInteger(v) ? String(v) : v.toFixed(2)) : v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v));

/** A proposed change as one sentence. */
export function changeSentence(c: PolicyChange): string {
  switch (c.type) {
    case 'ThresholdChange': return `${decisionLabel(c.decision)}: move the ${c.band} confidence threshold from ${c.from.toFixed(2)} to ${c.to.toFixed(2)}`;
    case 'WeightChange': return `${decisionLabel(c.target.replace(/^config:/, ''))}: change the ${humanize(c.weightKey)} weight from ${num(c.from)} to ${num(c.to)}`;
    case 'RuleAdd': return `${decisionLabel(c.decision)}: add a rule — when ${c.expression}, answer ${c.thenValue}`;
    case 'RuleRetire': return `${decisionLabel(c.decision)}: retire rule ${c.ruleId}${c.reason ? ` (${c.reason})` : ''}`;
    case 'ParameterChange': {
      if (c.parameter.startsWith('live:')) {
        const to = (c.to ?? {}) as Record<string, unknown>;
        const fam = familyName(c.parameter.slice(5));
        if (to.enabled === false) return `Stop using the learned "${fam}" in live decisions`;
        const range = to.min !== undefined || to.max !== undefined ? ` within ${num(to.min)}–${num(to.max)}` : '';
        return `Use the learned "${fam}" in live decisions${range}${to.maxStep !== undefined ? `, at most ${num(to.maxStep)} from the standard value` : ''}`;
      }
      return `${decisionLabel(c.parameter.replace(/:/g, ' · '))}: change from ${num(c.from)} to ${num(c.to)}`;
    }
    case 'AllowListChange': return `${decisionLabel(c.listName)}: ${c.add ? 'add' : 'remove'} ${c.entry}`;
    default: return 'A change to a policy';
  }
}

export function proposalTitle(p: Proposal): string {
  const c = p.changeset.changes[0];
  const s = c ? changeSentence(c) : `Change ${p.changeset.target.name}`;
  return p.changeset.changes.length > 1 ? `${s} (+${p.changeset.changes.length - 1} more)` : s;
}

/** System actors that draft, review or change proposals, in words a person would use. */
const SYSTEM_ACTORS: Record<string, string> = {
  author: 'the AI change drafter',
  'template-author': 'the change drafter (template, no AI)',
  analyst: 'the AI analyst',
  'promotion-service': 'the promotion gate',
  'guardrail-breach-monitor': 'the guardrail monitor',
  'experiment-manager': 'the experiment manager',
  'coverage-monitor': 'the coverage monitor',
  'fraud-drift-monitor': 'the fraud drift monitor',
  'learning-governance': 'learning governance',
  'crypto-shred': 'customer erasure',
};

/** A seeded actor's "who": a person's role, a system actor, or a learner key. */
function seededWho(who: string, learnerNames?: Map<string, string>): string {
  return learnerNames?.get(who) ?? SYSTEM_ACTORS[who] ?? (who === 'strategy' ? 'strategy lead' : who);
}

/** An actor reference as a person would read it: "user:alice" → "alice", a seeded actor → "admin (demo)". */
export function personName(ref: string | null | undefined): string | undefined {
  if (!ref) return undefined;
  if (ref === 'system:demo-seed') return 'demo data';
  if (ref.startsWith('system:demo-seed.')) return `${seededWho(ref.slice('system:demo-seed.'.length))} (demo)`;
  if (ref.startsWith('system:') && SYSTEM_ACTORS[ref.slice(7)]) return SYSTEM_ACTORS[ref.slice(7)];
  return ref.replace(/^(user|system):/, '');
}

/** Who drafted it, as a person would say it. */
export function authorName(createdBy: string | null | undefined, learnerNames: Map<string, string>): string {
  if (!createdBy) return 'unknown';
  if (createdBy === 'system:demo-seed') return 'demo data';
  // Seeded on behalf of a person, a system actor or a learner: say who, and that it is demo data.
  if (createdBy.startsWith('system:demo-seed.')) return `${seededWho(createdBy.slice('system:demo-seed.'.length), learnerNames)} (demo)`;
  const key = createdBy.replace(/^system:/, '');
  if (createdBy.startsWith('system:') && SYSTEM_ACTORS[key]) return SYSTEM_ACTORS[key];
  if (learnerNames.has(key)) return learnerNames.get(key)!;
  return createdBy.replace(/^(user|system):/, '');
}

/** Seeded reference data: anything a demo-seed actor wrote ({@code system:demo-seed}, {@code system:demo-seed.<who>}), or a seeded proposal id. */
export const isDemo = (by: string | null | undefined, id?: string | null): boolean => !!by?.startsWith('system:demo-seed') || !!id?.startsWith('prop_demo_');

/** Mirrors the server's GV-07 rule: a change scoped to postal codes needs a reason code to approve. */
export function isPostalScoped(p: Proposal): boolean {
  const texts = [p.changeset.target.name, ...p.changeset.changes.map((c) => (c.type === 'ParameterChange' ? c.parameter : c.type === 'RuleAdd' ? c.expression : ''))];
  return texts.some((t) => /zip|postal/i.test(t ?? ''));
}
export const POSTAL_REASON_CODES: { code: string; label: string }[] = [
  { code: 'carrier_service_gap', label: 'Carrier does not serve these areas reliably' },
  { code: 'confirmed_fraud_ring', label: 'Confirmed fraud ring in these areas' },
  { code: 'regulatory_restriction', label: 'Regulatory restriction' },
  { code: 'natural_disaster', label: 'Natural disaster or emergency' },
];

/** E-2: a lift is a difference in mean reward on the 0-1 scale, shown as a signed number, never as a percentage. */
export const rewardDelta = (lift: number): string => `${lift >= 0 ? '+' : '−'}${Math.abs(lift).toFixed(3)}`;

/** Replay result as one short phrase. */
export function replayPhrase(lift: number | null | undefined, ci: { lower: number; upper: number } | null | undefined): { text: string; tone: string } {
  if (lift === null || lift === undefined) return { text: 'No replay estimate', tone: 'neutral' };
  if (ci && ci.lower > 0) return { text: `Looks better: ${rewardDelta(lift)} reward`, tone: 'ok' };
  if (ci && ci.upper < 0) return { text: `Looks worse: ${rewardDelta(lift)} reward`, tone: 'danger' };
  return { text: 'No clear difference', tone: 'neutral' };
}
