import { useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { analyticsApi, type EconomicsDay } from '../../api/analytics';
import { useSession } from '../../auth/session';
import { StatTile } from '../../components/StatTile';
import { Button } from '../../components/Button';
import { Input } from '../../components/Form';
import { Table } from '../../components/Table';
import { TierBadge } from '../../components/Badge';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { IconSparkle } from '../../components/Icons';
import { StackedBar } from '../../components/charts/BarChart';
import { fmtNum, fmtPct, fmtUsd } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { toast } from '../../lib/toast';
import { aiEconomicsMarkdown, LAYER_COLORS, layerLabel } from './aiEconomicsMarkdown';

const LAYERS = ['L0_RULE', 'L1_SYSTEM_ONE', 'L2_LLM', 'L3_HUMAN', 'DEFAULT'];

/** "AI Economics" tab: what the decision fabric automated, what it cost and what it saved (estimated), over a day range. */
export function AiEconomics() {
  const { principal } = useSession();
  const [from, setFrom] = useQueryState('from'); const [to, setTo] = useQueryState('to');
  const q = useQuery({ queryKey: ['ai-economics', from, to], queryFn: () => analyticsApi.aiEconomics({ from: from || undefined, to: to || undefined }) });
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    if (!q.data) return;
    const md = aiEconomicsMarkdown(q.data, principal?.tenantId);
    try { await navigator.clipboard.writeText(md); setCopied(true); setTimeout(() => setCopied(false), 1500); toast.ok('Copied', 'Markdown summary is on the clipboard'); }
    catch { toast.danger('Clipboard unavailable', 'Select the text in the Assumptions card instead.'); }
  };
  const a = q.data;
  const empty = !!a && a.totalDecisions === 0;
  return (
    <div className="col gap4">
      <div className="row gap3" style={{ alignItems: 'flex-start' }}>
        <span className="icon-badge ai" aria-hidden="true"><IconSparkle size={18} /></span>
        <p className="muted" style={{ margin: 0, fontSize: 'var(--fs-lg)', lineHeight: 1.5 }}>What the decision fabric automated on its own, what that cost, and the analyst time it saved — over any date range.</p>
      </div>
      <div className="row gap3 wrap" style={{ alignItems: 'flex-end' }}>
        <Input label="From" type="date" sm value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From date" />
        <Input label="To" type="date" sm value={to} onChange={(e) => setTo(e.target.value)} aria-label="To date" />
        {(from || to) && <Button size="sm" variant="ghost" onClick={() => { setFrom(''); setTo(''); }}>Last 30 days</Button>}
        <span className="grow" />
        {a && <span className="small muted">{a.range.from} → {a.range.to} · {a.range.days} days</span>}
        <Button size="sm" onClick={copy} disabled={!a}>{copied ? 'Copied' : 'Copy as Markdown'}</Button>
      </div>
      {q.isLoading && <Loading />}
      {q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
      {a && (
        <>
          <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' }}>
            <StatTile label="Decisions" value={fmtNum(a.totalDecisions)} hint={`${fmtNum(a.orders)} orders in range`} />
            <StatTile label="Automation rate" value={fmtPct(a.automationRate)} tone={empty ? undefined : a.automationRate >= 0.7 ? 'ok' : 'warn'} hint={`${fmtNum(a.actingDecisions)} acted without a person`} />
            <StatTile label="Human decisions avoided" value={fmtNum(a.humanDecisionsAvoided)} hint="acting decisions on T1/T2" />
            <StatTile label="Analyst hours saved (est.)" value={`${a.estimatedAnalystHoursSaved.toFixed(1)} h`} hint={`estimate · ${a.minutesPerHumanDecision} min per decision`} />
            <StatTile label="AI spend" value={fmtUsd(a.totalAiCostUsd, 2)} hint={`${fmtUsd(a.costPer1kDecisionsUsd, 4)} per 1k decisions`} />
            <StatTile label="Cost per order" value={fmtUsd(a.costPerOrderUsd, a.costPerOrderUsd < 0.01 ? 5 : 3)} hint="AI spend / orders placed" />
            <StatTile label="Override rate" value={fmtPct(a.overrideRate)} tone={empty ? undefined : a.overrideRate <= 0.05 ? 'ok' : 'warn'} hint={`${fmtNum(a.overrides)} overrides · target ≤ 5%`} />
            <StatTile label="Escalation rate" value={fmtPct(a.escalationRate)} hint={`${fmtNum(a.escalations)} needed a person`} />
          </div>
          {empty && <EmptyState icon={<IconSparkle size={18} />} title="No decisions in this range yet" hint={<>Every figure above reads as zero until the decision fabric has acted on an order. Run a demo scenario from the <Link to="/showcase">Showcase</Link>, or place one yourself in the <Link to="/playground">Playground</Link> — it only takes a few seconds — then widen or shift the date range above.</>} />}
          <div className="grid grid-3">
            <div className="card card-pad col">
              <div className="section-title">Decisions by layer</div>
              <StackedBar ariaLabel="Decisions by layer" height={18} segments={a.byLayer.map((l) => ({ label: layerLabel(l.layer), value: l.count, color: LAYER_COLORS[l.layer] ?? 'var(--c6)' }))} />
              <table className="mt3" style={{ width: '100%', fontSize: 'var(--fs-sm)', borderCollapse: 'collapse' }}>
                <thead><tr className="muted"><th style={{ textAlign: 'left', fontWeight: 500 }}>Layer</th><th style={{ textAlign: 'right', fontWeight: 500 }}>Count</th><th style={{ textAlign: 'right', fontWeight: 500 }}>p50</th><th style={{ textAlign: 'right', fontWeight: 500 }}>p95</th></tr></thead>
                <tbody>{a.byLayer.map((l) => <tr key={l.layer}><td><i className="legend-dot" style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 2, marginRight: 6, background: LAYER_COLORS[l.layer] ?? 'var(--c6)', verticalAlign: -1 }} />{layerLabel(l.layer)}</td><td className="num" style={{ textAlign: 'right' }}>{fmtNum(l.count)}</td><td className="num muted" style={{ textAlign: 'right' }}>{l.count ? `${Math.round(l.latencyP50Ms)} ms` : '—'}</td><td className="num muted" style={{ textAlign: 'right' }}>{l.count ? `${Math.round(l.latencyP95Ms)} ms` : '—'}</td></tr>)}</tbody>
              </table>
            </div>
            <div className="card card-pad col" style={{ gridColumn: 'span 2' }}>
              <div className="section-title">Decisions per day, stacked by layer</div>
              <DailyStackedChart days={a.daily} height={180} />
              <div className="legend mt2">{LAYERS.map((l) => <span key={l}><i style={{ background: LAYER_COLORS[l] }} />{layerLabel(l)}</span>)}</div>
            </div>
          </div>
          <Section title="Per decision" tight>
            <Table rows={a.decisions} rowKey={(d) => d.name} dense emptyTitle="No decisions evaluated in this range" columns={[
              { key: 'name', header: 'Decision', render: (d) => <Link to={`/studio/${encodeURIComponent(d.name)}`} className="mono">{d.name}</Link> },
              { key: 'tier', header: 'Tier', render: (d) => d.tier ? <TierBadge tier={d.tier as 'T0' | 'T1' | 'T2' | 'T3'} /> : <span className="muted">—</span> },
              { key: 'vol', header: 'Volume', align: 'right', render: (d) => <span className="num">{fmtNum(d.volume)}</span> },
              { key: 'auto', header: 'Automation', align: 'right', render: (d) => <span className="num">{fmtPct(d.automationRate)}</span> },
              { key: 'conf', header: 'Avg confidence', align: 'right', render: (d) => <span className="num">{d.avgConfidence.toFixed(2)}</span> },
              { key: 'cost', header: 'Cost / 1k', align: 'right', render: (d) => <span className="num">{fmtUsd(d.costPer1kUsd, 4)}</span> },
              { key: 'p95', header: 'p95', align: 'right', render: (d) => <span className="num">{Math.round(d.latencyP95Ms)} ms</span> },
              { key: 'ovr', header: 'Override', align: 'right', render: (d) => <span className="num" style={{ color: d.overrideRate > 0.05 ? 'var(--warn-fg)' : undefined }}>{fmtPct(d.overrideRate)}</span> },
              { key: 'gold', header: <span title="Accuracy against the golden set — the hand-checked test cases every policy change must pass before it can go live">Golden set</span>, align: 'right', render: (d) => <span className="num muted">{d.goldenAccuracy === null || d.goldenAccuracy === undefined ? '—' : fmtPct(d.goldenAccuracy)}</span> },
            ]} />
          </Section>
          <div className="card card-pad">
            <div className="section-title">Assumptions and definitions</div>
            <ul className="small muted" style={{ margin: 0, paddingLeft: 18, lineHeight: 1.6 }}>{a.assumptions.map((s) => <li key={s}>{s}</li>)}</ul>
            <div className="small muted mt2">Change the minutes-per-decision assumption in <Link to="/settings?tab=config">Settings → Tenant config</Link> (<code>analytics.minutes_per_human_decision</code>).</div>
          </div>
        </>
      )}
    </div>
  );
}

