import { describe, expect, it } from 'vitest';
import { filterRows, fmtSigned, fmtUsd0, headline, intentLabel, paginate, regionBars, savedUsd, sortRows, toMarkdown } from './report';
import type { ReplayRegion, ReplayResults, ReplayRow, ReplaySummary } from '../../api/replay';

const rows: ReplayRow[] = [
  { orderId: '#1001', state: 'CA', city: 'Los Angeles', region: 'California', costBefore: 12, costAfter: 9.5, marginBeforeUsd: 20, marginAfterUsd: 22.5, marginDeltaUsd: 2.5, packagesBefore: 1, packagesAfter: 1, nodesBefore: ['ST-LA-02'], nodesAfter: ['DC-NV'], onTimeBefore: 0.9, onTimeAfter: 0.93, intent: 'none', changed: true, placedAt: '2025-09-02T10:00:00Z' },
  { orderId: '#1002', state: 'NY', city: 'New York', region: 'Northeast', costBefore: 8, costAfter: 8, marginBeforeUsd: 30, marginAfterUsd: 30, marginDeltaUsd: 0, packagesBefore: 1, packagesAfter: 1, nodesBefore: ['DC-NJ'], nodesAfter: ['DC-NJ'], onTimeBefore: 0.95, onTimeAfter: 0.95, intent: 'gift', changed: false, placedAt: '2025-09-01T10:00:00Z' },
  { orderId: '#1003', state: 'TX', city: 'Austin', region: 'Texas & South Central', costBefore: 15, costAfter: 11, marginBeforeUsd: -2, marginAfterUsd: 2, marginDeltaUsd: 4, packagesBefore: 2, packagesAfter: 1, nodesBefore: ['ST-AUS-03', 'DC-TX'], nodesAfter: ['DC-TX'], onTimeBefore: 0.8, onTimeAfter: 0.94, intent: 'together', belowFloor: true, changed: true, placedAt: '2025-09-03T10:00:00Z' },
  { orderId: '#1004', skipped: true, error: 'non-US destination (CA)' },
];
const summary: ReplaySummary = {
  orders: 4, parsed: 4, planned: 3, skipped: 1, skippedReasons: { 'non-US destination (CA)': 1 }, ordersChanged: 2, nodesShifted: 2, storeToDc: 2, revenue: 500, units: 6,
  baselineShippingCost: 25, intentShippingCost: 19.5, shippingSaved: 5.5, baselineFulfilmentCost: 35, intentFulfilmentCost: 28.5, fulfilmentSaved: 6.5, baselineMarginUsd: 48, intentMarginUsd: 54.5, marginRecoveredUsd: 6.5, marginRecoveredPerOrder: 2.17,
  baselineMarginRatio: 0.096, intentMarginRatio: 0.109, baselineSplits: 1, intentSplits: 0, splitsAvoided: 1, splitsAdded: 0, ordersBelowFloorFlagged: 1, baselineOrdersBelowFloor: 1, belowFloorMarginUsd: -2,
  intentsDetected: { gift: 1, together: 1 }, intentsByBand: { CONFIRM: 2 }, ordersWithIntent: 2, ordersWithNotes: 2, avgOnTimeBefore: 0.883, avgOnTimeAfter: 0.94, unknownSkuLines: 1, ordersWithUnknownSku: 1, poBoxOrders: 0, hazmatOrders: 0, pickupOrders: 0,
  dateFrom: '2025-09-01T10:00:00Z', dateTo: '2025-09-03T10:00:00Z', annualisationDays: 3, estimatedAnnualisedSavings: 790.83, estimatedAnnualisedOrders: 365, estimateLabel: 'Estimate',
};
const regions: ReplayRegion[] = [
  { state: 'CA', region: 'California', orders: 1, changed: 1, baselineCost: 12, intentCost: 9.5, baselineShipping: 7, intentShipping: 6, shippingSaved: 1, saved: 2.5, baselineSplits: 0, intentSplits: 0 },
  { state: 'NY', region: 'Northeast', orders: 1, changed: 0, baselineCost: 8, intentCost: 8, baselineShipping: 6, intentShipping: 6, shippingSaved: 0, saved: 0, baselineSplits: 0, intentSplits: 0 },
  { state: 'TX', region: 'Texas & South Central', orders: 1, changed: 1, baselineCost: 15, intentCost: 11, baselineShipping: 12, intentShipping: 7.5, shippingSaved: 4.5, saved: 4, baselineSplits: 1, intentSplits: 0 },
];

