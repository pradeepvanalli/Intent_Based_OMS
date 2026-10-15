import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { controlTowerApi, exceptionsApi } from '../../api/monitoring';
import { analyticsApi } from '../../api/analytics';
import type { PipelineView } from '../../api/types';
import { StatTile } from '../../components/StatTile';
import { Section, Loading, ErrorState, EmptyState } from '../../components/States';
import { LiveIndicator } from '../../components/Misc';
import { Badge, SeverityBadge, StatusBadge } from '../../components/Badge';
import { Table } from '../../components/Table';
import { Select } from '../../components/Form';
import { fmtMinutes, fmtPct, fmtUsd, humanize, relTime } from '../../lib/format';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { IconWhy } from '../../components/Icons';
import { OpsCopilot } from './OpsCopilot';
import { AgenticOps } from './AgenticOps';
import { hasPermission, useSession } from '../../auth/session';

const REFRESH = 5000;

export default function ControlTower() {
  const navigate = useNavigate(); const { principal } = useSession();
  const [channel, setChannel] = useState(''); const [orderType, setOrderType] = useState(''); const [nodeId, setNodeId] = useState('');
  const [why, setWhy] = useState<string | null>(null);
  const pipeline = useQuery({ queryKey: ['pipeline', channel, orderType, nodeId], queryFn: () => controlTowerApi.pipeline({ channel: channel || undefined, orderType: orderType || undefined, nodeId: nodeId || undefined }), refetchInterval: REFRESH });
  const atRisk = useQuery({ queryKey: ['at-risk'], queryFn: () => controlTowerApi.atRisk(30), refetchInterval: REFRESH });
  const kpis = useQuery({ queryKey: ['kpis', 'tower'], queryFn: () => analyticsApi.kpis(), refetchInterval: 30000 });
  const exc = useQuery({ queryKey: ['exceptions', 'preview'], queryFn: () => exceptionsApi.list({ status: 'active', limit: 8 }), refetchInterval: REFRESH });
  const kpi = (k: string) => kpis.data?.kpis.find((x) => x.key === k);
  const p = pipeline.data;
  return (
    <div className="page">
      <div className="page-head">
        <div><h1>Control Tower</h1><div className="sub">Live pipeline by state and age · at-risk orders · open exceptions</div></div>
        <div className="page-actions">
          <div className="filters">
            <Select aria-label="Channel" sm value={channel} onChange={(e) => setChannel(e.target.value)}><option value="">All channels</option>{Object.keys(p?.byChannel ?? {}).sort().map((c) => <option key={c} value={c}>{c}</option>)}</Select>
            <Select aria-label="Order type" sm value={orderType} onChange={(e) => setOrderType(e.target.value)}><option value="">All order types</option>{Object.keys(p?.byOrderType ?? {}).sort().map((c) => <option key={c} value={c}>{humanize(c)}</option>)}</Select>
            <Select aria-label="Node" sm value={nodeId} onChange={(e) => setNodeId(e.target.value)}><option value="">All nodes</option>{Object.keys(p?.byNode ?? {}).sort().map((c) => <option key={c} value={c}>{c}</option>)}</Select>
          </div>
          <LiveIndicator updatedAt={pipeline.dataUpdatedAt || undefined} intervalMs={REFRESH} />
        </div>
      </div>

      <div className="grid grid-5">
        <StatTile label="Open orders" value={p?.openOrders ?? '—'} loading={pipeline.isLoading} onClick={() => navigate('/orders?open=1')} hint={p ? `${p.held ?? 0} on hold` : undefined} />
        <StatTile label="At risk" value={p?.atRisk ?? '—'} tone={p && p.atRisk > 0 ? 'danger' : undefined} loading={pipeline.isLoading} onClick={() => navigate('/orders?atRisk=true')} hint="breach-risk score ≥ 3" />
        <StatTile label="Exceptions open" value={exc.data?.total ?? '—'} tone={exc.data && exc.data.total > 5 ? 'warn' : undefined} loading={exc.isLoading} onClick={() => navigate('/exceptions')} hint="open + in progress" />
        <StatTile label="Touchless rate today" value={fmtPct(kpi('touchless_today')?.value)} tone={kpi('touchless_today') && (kpi('touchless_today')!.value < (kpi('touchless_today')!.target ?? 0)) ? 'warn' : 'ok'} trend={kpi('touchless_today')?.trend} target={`target ≥ ${fmtPct(kpi('touchless_today')?.target, 0)}`} loading={kpis.isLoading} />
        <StatTile label="AI cost / order" value={fmtUsd(kpi('ai_cost_per_order')?.value, 4)} trend={kpi('ai_cost_per_order')?.trend} target={`budget ${fmtUsd(kpi('ai_cost_per_order')?.target, 2)}`} loading={kpis.isLoading} />
      </div>

      {hasPermission(principal, 'analytics.read') && <OpsCopilot />}

      <div className="two-col">
        <Section title={<span className="row gap3">Pipeline <span className="muted small" style={{ fontWeight: 400 }}>state × age, open orders</span></span>} actions={p && <span className="xs muted">as of {relTime(p.generatedAt)}</span>}>
          {pipeline.isLoading && <Loading />}
          {pipeline.isError && <ErrorState error={pipeline.error} retry={() => pipeline.refetch()} />}
          {p && <Heatmap view={p} onCell={(state, age) => navigate(`/orders?status=${encodeURIComponent(state)}&ageBucket=${encodeURIComponent(age)}`)} />}
          {p && <div className="grid grid-3 mt4">
            <Breakdown title="By channel" data={p.byChannel} onPick={(k) => setChannel(k)} />
            <Breakdown title="By node" data={p.byNode} onPick={(k) => setNodeId(k)} />
            <Breakdown title="By order type" data={p.byOrderType} onPick={(k) => setOrderType(k)} />
          </div>}
        </Section>
        <Section title={<span className="row gap3">Exceptions inbox<Badge tone="warn">{exc.data?.total ?? 0}</Badge></span>} actions={<Link to="/exceptions" className="small">Open inbox →</Link>} tight>
          {exc.isLoading && <Loading />}
          {exc.data && exc.data.items.length === 0 && <EmptyState title="No open exceptions" hint="Playbooks are keeping up." />}
          {exc.data && exc.data.items.length > 0 && <div className="subtle-list" style={{ padding: '0 var(--s4)' }}>{exc.data.items.map((e) => <Link key={e.id} to={`/exceptions?selected=${e.id}`} className="col gap1" style={{ color: 'inherit', textDecoration: 'none' }}><div className="row gap3"><SeverityBadge severity={e.severity} /><Badge tone="outline">{humanize(e.type)}</Badge><span className="grow" /><span className="xs muted">{relTime(e.raisedAt)}</span></div><div className="small truncate" title={e.title}>{e.title}</div><div className="xs muted">queue {e.queue}{e.playbook && <> · playbook <span className="mono">{e.playbook}</span></>}</div></Link>)}</div>}
        </Section>
      </div>

      <Section title={<span className="row gap3">At-risk orders <span className="muted small" style={{ fontWeight: 400 }}>predicted breach risk (decision sla_breach_risk)</span></span>} actions={<Link to="/orders?atRisk=true" className="small">All at-risk →</Link>} tight>
        {atRisk.isLoading && <Loading />}
        {atRisk.isError && <ErrorState error={atRisk.error} retry={() => atRisk.refetch()} />}
        {atRisk.data && <Table rows={atRisk.data} rowKey={(r) => r.orderId} dense emptyTitle="No at-risk orders" onRowClick={(r) => navigate(`/orders/${r.orderId}`)} caption="At-risk orders" columns={[
          { key: 'id', header: 'Order', render: (r) => <span className="col gap1"><span className="mono">{r.orderId}</span><span className="xs muted">{r.channel} · {r.channelOrderId}</span></span> },
          { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
          { key: 'node', header: 'Node', render: (r) => <span className="mono small">{r.nodeId ?? '—'}</span> },
          { key: 'risk', header: 'Breach risk', width: 200, render: (r) => <RiskBar score={r.riskScore} level={r.riskLevel} /> },
          { key: 'reasons', header: 'Signals', render: (r) => <span className="pill-row">{r.reasons.slice(0, 3).map((x) => <Badge key={x} tone="outline">{x}</Badge>)}</span> },
          { key: 'age', header: 'Age', align: 'right', render: (r) => <span className="num">{fmtMinutes(r.ageMinutes)}</span> },
          { key: 'promise', header: 'Promise', render: (r) => r.promiseDate ?? '—' },
          { key: 'why', header: '', width: 60, render: (r) => r.decisionRecordId ? <button className="btn btn-sm btn-ghost" aria-label={`Why is ${r.orderId} at risk`} onClick={(e) => { e.stopPropagation(); setWhy(r.decisionRecordId!); }}><IconWhy size={14} /> Why</button> : null },
        ]} />}
      </Section>

      <AgenticOps />

      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </div>
  );
}

function Heatmap({ view, onCell }: { view: PipelineView; onCell: (state: string, age: string) => void }) {
  const max = Math.max(1, ...view.cells.map((c) => c.count));
  const get = (s: string, a: string) => view.cells.find((c) => c.state === s && c.ageBucket === a)?.count ?? 0;
  const rowTotal = (s: string) => view.ageBuckets.reduce((t, a) => t + get(s, a), 0);
  return (
    <div className="heat" style={{ gridTemplateColumns: `150px repeat(${view.ageBuckets.length}, 1fr) 56px` }} role="grid" aria-label="Pipeline heatmap: orders by state and age bucket">
      <div className="hcol" role="columnheader" />{view.ageBuckets.map((a) => <div key={a} className="hcol" role="columnheader">{a}</div>)}<div className="hcol" role="columnheader">total</div>
      {view.states.map((s) => (<div key={s} style={{ display: 'contents' }} role="row">
        <div className="hrow" role="rowheader">{s}</div>
        {view.ageBuckets.map((a) => { const n = get(s, a); const heat = n / max; return <button key={a} role="gridcell" className="hcell" style={{ '--heat': heat } as React.CSSProperties} data-hot={heat > 0.55} onClick={() => onCell(s, a)} aria-label={`${n} orders in ${s} aged ${a}; click to list`} disabled={n === 0}>{n || ''}</button>; })}
        <div className="hcol num strong" role="gridcell">{rowTotal(s)}</div>
      </div>))}
    </div>
  );
}
function Breakdown({ title, data, onPick }: { title: string; data?: Record<string, number>; onPick: (k: string) => void }) {
  const entries = Object.entries(data ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 6); const max = Math.max(1, ...entries.map((e) => e[1]));
  return <div><div className="section-title">{title}</div><div className="col gap1">{entries.map(([k, v]) => <button key={k} className="prob" style={{ all: 'unset', display: 'grid', gridTemplateColumns: 'minmax(70px, 110px) 1fr 32px', gap: 8, alignItems: 'center', cursor: 'pointer', fontSize: 'var(--fs-sm)' }} onClick={() => onPick(k)} aria-label={`Filter by ${k}`}><span className="k truncate">{k}</span><span className="bar"><span style={{ width: `${(v / max) * 100}%` }} /></span><span className="v num">{v}</span></button>)}{entries.length === 0 && <span className="xs muted">—</span>}</div></div>;
}
export function RiskBar({ score, level }: { score: number; level?: string }) {
  const tone = score >= 4 ? 'danger' : score >= 3 ? 'danger' : score >= 2 ? 'warn' : 'ok';
  return <div className="row gap3" aria-label={`risk score ${score} of 4${level ? `, ${level}` : ''}`}><div className={`bar ${tone}`} style={{ width: 90 }}><span style={{ width: `${(score / 4) * 100}%` }} /></div><span className="small num" style={{ minWidth: 60 }}>{score}/4 <span className="muted">{level}</span></span></div>;
}
