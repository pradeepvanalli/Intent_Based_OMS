/* Decision-trace helpers for the Playground: normalisation, ordering, totals and the primary record. Pure. */
import type { Band, DecisionRecord, Layer } from '../../api/types';

/** A decision record as either the UI type (`evaluatedAt`) or the backend row (`createdAt`) presents it. */
export type RawRecord = Partial<DecisionRecord> & { id: string; decision: string; createdAt?: string; evaluatedAt?: string; rulesFired?: string[]; requiresHuman?: boolean; escalationId?: string | null };

export interface TraceRecord {
  id: string; decision: string; version: number; layer: Layer | string; band: Band | string; action: string; value: unknown; confidence: number;
  provider: string; modelVersion?: string; latencyMs: number; costUsd: number; at: string; reasonCodes: string[]; rulesFired: string[]; requiresHuman: boolean;
}

export function normalizeRecord(r: RawRecord): TraceRecord {
  const at = r.evaluatedAt ?? r.createdAt ?? '';
  return {
    id: r.id, decision: r.decision, version: Number(r.version ?? 0), layer: (r.layer ?? 'DEFAULT') as Layer, band: (r.band ?? 'ESCALATE') as Band, action: String(r.action ?? ''),
    value: r.value, confidence: Number(r.confidence ?? 0) || 0, provider: String(r.provider ?? (r.layer === 'L0_RULE' ? 'rules' : 'unknown')), modelVersion: r.modelVersion ?? undefined,
    latencyMs: Number(r.latencyMs ?? 0) || 0, costUsd: Number(r.costUsd ?? 0) || 0, at, reasonCodes: (r.reasonCodes ?? []).map(String), rulesFired: (r.rulesFired ?? []).map(String),
    requiresHuman: !!r.requiresHuman || r.layer === 'L3_HUMAN' || !!r.escalationId,
  };
}

/** Chronological, de-duplicated by id; records without a timestamp keep arrival order after timestamped ones. */
export function orderTrace(records: RawRecord[]): TraceRecord[] {
  const seen = new Set<string>();
  const out: TraceRecord[] = [];
  for (const r of records) { if (!r || !r.id || seen.has(r.id)) continue; seen.add(r.id); out.push(normalizeRecord(r)); }
  return out
    .map((r, i) => ({ r, i }))
    .sort((a, b) => { if (!a.r.at && !b.r.at) return a.i - b.i; if (!a.r.at) return 1; if (!b.r.at) return -1; const c = a.r.at.localeCompare(b.r.at); return c !== 0 ? c : a.i - b.i; })
    .map((x) => x.r);
}

export interface TraceStats { decisions: number; autonomous: number; confirmed: number; escalated: number; humanAvoided: number; costUsd: number; latencyMs: number; aiCalls: number; }

/** "Human decisions avoided" counts every decision the fabric took without a person (act band, or a deterministic default/rule). */
export function traceStats(records: TraceRecord[]): TraceStats {
  let autonomous = 0, confirmed = 0, escalated = 0, costUsd = 0, latencyMs = 0, aiCalls = 0;
  for (const r of records) {
    const acted = r.band === 'ACT' || r.action === 'act' || r.layer === 'L0_RULE' || (r.layer === 'DEFAULT' && !r.requiresHuman);
    if (r.requiresHuman || r.band === 'ESCALATE' && !acted) escalated++; else if (acted) autonomous++; else confirmed++;
    costUsd += r.costUsd; latencyMs += r.latencyMs;
    if (r.layer === 'L1_SYSTEM_ONE' || r.layer === 'L2_LLM') aiCalls++;
  }
  return { decisions: records.length, autonomous, confirmed, escalated, humanAvoided: autonomous + confirmed, costUsd, latencyMs, aiCalls };
}

const PRIMARY_ORDER = ['fulfilment_intent', 'sourcing_node_score', 'split_acceptability', 'address_risk', 'agent_mandate_match', 'injection_screen', 'sla_breach_risk'];
/** The record the "Explain this order" button talks about: intent first, then sourcing, then anything else. */
export function primaryRecord(records: TraceRecord[]): TraceRecord | undefined {
  for (const name of PRIMARY_ORDER) { const r = records.find((x) => x.decision === name); if (r) return r; }
  return records[0];
}

export const LAYER_META: Record<string, { short: string; name: string; tone: string }> = {
  L0_RULE: { short: 'L0', name: 'Rules', tone: 'L0' }, L1_SYSTEM_ONE: { short: 'L1', name: 'Jev', tone: 'L1' }, L2_LLM: { short: 'L2', name: 'LLM', tone: 'L2' }, L3_HUMAN: { short: 'L3', name: 'Human', tone: 'L3' }, DEFAULT: { short: 'DEF', name: 'Default', tone: 'DEFAULT' },
};
export const DECISION_LABEL: Record<string, string> = {
  fulfilment_intent: 'Fulfilment intent', sourcing_node_score: 'Sourcing node score', split_acceptability: 'Split acceptability', sla_breach_risk: 'SLA breach risk', address_risk: 'Address risk',
  injection_screen: 'Injection screen', agent_mandate_match: 'Mandate match', exception_classify: 'Exception classify', backorder_next_action: 'Backorder next action',
};
export const labelFor = (decision: string): string => DECISION_LABEL[decision] ?? decision.replace(/_/g, ' ');