describe('Margin Replay report helpers', () => {
  it('filters skipped, changed-only, intent-only, below-floor and free text', () => {
    expect(filterRows(rows).map((r) => r.orderId)).toEqual(['#1001', '#1002', '#1003']);
    expect(filterRows(rows, { includeSkipped: true })).toHaveLength(4);
    expect(filterRows(rows, { changedOnly: true }).map((r) => r.orderId)).toEqual(['#1001', '#1003']);
    expect(filterRows(rows, { intentOnly: true }).map((r) => r.orderId)).toEqual(['#1002', '#1003']);
    expect(filterRows(rows, { belowFloorOnly: true }).map((r) => r.orderId)).toEqual(['#1003']);
    expect(filterRows(rows, { query: 'dc-nv' }).map((r) => r.orderId)).toEqual(['#1001']);
    expect(filterRows(rows, { query: 'austin', changedOnly: true }).map((r) => r.orderId)).toEqual(['#1003']);
    expect(filterRows(rows, { query: 'nowhere' })).toEqual([]);
  });

  it('sorts by saved, cost, packages, state and date in both directions', () => {
    const planned = filterRows(rows);
    expect(sortRows(planned, { key: 'saved', dir: 'desc' }).map((r) => r.orderId)).toEqual(['#1003', '#1001', '#1002']);
    expect(sortRows(planned, { key: 'saved', dir: 'asc' }).map((r) => r.orderId)).toEqual(['#1002', '#1001', '#1003']);
    expect(sortRows(planned, { key: 'costBefore', dir: 'desc' })[0].orderId).toBe('#1003');
    expect(sortRows(planned, { key: 'packages', dir: 'asc' })[0].orderId).toBe('#1003');
    expect(sortRows(planned, { key: 'state', dir: 'asc' }).map((r) => r.state)).toEqual(['CA', 'NY', 'TX']);
    expect(sortRows(planned, { key: 'placedAt', dir: 'asc' })[0].orderId).toBe('#1002');
    expect(sortRows(planned, { key: 'orderId', dir: 'desc' })[0].orderId).toBe('#1003');
    expect(savedUsd({ orderId: 'x', marginBeforeUsd: 1, marginAfterUsd: 3 })).toBe(2);
  });

  it('builds region bars from the top saving states only', () => {
    const bars = regionBars(regions, 10);
    expect(bars.map((b) => b.label)).toEqual(['TX', 'CA']);
    expect(bars[0].value).toBe(4);
    expect(regionBars(regions, 1)).toHaveLength(1);
    const negative = regionBars([{ ...regions[1], saved: -3 }, { ...regions[0], saved: -1 }]);
    expect(negative.map((b) => b.label)).toEqual(['CA', 'NY']);
    expect(negative.every((b) => b.value === 0)).toBe(true);
  });

  it('formats headline tiles and money', () => {
    const tiles = headline(summary);
    expect(tiles.map((t) => t.key)).toEqual(['shipping', 'margin', 'splits', 'floor', 'intents', 'annual']);
    expect(tiles[0].value).toBe('$5.50');
    expect(tiles[0].tone).toBe('ok');
    expect(tiles[1].hint).toContain('9.6% → 10.9%');
    expect(tiles[2].value).toBe('1');
    expect(tiles[3].tone).toBe('warn');
    expect(tiles[4].value).toBe('2');
    expect(tiles[4].hint).toContain('1 gift');
    expect(tiles[5].value).toBe('$790.83');
    expect(fmtUsd0(1234.5)).toBe('$1,235');
    expect(fmtUsd0(null)).toBe('—');
    expect(fmtSigned(2.5)).toBe('+$2.50');
    expect(fmtSigned(-2.5)).toBe('−$2.50');
    expect(fmtSigned(0)).toBe('$0.00');
    expect(intentLabel('together')).toBe('ship together');
    expect(intentLabel('none')).toBe('—');
    expect(paginate([1, 2, 3, 4, 5], 2, 2)).toEqual([3, 4]);
  });

  it('prefers the server markdown and falls back to a client rendering', () => {
    const base: ReplayResults = { kind: 'import', options: { format: 'auto', assumeStock: true, unknownSkuCogsRatio: 0.6, baseline: 'nearest_node', useDecisions: true }, file: { name: 'orders.csv', bytes: 10, format: 'shopify', rows: 5, orders: 4, warnings: [] },
      summary, topFindings: ['Finding one.'], byRegion: regions, assumptions: ['Assumption A'], perOrder: rows, markdownSummary: '', processed: 4, partial: false, durationMs: 10 };
    const md = toMarkdown(base);
    expect(md).toContain('# Margin Replay — orders.csv');
    expect(md).toContain('| Shipping cost | $25.00 | $19.50 | $5.50 saved |');
    expect(md).toContain('- Finding one.');
    expect(md).toContain('- Assumption A');
    expect(toMarkdown({ ...base, markdownSummary: '# server' })).toBe('# server');
  });
});
