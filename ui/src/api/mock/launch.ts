/* Mock routes for the launch visibility features: AI economics, provider availability, tenants, actuator info. */
import type { AiEconomics, EconomicsDay } from '../analytics';
import { config, decisionSummaries, rnd, round } from './data';

type Ctx = { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> };
type On = (method: string, path: string, handler: (ctx: Ctx) => unknown) => void;

const LAYERS = ['L0_RULE', 'L1_SYSTEM_ONE', 'L2_LLM', 'L3_HUMAN', 'DEFAULT'] as const;
const SHARE: Record<string, number> = { L0_RULE: 0.26, L1_SYSTEM_ONE: 0.62, L2_LLM: 0.05, L3_HUMAN: 0.06, DEFAULT: 0.01 };
const LATENCY: Record<string, [number, number]> = { L0_RULE: [4, 12], L1_SYSTEM_ONE: [280, 720], L2_LLM: [1900, 4200], L3_HUMAN: [0, 0], DEFAULT: [2, 6] };
const day = (d: Date) => d.toISOString().slice(0, 10);

export function registerLaunchRoutes({ on }: { on: On }) {
  on('GET', '/api/v1/analytics/ai-economics', ({ query }): AiEconomics => {
    const to = query.get('to') ? new Date(query.get('to') + 'T00:00:00Z') : new Date(new Date().toISOString().slice(0, 10) + 'T00:00:00Z');
    const from = query.get('from') ? new Date(query.get('from') + 'T00:00:00Z') : new Date(to.getTime() - 29 * 86_400_000);
    const days = Math.max(1, Math.round((to.getTime() - from.getTime()) / 86_400_000) + 1);
    const empty = query.get('from') !== null && from.getTime() > Date.now();   // a future range renders the empty state
    const daily: EconomicsDay[] = [];
    const layerCounts: Record<string, number> = Object.fromEntries(LAYERS.map((l) => [l, 0]));
    for (let i = 0; i < days; i++) {
      const d = new Date(from.getTime() + i * 86_400_000);
      const total = empty ? 0 : Math.round(380 + rnd() * 260 + (d.getUTCDay() === 0 || d.getUTCDay() === 6 ? -150 : 0));
      const byLayer: Record<string, number> = {}; let acc = 0;
      LAYERS.forEach((l, idx) => { const n = idx === LAYERS.length - 1 ? Math.max(0, total - acc) : Math.round(total * SHARE[l] * (0.85 + rnd() * 0.3)); byLayer[l] = n; acc += n; layerCounts[l] += n; });
      daily.push({ date: day(d), byLayer, total: Object.values(byLayer).reduce((a, b) => a + b, 0) });
    }
    const total = daily.reduce((a, d) => a + d.total, 0);
    const acting = Math.round(total * 0.83); const escalations = layerCounts.L3_HUMAN; const overrides = Math.round(acting * 0.021);
    const cost = round(layerCounts.L1_SYSTEM_ONE * 0.00002 + layerCounts.L2_LLM * 0.011, 4);
    const orders = Math.round(total / 3.4);
    const avoided = Math.round(acting * 0.71);
    const minutes = Number(config['analytics.minutes_per_human_decision'] ?? 2) || 2;
    const sums = decisionSummaries();
    return {
      range: { from: day(from), to: day(to), days },
      totalDecisions: total, actingDecisions: acting, automationRate: total ? round(acting / total, 4) : 0, humanDecisionsAvoided: avoided,
      overrides, overrideRate: acting ? round(overrides / acting, 4) : 0, escalations, escalationRate: total ? round(escalations / total, 4) : 0,
      totalAiCostUsd: cost, costPer1kDecisionsUsd: total ? round(cost / total * 1000, 4) : 0, orders, costPerOrderUsd: orders ? round(cost / orders, 5) : 0,
      minutesPerHumanDecision: minutes, estimatedAnalystHoursSaved: round(avoided * minutes / 60, 1),
      byLayer: LAYERS.map((l) => { const [lo, hi] = LATENCY[l]; const p50 = lo + (hi - lo) * 0.35; return { layer: l, count: layerCounts[l], share: total ? round(layerCounts[l] / total, 4) : 0, latencyP50Ms: layerCounts[l] ? Math.round(p50) : 0, latencyP95Ms: layerCounts[l] ? Math.round(hi) : 0 }; }),
      daily,
      decisions: empty ? [] : sums.slice(0, 10).map((s, i) => { const volume = Math.max(0, Math.round(total * [0.34, 0.22, 0.14, 0.1, 0.07, 0.05, 0.04, 0.02, 0.01, 0.01][i])); const auto = s.tier === 'T3' ? 0 : s.tier === 'T0' ? 0.98 : round(0.72 + rnd() * 0.2, 3); return { name: s.name, tier: s.tier, volume, automationRate: auto, avgConfidence: round(0.78 + rnd() * 0.15, 3), costPer1kUsd: round(0.015 + rnd() * 0.04, 4), latencyP95Ms: Math.round(420 + rnd() * 300), overrideRate: round((s.overrideRate ?? rnd() * 0.04), 4), goldenAccuracy: i % 3 === 0 ? round(0.86 + rnd() * 0.1, 3) : null }; }),
      assumptions: [
        'Only live decision records count (shadow, simulation and replay runs are excluded).',
        'Automation rate = decisions that acted autonomously (band ACT) / all decisions.',
        'Human decisions avoided = acting decisions on T1/T2 decisions; T0 is informational and T3 never acts autonomously.',
        'Override rate = human overrides / acting decisions; escalation rate = decisions that required a person / all decisions.',
        'AI spend is the provider cost recorded on each decision record; cost per order divides it by orders placed in the range.',
        `Estimated analyst hours saved = human decisions avoided × ${minutes} min (tenant config analytics.minutes_per_human_decision). It is an estimate, not a measurement.`,
      ],
    };
  });
  on('GET', '/api/v1/decisions/providers', () => { const off = !!config['ai.kill_switch']; return { heuristic: true, jev: !off && !config['provider.kill_switch.jev'], 'llm.anthropic': !off && !config['provider.kill_switch.llm.anthropic'], 'llm.openai': false }; });
  on('GET', '/api/v1/admin/tenants', () => [{ tenant_id: 'demo', name: 'Demo Retail', status: 'active', created_at: '2026-10-01T09:00:00Z' }]);
  on('GET', '/actuator/info', () => ({ app: { name: 'intent-oms', version: '0.1.0-mock' }, git: { commit: { id: 'mock0000' } } }));
}
