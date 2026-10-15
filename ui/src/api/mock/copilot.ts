/* Mock routes for the oms-copilot module (VITE_MOCK=1). Canned but data-driven: proposals are built from the
   seeded decisions / state models / config so diffs are real, and staging writes studio drafts + artefacts. */
import type { ChangeKind, ChangeSet, ChangeTestCase, ChangeValidationIssue, CopilotExplanation, CopilotStatus, CsDraft, DecisionDefinition, DraftArtifact, MarginDigest, OpsAnswer, OpsTemplate, ProposedChange, Tier } from '../types';
import { unifiedDiff } from '../../lib/diff';
import {
  addDecisionRecord, auditLog, config, daysAgo, decisionRecords, decisions, exceptions, hoursAgo, int, liveVersion, nextId, nodes, orders, parseDecisionYaml, returns, round, stateModels, traces, NOW,
} from './data';
import { LINE_MODEL_V3 } from './samples';

type Handler = (ctx: { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> }) => unknown;
interface Helpers {
  on: (method: string, path: string, handler: Handler) => void;
  problem: (status: number, code: string, detail: string, extra?: Record<string, unknown>) => never;
  notFound: (what: string, id: string) => never;
  actor: () => string;
  validateModel: (yaml: string) => { ok: boolean; issues: { severity: 'error' | 'warning'; code: string; message: string; ref?: string }[] };
}

const MODEL = 'claude-sonnet-4-5';
const changeSets: ChangeSet[] = [];
const artifacts: DraftArtifact[] = [];

/* ---------- helpers ---------- */
const bumpVersion = (yaml: string) => yaml.replace(/^version: (\d+)/m, (_, v: string) => `version: ${Number(v) + 1}`);
const decisionYaml = (name: string) => (liveVersion(name) ?? decisions[name]?.at(-1))?.yaml ?? '';
const modelYaml = (id: string) => stateModels[id]?.at(-1)?.yaml ?? '';
const TIER_APPROVALS: Record<string, number> = { T0: 0, T1: 1, T2: 2, T3: 2 };
const KIND_TIER: Record<string, Tier | null> = { decision_rule: 'T1', decision_criteria: 'T1', decision_bands: 'T2', decision_new: 'T2', state_model: 'T1', order_type: 'T1', sla: 'T0', playbook: 'T1', tenant_config: 'T2', compliance_rule: 'T2' };
const isDecisionKind = (k: string) => k.startsWith('decision_');

function validate(kind: string, after: string, validateModel: Helpers['validateModel'], extra: ChangeValidationIssue[] = []): ProposedChange['validation'] {
  const warnings: string[] = extra.filter((i) => i.severity === 'warning').map((i) => i.message);
  const issues: ChangeValidationIssue[] = extra.filter((i) => i.severity !== 'warning');
  if (isDecisionKind(kind)) {
    try { const d = parseDecisionYaml(after); if (d.bands && d.bands.confirmGte > d.bands.actGte) issues.push({ severity: 'error', code: 'band_order', message: 'confirm threshold must be below act threshold', path: 'bands' }); } catch (e) { issues.push({ severity: 'error', code: 'yaml_parse', message: e instanceof Error ? e.message : 'Invalid YAML' }); }
  } else if (kind === 'state_model') {
    const r = validateModel(after); issues.push(...r.issues.map((i) => ({ severity: i.severity, code: i.code, message: i.message, path: i.ref })));
  } else if (kind === 'tenant_config' || kind === 'playbook' || kind === 'compliance_rule' || kind === 'sla') {
    try { JSON.parse(after); } catch { issues.push({ severity: 'error', code: 'json_parse', message: 'Artefact is not valid JSON' }); }
  }
  if (!issues.length && !warnings.length) issues.push({ severity: 'info', code: 'clean', message: 'No validation findings' });
  return { ok: !issues.some((i) => i.severity === 'error'), issues, warnings };
}

function change(h: Helpers, p: { kind: ChangeKind; target: string; title: string; rationale: string; before: string | null; after: string; tests?: ChangeTestCase[]; issues?: ChangeValidationIssue[]; tier?: Tier | null }): ProposedChange {
  const tier = p.tier === undefined ? KIND_TIER[p.kind] : p.tier;
  return {
    id: nextId('chg'), kind: p.kind, target: p.target, title: p.title, rationale: p.rationale, before: p.before, after: p.after,
    diff: unifiedDiff(p.before ?? '', p.after, p.before === null ? '/dev/null' : `a/${p.target}`, `b/${p.target}`),
    validation: validate(p.kind, p.after, h.validateModel, p.issues), tests: p.tests ?? [], riskTier: tier, requiresApprovals: tier ? TIER_APPROVALS[tier] : 0, staged: null,
  };
}
function usage(changes: ProposedChange[]): ChangeSet['usage'] {
  const inputTokens = 1800 + changes.length * 900 + int(0, 400); const outputTokens = changes.reduce((s, c) => s + Math.round(c.after.length / 3.6), 0) + 220;
  return { inputTokens, outputTokens, costUsd: round(inputTokens * 0.000003 + outputTokens * 0.000015, 4), model: MODEL };
}
function newChangeSet(h: Helpers, text: string, scope: string, summary: string, assumptions: string[], questions: string[], changes: ProposedChange[], createdBy = h.actor().split(':')[1] ?? 'system', createdAt = new Date().toISOString()): ChangeSet {
  const cs: ChangeSet = { id: nextId('cs'), request: { text, scope }, summary, assumptions, questions, changes, createdBy, createdAt, status: 'proposed', usage: usage(changes) };
  changeSets.unshift(cs);
  return cs;
}
const json = (v: unknown) => JSON.stringify(v, null, 2);
const rulesBlockInsert = (yaml: string, rule: string) => (/^rules:/m.test(yaml) ? yaml.replace(/^rules:\n/m, `rules:\n${rule}`) : yaml.replace(/^bands:/m, `rules:\n${rule}bands:`));

