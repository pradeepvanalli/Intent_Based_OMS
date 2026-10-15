import type { BarDatum } from '../../components/charts/BarChart';
import type { ReplayRegion, ReplayResults, ReplayRow, ReplaySummary } from '../../api/replay';

/* Pure helpers for the Margin Replay report (unit-tested in report.test.ts). */

export type RowSortKey = 'orderId' | 'placedAt' | 'state' | 'saved' | 'costBefore' | 'costAfter' | 'packages' | 'intent' | 'onTime';
export interface RowSort { key: RowSortKey; dir: 'asc' | 'desc'; }
export interface RowFilter { changedOnly?: boolean; intentOnly?: boolean; belowFloorOnly?: boolean; includeSkipped?: boolean; query?: string; }

export const savedUsd = (r: ReplayRow): number => (r.marginDeltaUsd ?? ((r.marginAfterUsd ?? 0) - (r.marginBeforeUsd ?? 0)));

export function filterRows(rows: ReplayRow[], f: RowFilter = {}): ReplayRow[] {
  const q = (f.query ?? '').trim().toLowerCase();
  return rows.filter((r) => {
    if (r.skipped && !f.includeSkipped) return false;
    if (f.changedOnly && !r.changed) return false;
    if (f.intentOnly && (!r.intent || r.intent === 'none')) return false;
    if (f.belowFloorOnly && !r.belowFloor) return false;
    if (q) {
      const hay = [r.orderId, r.city, r.state, r.region, r.intent, r.policy, ...(r.nodesBefore ?? []), ...(r.nodesAfter ?? []), r.error].filter(Boolean).join(' ').toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });
}

function sortValue(r: ReplayRow, key: RowSortKey): number | string {
  switch (key) {
    case 'saved': return savedUsd(r);
    case 'costBefore': return r.costBefore ?? 0;
    case 'costAfter': return r.costAfter ?? 0;
    case 'packages': return (r.packagesAfter ?? 0) - (r.packagesBefore ?? 0);
    case 'onTime': return (r.onTimeAfter ?? 0) - (r.onTimeBefore ?? 0);
    case 'intent': return r.intent ?? '';
    case 'state': return `${r.state ?? ''}|${r.city ?? ''}`;
    case 'placedAt': return r.placedAt ?? '';
    default: return r.orderId;
  }
}

export function sortRows(rows: ReplayRow[], sort: RowSort): ReplayRow[] {
  const dir = sort.dir === 'asc' ? 1 : -1;
  return rows.slice().sort((a, b) => {
    const va = sortValue(a, sort.key); const vb = sortValue(b, sort.key);
    const c = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va).localeCompare(String(vb));
    return (c || a.orderId.localeCompare(b.orderId)) * dir;
  });
}

/** Top states by margin recovered; negative states are kept (shown as 0-height) only when nothing is positive. */
export function regionBars(regions: ReplayRegion[], limit = 10): BarDatum[] {
  const sorted = regions.slice().sort((a, b) => b.saved - a.saved || a.state.localeCompare(b.state));
  const positive = sorted.filter((r) => r.saved > 0);
  const picked = (positive.length ? positive : sorted).slice(0, limit);
  return picked.map((r) => ({ label: r.state, value: Math.max(0, Math.round(r.saved * 100) / 100), color: r.saved > 0 ? 'var(--c1)' : 'var(--neutral)' }));
}

export const fmtUsd0 = (n?: number | null): string => (n === undefined || n === null || Number.isNaN(n) ? '—' : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: Math.abs(n) >= 1000 ? 0 : 2 }).format(n));
export const fmtSigned = (n: number, fmt: (v: number) => string = fmtUsd0): string => (n > 0 ? `+${fmt(n)}` : n < 0 ? `−${fmt(Math.abs(n))}` : fmt(0));
export const fmtPct1 = (n?: number | null): string => (n === undefined || n === null || Number.isNaN(n) ? '—' : `${(n * 100).toFixed(1)}%`);

export interface Headline { key: string; label: string; value: string; hint: string; tone?: 'ok' | 'warn' | 'danger'; }