/** Stacked columns, one per day, segments per layer; follows the LineChart/BarChart SVG conventions. */
function DailyStackedChart({ days, height = 180 }: { days: EconomicsDay[]; height?: number }) {
  const id = useId();
  const w = 600; const padL = 40; const padR = 8; const padT = 10; const padB = 22;
  const max = Math.max(1, ...days.map((d) => d.total));
  const n = Math.max(1, days.length);
  const bw = (w - padL - padR) / n;
  const y = (v: number) => padT + (1 - v / max) * (height - padT - padB);
  const every = Math.max(1, Math.ceil(n / 8));
  const total = days.reduce((s, d) => s + d.total, 0);
  return (
    <svg className="svg-chart" viewBox={`0 0 ${w} ${height}`} role="img" aria-labelledby={`${id}-t`} style={{ width: '100%', height }}>
      <title id={`${id}-t`}>{total === 0 ? 'No decisions per day in this range' : `Decisions per day by layer, ${days.length} days, peak ${max}`}</title>
      {[0, 0.5, 1].map((t) => <g key={t}><line x1={padL} x2={w - padR} y1={y(max * t)} y2={y(max * t)} stroke="var(--grid)" strokeWidth={1} /><text x={padL - 6} y={y(max * t) + 3} textAnchor="end">{fmtNum(Math.round(max * t))}</text></g>)}
      {days.map((d, i) => {
        let acc = 0; const x = padL + i * bw + bw * 0.15; const cw = Math.max(1, bw * 0.7);
        return <g key={d.date}>{LAYERS.map((l) => { const v = d.byLayer[l] ?? 0; if (!v) return null; const y1 = y(acc + v); const h = y(acc) - y1; acc += v; return <rect key={l} x={x} y={y1} width={cw} height={Math.max(0.5, h)} fill={LAYER_COLORS[l]}><title>{`${d.date} · ${layerLabel(l)}: ${v}`}</title></rect>; })}</g>;
      })}
      {days.map((d, i) => (i === n - 1 || (i % every === 0 && n - 1 - i >= every / 2)) && <text key={d.date} x={padL + i * bw + bw / 2} y={height - 6} textAnchor="middle">{d.date.slice(5)}</text>)}
      {total === 0 && <text x={padL + (w - padL - padR) / 2} y={height / 2} textAnchor="middle" style={{ fill: 'var(--text-faint)' }}>no decisions yet</text>}
    </svg>
  );
}