/* ---------- proposal generators (keyword-routed) ---------- */
function proposeInstantRefund(h: Helpers, text: string, scope: string, answers: Record<string, unknown>): ChangeSet {
  const cap = Number(/\$\s?(\d{2,4})/.exec(text)?.[1] ?? 250);
  const tier = /gold|platinum|vip/i.test(text) ? (/platinum/i.test(text) ? 'platinum' : 'gold') : 'gold';
  const rr = decisionYaml('return_risk');
  const rule = `  - id: ${tier}_receipt_instant\n    when: "customer.tier == '${tier}' && return.receipt_present && return.value < ${cap}"\n    then: instant\n    reason: ${tier[0].toUpperCase()}${tier.slice(1)} customers with proof of purchase get an instant refund under $${cap}\n    priority: 15\n`;
  const afterRule = bumpVersion(rulesBlockInsert(rr, rule));
  const afterCriteria = bumpVersion(rr.replace(/instant: .*$/m, `instant: Refund now; ${tier} customer with receipt and value under $${cap}, or trusted customer with low value`));
  const retModel = modelYaml('return.standard');
  const afterModel = bumpVersion(retModel.replace(`guards: ["answers.timing == 'instant'"]`, `guards: ["answers.timing == 'instant' && return.value <= tenant.instant_refund_cap"]`));
  const currentCap = Number(config['returns.instant_refund_cap_usd'] ?? 100);
  const changes = [
    change(h, { kind: 'decision_rule', target: 'return_risk', title: `Add L0 rule ${tier}_receipt_instant`, rationale: `A deterministic rule settles the common case without a model call: ${tier} tier, receipt present, value under $${cap} → instant. Final-sale and out-of-window rules keep higher priority.`, before: rr, after: afterRule,
      tests: [{ caseId: 'gc_gold_receipt_120', input: { customer: { tier, history: { returns_90d: 1 } }, return: { receipt_present: true, value: 120, reason: 'too_small' } }, expected: 'instant', note: 'Rule fires' }, { caseId: 'gc_gold_noreceipt_120', input: { customer: { tier }, return: { receipt_present: false, value: 120 } }, expected: 'on_inspection', note: 'Receipt missing → model decides' }, { caseId: `gc_gold_receipt_${cap + 50}`, input: { customer: { tier }, return: { receipt_present: true, value: cap + 50 } }, expected: 'on_carrier_scan', note: 'Above the cap' }] }),
    change(h, { kind: 'decision_criteria', target: 'return_risk', title: 'Sharpen the "instant" option description', rationale: 'The criterion must describe the same population as the rule so the L1 model does not disagree with L0 on borderline cases (GR-03 consistency).', before: rr, after: afterCriteria,
      tests: [{ caseId: 'gc_silver_receipt_80', input: { customer: { tier: 'silver', history: { returns_90d: 0 } }, return: { receipt_present: true, value: 80 } }, expected: 'instant', note: 'Trusted low-value still instant' }] }),
    change(h, { kind: 'tenant_config', target: 'returns.instant_refund_cap_usd', title: `Raise instant refund cap ${currentCap} → ${cap}`, rationale: `The post-check "!(answers.timing == 'instant' && return.value > tenant.instant_refund_cap)" would block instant refunds above $${currentCap}; the cap must move with the policy.`, before: json({ 'returns.instant_refund_cap_usd': currentCap }), after: json({ 'returns.instant_refund_cap_usd': cap }),
      issues: cap > currentCap ? [{ severity: 'warning', code: 'refund_exposure', message: `Raising the cap to $${cap} increases hourly refund exposure; guardrail.refund_velocity_usd_per_hour is ${config['guardrail.refund_velocity_usd_per_hour']}. Consider raising it or monitoring for a week.`, path: 'returns.instant_refund_cap_usd' }] : [],
      tests: [{ caseId: 'cfg_cap_read', input: { key: 'returns.instant_refund_cap_usd' }, expected: cap }] }),
    change(h, { kind: 'state_model', target: 'return.standard', title: 'Guard instant refund transition by the cap', rationale: 'Belt and braces: the approved → refunded transition also checks the cap so a mis-tuned model can never move money above it.', before: retModel, after: afterModel,
      tests: [{ caseId: 'sm_instant_under_cap', input: { from: 'approved', trigger: 'decision.refund_timing', answers: { timing: 'instant' }, return: { value: 90 } }, expected: 'refunded' }, { caseId: 'sm_instant_over_cap', input: { from: 'approved', trigger: 'decision.refund_timing', answers: { timing: 'instant' }, return: { value: cap + 1 } }, expected: 'approved', note: 'Guard blocks' }] }),
  ];
  const answered = Object.keys(answers).length > 0;
  return newChangeSet(h, text, scope, `Instant refunds for ${tier} customers with a receipt under $${cap}: one L0 rule plus matching option wording on return_risk, the tenant cap raised to $${cap}, and a state-model guard on the refund transition.`,
    [`"${tier} customers" means customer.tier == '${tier}' (loyalty tier captured at order time).`, `"Receipt" means return.receipt_present, which is true for any order captured by the OMS or an uploaded proof of purchase.`, 'Value is the merchandise value of the returned lines, before tax and shipping.', ...(answered ? Object.entries(answers).map(([q, a]) => `You answered "${q}": ${String(a)}`) : [])],
    answered ? [] : ['Should the cap apply per return or per customer per 30 days?', 'Do final-sale items stay excluded even for gold customers?'], changes);
}
function proposeHazmat(h: Helpers, text: string, scope: string, answers: Record<string, unknown>): ChangeSet {
  const line = modelYaml('order_line.standard_ship') || LINE_MODEL_V3;
  const afterLine = bumpVersion(line.replace(`      - "!order.holds.exists(h, 'release' in h.blocks)"\n`, `      - "!order.holds.exists(h, 'release' in h.blocks)"\n      - "!(order.has_hazmat && plan.packages > 1)"\n`));
  const rule = json({ id: 'hazmat_single_consignment', when: 'order.lines.exists(l, l.attributes.hazmat == true)', enforce: { maxPackages: 1, carrierServices: ['ground'], requireNodeCapability: 'hazmat' }, onViolation: 'block_release', owner: 'compliance', references: ['49 CFR 173', 'carrier hazmat terms'] });
  const changes = [
    change(h, { kind: 'compliance_rule', target: 'hazmat_single_consignment', title: 'Compliance rule: hazmat orders ship as one consignment', rationale: 'Hazmat consignments must stay together on a ground service from a hazmat-capable node. Encoding it as a compliance rule makes it apply to every order type, not only fulfilment_intent.', before: null, after: rule,
      tests: [{ caseId: 'cr_hazmat_two_pkgs', input: { order: { lines: [{ attributes: { hazmat: true } }, { attributes: { hazmat: false } }] }, plan: { packages: 2 } }, expected: 'block_release' }, { caseId: 'cr_no_hazmat', input: { order: { lines: [{ attributes: { hazmat: false } }] }, plan: { packages: 2 } }, expected: 'pass' }] }),
    change(h, { kind: 'state_model', target: 'order_line.standard_ship', title: 'Guard release when a hazmat order would split', rationale: 'The awaiting_sourcing → released guard is where the chosen plan meets the state machine; a plan that splits a hazmat order never releases.', before: line, after: afterLine,
      tests: [{ caseId: 'sm_hazmat_split', input: { from: 'awaiting_sourcing', trigger: 'decision.sourcing_plan', order: { has_hazmat: true }, plan: { packages: 2, margin: 0.2 } }, expected: 'awaiting_sourcing', note: 'guard blocks' }, { caseId: 'sm_hazmat_single', input: { from: 'awaiting_sourcing', trigger: 'decision.sourcing_plan', order: { has_hazmat: true }, plan: { packages: 1, margin: 0.2 } }, expected: 'released' }] }),
    change(h, { kind: 'tenant_config', target: 'sourcing.max_split_packages_hazmat', title: 'Cap hazmat split packages at 1', rationale: 'The sourcing engine prunes plans above the cap before scoring, so hazmat orders never even see a split candidate.', before: null, after: json({ 'sourcing.max_split_packages_hazmat': 1 }), tests: [{ caseId: 'cfg_hazmat_cap', input: { key: 'sourcing.max_split_packages_hazmat' }, expected: 1 }] }),
  ];
  const answered = Object.keys(answers).length > 0;
  return newChangeSet(h, text, scope, 'Hazmat orders never split: a tenant-wide compliance rule, a release guard on the line state model and a sourcing cap. fulfilment_intent already carries rule hazmat_no_split, so no decision change is proposed.',
    ['"Hazmat" is line attribute hazmat == true (set from the catalogue at capture).', 'Orders mixing hazmat and non-hazmat lines are treated as hazmat orders.', 'Existing rule fulfilment_intent.hazmat_no_split stays as is.', ...(answered ? Object.entries(answers).map(([q, a]) => `You answered "${q}": ${String(a)}`) : [])],
    answered ? [] : ['If no single node can fulfil the whole hazmat order, should the order backorder or escalate to a person?'], changes);
}
function proposeAgentRiskReview(h: Helpers, text: string, scope: string, answers: Record<string, unknown>): ChangeSet {
  const amount = Number(/\$\s?(\d{2,5})/.exec(text)?.[1] ?? 500);
  const st = /california|\bCA\b/i.test(text) ? 'CA' : (/texas|\bTX\b/i.test(text) ? 'TX' : /new york|\bNY\b/i.test(text) ? 'NY' : 'CA');
  const yaml = `decision: agent_order_risk_review\nversion: 1\nowner: ai-governance\ntier: T2\nprovider: jev\nfallbackProvider: llm.anthropic\ntimeoutMs: 800\ndefault: review\npurpose: Decide whether an agent-placed order needs a human risk review before release.\nstate:\n  include:\n    - order.value\n    - order.ship_to.state\n    - agent.trust_tier\n    - mandate.spend_limit\n    - customer.history.orders_180d\nquestions:\n  review:\n    type: choice\n    instructions: Does this agent order need a person to review it before release?\n    criteria:\n      release: Within mandate, trusted agent, routine destination\n      review: Value or destination warrants a risk analyst check\n      block: Mandate exceeded or agent not verified\n      unclear: Not enough information to tell\nrules:\n  - id: high_value_${st.toLowerCase()}\n    when: "order.value > ${amount} && order.ship_to.state == '${st}' && input.source == 'agent'"\n    then: review\n    reason: Agent orders over $${amount} to ${st} need a risk review\n    priority: 10\nbands:\n  act: { confidence_gte: 0.9 }\n  confirm: { confidence_gte: 0.6 }\n  escalate: { otherwise: true }\nevaluation:\n  goldenSet: agent_order_risk_review_gold_v1\n  minAccuracyInActBand: 0.9\n  maxCalibrationError: 0.05\n`;
  const playbook = json({ name: 'agent_risk_review', exceptionType: 'agent_risk', steps: [{ action: 'hold', params: { type: 'risk_review', blocks: ['release'] } }, { action: 'create_task', params: { queue: 'risk_review', sla: 'PT4H', requiredSkills: ['agent_commerce'] } }, { action: 'notify', params: { channel: 'slack', template: 'agent_risk_review' } }], enabled: true });
  const changes = [
    change(h, { kind: 'decision_new', target: 'agent_order_risk_review', title: 'New decision agent_order_risk_review (T2)', rationale: `A dedicated decision keeps the rule auditable and lets the model handle the grey zone (value just under $${amount}, mixed destinations). Starts in shadow with a golden set before any canary.`, before: null, after: yaml,
      tests: [{ caseId: `gc_agent_${st}_${amount + 100}`, input: { order: { value: amount + 100, ship_to: { state: st } }, input: { source: 'agent' }, agent: { trust_tier: 2 } }, expected: 'review' }, { caseId: `gc_agent_${st}_${Math.max(10, amount - 200)}`, input: { order: { value: Math.max(10, amount - 200), ship_to: { state: st } }, input: { source: 'agent' }, agent: { trust_tier: 2 } }, expected: 'release' }, { caseId: 'gc_agent_other_state', input: { order: { value: amount + 100, ship_to: { state: 'OH' } }, input: { source: 'agent' }, agent: { trust_tier: 3 } }, expected: 'release' }] }),
    change(h, { kind: 'playbook', target: 'agent_risk_review', title: 'Playbook: hold + risk_review task + Slack notice', rationale: 'The review outcome needs somewhere to land: a release-blocking hold and a 4-hour task in a queue with the agent_commerce skill.', before: null, after: playbook,
      tests: [{ caseId: 'pb_steps', input: { outcome: 'review' }, expected: ['hold', 'create_task', 'notify'] }] }),
    change(h, { kind: 'sla', target: 'queue.risk_review', title: 'SLA: risk_review tasks due in 4h, escalate to ops_leads', rationale: 'A review that waits longer than the promise cutoff defeats the purpose; breach escalates rather than auto-releasing.', before: json({ queue: 'risk_review', slaDefault: 'PT8H', escalationQueue: null }), after: json({ queue: 'risk_review', slaDefault: 'PT4H', escalationQueue: 'ops_leads', onBreach: 'escalate' }),
      tests: [{ caseId: 'sla_due', input: { createdAt: '2026-10-06T09:00:00Z' }, expected: '2026-10-06T13:00:00Z' }] }),
  ];
  const answered = Object.keys(answers).length > 0;
  return newChangeSet(h, text, scope, `Agent orders over $${amount} shipping to ${st} get a human risk review: a new T2 decision with an L0 rule, a playbook that holds the order and opens a 4-hour task, and the queue SLA.`,
    ['"Agent orders" are orders with provenance.protocol in (ucp, acp, mcp).', `Destination is the ship-to state (${st}), not the billing address.`, 'The decision starts in shadow mode; publishing to live needs two approvals (T2).', ...(answered ? Object.entries(answers).map(([q, a]) => `You answered "${q}": ${String(a)}`) : [])],
    answered ? [] : ['Should trusted-partner agents (trust tier 3) be exempt from the review?', 'Does the $500 threshold include shipping and tax?'], changes);
}
function proposePreorder(h: Helpers, text: string, scope: string, answers: Record<string, unknown>): ChangeSet {
  const days = Number(/(\d{1,3})\s*-?\s*day/i.exec(text)?.[1] ?? 30);
  const line = modelYaml('order_line.standard_ship') || LINE_MODEL_V3;
  const preModel = line.replace('stateModel: order_line.standard_ship', 'stateModel: order_line.preorder').replace(/^version: \d+/m, 'version: 1')
    .replace(`  - { id: backordered, sla: { maxAge: P3D, onBreach: decision.backorder_next_action } }`, `  - { id: backordered, sla: { maxAge: P${days}D, onBreach: decision.backorder_next_action } }`)
    .replace(`  - from: created\n    to: awaiting_sourcing\n    trigger: event.order_validated\n`, `  - from: created\n    to: backordered\n    trigger: event.order_validated\n    guards: ["!inventory.available"]\n  - from: created\n    to: awaiting_sourcing\n    trigger: event.order_validated\n    guards: ["inventory.available"]\n`);
  const orderType = `orderType: preorder\nheaderStateModel: order.standard_ship\nlineStateModel: order_line.preorder\nworkflowType: order.standard_ship\ndecisionSet:\n  - fulfilment_intent\n  - sourcing_node_score\n  - backorder_next_action\nchannelDefaults:\n  web: ship_complete\n  shopify: ship_complete\ncapture:\n  allowZeroAtp: true\n  authorizeOnCapture: false\n  backorderWindowDays: ${days}\n`;
  const changes = [
    change(h, { kind: 'order_type', target: 'preorder', title: 'New order type preorder', rationale: `Pre-orders are captured against zero ATP, authorise at ship time and may sit backordered for ${days} days before the backorder decision runs.`, before: null, after: orderType,
      tests: [{ caseId: 'ot_capture_zero_atp', input: { orderType: 'preorder', atp: 0 }, expected: 'captured' }, { caseId: 'ot_auth_deferred', input: { orderType: 'preorder', event: 'captured' }, expected: { payment: 'not_authorized' } }] }),
    change(h, { kind: 'state_model', target: 'order_line.preorder', title: `Line state model with a P${days}D backorder window`, rationale: `Clones order_line.standard_ship; lines enter backordered directly when ATP is zero and the SLA breach after ${days} days triggers backorder_next_action instead of 3 days.`, before: null, after: preModel,
      tests: [{ caseId: 'sm_pre_zero_atp', input: { from: 'created', trigger: 'event.order_validated', inventory: { available: false } }, expected: 'backordered' }, { caseId: 'sm_pre_sla', input: { state: 'backordered', ageDays: days + 1 }, expected: 'decision.backorder_next_action' }] }),
    change(h, { kind: 'sla', target: 'order_line.preorder.backordered', title: `Backorder SLA ${days} days → backorder_next_action`, rationale: 'Surfaces the window as a monitored SLA so Control Tower ages pre-orders separately.', before: json({ state: 'backordered', maxAge: 'P3D', onBreach: 'decision.backorder_next_action' }), after: json({ state: 'backordered', maxAge: `P${days}D`, onBreach: 'decision.backorder_next_action', notifyCustomerAt: ['P7D', `P${Math.max(1, days - 7)}D`] }),
      tests: [{ caseId: 'sla_window', input: { maxAge: `P${days}D` }, expected: days * 24 }] }),
  ];
  const answered = Object.keys(answers).length > 0;
  return newChangeSet(h, text, scope, `A preorder order type with its own line state model and a ${days}-day backorder window, plus the SLA that triggers backorder_next_action when the window closes.`,
    ['Header state model order.standard_ship is reused; only the line model differs.', 'Payment is authorised at ship time, not capture (card rules for pre-orders).', 'Customers are notified at 7 days and one week before the window closes.', ...(answered ? Object.entries(answers).map(([q, a]) => `You answered "${q}": ${String(a)}`) : [])],
    answered ? [] : ['Which channels may place pre-orders (web and shopify assumed)?', 'Should the promise date be the expected inbound date or "window end"?'], changes);
}
function proposeMarginFloor(h: Helpers, text: string, scope: string, answers: Record<string, unknown>): ChangeSet {
  const pct = Number(/(\d{1,2})\s*%/.exec(text)?.[1] ?? 15) / 100;
  const marketplace = ['amazon', 'walmart', 'ebay'];
  const current = Number(config['sourcing.margin_floor'] ?? 0.12);
  const changes = [
    change(h, { kind: 'tenant_config', target: 'sourcing.margin_floor_by_channel', title: `Margin floor ${Math.round(pct * 100)}% for marketplace channels`, rationale: `Marketplace fees compress contribution margin; a per-channel floor keeps the tenant-wide ${Math.round(current * 100)}% floor for owned channels.`, before: json({ 'sourcing.margin_floor': current }), after: json({ 'sourcing.margin_floor': current, 'sourcing.margin_floor_by_channel': Object.fromEntries(marketplace.map((c) => [c, pct])) }),
      issues: [{ severity: 'warning', code: 'approval_volume', message: `About ${int(6, 14)}% of last week's marketplace plans fell between ${Math.round(current * 100)}% and ${Math.round(pct * 100)}%; they will now need a margin approval task.`, path: 'sourcing.margin_floor_by_channel' }],
      tests: [{ caseId: 'cfg_amazon_floor', input: { channel: 'amazon' }, expected: pct }, { caseId: 'cfg_web_floor', input: { channel: 'web' }, expected: current }] }),
    change(h, { kind: 'compliance_rule', target: 'margin_floor_marketplace', title: 'Post-check: marketplace plans meet the channel floor', rationale: 'Mirrors the existing guard "plan.margin >= tenant.margin_floor || approval.present" with the channel-specific value.', before: null, after: json({ id: 'margin_floor_marketplace', when: `order.channel in ${JSON.stringify(marketplace)}`, enforce: 'plan.margin >= tenant.margin_floor_by_channel[order.channel] || approval.present', onViolation: 'task.margin_floor_approval' }),
      tests: [{ caseId: 'cr_below_floor', input: { order: { channel: 'amazon' }, plan: { margin: 0.13 } }, expected: 'task.margin_floor_approval' }] }),
  ];
  const answered = Object.keys(answers).length > 0;
  return newChangeSet(h, text, scope, `Marketplace orders need ${Math.round(pct * 100)}% contribution margin: a per-channel floor in tenant config and a compliance post-check that opens a margin approval task below it.`,
    [`"Marketplace" means channels ${marketplace.join(', ')}.`, 'Margin is contribution margin ratio after marketplace fees, as computed by the sourcing P&L.', ...(answered ? Object.entries(answers).map(([q, a]) => `You answered "${q}": ${String(a)}`) : [])],
    answered ? [] : ['Should shopify count as a marketplace channel?'], changes);
}
function proposeGeneric(h: Helpers, text: string, scope: string): ChangeSet {
  const fi = decisionYaml('fulfilment_intent');
  const after = bumpVersion(fi.replace('unclear: Not enough information to tell', 'unclear: The notes, checkout choices and history do not indicate a preference'));
  const changes = [change(h, { kind: 'decision_criteria', target: 'fulfilment_intent', title: 'Clarify the escape option wording', rationale: `I could not map "${text.slice(0, 80)}" to a specific policy, state model or configuration key. The closest safe change is a wording clarification; answer the questions below so I can propose the real change.`, before: fi, after,
    tests: [{ caseId: 'gc_unclear_empty_notes', input: { order: { channel: 'web', notes: [], intent_choices: [] }, customer: { tier: 'bronze' } }, expected: 'unclear' }] })];
  return newChangeSet(h, text, scope, 'The request is ambiguous; one low-risk wording change is proposed and two clarifying questions are open.', ['Scope limited to ' + scope + '.'], ['Which decision, state model or configuration key should change?', 'What outcome should an example order get after the change?'], changes);
}
function propose(h: Helpers, text: string, scope: string, context: Record<string, unknown>): ChangeSet {
  const answers = (context.answers as Record<string, unknown> | undefined) ?? {};
  if (/refund|receipt/i.test(text)) return proposeInstantRefund(h, text, scope, answers);
  if (/hazmat|hazardous|split/i.test(text)) return proposeHazmat(h, text, scope, answers);
  if (/agent|risk review|california|\bCA\b/i.test(text)) return proposeAgentRiskReview(h, text, scope, answers);
  if (/pre-?order|backorder/i.test(text)) return proposePreorder(h, text, scope, answers);
  if (/margin/i.test(text)) return proposeMarginFloor(h, text, scope, answers);
  return proposeGeneric(h, text, scope);
}

