import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { analyticsApi } from '../../api/analytics';
import { decisionsApi } from '../../api/decisions';
import type { Kpi } from '../../api/types';
import { StatTile } from '../../components/StatTile';
import { Badge, StatusBadge, TierBadge } from '../../components/Badge';
import { Select } from '../../components/Form';
import { ErrorState, Loading, Section } from '../../components/States';
import { BarChart, StackedBar } from '../../components/charts/BarChart';
import { LineChart } from '../../components/charts/LineChart';
import { CalibrationCurve } from '../../components/charts/CalibrationCurve';
import { fmtNum, fmtPct, fmtUsd, humanize } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { Tabs, TabPanel } from '../../components/Tabs';
import { IconChart } from '../../components/Icons';
import { MarginGuardian } from './MarginGuardian';
import { AiEconomics } from './AiEconomics';

const SECTION13 = ['touchless_rate', 'promise_accuracy', 'split_rate', 'cost_to_serve', 'margin_floor_compliance', 'store_reject_rate', 'exception_resolution_median', 'return_abuse_loss', 'wismo_per_100', 'policy_lead_time', 'decision_accuracy', 'calibration_error', 'override_rate', 'ai_cost_per_order'];
function fmtKpi(k: Kpi, v = k.value): string { return k.unit === 'pct' ? fmtPct(v) : k.unit === 'usd' ? fmtUsd(v, v < 1 ? 4 : 2) : k.unit === 'minutes' ? `${fmtNum(v)} min` : k.unit === 'ratio' ? v.toFixed(3) : fmtNum(v, 1); }
function meetsTarget(k: Kpi): boolean | null { if (k.target === undefined || k.target === null) return null; return k.targetDirection === 'lte' ? k.value <= k.target : k.value >= k.target; }

