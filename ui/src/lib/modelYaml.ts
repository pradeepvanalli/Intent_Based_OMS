/* YAML <-> typed model conversion for decision definitions (PRD Appendix B) and state models (Appendix A). */
import yaml from 'js-yaml';
import type { DecisionDefinition, QuestionDef, StateModel, Tier } from '../api/types';

export const TIER_DEFAULT_BANDS: Record<Tier, { actGte: number; confirmGte: number }> = { T0: { actGte: 0.5, confirmGte: 0 }, T1: { actGte: 0.8, confirmGte: 0.5 }, T2: { actGte: 0.9, confirmGte: 0.6 }, T3: { actGte: 1.01, confirmGte: 0.9 } };

interface YamlDecision { decision: string; version: number; owner: string; tier: Tier; provider: string; fallbackProvider?: string; timeoutMs: number; default?: unknown; purpose?: string; state?: { include?: string[] }; questions?: Record<string, { type: string; instructions?: string; criteria?: Record<string, string>; levels?: string[]; statement?: string; reasonCode?: boolean; feeds?: string }>; rules?: { id: string; when: string; then: string; reason?: string; priority: number }[]; bands?: { act?: { confidence_gte: number }; confirm?: { confidence_gte: number } }; preChecks?: string[]; postChecks?: string[]; composite?: { weights: Record<string, number>; outputKey: string }; topK?: { scoreQuestion: string; k: number; candidatesField: string }; evaluation?: { goldenSet: string; minAccuracyInActBand: number; maxCalibrationError: number }; cache?: { ttl: string }; }

export type DecisionForm = Pick<DecisionDefinition, 'name' | 'version' | 'owner' | 'tier' | 'provider' | 'fallbackProvider' | 'timeoutMs' | 'dflt' | 'purpose' | 'stateInclude' | 'questions' | 'bands' | 'preChecks' | 'postChecks' | 'rules' | 'composite' | 'topK' | 'evaluation'> & { questionOrder: string[] };

export function parseDecisionYaml(text: string): Partial<DecisionDefinition> & { yaml: string } {
  const y = yaml.load(text) as YamlDecision | null;
  if (!y || typeof y !== 'object') throw new Error('YAML document is empty or not a mapping');
  if (!y.decision) throw new Error("Missing top-level 'decision' name");
  const questions: Record<string, QuestionDef> = {};
  for (const [key, q] of Object.entries(y.questions ?? {})) {
    questions[key] = { key, type: q.type as QuestionDef['type'], instructions: q.instructions, choiceCriteria: q.criteria, scoreLevels: q.levels, statement: q.statement, reasonCode: q.reasonCode, feeds: q.feeds };
  }
  const tier: Tier = (y.tier ?? 'T1') as Tier;
  const d = TIER_DEFAULT_BANDS[tier] ?? TIER_DEFAULT_BANDS.T1;
  return {
    name: y.decision, version: Number(y.version ?? 1), owner: y.owner ?? '', tier, provider: y.provider ?? 'jev', fallbackProvider: y.fallbackProvider, timeoutMs: Number(y.timeoutMs ?? 800), dflt: y.default, purpose: y.purpose,
    stateInclude: y.state?.include ?? [], questions, rules: y.rules ?? [], preChecks: y.preChecks ?? [], postChecks: y.postChecks ?? [], composite: y.composite ?? null, topK: y.topK ?? null, evaluation: y.evaluation ?? null,
    bands: { actGte: y.bands?.act?.confidence_gte ?? d.actGte, confirmGte: y.bands?.confirm?.confidence_gte ?? d.confirmGte }, yaml: text,
  };
}