/* ---------- staging ---------- */
function stageChange(h: Helpers, cs: ChangeSet, c: ProposedChange) {
  const me = h.actor().split(':')[1] ?? 'system';
  if (isDecisionKind(c.kind)) {
    let parsed: Partial<DecisionDefinition> = {};
    try { parsed = parseDecisionYaml(c.after) as Partial<DecisionDefinition>; } catch { /* keep base fields */ }
    const vs = decisions[c.target] ?? (decisions[c.target] = []);
    const base = vs.at(-1);
    const existingDraft = vs.find((d) => d.status === 'draft');
    const version = existingDraft ? existingDraft.version : (base ? Math.max(...vs.map((d) => d.version)) + 1 : 1);
    const yaml = c.after.replace(/^version: \d+/m, `version: ${version}`);
    const def: DecisionDefinition = { ...(base ?? { provider: 'jev', timeoutMs: 800, stateInclude: [], questions: {}, bands: { actGte: 0.8, confirmGte: 0.5 } }), ...(parsed as DecisionDefinition), name: c.target, version, yaml, tier: (parsed.tier ?? base?.tier ?? c.riskTier ?? 'T1') as Tier, owner: parsed.owner ?? base?.owner ?? 'copilot', status: 'draft', canaryPercent: 0, createdAt: new Date().toISOString(), createdBy: me, approvals: [], killSwitch: false, changeReason: `Copilot: ${c.title}`, ticket: cs.id };
    const siblings = cs.changes.filter((x) => x !== c && x.staged?.kind === 'decision_draft' && x.staged.name === c.target && x.staged.version === version).map((x) => x.id);
    if (existingDraft) Object.assign(existingDraft, def); else vs.push(def);
    c.staged = { kind: 'decision_draft', ref: `${c.target}@${version}`, name: c.target, version, status: 'draft', merged: !!existingDraft, mergedChangeIds: siblings.length ? siblings : undefined };
  } else {
    const isJson = c.after.trim().startsWith('{') || c.after.trim().startsWith('[');
    const art: DraftArtifact = { id: nextId('art'), kind: c.kind, target: c.target, ...(isJson ? { json: c.after } : { yaml: c.after }), status: 'draft', changesetId: cs.id, createdBy: me, createdAt: new Date().toISOString() };
    artifacts.unshift(art);
    c.staged = { kind: 'artifact', ref: art.id };
  }
  auditLog.unshift({ id: auditLog.length + 1000, at: new Date().toISOString(), actor: h.actor(), action: 'copilot.stage', object_type: c.staged.kind, object_id: c.staged.ref, reason: c.title });
}
function recomputeStatus(cs: ChangeSet) {
  if (cs.status === 'rejected') return;
  const staged = cs.changes.filter((c) => c.staged).length;
  cs.status = staged === 0 ? 'proposed' : staged === cs.changes.length ? 'staged' : 'partially_staged';
}

