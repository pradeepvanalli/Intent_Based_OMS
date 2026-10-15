import { api } from './client';
import type { DecisionAnalytics, KpiDashboard } from './types';

/* ---- AI economics (GET /analytics/ai-economics) ---- */
export type EconomicsLayer = 'L0_RULE' | 'L1_SYSTEM_ONE' | 'L2_LLM' | 'L3_HUMAN' | 'DEFAULT';
export interface EconomicsLayerStats { layer: EconomicsLayer | string; count: number; share: number; latencyP50Ms: number; latencyP95Ms: number; }
export interface EconomicsDay { date: string; byLayer: Record<string, number>; total: number; }
export interface EconomicsDecisionRow { name: string; tier: string | null; volume: number; automationRate: number; avgConfidence: number; costPer1kUsd: number; latencyP95Ms: number; overrideRate: number; goldenAccuracy?: number | null; }
export interface AiEconomics {
  range: { from: string; to: string; days: number };
  totalDecisions: number; actingDecisions: number; automationRate: number; humanDecisionsAvoided: number;
  overrides: number; overrideRate: number; escalations: number; escalationRate: number;
  totalAiCostUsd: number; costPer1kDecisionsUsd: number; orders: number; costPerOrderUsd: number;
  minutesPerHumanDecision: number; estimatedAnalystHoursSaved: number;
  byLayer: EconomicsLayerStats[]; daily: EconomicsDay[]; decisions: EconomicsDecisionRow[]; assumptions: string[];
}

export const analyticsApi = {
  kpis: (range: { from?: string; to?: string } = {}) => api.get<KpiDashboard>('/api/v1/analytics/kpis', { query: range, silent: true }),
  decision: (name: string) => api.get<DecisionAnalytics>(`/api/v1/analytics/decisions/${encodeURIComponent(name)}`),
  aiEconomics: (range: { from?: string; to?: string } = {}) => api.get<AiEconomics>('/api/v1/analytics/ai-economics', { query: range, silent: true }),
};