export default function Analytics() {
  const kpis = useQuery({ queryKey: ['kpis'], queryFn: () => analyticsApi.kpis() });
  const decisions = useQuery({ queryKey: ['decisions'], queryFn: decisionsApi.list });
  const [tab, setTab] = useQueryState('tab', 'overview');
  const [name, setName] = useQueryState('decision', 'fulfilment_intent');
  const da = useQuery({ queryKey: ['decision-analytics', name], queryFn: () => analyticsApi.decision(name), enabled: !!name });
  const [vIdx, setVIdx] = useState<number | null>(null);
  const versions = da.data?.versions ?? [];
  const defaultIdx = Math.max(0, versions.findIndex((x) => x.status === 'live'));
  const idx = vIdx ?? defaultIdx; const v = versions[Math.min(idx, Math.max(0, versions.length - 1))];
  return (
    <div className="page">
      <div className="page-head">
        <div className="row gap3" style={{ alignItems: 'flex-start' }}>
          <span className="icon-badge" aria-hidden="true"><IconChart size={20} /></span>
          <div><div className="eyebrow">Performance &amp; AI economics</div><h1>Analytics</h1><div className="sub">How the decision fabric is performing against its targets, and where this week's margin went — one screen, no spreadsheet.</div></div>
        </div>
        {kpis.data && tab === 'overview' && <span className="small muted">{kpis.data.range.from} → {kpis.data.range.to}</span>}
      </div>
      <Tabs ariaLabel="Analytics sections" active={tab} onChange={setTab} tabs={[{ id: 'overview', label: 'Overview' }, { id: 'ai-economics', label: 'AI Economics' }]} />
      <TabPanel id="ai-economics" active={tab}><AiEconomics /></TabPanel>
      {tab === 'overview' && <>
      {kpis.isLoading && <Loading />}{kpis.isError && <ErrorState error={kpis.error} retry={() => kpis.refetch()} />}
      {kpis.data && <div className="grid grid-4" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))' }}>{SECTION13.map((key) => kpis.data!.kpis.find((k) => k.key === key)).filter((k): k is Kpi => !!k).map((k) => { const ok = meetsTarget(k); return <StatTile key={k.key} label={k.label} value={fmtKpi(k)} tone={ok === null ? undefined : ok ? 'ok' : 'warn'} trend={k.trend} delta={null} target={k.target !== undefined && k.target !== null ? `target ${k.targetDirection === 'lte' ? '≤' : '≥'} ${fmtKpi(k, k.target)}${k.delta ? ` · ${k.delta > 0 ? '+' : ''}${fmtKpi(k, k.delta)} vs prior` : ''}` : k.definition} hint={k.definition} />; })}</div>}

      <MarginGuardian />

      <Section title="Decision analytics" actions={<div className="row gap3"><Select aria-label="Decision" sm value={name} onChange={(e) => { setName(e.target.value); setVIdx(null); }}>{(decisions.data ?? []).map((d) => <option key={d.name} value={d.name}>{d.name}</option>)}</Select>{versions.length > 1 && <Select aria-label="Version" sm value={String(idx)} onChange={(e) => setVIdx(Number(e.target.value))}>{versions.map((x, i) => <option key={x.version} value={i}>v{x.version}{x.status ? ` · ${x.status}` : ''}</option>)}</Select>}<Link to={`/studio/${name}`} className="btn btn-sm">Open in Studio</Link></div>}>
        {da.isLoading && <Loading />}{da.isError && <ErrorState error={da.error} retry={() => da.refetch()} />}
        {da.data && v && (
          <div className="col gap4">
            <div className="row gap3 wrap"><TierBadge tier={da.data.tier} title /><Badge tone="outline">v{v.version}</Badge>{v.status && <StatusBadge status={v.status} />}</div>
            <div className="impact-grid" style={{ gridTemplateColumns: 'repeat(6, minmax(0,1fr))' }}>
              <Mini l="Volume" v={fmtNum(v.volume)} /><Mini l="Override rate" v={fmtPct(v.overrideRate)} d={v.overrideRate > 0.05 ? 'above 5% target' : 'within target'} /><Mini l="Escalation rate" v={fmtPct(v.escalationRate)} /><Mini l="Latency p50 / p95" v={`${v.latencyP50Ms} / ${v.latencyP95Ms} ms`} d={v.latencyP95Ms > 800 ? 'p95 over 800 ms budget' : 'within 800 ms budget'} /><Mini l="Cost" v={fmtUsd(v.costUsd)} d={`${fmtUsd(v.costPerDecisionUsd, 5)} per decision`} /><Mini l="Accuracy" v={v.accuracy ? fmtPct(v.accuracy) : '—'} d="vs labelled outcomes" />
            </div>
            <div className="grid grid-3">
              <div className="card card-pad col"><div className="section-title" title="How often this decision acts on its own (act), asks an LLM to confirm (confirm), or hands off to a person (escalate)">Band mix</div><StackedBar ariaLabel="Band mix" segments={[{ label: 'act', value: v.bandMix.ACT, color: 'var(--band-act)' }, { label: 'confirm', value: v.bandMix.CONFIRM, color: 'var(--band-confirm)' }, { label: 'escalate', value: v.bandMix.ESCALATE, color: 'var(--band-escalate)' }]} height={18} />
                <div className="section-title mt3" title="Which layer answered: L0 deterministic rules, L1 Jev, L2 LLM, or L3 a person">Layer mix</div><StackedBar ariaLabel="Layer mix" segments={Object.entries(v.layerMix).map(([k, val]) => ({ label: humanize(k).replace('L0 RULE', 'L0 rules').replace('L1 SYSTEM ONE', 'L1 Jev').replace('L2 LLM', 'L2 LLM').replace('L3 HUMAN', 'L3 human'), value: val, color: ({ L0_RULE: 'var(--l0)', L1_SYSTEM_ONE: 'var(--l1)', L2_LLM: 'var(--l2)', L3_HUMAN: 'var(--l3)' } as Record<string, string>)[k] ?? 'var(--c6)' }))} height={18} /></div>
              <div className="card card-pad col"><div className="section-title">Daily volume (30d)</div>{v.dailyVolume && <LineChart ariaLabel="Daily decision volume" series={[{ name: 'volume', data: v.dailyVolume }]} height={160} yFormat={(n) => fmtNum(n)} xLabels={v.dailyVolume.map((_, i) => `d${i + 1}`)} />}</div>
              <div className="card card-pad col"><div className="section-title" title="Predicted confidence vs. how often the decision was actually right — points should sit on the diagonal">Calibration</div><CalibrationCurve bins={v.calibration} size={200} /></div>
            </div>
            {versions.length > 1 && <div className="card card-pad"><div className="section-title">Versions compared</div><div className="grid grid-3"><BarChart ariaLabel="Volume by version" data={versions.map((x) => ({ label: `v${x.version}`, value: x.volume }))} height={120} format={(n) => fmtNum(n)} /><BarChart ariaLabel="Override rate by version" data={versions.map((x) => ({ label: `v${x.version}`, value: x.overrideRate, color: 'var(--c3)' }))} height={120} format={(n) => fmtPct(n)} /><BarChart ariaLabel="p95 latency by version" data={versions.map((x) => ({ label: `v${x.version}`, value: x.latencyP95Ms, color: 'var(--c4)' }))} height={120} format={(n) => `${n} ms`} /></div></div>}
          </div>
        )}
      </Section>
      </>}
    </div>
  );
}
function Mini({ l, v, d }: { l: string; v: string; d?: string }) { return <div className="mini-stat"><span className="l">{l}</span><span className="v">{v}</span>{d && <span className="d">{d}</span>}</div>; }
