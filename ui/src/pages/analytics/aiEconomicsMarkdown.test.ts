import { describe, expect, it } from 'vitest';
import type { AiEconomics } from '../../api/analytics';
import { aiEconomicsMarkdown, layerLabel } from './aiEconomicsMarkdown';

const base: AiEconomics = {
  range: { from: '2026-09-15', to: '2026-10-14', days: 30 }, totalDecisions: 0, actingDecisions: 0, automationRate: 0, humanDecisionsAvoided: 0,
  overrides: 0, overrideRate: 0, escalations: 0, escalationRate: 0, totalAiCostUsd: 0, costPer1kDecisionsUsd: 0, orders: 0, costPerOrderUsd: 0,
  minutesPerHumanDecision: 2, estimatedAnalystHoursSaved: 0,
  byLayer: ['L0_RULE', 'L1_SYSTEM_ONE', 'L2_LLM', 'L3_HUMAN', 'DEFAULT'].map((l) => ({ layer: l, count: 0, share: 0, latencyP50Ms: 0, latencyP95Ms: 0 })),
  daily: [], decisions: [], assumptions: ['Only live decision records count.'],
};

describe('aiEconomicsMarkdown', () => {
  it('renders an explicit empty state with zero decisions', () => {
    const md = aiEconomicsMarkdown(base, 'demo');
    expect(md).toContain('## AI economics — demo · 2026-09-15 → 2026-10-14 (30 days)');
    expect(md).toContain('No live decisions were recorded in this range.');
    expect(md).toContain('### Assumptions');
    expect(md).not.toContain('| Metric |');
  });
  it('renders the metric, layer and per-decision tables when there is data', () => {
    const a: AiEconomics = { ...base, totalDecisions: 1200, actingDecisions: 1000, automationRate: 0.8333, humanDecisionsAvoided: 700, overrides: 20, overrideRate: 0.02, escalations: 60, escalationRate: 0.05,
      totalAiCostUsd: 1.5, costPer1kDecisionsUsd: 1.25, orders: 400, costPerOrderUsd: 0.00375, estimatedAnalystHoursSaved: 23.3,
      byLayer: [{ layer: 'L0_RULE', count: 300, share: 0.25, latencyP50Ms: 4, latencyP95Ms: 11 }, { layer: 'L1_SYSTEM_ONE', count: 900, share: 0.75, latencyP50Ms: 310, latencyP95Ms: 690 }, { layer: 'L2_LLM', count: 0, share: 0, latencyP50Ms: 0, latencyP95Ms: 0 }],
      decisions: [{ name: 'fulfilment_intent', tier: 'T1', volume: 900, automationRate: 0.9, avgConfidence: 0.87, costPer1kUsd: 0.02, latencyP95Ms: 690, overrideRate: 0.021, goldenAccuracy: 0.9 }] };
    const md = aiEconomicsMarkdown(a, 'demo');
    expect(md).toContain('| Automation rate | 83.3% (1,000 acted autonomously) |');
    expect(md).toContain('| Estimated analyst hours saved | 23.3 h (at 2 min per decision, estimate) |');
    expect(md).toContain('| L1 Jev | 900 | 75.0% | 310 | 690 |');
    expect(md).not.toContain('| L2 LLM |');
    expect(md).toContain('| fulfilment_intent | T1 | 900 | 90.0% | 0.87 | $0.0200 | 690 | 2.1% |');
  });
  it('labels layers for humans', () => {
    expect(layerLabel('L1_SYSTEM_ONE')).toBe('L1 Jev');
    expect(layerLabel('L3_HUMAN')).toBe('L3 human');
    expect(layerLabel('something_else')).toBe('something_else');
  });
});