export function toForm(def: Partial<DecisionDefinition>): DecisionForm {
  return { name: def.name ?? '', version: def.version ?? 1, owner: def.owner ?? '', tier: def.tier ?? 'T1', provider: def.provider ?? 'jev', fallbackProvider: def.fallbackProvider, timeoutMs: def.timeoutMs ?? 800, dflt: def.dflt, purpose: def.purpose, stateInclude: [...(def.stateInclude ?? [])], questions: structuredClone(def.questions ?? {}), questionOrder: Object.keys(def.questions ?? {}), bands: { ...(def.bands ?? TIER_DEFAULT_BANDS[def.tier ?? 'T1']) }, preChecks: [...(def.preChecks ?? [])], postChecks: [...(def.postChecks ?? [])], rules: structuredClone(def.rules ?? []), composite: def.composite ? structuredClone(def.composite) : null, topK: def.topK ? structuredClone(def.topK) : null, evaluation: def.evaluation ? structuredClone(def.evaluation) : null };
}

export function decisionToYaml(f: DecisionForm): string {
  const questions: Record<string, unknown> = {};
  for (const key of f.questionOrder) {
    const q = f.questions[key]; if (!q) continue;
    const o: Record<string, unknown> = { type: q.type };
    if (q.instructions) o.instructions = q.instructions;
    if (q.type === 'choice') o.criteria = q.choiceCriteria ?? {};
    if (q.type === 'score') o.levels = q.scoreLevels ?? [];
    if (q.type === 'noul') o.statement = q.statement ?? '';
    if (q.reasonCode) o.reasonCode = true;
    if (q.feeds) o.feeds = q.feeds;
    questions[key] = o;
  }
  const doc: Record<string, unknown> = { decision: f.name, version: f.version, owner: f.owner, tier: f.tier, provider: f.provider };
  if (f.fallbackProvider) doc.fallbackProvider = f.fallbackProvider;
  doc.timeoutMs = f.timeoutMs;
  if (f.dflt !== undefined && f.dflt !== null && f.dflt !== '') doc.default = f.dflt;
  if (f.purpose) doc.purpose = f.purpose;
  doc.state = { include: f.stateInclude };
  doc.questions = questions;
  if (f.rules && f.rules.length) doc.rules = f.rules;
  if (f.composite && Object.keys(f.composite.weights).length) doc.composite = f.composite;
  if (f.topK) doc.topK = f.topK;
  const bands: Record<string, unknown> = { act: { confidence_gte: f.bands.actGte } };
  if (f.bands.confirmGte > 0) bands.confirm = { confidence_gte: f.bands.confirmGte };
  bands.escalate = { otherwise: true };
  doc.bands = bands;
  if (f.preChecks?.length) doc.preChecks = f.preChecks;
  if (f.postChecks?.length) doc.postChecks = f.postChecks;
  if (f.evaluation) doc.evaluation = f.evaluation;
  return yaml.dump(doc, { lineWidth: 120, noRefs: true, quotingType: '"', forceQuotes: false });
}

interface YamlStateModel { stateModel: string; version: number; entity: string; quantityLevel?: boolean; states: ({ id: string; meta?: string; sla?: { maxAge: string; onBreach: string }; parent?: string })[]; transitions: ({ from: string | string[]; to: string; trigger: string; guards?: string[]; actions?: string[]; permission?: string })[]; rollup?: { parentState: string; expression: string; priority: number }[]; }
export function parseStateModelYaml(text: string): StateModel {
  const y = yaml.load(text) as YamlStateModel | null;
  if (!y || typeof y !== 'object') throw new Error('YAML document is empty or not a mapping');
  if (!y.stateModel) throw new Error("Missing top-level 'stateModel' id");
  return {
    id: y.stateModel, version: Number(y.version ?? 1), entity: y.entity ?? 'order_line', quantityLevel: !!y.quantityLevel,
    states: (y.states ?? []).filter((s) => s && s.id).map((s) => ({ id: s.id, meta: ((s.meta ?? 'ACTIVE').toUpperCase() as StateModel['states'][number]['meta']), sla: s.sla ?? null, parent: s.parent ?? null })),
    transitions: (y.transitions ?? []).filter((t) => t && t.to && t.from).map((t) => { const from = Array.isArray(t.from) ? t.from : [t.from]; return { id: `${from.join('|')}->${t.to}#${t.trigger}`, from, to: t.to, trigger: t.trigger ?? '', guards: t.guards ?? [], actions: t.actions ?? [], permission: t.permission ?? null }; }),
    rollup: y.rollup ?? [], yaml: text,
  };
}
