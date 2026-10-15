import { api, ApiError, API_BASE, MOCK } from './client';
import { getSession } from '../auth/session';
import type { ProblemDetail, SimulationJob } from './types';

/* ---- Margin Replay (oms-simulation, POST /api/v1/simulations/import) ---- */
export type ReplayFormat = 'auto' | 'shopify' | 'generic';
export interface ReplayOptions { format: ReplayFormat; assumeStock: boolean; unknownSkuCogsRatio: number; baseline: string; useDecisions: boolean; }
export const DEFAULT_REPLAY_OPTIONS: ReplayOptions = { format: 'auto', assumeStock: true, unknownSkuCogsRatio: 0.6, baseline: 'nearest_node', useDecisions: true };

export interface ReplaySummary {
  orders: number; parsed: number; planned: number; skipped: number; skippedReasons: Record<string, number>; ordersChanged: number; nodesShifted: number; storeToDc: number; revenue: number; units: number;
  baselineShippingCost: number; intentShippingCost: number; shippingSaved: number; baselineFulfilmentCost: number; intentFulfilmentCost: number; fulfilmentSaved: number;
  baselineMarginUsd: number; intentMarginUsd: number; marginRecoveredUsd: number; marginRecoveredPerOrder: number; baselineMarginRatio: number; intentMarginRatio: number;
  baselineSplits: number; intentSplits: number; splitsAvoided: number; splitsAdded: number; ordersBelowFloorFlagged: number; baselineOrdersBelowFloor: number; belowFloorMarginUsd: number;
  intentsDetected: Record<string, number>; intentsByBand: Record<string, number>; ordersWithIntent: number; ordersWithNotes: number; avgOnTimeBefore: number; avgOnTimeAfter: number;
  unknownSkuLines: number; ordersWithUnknownSku: number; poBoxOrders: number; hazmatOrders: number; pickupOrders: number; dateFrom?: string | null; dateTo?: string | null;
  annualisationDays: number; estimatedAnnualisedSavings: number; estimatedAnnualisedOrders: number; estimateLabel: string;
}
export interface ReplayRegion { state: string; region: string; orders: number; changed: number; baselineCost: number; intentCost: number; baselineShipping: number; intentShipping: number; shippingSaved: number; saved: number; baselineSplits: number; intentSplits: number; }
export interface ReplayRow {
  orderId: string; skipped?: boolean; error?: string; row?: number; placedAt?: string | null; city?: string | null; state?: string; region?: string; geoPrecision?: string; lines?: number; units?: number; revenue?: number; shipMethod?: string;
  costBefore?: number; costAfter?: number; shippingBefore?: number; shippingAfter?: number; packagesBefore?: number; packagesAfter?: number; nodesBefore?: string[]; nodesAfter?: string[];
  onTimeBefore?: number; onTimeAfter?: number; marginBefore?: number; marginAfter?: number; marginBeforeUsd?: number; marginAfterUsd?: number; marginDeltaUsd?: number;
  flippedDecisions?: string[]; blockedTransitions?: number; policy?: string; intent?: string; intentPolicy?: string | null; intentSource?: string; intentBand?: string | null; intentRecordId?: string | null;
  isGift?: boolean; urgent?: boolean; splitOk?: boolean | null; belowFloor?: boolean; baselineBelowFloor?: boolean; poBox?: boolean; hazmat?: boolean; unknownSkus?: number; changed?: boolean; reason?: string; baselineReason?: string; note?: string;
}
export interface ReplayResults {
  kind: 'import'; options: ReplayOptions; file: { name: string; bytes: number; format: string | null; rows: number; orders: number; warnings: string[] };
  summary: ReplaySummary; topFindings: string[]; byRegion: ReplayRegion[]; assumptions: string[]; perOrder: ReplayRow[]; perOrderTruncated?: boolean; markdownSummary: string; processed: number; partial: boolean; durationMs: number;
}
export interface ReplayJob extends Omit<SimulationJob, 'results'> { kind?: string; label?: string | null; results?: ReplayResults | null; }

/** Multipart upload (the shared client only speaks JSON). Same auth headers, same problem-detail handling. */
async function upload<T>(path: string, form: FormData): Promise<T> {
  const { token, principal } = getSession();
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (principal?.tenantId) headers['X-Tenant-Id'] = principal.tenantId;
  let res: Response;
  try { res = await fetch(`${API_BASE}${path}`, { method: 'POST', headers, body: form }); } catch (e) {
    throw new ApiError({ status: 0, title: 'network_error', detail: e instanceof Error ? e.message : 'Network error' });
  }
  if (!res.ok) {
    let problem: ProblemDetail = { status: res.status, title: res.statusText || `HTTP ${res.status}` };
    try { if ((res.headers.get('content-type') ?? '').includes('json')) problem = { ...problem, ...(await res.json()) } as ProblemDetail; } catch { /* unparseable */ }
    throw new ApiError(problem);
  }
  return (await res.json()) as T;
}

export const replayApi = {
  upload: async (file: File, opts: ReplayOptions): Promise<ReplayJob> => {
    if (MOCK) return api.post<ReplayJob>('/api/v1/simulations/import', { fileName: file.name, size: file.size, options: opts, text: await file.text() });
    const fd = new FormData();
    fd.append('file', file, file.name);
    fd.append('options', JSON.stringify(opts));
    return upload<ReplayJob>('/api/v1/simulations/import', fd);
  },
  sample: (opts: ReplayOptions) => api.post<ReplayJob>('/api/v1/simulations/import/sample', opts),
  get: (id: string) => api.get<ReplayJob>(`/api/v1/simulations/${encodeURIComponent(id)}`, { silent: true }),
  list: async (): Promise<ReplayJob[]> => (await api.get<ReplayJob[]>('/api/v1/simulations', { silent: true })).filter((j) => j.kind === 'import'),
};