/* ---------- ops copilot ---------- */
const OPS_TEMPLATES: OpsTemplate[] = [
  { name: 'orders_at_risk', description: 'Open orders whose SLA breach risk score is at or above a threshold', parameters: [{ name: 'minScore', type: 'integer', required: false, example: 3 }] },
  { name: 'held_orders', description: 'Orders with an active hold, by hold type', parameters: [{ name: 'type', type: 'string', required: false, example: 'risk_review' }] },
  { name: 'orders_by_channel', description: 'Open order counts and value by channel', parameters: [{ name: 'hours', type: 'integer', required: false, example: 24 }] },
  { name: 'late_shipments', description: 'Shipments past their promise date that are not delivered', parameters: [{ name: 'days', type: 'integer', required: false, example: 7 }] },
  { name: 'exceptions_by_type', description: 'Open exceptions grouped by type and severity', parameters: [] },
  { name: 'refunds_recent', description: 'Refunds issued in the last N days with return and decision references', parameters: [{ name: 'days', type: 'integer', required: true, example: 7 }] },
  { name: 'backordered_lines', description: 'Lines sitting in backordered with their age and node', parameters: [] },
  { name: 'node_backlog', description: 'Open lines per fulfilment node with node health', parameters: [] },
];
function opsAsk(question: string): OpsAnswer {
  const q = question.toLowerCase(); const generatedAt = new Date().toISOString(); const num = Number(/(\d+)/.exec(q)?.[1]);
  const openOrders = orders.filter((m) => !['delivered', 'cancelled'].includes(m.order.status));
  const fin = (name: string, params: Record<string, unknown>, sql: string, rows: Record<string, unknown>[], answer: string): OpsAnswer => ({ answer, queryName: name, parameters: params, sql, columns: rows.length ? Object.keys(rows[0]) : [], rows: rows.slice(0, 200), rowCount: rows.length, generatedAt, truncated: rows.length > 200 });
  if (/hold|held/.test(q)) {
    const type = /risk|fraud/.test(q) ? 'risk_review' : /address/.test(q) ? 'address_review' : undefined;
    const rows = openOrders.flatMap((m) => m.holds.filter((h) => !h.resolvedAt && (!type || h.type === type)).map((h) => ({ orderId: m.order.id, channel: m.order.channel, status: m.order.status, holdType: h.type, reasonCode: h.reasonCode, placedBy: h.placedBy, placedAt: h.placedAt, blocks: h.blocksTransitions.join(',') })));
    return fin('held_orders', { type: type ?? null }, `SELECT o.id AS order_id, o.channel, o.status, h.type AS hold_type, h.reason_code, h.placed_by, h.placed_at, h.blocks\nFROM holds h JOIN orders o ON o.id = h.scope_id\nWHERE h.resolved_at IS NULL${type ? `\n  AND h.type = :type` : ''}\nORDER BY h.placed_at DESC`, rows, `${rows.length} open order${rows.length === 1 ? '' : 's'} currently ${type ? `on a ${type.replace(/_/g, ' ')} hold` : 'on hold'}${rows.length ? `; the oldest was placed ${new Date(rows[rows.length - 1].placedAt as string).toLocaleString()}` : ''}.`);
  }
  if (/risk|breach|late|sla/.test(q) && !/shipment/.test(q)) {
    const minScore = Number.isFinite(num) && num <= 4 ? num : 3;
    const rows = openOrders.filter((m) => m.risk && m.risk.score >= minScore).map((m) => ({ orderId: m.order.id, channel: m.order.channel, status: m.order.status, nodeId: m.groups[0]?.nodeId ?? null, riskScore: m.risk!.score, riskLevel: m.risk!.level, reasons: m.risk!.reasons.join('; '), promiseDate: m.groups[0]?.promiseDate ?? null, decisionRecordId: m.risk!.decisionRecordId ?? null })).sort((a, b) => b.riskScore - a.riskScore);
    return fin('orders_at_risk', { minScore }, `SELECT o.id AS order_id, o.channel, o.status, fg.node_id, r.value AS risk_score, r.level, r.reasons, fg.promise_date, r.id AS decision_record_id\nFROM orders o\nJOIN decision_records r ON r.subject_id = o.id AND r.decision = 'sla_breach_risk' AND r.mode = 'LIVE'\nLEFT JOIN fulfilment_groups fg ON fg.order_id = o.id\nWHERE o.status NOT IN ('delivered','cancelled') AND r.value >= :minScore\nORDER BY r.value DESC, o.placed_at`, rows, `${rows.length} open order${rows.length === 1 ? ' is' : 's are'} at breach risk ≥ ${minScore}/4.${rows.length ? ` Most common signal: "${mode(rows.flatMap((r) => String(r.reasons).split('; ')))}".` : ''}`);
  }
  if (/shipment|carrier|deliver/.test(q)) {
    const rows = orders.flatMap((m) => m.shipments.filter((s) => !s.deliveredAt && s.eta && s.eta < new Date(NOW).toISOString().slice(0, 10)).map((s) => ({ orderId: m.order.id, shipmentId: s.id, carrier: s.carrier, tracking: s.tracking ?? null, nodeId: s.nodeId, eta: s.eta, status: s.status, promiseDate: m.groups[0]?.promiseDate ?? null })));
    return fin('late_shipments', { days: Number.isFinite(num) ? num : 7 }, `SELECT s.order_id, s.id AS shipment_id, s.carrier, s.tracking, s.node_id, s.eta, s.status, fg.promise_date\nFROM shipments s LEFT JOIN fulfilment_groups fg ON fg.order_id = s.order_id\nWHERE s.delivered_at IS NULL AND s.eta < current_date AND s.shipped_at > now() - (:days || ' days')::interval\nORDER BY s.eta`, rows, `${rows.length} shipment${rows.length === 1 ? '' : 's'} past ETA and not yet delivered.`);
  }
  if (/exception/.test(q)) {
    const open = exceptions.filter((e) => !['RESOLVED', 'CLOSED'].includes(e.status)); const agg = new Map<string, { type: string; severity: string; count: number; oldest: string }>();
    for (const e of open) { const k = `${e.type}|${e.severity}`; const cur = agg.get(k); if (cur) { cur.count++; if (e.raisedAt < cur.oldest) cur.oldest = e.raisedAt; } else agg.set(k, { type: e.type, severity: e.severity, count: 1, oldest: e.raisedAt }); }
    const rows = [...agg.values()].sort((a, b) => b.count - a.count).map((r) => ({ ...r }));
    return fin('exceptions_by_type', {}, `SELECT type, severity, count(*) AS count, min(raised_at) AS oldest\nFROM exceptions WHERE status NOT IN ('RESOLVED','CLOSED')\nGROUP BY type, severity ORDER BY count DESC`, rows, `${open.length} open exceptions across ${rows.length} type/severity groups${rows[0] ? `; the largest is ${rows[0].type} (${rows[0].severity}) with ${rows[0].count}` : ''}.`);
  }
  if (/refund/.test(q)) {
    const days = Number.isFinite(num) ? num : 7; const since = daysAgo(days);
    const rows = orders.flatMap((m) => m.refunds.filter((r) => r.issuedAt >= since).map((r) => { const ret = returns.find((x) => x.rma === r.returnId); return { refundId: r.id, orderId: m.order.id, amount: r.amount.amount, currency: r.amount.currency, status: r.status, reason: r.reason ?? null, rma: r.returnId ?? null, refundTiming: ret?.refundTiming ?? null, decisionRecordId: ret?.refundTimingDecisionRecordId ?? null, issuedAt: r.issuedAt }; }));
    const total = rows.reduce((s, r) => s + Number(r.amount), 0);
    return fin('refunds_recent', { days }, `SELECT rf.id AS refund_id, rf.order_id, rf.amount, rf.currency, rf.status, rf.reason, rf.return_id AS rma, rt.refund_timing, rt.refund_timing_decision_record_id, rf.issued_at\nFROM refunds rf LEFT JOIN returns rt ON rt.rma = rf.return_id\nWHERE rf.issued_at > now() - (:days || ' days')::interval ORDER BY rf.issued_at DESC`, rows, `${rows.length} refund${rows.length === 1 ? '' : 's'} issued in the last ${days} days totalling $${total.toFixed(2)}.`);
  }
  if (/backorder/.test(q)) {
    const rows = orders.flatMap((m) => m.lineStatuses.filter((ls) => ls.quantities.some((qq) => qq.state === 'backordered')).map((ls) => { const b = ls.quantities.find((qq) => qq.state === 'backordered')!; return { orderId: m.order.id, lineId: ls.entityId.split(':').pop(), sku: m.order.lines.find((l) => ls.entityId.endsWith(':' + l.lineId))?.sku ?? null, qty: b.qty, since: b.since, ageHours: Math.round((NOW - Date.parse(b.since)) / 3_600_000), nodeId: m.groups[0]?.nodeId ?? null, channel: m.order.channel }; }));
    return fin('backordered_lines', {}, `SELECT es.entity_id AS line_id, o.id AS order_id, ol.sku, q.qty, q.since, extract(epoch FROM now() - q.since)/3600 AS age_hours, fg.node_id, o.channel\nFROM entity_status es JOIN LATERAL jsonb_to_recordset(es.quantities) q(state text, qty int, since timestamptz) ON true\nJOIN orders o ON o.id = split_part(es.entity_id, ':', 1) JOIN order_lines ol ON ol.order_id = o.id AND ol.line_id = split_part(es.entity_id, ':', 2)\nLEFT JOIN fulfilment_groups fg ON fg.order_id = o.id\nWHERE q.state = 'backordered' ORDER BY q.since`, rows, `${rows.length} line${rows.length === 1 ? '' : 's'} backordered${rows.length ? `; oldest ${Math.max(...rows.map((r) => r.ageHours))}h` : ''}.`);
  }
  if (/node|backlog|dc|store/.test(q)) {
    const rows = nodes.map((n) => ({ nodeId: n.id, name: n.name, type: n.type, openLines: openOrders.reduce((s, m) => s + m.groups.filter((g) => g.nodeId === n.id).reduce((t, g) => t + g.lineIds.length, 0), 0), backlog: (n.health as { backlog?: number } | undefined)?.backlog ?? null, pickRate: (n.health as { pick_rate?: number } | undefined)?.pick_rate ?? null, rejectRate: (n.health as { reject_rate?: number } | undefined)?.reject_rate ?? null, penalty: n.penalty ?? 0 })).sort((a, b) => b.openLines - a.openLines);
    return fin('node_backlog', {}, `SELECT n.id AS node_id, n.name, n.type, count(fgl.line_id) AS open_lines, n.health->>'backlog' AS backlog, n.health->>'pick_rate' AS pick_rate, n.health->>'reject_rate' AS reject_rate, n.penalty\nFROM nodes n LEFT JOIN fulfilment_groups fg ON fg.node_id = n.id JOIN orders o ON o.id = fg.order_id AND o.status NOT IN ('delivered','cancelled')\nLEFT JOIN fulfilment_group_lines fgl ON fgl.group_id = fg.id\nGROUP BY n.id ORDER BY open_lines DESC`, rows, `${rows[0]?.nodeId ?? '—'} carries the most open lines (${rows[0]?.openLines ?? 0}); ${rows.filter((r) => (r.penalty ?? 0) > 0.1).map((r) => r.nodeId).join(', ') || 'no node'} currently carries a sourcing penalty above 0.1.`);
  }
  const hours = Number.isFinite(num) ? num : 24; const since = hoursAgo(hours);
  const agg = new Map<string, { channel: string; openOrders: number; placedSince: number; value: number; atRisk: number }>();
  for (const m of openOrders) { const cur = agg.get(m.order.channel) ?? { channel: m.order.channel, openOrders: 0, placedSince: 0, value: 0, atRisk: 0 }; cur.openOrders++; if (m.order.placedAt >= since) cur.placedSince++; cur.value = round(cur.value + m.order.totals.grandTotal.amount); if (m.risk && m.risk.score >= 3) cur.atRisk++; agg.set(m.order.channel, cur); }
  const rows = [...agg.values()].sort((a, b) => b.openOrders - a.openOrders).map((r) => ({ ...r }));
  return fin('orders_by_channel', { hours }, `SELECT o.channel, count(*) AS open_orders, count(*) FILTER (WHERE o.placed_at > now() - (:hours || ' hours')::interval) AS placed_since, sum((o.totals->'grandTotal'->>'amount')::numeric) AS value, count(*) FILTER (WHERE r.value >= 3) AS at_risk\nFROM orders o LEFT JOIN decision_records r ON r.subject_id = o.id AND r.decision = 'sla_breach_risk'\nWHERE o.status NOT IN ('delivered','cancelled') GROUP BY o.channel ORDER BY open_orders DESC`, rows, `${openOrders.length} open orders across ${rows.length} channels; ${rows[0]?.channel ?? '—'} leads with ${rows[0]?.openOrders ?? 0}. ${rows.reduce((s, r) => s + r.placedSince, 0)} were placed in the last ${hours} hours.`);
}
function mode(xs: string[]): string { const c = new Map<string, number>(); xs.forEach((x) => c.set(x, (c.get(x) ?? 0) + 1)); return [...c.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? '—'; }

/* ---------- explain ---------- */
function explainRecord(id: string, audience: 'internal' | 'customer', h: Helpers): CopilotExplanation {
  const r = decisionRecords.find((x) => x.id === id) ?? h.notFound('Decision record', id);
  const def = decisions[r.decision]?.find((d) => d.version === r.version) ?? decisions[r.decision]?.at(-1); const t = traces[r.id];
  const q = def ? Object.values(def.questions)[0] : undefined;
  const label = q?.type === 'score' ? `${q.scoreLevels?.[Number(r.value)] ?? r.value}` : String(r.value);
  const subject = r.subjectType === 'order' ? `order ${r.subjectId}` : r.subjectType === 'return' ? `return ${r.subjectId}` : `${r.subjectType} ${r.subjectId}`;
  if (audience === 'customer') {
    const text = r.decision === 'return_risk' ? `Your return was reviewed automatically and the refund will be issued ${label === 'instant' ? 'right away' : label === 'on_carrier_scan' ? 'as soon as the carrier scans your package' : 'once the item is checked at our warehouse'}. This is based on your order details and the items being returned; no personal information beyond the order was used.`
      : r.decision === 'fulfilment_intent' ? `We will ${label === 'ship_complete' ? 'ship everything together so it arrives as one delivery' : label === 'ship_as_available' ? 'send each item as soon as it is ready' : label === 'pickup_consolidated' ? 'hold the order for collection in store' : 'confirm your delivery preference with you'}. This is based on the notes and choices you made at checkout.`
      : `This step of your order was handled automatically based on your order details: ${label.replace(/_/g, ' ')}. If anything looks wrong, our support team can review it.`;
    return { text, audience, generated: true, recordId: id, model: MODEL };
  }
  const fired = t?.rulesFired ?? []; const probs = Object.values(t?.answers ?? {})[0]?.probabilities; const runner = probs ? Object.entries(probs).sort((a, b) => b[1] - a[1])[1] : undefined;
  const parts = [
    `${r.decision.replace(/_/g, ' ')} v${r.version} evaluated ${subject} on ${new Date(r.evaluatedAt).toLocaleString()} and answered "${label}" with confidence ${r.confidence.toFixed(2)}.`,
    r.layer === 'L0_RULE' ? `Deterministic rule "${fired[0] ?? 'default'}" matched, so no model was called and the band is ACT by construction.` : `No L0 rule matched (${(def?.rules ?? []).map((x) => x.id).join(', ') || 'none defined'}); ${r.provider} answered at layer ${r.layer.replace(/_/g, ' ')} in ${r.latencyMs} ms for ${r.costUsd ? `$${r.costUsd.toFixed(5)}` : 'no cost'}.`,
    `Confidence ${r.confidence.toFixed(2)} falls in the ${r.band} band for a ${def?.tier ?? 'T1'} decision (act ≥ ${def?.bands.actGte ?? 0.8}, confirm ≥ ${def?.bands.confirmGte ?? 0.5}), so the action was "${r.action}".`,
    runner ? `The runner-up option was "${runner[0]}" at ${(runner[1] * 100).toFixed(0)}%; the gap of ${((r.confidence - runner[1]) * 100).toFixed(0)} points is what drove the band.` : '',
    t?.checks?.length ? `All ${t.checks.length} pre/post checks passed (${t.checks.map((c) => c.expression).slice(0, 2).join('; ')}${t.checks.length > 2 ? '; …' : ''}).` : '',
    r.overridden ? `A person later overrode the value to "${String(r.overrideValue)}": ${r.overrideReason}.` : '',
    'This explanation was generated from the stored trace; the trace, not this text, is the source of truth (FR-XAI-03).',
  ].filter(Boolean);
  return { text: parts.join(' '), audience, generated: true, recordId: id, model: MODEL };
}

/* ---------- CS draft ---------- */
function csDraft(orderId: string, message: string, h: Helpers): CsDraft {
  const m = orders.find((o) => o.order.id === orderId || o.order.channelOrderId === orderId) ?? h.notFound('Order', orderId);
  const msg = message.toLowerCase();
  const intent = /damag|broken|defect|cracked/.test(msg) ? 'damaged_item' : /refund|money back|charge/.test(msg) ? 'refund_request' : /cancel/.test(msg) ? 'cancel_request' : /address|moved|deliver to|different place/.test(msg) ? 'address_change' : /where|when|track|arriv|late|shipped|status|eta/.test(msg) ? 'wismo' : 'other';
  const rec = addDecisionRecord('cs_intent', 'order', m.order.id, new Date().toISOString(), { input: { text: message.length > 200 ? message.slice(0, 200) + '…' : message }, order: { status: m.order.status, channel: m.order.channel } }, { forceValue: intent, sharp: intent === 'other' ? 1 : 4 });
  const g = m.groups[0]; const sh = m.shipments[0]; const name = m.order.customer?.name?.split(' ')[0] ?? 'there';
  const promise = g?.promiseDate; const tracking = sh?.tracking; const total = m.order.totals.grandTotal.amount;
  const factsUsed: string[] = [`order.status=${m.order.status}`]; const slots: CsDraft['slots'] = { status: m.order.status };
  if (promise) { factsUsed.push(`fulfilmentGroup.promiseDate=${promise}`); slots.promiseDate = promise; }
  if (tracking) { factsUsed.push(`shipment.tracking=${tracking}`); slots.tracking = tracking; }
  if (sh?.carrier) factsUsed.push(`shipment.carrier=${sh.carrier}`);
  const windowDays = Number(config['returns.window_days'] ?? 30);
  let draft: string;
  switch (intent) {
    case 'wismo':
      draft = `Hi ${name},\n\nThanks for reaching out about order ${m.order.id}. ${sh ? `It shipped with ${sh.carrier}${tracking ? ` (tracking ${tracking})` : ''} and is ${sh.deliveredAt ? `recorded as delivered on ${new Date(sh.deliveredAt).toLocaleDateString()}` : `currently ${sh.status.replace(/_/g, ' ')}`}.` : `It is currently ${m.order.status.replace(/_/g, ' ')} and on track for delivery by ${promise ?? 'the date shown at checkout'}.`}${sh?.eta && !sh.deliveredAt ? ` The carrier's latest estimate is ${sh.eta}.` : ''}\n\nI'll keep an eye on it and let you know if anything changes. Is there anything else I can help with?\n\nBest regards`;
      break;
    case 'refund_request': {
      const amt = round(total); slots.refundAmount = amt; factsUsed.push(`order.totals.grandTotal=${amt}`, `tenant.returns.window_days=${windowDays}`);
      draft = `Hi ${name},\n\nI'm sorry the order didn't work out. For order ${m.order.id} (${m.order.totals.grandTotal.currency} ${amt.toFixed(2)}), ${['delivered', 'shipped', 'partially_shipped'].includes(m.order.status) ? `you can start a return within ${windowDays} days of delivery and the refund is issued according to our returns policy once the return is approved` : `because it has not shipped yet I can cancel it and the authorisation will be released, usually within 3–5 business days`}.\n\nWould you like me to go ahead? If a specific item is the problem, let me know which one.\n\nBest regards`;
      break;
    }
    case 'cancel_request':
      draft = `Hi ${name},\n\nThanks for letting us know. Order ${m.order.id} is ${m.order.status.replace(/_/g, ' ')}${['shipped', 'partially_shipped', 'delivered'].includes(m.order.status) ? ', so it can no longer be cancelled, but I can set up a return as soon as it arrives' : ', so I can cancel it now and release the payment authorisation'}.\n\nShall I proceed?\n\nBest regards`;
      break;
    case 'address_change':
      draft = `Hi ${name},\n\nHappy to help with the delivery address for order ${m.order.id}. ${['shipped', 'partially_shipped'].includes(m.order.status) ? `The parcel is already with ${sh?.carrier ?? 'the carrier'}, so I'll request a carrier redirect; this can take a day and may change the delivery date` : 'It has not shipped yet, so I can update the address before release; the delivery estimate may change slightly'}.\n\nPlease confirm the full new address and I'll take care of it.\n\nBest regards`;
      break;
    case 'damaged_item':
      factsUsed.push(`tenant.returns.window_days=${windowDays}`);
      draft = `Hi ${name},\n\nI'm really sorry an item from order ${m.order.id} arrived damaged. I can arrange a replacement or a refund straight away; a photo of the damage helps us close this with the carrier, but it won't hold up your refund.\n\nLet me know which you'd prefer and I'll set it up today.\n\nBest regards`;
      break;
    default:
      draft = `Hi ${name},\n\nThanks for getting in touch about order ${m.order.id}. I want to make sure I help with the right thing. Could you tell me a little more about what you need?\n\nBest regards`;
  }
  return { draft, intent: { value: intent, confidence: rec.confidence, decisionRecordId: rec.id }, factsUsed, slots, disclaimer: 'Generated from order facts in the OMS; the agent is responsible for the final message. Promise dates and refund amounts are quoted from the order record, not inferred.' };
}

/* ---------- bootstrap ---------- */
function bootstrap(h: Helpers, body: Record<string, unknown>): ChangeSet {
  const text = String(body.text ?? ''); const channels = ((body.channels as string[] | undefined) ?? ['web']).filter(Boolean); const categories = ((body.categories as string[] | undefined) ?? []).filter(Boolean);
  const floor = Number(body.marginFloor ?? 0.12); const windowDays = Number(body.returnWindowDays ?? 30); const bnodes = ((body.nodes as { id: string; type: string; city: string; state: string }[] | undefined) ?? []).filter((n) => n.id);
  const hasStores = bnodes.some((n) => n.type === 'store') || /store|bopis|pickup/i.test(text);
  const hazmat = categories.some((c) => /batter|chemical|aerosol|hazmat/i.test(c)) || /hazmat|batter/i.test(text);
  const orderType = `orderType: standard_ship\nheaderStateModel: order.standard_ship\nlineStateModel: order_line.standard_ship\nworkflowType: order.standard_ship\ndecisionSet:\n  - fulfilment_intent\n  - sourcing_node_score\n  - sla_breach_risk\nchannelDefaults:\n${channels.map((c) => `  ${c}: ${/amazon|walmart|ebay|marketplace/i.test(c) ? 'ship_complete' : 'ship_as_available'}`).join('\n')}\n`;
  const changes: ProposedChange[] = [
    change(h, { kind: 'order_type', target: 'standard_ship', title: 'Order type standard_ship', rationale: `Default ship-to-home order type for ${channels.join(', ')} with channel default policies.`, before: null, after: orderType, tests: [{ caseId: 'ot_default_policy', input: { channel: channels[0] }, expected: /amazon|walmart|ebay/i.test(channels[0]) ? 'ship_complete' : 'ship_as_available' }] }),
    change(h, { kind: 'state_model', target: 'order_line.standard_ship', title: 'Line state model (quantity-level)', rationale: 'Standard capture → sourcing → release → pick → pack → ship → deliver lifecycle with backorder loop and cancellation.', before: null, after: LINE_MODEL_V3.replace(/^version: \d+/m, 'version: 1'), tests: [{ caseId: 'sm_happy_path', input: { events: ['order_validated', 'sourcing_plan', 'pick_started', 'packed', 'shipped', 'delivered'] }, expected: 'delivered' }] }),
    change(h, { kind: 'state_model', target: 'order.standard_ship', title: 'Header state model with roll-up rules', rationale: 'Header derives its state from line quantities (rollup expressions); no header-level events needed.', before: null, after: modelYaml('order.standard_ship').replace(/^version: \d+/m, 'version: 1'), tests: [{ caseId: 'sm_rollup_partial', input: { lines: { shipped: 1, released: 1 } }, expected: 'partially_shipped' }] }),
    change(h, { kind: 'tenant_config', target: 'tenant.core', title: `Core config: margin floor ${Math.round(floor * 100)}%, return window ${windowDays}d`, rationale: 'The guardrail values the sourcing and returns decisions read at run time.', before: null, after: json({ 'sourcing.margin_floor': floor, 'sourcing.max_split_packages': 3, 'sourcing.split_penalty_usd': 4.5, 'returns.window_days': windowDays, 'returns.instant_refund_cap_usd': 100, 'ai.budget_usd_per_day': 100, 'inventory.channel_buffer': Object.fromEntries(channels.map((c) => [c, /amazon|walmart/i.test(c) ? 2 : 0])) }), tests: [{ caseId: 'cfg_floor', input: { key: 'sourcing.margin_floor' }, expected: floor }] }),
    change(h, { kind: 'sla', target: 'order_line.standard_ship.released', title: 'Release SLA 4h → playbook store_backlog', rationale: 'Lines released to a node that are not picked within 4 hours trigger the backlog playbook.', before: null, after: json({ state: 'released', maxAge: 'PT4H', onBreach: 'playbook.store_backlog' }), tests: [{ caseId: 'sla_release', input: { maxAge: 'PT4H' }, expected: 4 }] }),
    change(h, { kind: 'playbook', target: 'short_pick', title: 'Playbook short_pick (re-source → contact → reship)', rationale: 'Most frequent exception on day one; fully automated below the spend cap.', before: null, after: json({ name: 'short_pick', exceptionType: 'inventory_short', steps: [{ action: 're_source', minConfidence: 0.8 }, { action: 'contact_customer', minConfidence: 0.9 }, { action: 'reship', maxSpendUsd: 150 }], enabled: true }), tests: [{ caseId: 'pb_short_pick', input: { exceptionType: 'inventory_short' }, expected: 're_source' }] }),
    change(h, { kind: 'decision_new', target: 'fulfilment_intent', title: 'Decision fulfilment_intent (T1)', rationale: 'Infers the buyer\'s preferred fulfilment outcome; starts in shadow until the golden set is populated.', before: null, after: decisionYaml('fulfilment_intent').replace(/^version: \d+/m, 'version: 1').replace(/^evaluation:[\s\S]*$/m, 'evaluation:\n  goldenSet: fulfilment_intent_gold_v1\n  minAccuracyInActBand: 0.9\n  maxCalibrationError: 0.05\n'), tests: [{ caseId: 'gc_gift_note', input: { order: { channel: channels[0], notes: ['Please ship everything together, it is a gift'], intent_choices: [] }, customer: { tier: 'gold' } }, expected: 'ship_complete' }], tier: 'T1' }),
    change(h, { kind: 'decision_new', target: 'return_risk', title: 'Decision return_risk (T2)', rationale: `Return abuse scoring and refund timing with a ${windowDays}-day window rule.`, before: null, after: decisionYaml('return_risk').replace(/^version: \d+/m, 'version: 1'), tests: [{ caseId: 'gc_outside_window', input: { return: { days_since_delivery: windowDays + 5 } }, expected: 'escalate' }], tier: 'T2' }),
  ];
  if (hasStores) changes.push(change(h, { kind: 'order_type', target: 'bopis', title: 'Order type bopis (buy online, pick up in store)', rationale: `${bnodes.filter((n) => n.type === 'store').length || 'Your'} store nodes can serve pickup; uses the bopis line model with a 5-day pickup SLA.`, before: null, after: `orderType: bopis\nheaderStateModel: order.standard_ship\nlineStateModel: order_line.bopis\nworkflowType: order.bopis\ndecisionSet:\n  - sourcing_node_score\n  - sla_breach_risk\n`, tests: [{ caseId: 'ot_bopis_pickup', input: { orderType: 'bopis', events: ['ready'] }, expected: 'ready_for_pickup' }] }));
  if (hazmat) changes.push(change(h, { kind: 'compliance_rule', target: 'hazmat_single_consignment', title: 'Compliance: hazmat ships as one consignment by ground', rationale: 'Categories include regulated goods; the rule applies to every order type.', before: null, after: json({ id: 'hazmat_single_consignment', when: 'order.lines.exists(l, l.attributes.hazmat == true)', enforce: { maxPackages: 1, carrierServices: ['ground'], requireNodeCapability: 'hazmat' }, onViolation: 'block_release' }), tests: [{ caseId: 'cr_hazmat', input: { plan: { packages: 2 }, order: { lines: [{ attributes: { hazmat: true } }] } }, expected: 'block_release' }] }));
  if (bnodes.length) changes.push(change(h, { kind: 'tenant_config', target: 'nodes', title: `${bnodes.length} fulfilment node${bnodes.length > 1 ? 's' : ''}`, rationale: 'Node records with default capabilities by type; capacities and cut-offs are placeholders to confirm.', before: null, after: json(bnodes.map((n) => ({ id: n.id, name: `${n.city} ${n.type === 'dc' ? 'Fulfilment Center' : n.type === 'store' ? 'Store' : n.type}`, type: n.type, state: n.state, country: 'US', capabilities: n.type === 'store' ? ['ship', 'bopis', 'sfs'] : n.type === 'dc' ? ['ship', ...(hazmat ? ['hazmat'] : [])] : ['ship'], dailyCapacity: n.type === 'dc' ? 10000 : n.type === 'store' ? 150 : 2000, cutoff: n.type === 'store' ? '14:00' : '16:00', active: true }))), tests: [{ caseId: 'nodes_count', input: {}, expected: bnodes.length }] }));
  const cs = newChangeSet(h, text, 'all', `Blueprint from “${text.split(/[.!\n]/)[0].slice(0, 90) || 'your description'}”: ${changes.length} artefacts — order types, state models, core config, SLAs, playbooks and ${changes.filter((c) => c.kind === 'decision_new').length} starter decisions. Everything stages as drafts; nothing goes live without review.`,
    [`Channels: ${channels.join(', ')}.`, categories.length ? `Categories: ${categories.join(', ')}.` : 'No category list given; no category-specific rules proposed.', `Margin floor ${Math.round(floor * 100)}% and return window ${windowDays} days.`, 'Decisions start in shadow mode; golden sets are empty until you add cases.', 'Payment is authorised at capture and captured at ship.'],
    [...(bnodes.length ? [] : ['Where do you ship from (DCs, stores, 3PLs)? Add nodes to get node records and store-fulfilment rules.']), 'Do any channels require signature-on-delivery or age verification?'], changes);
  return cs;
}

/* ---------- Margin Guardian digest ---------- */
const digests: MarginDigest[] = [];
function buildDigest(days: number, at = new Date().toISOString()): MarginDigest {
  const since = new Date(Date.parse(at) - days * 86_400_000).toISOString();
  const inPeriod = orders.filter((m) => m.order.placedAt >= since && m.order.placedAt <= at);
  const splitOrders = inPeriod.filter((m) => m.groups.length > 1);
  const extraPackages = splitOrders.reduce((s, m) => s + m.groups.length - 1, 0);
  const splitCost = round(extraPackages * Number(config['sourcing.split_penalty_usd'] ?? 4.5), 2);
  const byChannel = [...new Set(inPeriod.map((m) => m.order.channel))].map((channel) => { const os = inPeriod.filter((m) => m.order.channel === channel); const sp = os.filter((m) => m.groups.length > 1).length; return { channel, orders: os.length, split_orders: sp, split_rate_pct: round((sp / Math.max(1, os.length)) * 100, 1) }; }).sort((a, b) => b.split_rate_pct - a.split_rate_pct);
  const floor = Number(config['sourcing.margin_floor'] ?? 0.12);
  const below = inPeriod.filter((m) => m.order.margin?.belowFloor);
  const givenUp = round(below.reduce((s, m) => s + Math.max(0, (floor - (m.order.margin?.marginRatio ?? floor)) * m.order.totals.grandTotal.amount), 0), 2);
  const returnsIn = returns.filter((r) => r.createdAt >= since);
  const timing = [...new Set(returnsIn.map((r) => r.refundTiming ?? 'undecided'))].map((t) => ({ timing: t, count: returnsIn.filter((r) => (r.refundTiming ?? 'undecided') === t).length, refund_usd: round(returnsIn.filter((r) => (r.refundTiming ?? 'undecided') === t).reduce((s, r) => s + (r.refundAmount?.amount ?? 0), 0), 2) })).sort((a, b) => b.count - a.count);
  const exc = exceptions.filter((e) => e.raisedAt >= since); const topExceptions = [...new Set(exc.map((e) => e.type))].map((type) => ({ type, count: exc.filter((e) => e.type === type).length, severity: exc.find((e) => e.type === type)?.severity })).sort((a, b) => b.count - a.count).slice(0, 5);
  const recs = decisionRecords.filter((r) => r.evaluatedAt >= since && r.mode === 'LIVE');
  const decs = [...new Set(recs.map((r) => r.decision))].map((decision) => { const rs = recs.filter((r) => r.decision === decision); const esc = rs.filter((r) => r.band === 'ESCALATE').length; const ov = rs.filter((r) => r.overridden).length; return { decision, volume: rs.length, escalations: esc, overrides: ov, escalation_rate_pct: round((esc / Math.max(1, rs.length)) * 100, 1), override_rate_pct: round((ov / Math.max(1, rs.length)) * 100, 1), cost_usd: round(rs.reduce((s, r) => s + r.costUsd, 0), 4) }; }).sort((a, b) => b.volume - a.volume);
  const decisionsUsd = round(decs.reduce((s, d) => s + d.cost_usd, 0) + days * 0.9, 2); const copilotUsd = round(changeSets.reduce((s, c) => s + (c.usage?.costUsd ?? 0), 0), 4);
  const worstChannel = byChannel[0]; const worstDecision = decs.slice().sort((a, b) => b.escalation_rate_pct - a.escalation_rate_pct)[0];
  const findings: MarginDigest['findings'] = [
    { title: 'Split shipments cost more than the penalty assumes', metric: 'split_rate_pct', value: `${round((splitOrders.length / Math.max(1, inPeriod.length)) * 100, 1)}%`, delta: '+1.4 pts vs prior period', evidence: `${splitOrders.length} of ${inPeriod.length} orders shipped in more than one package (${extraPackages} extra packages, ≈ $${splitCost.toFixed(2)} in carrier cost)${worstChannel ? `; ${worstChannel.channel} splits most at ${worstChannel.split_rate_pct}%` : ''}.`, suggestedPrompt: worstChannel ? `Raise the split penalty for ${worstChannel.channel} orders so plans with more than one package need a 2% margin advantage to win` : 'Raise the split penalty so multi-package plans need a 2% margin advantage to win' },
    { title: 'Below-floor plans are being approved without margin recovery', metric: 'margin_given_up_usd', value: `$${givenUp.toFixed(2)}`, delta: below.length ? `${below.length} orders` : '0 orders', evidence: `${below.length} orders sourced below the ${Math.round(floor * 100)}% floor; ${below.filter((m) => m.order.margin?.approvalPresent).length} carried an approval. Margin given up ≈ $${givenUp.toFixed(2)} on $${round(below.reduce((s, m) => s + m.order.totals.grandTotal.amount, 0), 2).toFixed(2)} revenue.`, suggestedPrompt: 'Require a margin approval task to include a recovery note and cap approvals at 3 per analyst per day' },
    { title: 'Instant refunds skew towards one timing bucket', metric: 'returns_by_timing', value: timing[0] ? `${timing[0].timing} · ${timing[0].count}` : '—', delta: null, evidence: timing.length ? timing.map((t) => `${t.timing} ${t.count} ($${(t.refund_usd ?? 0).toFixed(0)})`).join(' · ') : 'No returns in the period.', suggestedPrompt: 'Refund on carrier scan instead of on inspection for customers with 3 or more clean returns' },
    { title: worstDecision ? `${worstDecision.decision} escalates ${worstDecision.escalation_rate_pct}% of the time` : 'Decision escalations', metric: 'escalation_rate_pct', value: worstDecision ? `${worstDecision.escalation_rate_pct}%` : '—', delta: worstDecision ? `${worstDecision.escalations} escalations / ${worstDecision.volume}` : null, evidence: worstDecision ? `${worstDecision.decision}: ${worstDecision.volume} decisions, ${worstDecision.escalations} escalated to a person, ${worstDecision.overrides} overridden; cost $${worstDecision.cost_usd.toFixed(4)}.` : 'No live decisions in the period.', suggestedPrompt: worstDecision ? `Lower the confirm threshold for ${worstDecision.decision} from the current value by 0.05 and add an escape option for ambiguous cases` : undefined },
    { title: 'AI spend is well inside budget', metric: 'ai_cost_total_usd', value: `$${(decisionsUsd + copilotUsd).toFixed(2)}`, delta: `${Math.round(((decisionsUsd + copilotUsd) / Math.max(1, Number(config['ai.budget_usd_per_day'] ?? 250) * days)) * 100)}% of budget`, evidence: `Decisions $${decisionsUsd.toFixed(2)} + copilot $${copilotUsd.toFixed(2)} over ${days} days against a budget of $${Number(config['ai.budget_usd_per_day'] ?? 250)}/day.` },
  ];
  return { id: nextId('dg'), period: { days, from: since.slice(0, 10), to: at.slice(0, 10) }, headline: `${inPeriod.length} orders in ${days} days: splits at ${round((splitOrders.length / Math.max(1, inPeriod.length)) * 100, 1)}% cost ≈ $${splitCost.toFixed(0)}, $${givenUp.toFixed(0)} of margin given up below the floor${worstDecision ? `, and ${worstDecision.decision} is the escalation hotspot` : ''}.`, findings, generated: true, model: MODEL, facts: { orders: inPeriod.length, splits: { split_rate_pct: round((splitOrders.length / Math.max(1, inPeriod.length)) * 100, 1), split_orders: splitOrders.length, extra_packages: extraPackages, estimated_split_cost_usd: splitCost, by_channel: byChannel }, belowFloor: { below_floor_orders: below.length, approved: below.filter((m) => m.order.margin?.approvalPresent).length, margin_given_up_usd: givenUp, revenue: round(below.reduce((s, m) => s + m.order.totals.grandTotal.amount, 0), 2) }, returnsByTiming: timing, topExceptions, decisions: decs, aiCost: { decisions_usd: decisionsUsd, copilot_usd: copilotUsd, total_usd: round(decisionsUsd + copilotUsd, 2), budget_usd_per_day: Number(config['ai.budget_usd_per_day'] ?? 250) } }, generatedAt: at, fallbackReason: null };
}

/* ---------- seed history ---------- */
function seed(h: Helpers) {
  const a = proposeInstantRefund(h, 'Gold customers with a receipt get an instant refund under $250', 'all', {});
  a.createdBy = 'strategy'; a.createdAt = hoursAgo(3);
  const b = proposeAgentRiskReview(h, 'Agent orders over $500 to California need a risk review', 'decisions', { 'Should trusted-partner agents (trust tier 3) be exempt from the review?': 'No, review all agents for now' });
  b.createdBy = 'risk'; b.createdAt = daysAgo(1.2);
  b.changes.filter((c) => c.kind !== 'decision_new').forEach((c) => stageChange(h, b, c)); recomputeStatus(b);
  const c = proposeMarginFloor(h, 'Raise the margin floor for marketplace orders to 15%', 'policies', {});
  c.createdBy = 'finance'; c.createdAt = daysAgo(3); c.status = 'rejected'; c.summary += ' (Rejected: finance wants to wait for Q4 fee changes.)';
  const d = proposePreorder(h, 'Add a pre-order order type with a 30-day backorder window', 'order_types', { 'Which channels may place pre-orders (web and shopify assumed)?': 'web only' });
  d.createdBy = 'ops'; d.createdAt = daysAgo(6); d.changes.forEach((x) => stageChange(h, d, x)); recomputeStatus(d);
  for (const art of artifacts) { const cs = changeSets.find((c) => c.id === art.changesetId); if (cs) { art.createdBy = cs.createdBy; art.createdAt = cs.createdAt; } }
  const applied = artifacts.find((x) => x.kind === 'sla'); if (applied) { applied.status = 'applied'; applied.appliedBy = 'admin'; applied.appliedAt = daysAgo(5); }
  auditLog.splice(0, auditLog.length, ...auditLog.filter((e) => e.action !== 'copilot.stage'));
  const old = buildDigest(7, daysAgo(7)); old.generated = false; old.model = 'deterministic'; old.fallbackReason = 'LLM budget exhausted for the day; deterministic digest'; digests.push(old);
  digests.unshift(buildDigest(7, hoursAgo(26)));
}

/* ---------- routes ---------- */
export function registerCopilotRoutes(h: Helpers) {
  const { on, problem, notFound } = h;
  const find = (id: string) => changeSets.find((c) => c.id === id) ?? notFound('Change set', id);
  on('GET', '/api/v1/copilot/status', (): CopilotStatus => { const off = !!config['ai.kill_switch'] || !!config['provider.kill_switch.llm.anthropic']; const budget = Number(config['ai.budget_usd_per_day'] ?? 0) || null; const copilotUsed = round(changeSets.reduce((s, c) => s + (c.usage?.costUsd ?? 0), 0) + digests.length * 0.018, 4); const spent = round(3.42 + copilotUsed, 2); return { available: !off, model: MODEL, baseUrl: 'https://api.anthropic.com', budgetUsdPerDay: budget, spentTodayUsd: spent, remainingUsd: budget ? round(Math.max(0, budget - spent), 2) : null, budgetExceeded: !!budget && spent >= budget, copilotUsedTodayUsd: copilotUsed, copilotCallsToday: changeSets.length + digests.length + 3, features: ['changes', 'bootstrap', 'ops', 'explain', 'cs_draft', 'digest', ...(off ? [] : ['llm'])] }; });
  on('POST', '/api/v1/copilot/changes', ({ body }) => { const text = String(body.text ?? '').trim(); if (text.length < 8) problem(422, 'validation_failed', 'Describe the change in at least a short sentence', { fields: { text: 'too short' } }); return propose(h, text, String(body.scope ?? 'all'), (body.context as Record<string, unknown>) ?? {}); });
  on('GET', '/api/v1/copilot/changes', ({ query }) => { const offset = Number(query.get('offset') ?? 0); const limit = Math.min(100, Number(query.get('limit') ?? 20)); const sorted = changeSets.slice().sort((a, b) => b.createdAt.localeCompare(a.createdAt)); return { items: sorted.slice(offset, offset + limit), total: sorted.length }; });
  on('GET', '/api/v1/copilot/changes/:id', ({ params }) => find(params.id));
  on('POST', '/api/v1/copilot/changes/:id/stage', ({ params, body }) => { const cs = find(params.id); if (cs.status === 'rejected') problem(409, 'conflict', 'Change set was rejected'); const ids = (body.changeIds as string[] | undefined) ?? []; if (!ids.length) problem(422, 'validation_failed', 'Select at least one change', { fields: { changeIds: 'required' } }); for (const c of cs.changes) { if (!ids.includes(c.id) || c.staged) continue; if (!c.validation.ok) problem(422, 'validation_failed', `Change "${c.title}" has validation errors; fix the proposal before staging`); stageChange(h, cs, c); } recomputeStatus(cs); return cs; });
  on('POST', '/api/v1/copilot/changes/:id/reject', ({ params, body }) => { const cs = find(params.id); if (!String(body.reason ?? '').trim()) problem(422, 'validation_failed', 'A rejection reason is required', { fields: { reason: 'required' } }); cs.status = 'rejected'; auditLog.unshift({ id: auditLog.length + 1000, at: new Date().toISOString(), actor: h.actor(), action: 'copilot.reject', object_type: 'change_set', object_id: cs.id, reason: String(body.reason) }); return cs; });
  on('GET', '/api/v1/copilot/changes/:id/changes/:changeId/explain', ({ params }) => {
    const cs = find(params.id); const c = cs.changes.find((x) => x.id === params.changeId) ?? notFound('Change', params.changeId);
    const kindText: Record<string, string> = { decision_rule: 'adds a deterministic L0 rule, so matching cases are settled without a model call and are fully auditable', decision_criteria: 'changes how an option is described to the model; the L1/L2 layers read this text verbatim, so wording is the policy', decision_bands: 'moves the confidence thresholds that decide whether the system acts, asks for confirmation or escalates', decision_new: 'creates a new decision that starts in shadow mode and can only go live after evaluation against a golden set and the approvals its tier requires', state_model: 'changes the lifecycle every in-flight entity follows; a new version is created and existing entities stay on the old one until migrated', order_type: 'defines a new way orders enter the system, binding state models, workflow and decision set', sla: 'changes when a state is considered late and what happens on breach', playbook: 'changes the automated steps that run when an exception of this type is raised', tenant_config: 'changes a value read at run time by decisions and guards; it takes effect immediately on apply and is audited', compliance_rule: 'adds a tenant-wide rule enforced regardless of order type' };
    const warn = c.validation.issues.filter((i) => i.severity !== 'info');
    return { text: `${c.title}. This change ${kindText[c.kind] ?? 'updates configuration'}. ${c.rationale} ${c.riskTier ? `It is a ${c.riskTier} change and needs ${c.requiresApprovals} approval${c.requiresApprovals === 1 ? '' : 's'} before it can go live.` : ''} ${warn.length ? `Validation raised ${warn.length} finding${warn.length > 1 ? 's' : ''}: ${warn.map((w) => w.message).join(' ')}` : 'Validation found no issues.'} ${c.tests.length ? `${c.tests.length} test case${c.tests.length > 1 ? 's were' : ' was'} generated; "${c.tests[0].caseId}" expects ${JSON.stringify(c.tests[0].expected)}.` : ''} Staging creates a ${isDecisionKind(c.kind) ? 'studio draft you can lint, run impact analysis on and submit for approval' : 'draft artefact that must be applied explicitly with a reason'}; nothing changes in production until then.`.replace(/\s+/g, ' ').trim() };
  });
  on('POST', '/api/v1/copilot/changes/:id/changes/:changeId/tests', ({ params }) => { const cs = find(params.id); const c = cs.changes.find((x) => x.id === params.changeId) ?? notFound('Change', params.changeId); if (!c.tests.length) problem(422, 'validation_failed', 'This change has no generated tests'); return { added: c.tests.length, goldenSet: `${c.target.replace(/[^\w]/g, '_')}_gold` }; });
  on('GET', '/api/v1/copilot/artifacts', ({ query }) => { const st = query.get('status'); return { items: artifacts.filter((a) => !st || a.status === st) }; });
  on('POST', '/api/v1/copilot/artifacts/:id/apply', ({ params, body }) => {
    const a = artifacts.find((x) => x.id === params.id) ?? notFound('Artifact', params.id);
    if (!String(body.reason ?? '').trim()) problem(422, 'validation_failed', 'An apply reason is required (audited)', { fields: { reason: 'required' } });
    if (a.status !== 'draft') problem(409, 'conflict', `Artifact is ${a.status}`);
    if (a.kind === 'tenant_config' && a.json) { try { const v = JSON.parse(a.json) as unknown; if (v && typeof v === 'object' && !Array.isArray(v)) Object.assign(config, v); } catch { /* leave config */ } }
    a.status = 'applied'; a.appliedBy = h.actor().split(':')[1]; a.appliedAt = new Date().toISOString();
    auditLog.unshift({ id: auditLog.length + 1000, at: a.appliedAt, actor: h.actor(), action: 'copilot.artifact.apply', object_type: a.kind, object_id: a.target, reason: String(body.reason) });
    return a;
  });
  on('POST', '/api/v1/copilot/artifacts/:id/reject', ({ params, body }) => { const a = artifacts.find((x) => x.id === params.id) ?? notFound('Artifact', params.id); if (!String(body.reason ?? '').trim()) problem(422, 'validation_failed', 'A rejection reason is required', { fields: { reason: 'required' } }); a.status = 'rejected'; return a; });
  on('POST', '/api/v1/copilot/bootstrap', ({ body }) => { if (String(body.text ?? '').trim().length < 12) problem(422, 'validation_failed', 'Describe the business in a sentence or two', { fields: { text: 'too short' } }); return bootstrap(h, body); });
  on('GET', '/api/v1/copilot/ops/templates', () => ({ templates: OPS_TEMPLATES }));
  on('POST', '/api/v1/copilot/ops/ask', ({ body }) => { const q = String(body.question ?? '').trim(); if (!q) problem(422, 'validation_failed', 'Ask a question', { fields: { question: 'required' } }); return opsAsk(q); });
  on('GET', '/api/v1/copilot/digest', ({ query }) => buildDigest(Math.max(1, Number(query.get('days') ?? 7))));
  on('POST', '/api/v1/copilot/digest/run', ({ query }) => { const d = buildDigest(Math.max(1, Number(query.get('days') ?? 7))); digests.unshift(d); auditLog.unshift({ id: auditLog.length + 1000, at: d.generatedAt, actor: h.actor(), action: 'copilot.digest.run', object_type: 'digest', object_id: d.id, reason: `${d.period.days} days` }); return d; });
  on('GET', '/api/v1/copilot/digest/latest', () => digests[0] ?? null);
  on('GET', '/api/v1/copilot/digest/history', ({ query }) => ({ items: digests.slice(0, Number(query.get('limit') ?? 10)) }));
  on('GET', '/api/v1/copilot/explain/:recordId', ({ params, query }) => explainRecord(params.recordId, query.get('audience') === 'customer' ? 'customer' : 'internal', h));
  on('POST', '/api/v1/copilot/cs/draft', ({ body }) => { const orderId = String(body.orderId ?? ''); const message = String(body.message ?? '').trim(); if (!message) problem(422, 'validation_failed', 'Paste the customer message', { fields: { message: 'required' } }); return csDraft(orderId, message, h); });
  seed(h);
}
