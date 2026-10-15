import type { Answer, Band, DecisionRecord, DecisionTrace, Layer, TraceCheck, TraceLayerStep, TraceQuestion, TraceRule } from './types';

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Raw) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown, d = ''): string => (v == null ? d : String(v));
const num = (v: unknown, d = 0): number => (typeof v === 'number' ? v : Number(v ?? d) || d);

/**
 * The backend's DecisionTrace (FR-XAI-01) is a flat document: recordId, decision, version, tier, layer, band, action,
 * value, confidence, state, questions[{key,type,instructions,choiceCriteria,scoreLevels,statement,answer}], answers,
 * rulesFired, preChecks, postChecks, bands, provider, modelVersion, latencyMs, costUsd, actor, escalation, labels,
 * details, createdAt, definition. The UI renders the richer nested shape; this adapter maps one onto the other and
 * accepts either (a trace that already carries `record` passes through).
 */
export function normalizeTrace(raw: unknown, audience: 'internal' | 'customer' = 'internal'): DecisionTrace {
  const t = obj(raw);
  if (t.record && t.layers && t.questions) return t as unknown as DecisionTrace;
  const def = obj(t.definition);
  const details = obj(t.details);
  const layer = str(t.layer, 'DEFAULT') as Layer;
  const band = str(t.band, 'ESCALATE') as Band;
  const escalation = t.escalation ? obj(t.escalation) : null;
  const labels = obj(t.labels);
  const labelKeys = Object.keys(labels);
  const overrideValue = t.overriddenValue ?? details.overriddenValue;
  const record: DecisionRecord = {
    id: str(t.recordId ?? t.id), decision: str(t.decision ?? def.name), version: num(t.version ?? def.version), subjectType: str(t.subjectType), subjectId: str(t.subjectId),
    mode: str(t.mode, 'LIVE') as DecisionRecord['mode'], layer, band, value: t.value, confidence: num(t.confidence), action: str(t.action), provider: str(t.provider),
    modelVersion: t.modelVersion == null ? undefined : str(t.modelVersion), latencyMs: num(t.latencyMs), costUsd: num(t.costUsd), evaluatedAt: str(t.createdAt ?? t.evaluatedAt),
    actor: t.actor == null ? undefined : str(t.actor), overridden: overrideValue != null, overrideValue, overrideReason: t.overrideReason == null ? undefined : str(t.overrideReason),
    label: labelKeys.length ? labelKeys[0] : null, reasonCodes: arr(t.reasonCodes).map(String), correlationId: t.correlationId == null ? undefined : str(t.correlationId),
  };
  const answers = obj(t.answers);
  const questions: TraceQuestion[] = arr(t.questions).map((q0) => {
    const q = obj(q0);
    const a = obj(q.answer ?? answers[str(q.key)]);
    const answer: Answer = { type: str(q.type) as Answer['type'], value: a.value, probabilities: obj(a.probabilities) as Record<string, number>, confidence: num(a.confidence), legend: obj(a.legend) as Record<string, string> };
    const criteria = obj(q.choiceCriteria ?? q.criteria) as Record<string, string>;
    const escapeKey = str(q.escapeOption);
    return { key: str(q.key), type: answer.type, instructions: str(q.instructions ?? q.statement), criteria: Object.keys(criteria).length ? criteria : undefined,
      levels: arr(q.scoreLevels ?? q.levels).map(String), statement: q.statement == null ? undefined : str(q.statement), answer,
      escape: escapeKey ? String(a.value) === escapeKey : ['unclear', 'none_of_these', 'not_enough_information', 'other', 'escalate', 'unclassified'].includes(String(a.value)) };
  });
  const defRules = arr(def.rules).map(obj);
  const fired = new Set(arr(t.rulesFired).map((r) => (typeof r === 'string' ? r : str(obj(r).id))));
  const rules: TraceRule[] = defRules.length
    ? defRules.map((r) => ({ id: str(r.id), when: str(r.when), then: str(r.then), reason: r.reason == null ? undefined : str(r.reason), matched: fired.has(str(r.id)) }))
    : [...fired].map((id) => ({ id, when: '', then: '', matched: true }));
  const checks: TraceCheck[] = [
    ...arr(t.preChecks).map((c) => ({ expression: str(obj(c).expression ?? c), passed: obj(c).passed !== false, phase: 'pre' as const, message: obj(c).error == null ? undefined : str(obj(c).error) })),
    ...arr(t.postChecks).map((c) => ({ expression: str(obj(c).expression ?? c), passed: obj(c).passed !== false, phase: 'post' as const, message: obj(c).error == null ? undefined : str(obj(c).error) })),
  ];
  const l2 = obj(details.l2);
  const layers: TraceLayerStep[] = [
    { layer: 'L0_RULE', ran: true, outcome: fired.size ? `rule fired: ${[...fired].join(', ')}` : 'no rule matched' },
    { layer: 'L1_SYSTEM_ONE', ran: layer !== 'L0_RULE' && !!t.provider && str(details.defaultReason) !== 'kill_switch', outcome: t.provider ? `${str(t.provider)} · confidence ${num(t.confidence).toFixed(2)} · ${band.toLowerCase()} band` : undefined, latencyMs: num(t.latencyMs) },
    { layer: 'L2_LLM', ran: l2.attempted === true, outcome: l2.attempted === true ? (l2.accepted ? `accepted ${str(l2.value)}` : `not accepted (${str(l2.reason ?? l2.provider)})`) : undefined },
    { layer: 'L3_HUMAN', ran: !!escalation || layer === 'L3_HUMAN', outcome: escalation ? `escalated (${str(escalation.status, 'open')})` : undefined },
    ...(layer === 'DEFAULT' ? [{ layer: 'DEFAULT' as Layer, ran: true, outcome: str(details.defaultReason, 'deterministic default') }] : []),
  ];
  const state = obj(t.state);
  const tokenised = Object.keys(flatten(state)).filter((k) => /_(em|nm|ph|ad)_|\b(em|nm|ph|ad)_[0-9a-f]{6,}/.test(String(flatten(state)[k])));
  const bands = obj(t.bands);
  return {
    record, definition: { name: str(def.name ?? t.decision), version: num(def.version ?? t.version), tier: str(t.tier ?? def.tier, 'T1') as DecisionTrace['definition']['tier'], owner: str(def.owner, '—'),
      purpose: def.purpose == null ? undefined : str(def.purpose), bands: { actGte: num(bands.actGte, 0.8), confirmGte: num(bands.confirmGte, 0.5) }, status: def.status == null ? undefined : (str(def.status) as DecisionTrace['definition']['status']) },
    provider: str(t.provider), modelVersion: t.modelVersion == null ? undefined : str(t.modelVersion), latencyMs: num(t.latencyMs), costUsd: num(t.costUsd),
    inputTokens: t.inputTokens == null ? undefined : num(t.inputTokens), layers, inputs: state, inputHash: t.inputHash == null ? undefined : str(t.inputHash), tokenisedFields: tokenised,
    questions, rules, checks, band, action: str(t.action), actor: str(t.actor, 'system'),
    escalation: escalation ? { id: str(escalation.id ?? t.escalationId), queue: escalation.queue == null ? undefined : str(escalation.queue), taskId: escalation.taskId == null ? undefined : str(escalation.taskId), status: escalation.status == null ? undefined : str(escalation.status) } : null,
    override: overrideValue != null ? { by: str(t.overriddenBy ?? details.overriddenBy, 'unknown'), at: str(t.overriddenAt ?? details.overriddenAt), value: overrideValue, reason: str(t.overrideReason ?? details.overrideReason) } : null,
    label: labelKeys.length ? { label: labelKeys[0], at: str(obj(labels[labelKeys[0]]).at), by: str(obj(labels[labelKeys[0]]).by, 'unknown') } : null,
    explanation: t.explanation == null ? undefined : str(t.explanation), customerSafeExplanation: t.customerSafeExplanation == null ? undefined : str(t.customerSafeExplanation), audience,
  };
}

function flatten(o: Raw, prefix = '', out: Record<string, unknown> = {}): Record<string, unknown> {
  for (const [k, v] of Object.entries(o)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v as Raw, key, out); else out[key] = v;
  }
  return out;
}
