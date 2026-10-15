/**
 * Showcase catalogue: what to look at, in which order, and where it lives in the UI. Data only — the page renders it.
 * Add a card by appending to CATALOGUE; `catalogue.test.ts` checks that every deep link is a known route.
 */
export type CardKind = 'demo' | 'reference' | 'feature';
export type Needs = 'none' | 'jev' | 'llm';

export interface DeepLink { label: string; to: string; hint?: string; }
export interface Scenario { id: string; title: string; }
export interface ShowcaseCard {
  id: string;
  kind: CardKind;
  title: string;
  /** Two lines of business value: the pain, and what Intent OMS does instead. */
  value: string;
  /** Shell command to run against a live system (repo root). */
  command?: string;
  /** Which AI provider the story needs to be fully autonomous; everything still runs in degraded mode. */
  needs?: Needs;
  links: DeepLink[];
  /** Reference implementations run on their own tenant. */
  tenant?: { id: string; name: string };
  scenarios?: Scenario[];
  /** Relative docs path (under docs/). */
  docs?: string;
}

/** Routes other agents are adding; a deep link may point at them before App.tsx has the route. */
export const PLANNED_ROUTES = ['/playground', '/replay'];

export const CATALOGUE: ShowcaseCard[] = [
  /* ---- Flagship: autonomous operations ---- */
  { id: 'control-tower', kind: 'feature', title: 'Agentic Control Tower · autonomous anomaly diagnosis and guardrail-bound self-healing orders', needs: 'none',
    value: 'Ops teams stop manually cross-referencing four systems to find why an order stalled. The tower now diagnoses the likely root cause on its own and fixes it within hard guardrails — or, when it cannot act safely, hands a person the diagnosis instead of a blank slate.',
    links: [{ label: 'Open Control Tower', to: '/control-tower', hint: 'Anomaly diagnosis, guardrails and self-healing actions' }] },

  /* ---- Demo scenarios (docs/DEMO_USE_CASES.md) ---- */
  { id: 'uc1', kind: 'demo', title: 'UC1 · The gift that must arrive together', needs: 'jev', command: 'scripts/usecases/uc1-intent-sourcing.sh', docs: 'DEMO_USE_CASES.md',
    value: 'Split shipments and cost-to-serve leak margin when "ship complete" is a static flag. Intent OMS reads the buyer\'s intent as a typed decision and scores every plan on margin, ETA and splits — nothing ships below the floor without a recorded approval.',
    links: [{ label: 'Orders UC1-*', to: '/orders?q=UC1-', hint: 'three orders, one box vs two packages vs margin floor' }, { label: 'fulfilment_intent', to: '/studio/fulfilment_intent' }, { label: 'sourcing_node_score', to: '/studio/sourcing_node_score' }, { label: 'Margin-floor approvals', to: '/tasks?queue=margin_floor_approvals' }, { label: 'State model', to: '/state-models/order_line.standard_ship' }] },
  { id: 'uc2', kind: 'demo', title: 'UC2 · Returns as margin recovery', needs: 'jev', command: 'scripts/usecases/uc2-returns-margin-recovery.sh', docs: 'DEMO_USE_CASES.md',
    value: 'Refund-on-scan for everyone, fraud scoring in another tool, store and online returns apart. Four identical returns get four outcomes — instant refund, keep-it, store credit, judgement call — each with a Why.',
    links: [{ label: 'Returns', to: '/returns' }, { label: 'Orders UC2-*', to: '/orders?q=UC2-' }, { label: 'return_risk', to: '/studio/return_risk' }, { label: 'Risk review tasks', to: '/tasks?queue=risk_review' }] },
  { id: 'uc3', kind: 'demo', title: 'UC3 · The Dallas DC goes dark', needs: 'none', command: 'scripts/usecases/uc3-disruption.sh', docs: 'DEMO_USE_CASES.md',
    value: 'Peak-day re-routing is a war room with spreadsheets. Simulate the closure on a week of real orders first, take the node out without a deploy, bulk re-source with a dry run and a per-order report.',
    links: [{ label: 'Simulation', to: '/simulation' }, { label: 'Orders UC3-TX*', to: '/orders?q=UC3-TX' }, { label: 'Exceptions', to: '/exceptions' }, { label: 'Control Tower', to: '/control-tower' }] },
  { id: 'uc4', kind: 'demo', title: 'UC4 · Agent-placed orders with proof', needs: 'none', command: 'scripts/usecases/uc4-agent-orders.sh', docs: 'DEMO_USE_CASES.md',
    value: 'Agents are "one more channel" with no mandate evidence and no injection defence. Signed mandates, spend limits, prompt-injection holds, human confirmation for low-trust assistants, and a dispute evidence pack.',
    links: [{ label: 'Agents', to: '/agents' }, { label: 'Orders UC4-*', to: '/orders?q=UC4-' }, { label: 'agent_mandate_match', to: '/studio/agent_mandate_match' }, { label: 'Risk review tasks', to: '/tasks?queue=risk_review' }] },
  { id: 'uc5', kind: 'demo', title: 'UC5 · A policy change before lunch', needs: 'none', command: 'scripts/usecases/uc5-policy-change.sh', docs: 'DEMO_USE_CASES.md',
    value: 'Decisions live in implementer-owned configuration; a change is a ticket and a deploy. Draft → lint → impact diff on 30 days of real inputs → golden gate → second-person approval → canary → live → rollback → kill switch.',
    links: [{ label: 'Policy Studio', to: '/studio' }, { label: 'fulfilment_intent versions', to: '/studio/fulfilment_intent' }, { label: 'Decision analytics', to: '/analytics?decision=fulfilment_intent' }, { label: 'Audit log', to: '/settings?tab=audit' }] },
  { id: 'uc6', kind: 'demo', title: 'UC6 · Configure the OMS by talking to it', needs: 'llm', command: 'scripts/usecases/uc6-prompt-configured.sh', docs: 'DEMO_USE_CASES.md',
    value: 'Every policy change is a YAML ticket. Type the policy in plain English and get a governed ChangeSet: diffs, deterministic validation, generated golden cases, risk tier — staged as drafts a human publishes.',
    links: [{ label: 'Copilot', to: '/copilot' }, { label: 'Studio drafts', to: '/studio' }, { label: 'return_risk', to: '/studio/return_risk' }, { label: 'State model', to: '/state-models/order_line.standard_ship' }] },
  { id: 'uc7', kind: 'demo', title: 'UC7 · Margin Guardian', needs: 'llm', command: 'scripts/usecases/uc7-margin-guardian.sh', docs: 'DEMO_USE_CASES.md',
    value: 'Nobody sees where this week\'s margin went until the month closes. A digest with facts computed from the ledger, 3–6 grounded findings and a copilot prompt per finding; ask the OMS in plain English.',
    links: [{ label: 'Analytics · digest', to: '/analytics' }, { label: 'Ask the OMS', to: '/control-tower' }, { label: 'Copilot', to: '/copilot' }, { label: 'AI Economics', to: '/analytics?tab=ai-economics' }] },

  /* ---- Reference implementations (own tenants; scripts/reference/<tenant>.sh) ---- */
  { id: 'ref-retail', kind: 'reference', title: 'Maple & Main General Store', tenant: { id: 'retail', name: 'Maple & Main General Store' }, command: 'scripts/reference/retail.sh', needs: 'none',
    value: 'A multi-store general retailer: store rejects, hazmat and oversized routing, marketplace margins, holiday surge and curbside promises — configured as business-owned decisions, not code.',
    scenarios: [{ id: 'R1', title: 'Store-reject reroute' }, { id: 'R2', title: 'Hazmat + oversized routing' }, { id: 'R3', title: 'Margin-floor marketplace order' }, { id: 'R4', title: 'Holiday surge capacity' }, { id: 'R5', title: 'Curbside promise' }],
    links: [{ label: 'Orders', to: '/orders' }, { label: 'Policy Studio', to: '/studio' }, { label: 'Simulation', to: '/simulation' }, { label: 'AI Economics', to: '/analytics?tab=ai-economics' }] },
  { id: 'ref-electronics', kind: 'reference', title: 'Voltline Electronics', tenant: { id: 'electronics', name: 'Voltline Electronics' }, command: 'scripts/reference/electronics.sh', needs: 'none',
    value: 'High-value consumer electronics: launch-day allocation, address risk on expensive baskets, backorders, warranty triage and drop-ship vendors that miss their SLA.',
    scenarios: [{ id: 'E1', title: 'Launch-day pre-order allocation' }, { id: 'E2', title: 'High-value address risk hold' }, { id: 'E3', title: 'Backorder next action from a customer note' }, { id: 'E4', title: 'Warranty triage and disposition' }, { id: 'E5', title: 'Drop-ship vendor SLA breach re-promise' }],
    links: [{ label: 'Orders', to: '/orders' }, { label: 'Tasks', to: '/tasks' }, { label: 'Exceptions', to: '/exceptions' }, { label: 'Policy Studio', to: '/studio' }] },
  { id: 'ref-apparel', kind: 'reference', title: 'Lumen & Loom', tenant: { id: 'apparel', name: 'Lumen & Loom' }, command: 'scripts/reference/apparel.sh', needs: 'none',
    value: 'Fashion with bracketing, size exchanges and final-sale disputes: detect bracketing at capture, exchange from the nearest store, decide keep-it vs return on margin, answer disputes with a grounded CS draft.',
    scenarios: [{ id: 'A1', title: 'Bracketing detected at capture' }, { id: 'A2', title: 'Size exchange from the nearest store' }, { id: 'A3', title: 'Keep-it vs return margin decision' }, { id: 'A4', title: 'Drop oversell handled by policy' }, { id: 'A5', title: 'Final-sale dispute answered with a grounded CS draft' }],
    links: [{ label: 'Returns', to: '/returns' }, { label: 'Orders', to: '/orders' }, { label: 'return_risk', to: '/studio/return_risk' }, { label: 'Copilot', to: '/copilot' }] },

  /* ---- Features to try ---- */
  { id: 'playground', kind: 'feature', title: 'Playground', needs: 'none',
    value: 'Evaluate any decision on any input, right now, and read the Why. The fastest way to feel how L0 rules, Jev and the LLM layers hand off inside a risk tier.',
    links: [{ label: 'Open Playground', to: '/playground' }, { label: 'Policy Studio', to: '/studio' }] },
  { id: 'replay', kind: 'feature', title: 'Margin Replay', needs: 'none',
    value: 'Replay a window of real orders under a different policy version and see what the margin would have been. The impact diff, at order level, before anything ships.',
    links: [{ label: 'Open Margin Replay', to: '/replay' }, { label: 'Simulation', to: '/simulation' }] },
  { id: 'copilot', kind: 'feature', title: 'Copilot', needs: 'llm',
    value: 'Plain-English configuration, "Ask the OMS", customer-safe explanations and CS reply drafts — every number post-checked against facts, nothing published by the model.',
    links: [{ label: 'Open Copilot', to: '/copilot' }, { label: 'Bootstrap a tenant', to: '/copilot/bootstrap' }, { label: 'Ask the OMS', to: '/control-tower' }] },
  { id: 'ai-economics', kind: 'feature', title: 'AI Economics', needs: 'none',
    value: 'Decisions by layer, automation rate, human decisions avoided, cost per 1 000 decisions and per order, overrides, latency — with a Markdown summary to paste into a review.',
    links: [{ label: 'Open AI Economics', to: '/analytics?tab=ai-economics' }] },
];

export const KIND_LABELS: Record<CardKind, { title: string; blurb: string }> = {
  demo: { title: 'Demo scenarios', blurb: 'Seven stories on the demo tenant. Run the script, then follow the links; keep the Why drawer open — it is the product.' },
  reference: { title: 'Reference implementations', blurb: 'Three verticals with their own tenants and five scenarios each. Switching tenant signs you out; pick the tenant id on the login page.' },
  feature: { title: 'Features to try', blurb: 'Standalone surfaces worth five minutes each.' },
};

/** Path part of a deep link (without the query string). */
export const linkPath = (to: string): string => to.split('?')[0];

/** True when a concrete path (e.g. /studio/return_risk) matches a React Router pattern (e.g. /studio/:name). */
export function routeMatches(path: string, pattern: string): boolean {
  const a = path.split('/').filter(Boolean); const b = pattern.split('/').filter(Boolean);
  if (a.length !== b.length) return false;
  return b.every((seg, i) => seg.startsWith(':') || seg === a[i]);
}