/** The six headline tiles. */
export function headline(s: ReplaySummary): Headline[] {
  const intents = Object.values(s.intentsDetected ?? {}).reduce((a, b) => a + b, 0);
  const intentParts = Object.entries(s.intentsDetected ?? {}).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${v} ${k}`).join(', ');
  return [
    { key: 'shipping', label: 'Shipping saved', value: fmtUsd0(s.shippingSaved), hint: `${fmtUsd0(s.baselineShippingCost)} → ${fmtUsd0(s.intentShippingCost)} carrier cost`, tone: s.shippingSaved > 0 ? 'ok' : s.shippingSaved < 0 ? 'warn' : undefined },
    { key: 'margin', label: 'Margin recovered', value: fmtUsd0(s.marginRecoveredUsd), hint: `${fmtPct1(s.baselineMarginRatio)} → ${fmtPct1(s.intentMarginRatio)} · ${fmtUsd0(s.marginRecoveredPerOrder)} per order`, tone: s.marginRecoveredUsd > 0 ? 'ok' : s.marginRecoveredUsd < 0 ? 'danger' : undefined },
    { key: 'splits', label: 'Splits avoided', value: String(s.splitsAvoided), hint: `${s.baselineSplits} → ${s.intentSplits} split orders${s.splitsAdded ? ` · ${s.splitsAdded} added for urgency` : ''}` },
    { key: 'floor', label: 'Flagged below margin floor', value: String(s.ordersBelowFloorFlagged), hint: `${fmtUsd0(s.belowFloorMarginUsd)} of margin shipped blind by the baseline`, tone: s.ordersBelowFloorFlagged > 0 ? 'warn' : undefined },
    { key: 'intents', label: 'Intents detected', value: String(s.ordersWithIntent ?? intents), hint: intentParts ? `${intentParts} (of ${s.ordersWithNotes} orders with notes)` : `${s.ordersWithNotes} orders with notes` },
    { key: 'annual', label: 'Est. annualised savings', value: fmtUsd0(s.estimatedAnnualisedSavings), hint: `estimate · ${s.annualisationDays} day window × 365`, tone: s.estimatedAnnualisedSavings > 0 ? 'ok' : undefined },
  ];
}

/** Markdown for the clipboard: the server's rendering when present, else a compact client rendering. */
export function toMarkdown(r: ReplayResults): string {
  if (r.markdownSummary) return r.markdownSummary;
  const s = r.summary;
  const lines = [`# Margin Replay — ${r.file?.name ?? 'orders'}`, '', `Orders replayed: ${s.planned} of ${s.orders} (${s.skipped} skipped)`, '', '| Metric | Baseline | Intent OMS | Delta |', '|---|---:|---:|---:|',
    `| Shipping cost | ${fmtUsd0(s.baselineShippingCost)} | ${fmtUsd0(s.intentShippingCost)} | ${fmtUsd0(s.shippingSaved)} saved |`,
    `| Contribution margin | ${fmtUsd0(s.baselineMarginUsd)} | ${fmtUsd0(s.intentMarginUsd)} | ${fmtUsd0(s.marginRecoveredUsd)} recovered |`,
    `| Split shipments | ${s.baselineSplits} | ${s.intentSplits} | ${s.splitsAvoided} avoided |`,
    `| Orders below margin floor | ${s.baselineOrdersBelowFloor} | ${s.ordersBelowFloorFlagged} flagged | |`,
    `| Est. annualised savings | | | ${fmtUsd0(s.estimatedAnnualisedSavings)} (estimate) |`, '', '## Findings', ...r.topFindings.map((f) => `- ${f}`), '', '## Assumptions', ...r.assumptions.map((a) => `- ${a}`), ''];
  return lines.join('\n');
}

export function intentLabel(intent?: string | null): string {
  switch (intent) {
    case 'together': return 'ship together';
    case 'urgent': return 'urgent item';
    case 'gift': return 'gift';
    case 'pickup': return 'store pickup';
    case 'separate': return 'ship as ready';
    default: return '—';
  }
}

export function paginate<T>(rows: T[], offset: number, limit: number): T[] { return rows.slice(offset, offset + limit); }
