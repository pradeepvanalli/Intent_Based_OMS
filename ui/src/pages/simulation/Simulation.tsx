import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { simulationsApi } from '../../api/simulations';
import { decisionsApi } from '../../api/decisions';
import { nodesApi } from '../../api/inventory';
import type { ReplayResult, SimulationJob } from '../../api/types';
import { Badge, BandBadge, LayerBadge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ChipInput, Input, Select } from '../../components/Form';
import { Table } from '../../components/Table';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { IconSim } from '../../components/Icons';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { fmtConf, fmtDateTime, fmtPct, fmtUsd, humanize, valueLabel } from '../../lib/format';
import { toast } from '../../lib/toast';

export default function Simulation() {
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">Testing &amp; forecasting</div>
          <h1><span className="icon-badge"><IconSim size={20} /></span> Simulation</h1>
          <div className="sub">See how a decision change would have played out before anyone bets real orders on it — replay one order against a newer version, or run a what-if batch over a date range.</div>
        </div>
      </div>
      <Replay />
      <Batch />
    </div>
  );
}

function Replay() {
  const decisions = useQuery({ queryKey: ['decisions'], queryFn: decisionsApi.list });
  const [orderId, setOrderId] = useState('ORD-100247'); const [overrides, setOverrides] = useState<Record<string, number>>({}); const [result, setResult] = useState<ReplayResult | null>(null); const [why, setWhy] = useState<string | null>(null);
  const run = useMutation({ mutationFn: () => simulationsApi.replay(orderId.trim(), { decisionVersionOverrides: overrides }), onSuccess: setResult });
  return (
    <Section title="Replay an order" actions={result && <span className="small muted">{result.eventsReplayed} ledger events replayed · {result.decisions.filter((d) => d.changed).length} of {result.decisions.length} decisions changed</span>}>
      <div className="two-col rev" style={{ gridTemplateColumns: '340px minmax(0,1fr)' }}>
        <form className="col gap3" onSubmit={(e) => { e.preventDefault(); run.mutate(); }}>
          <Input label="Order id" value={orderId} onChange={(e) => setOrderId(e.target.value)} mono required />
          <div className="field"><span className="field-label">Decision version overrides</span><div className="col gap1">{(decisions.data ?? []).map((d) => <div key={d.name} className="row gap3"><span className="mono small grow truncate">{d.name}</span><Select aria-label={`Version for ${d.name}`} sm value={overrides[d.name] ?? ''} onChange={(e) => setOverrides({ ...overrides, [d.name]: e.target.value ? Number(e.target.value) : undefined } as Record<string, number>)} style={{ width: 120 }}><option value="">live (v{d.liveVersion ?? '—'})</option>{Array.from({ length: d.latestVersion }, (_, i) => d.latestVersion - i).map((v) => <option key={v} value={v}>v{v}</option>)}</Select></div>)}</div><span className="hint">Leave on live to re-run with stored inputs in REPLAY mode (no side effects, no provider spend recorded as LIVE).</span></div>
          <Button type="submit" variant="primary" loading={run.isPending}>Replay</Button>
          {run.isError && <ErrorState error={run.error} />}
        </form>
        <div>
          {!result && !run.isPending && <EmptyState title="No replay yet" hint="Pick an order and versions, then Replay to see a before/after diff per decision." />}
          {run.isPending && <Loading label="Rebuilding order from the ledger…" />}
          {result && <Table rows={result.decisions} rowKey={(d) => d.recordId} dense caption="Replay diff" emptyTitle="No decisions recorded for this order" columns={[
            { key: 'd', header: 'Decision', render: (d) => <span className="col gap1"><span className="strong small">{humanize(d.decision)}</span><span className="xs muted mono">{d.recordId}</span></span> },
            { key: 'v', header: 'Version', render: (d) => <span className="mono small">v{d.version}</span> },
            { key: 'before', header: <span title="Value, confidence band (act / confirm / escalate) and which engine answered">Before</span>, render: (d) => <Cell v={d.before} /> }, { key: 'after', header: <span title="Value, confidence band (act / confirm / escalate) and which engine answered">After</span>, render: (d) => <Cell v={d.after} /> },
            { key: 'chg', header: 'Changed', render: (d) => d.changed ? <Badge tone="warn">flip</Badge> : <Badge tone="outline">same</Badge> },
            { key: 'why', header: '', render: (d) => <Button size="sm" variant="ghost" onClick={() => setWhy(d.recordId)}>Why (before)</Button> },
          ]} />}
        </div>
      </div>
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </Section>
  );
}
function Cell({ v }: { v: { value: unknown; band: string; confidence: number; layer: string } }) { return <span className="row gap1 wrap"><span className="mono small">{valueLabel(v.value)}</span><BandBadge band={v.band} /><LayerBadge layer={v.layer} /><span className="xs muted num">{fmtConf(v.confidence)}</span></span>; }

function Batch() {
  const nodes = useQuery({ queryKey: ['nodes'], queryFn: nodesApi.list });
  const jobs = useQuery({ queryKey: ['simulations'], queryFn: simulationsApi.list, refetchInterval: 5000 });
  const today = new Date().toISOString().slice(0, 10); const twoWeeks = new Date(Date.now() - 14 * 86_400_000).toISOString().slice(0, 10);
  const [from, setFrom] = useState(twoWeeks); const [to, setTo] = useState(today); const [closed, setClosed] = useState<string[]>([]); const [carriers, setCarriers] = useState<string[]>([]); const [tariff, setTariff] = useState(0);
  const [jobId, setJobId] = useState<string | null>(null);
  const submit = useMutation({ mutationFn: () => simulationsApi.batch(from, to, { closedNodes: closed, carrierOutage: carriers, tariffDelta: tariff }), onSuccess: (j) => { setJobId(j.id); toast.ok('Simulation queued', j.id); jobs.refetch(); } });
  const job = useQuery({ queryKey: ['simulation', jobId], queryFn: () => simulationsApi.get(jobId!), enabled: !!jobId, refetchInterval: (q) => (q.state.data && ['COMPLETED', 'FAILED'].includes(q.state.data.status) ? false : 1000) });
  useEffect(() => { if (!jobId && jobs.data?.[0]) setJobId(jobs.data[0].id); }, [jobs.data, jobId]);
  const j = job.data;
  return (
    <Section title="Batch what-if simulation">
      <div className="two-col rev" style={{ gridTemplateColumns: '340px minmax(0,1fr)' }}>
        <form className="col gap3" onSubmit={(e) => { e.preventDefault(); submit.mutate(); }}>
          <div className="row gap3"><Input label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} required /><Input label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} required /></div>
          <ChipInput label="Closed nodes" values={closed} onChange={setClosed} validate={(v) => (nodes.data?.some((n) => n.id === v) ? null : `Unknown node. Known: ${(nodes.data ?? []).map((n) => n.id).join(', ')}`)} hint={`Nodes: ${(nodes.data ?? []).map((n) => n.id).join(', ')}`} />
          <ChipInput label="Carrier outage" values={carriers} onChange={setCarriers} validate={(v) => (['UPS', 'FedEx', 'USPS', 'OnTrac'].includes(v) ? null : 'Known carriers: UPS, FedEx, USPS, OnTrac')} hint="UPS, FedEx, USPS, OnTrac" />
          <Input label="Tariff delta (percentage points on cross-border landed cost)" type="number" step={0.5} value={tariff} onChange={(e) => setTariff(Number(e.target.value))} />
          <Button type="submit" variant="primary" loading={submit.isPending}>Run simulation</Button>
          <div className="section-title mt3">Recent jobs</div>
          <div className="col gap1">{(jobs.data ?? []).map((x) => <button key={x.id} className="sel-row" aria-selected={x.id === jobId} onClick={() => setJobId(x.id)} type="button"><span className="mono small">{x.id}</span><StatusBadge status={x.status} /><span className="grow" /><span className="xs muted">{fmtDateTime(x.submittedAt)}</span></button>)}</div>
        </form>
        <div className="col gap4">
          {!j && <EmptyState title="No job selected" hint="Submit a simulation or pick a recent job." />}
          {j && <>
            <div className="row gap3 wrap"><StatusBadge status={j.status} lg /><span className="mono small">{j.id}</span><span className="small muted">{j.from} → {j.to}</span>{(j.overrides.closedNodes ?? []).map((n) => <Badge key={n} tone="warn">closed {n}</Badge>)}{(j.overrides.carrierOutage ?? []).map((n) => <Badge key={n} tone="warn">outage {n}</Badge>)}{j.overrides.tariffDelta ? <Badge tone="warn">tariff +{j.overrides.tariffDelta}pp</Badge> : null}</div>
            {j.status !== 'COMPLETED' && j.status !== 'FAILED' && <div><div className="bar" style={{ height: 8 }} role="progressbar" aria-valuenow={Math.round(j.progress * 100)} aria-valuemin={0} aria-valuemax={100}><span style={{ width: `${j.progress * 100}%` }} /></div><div className="xs muted mt2">Running over recorded inputs with sourcing economics… {Math.round(j.progress * 100)}%</div></div>}
            {j.status === 'FAILED' && <div className="error-box"><span className="title">Simulation failed</span><span className="small">{j.error}</span></div>}
            {j.results && <Results j={j} />}
          </>}
        </div>
      </div>
    </Section>
  );
}
function Results({ j }: { j: SimulationJob }) {
  const r = j.results!;
  const tile = (label: string, v: number, base: number | undefined, fmt: (n: number) => string, lowerBetter: boolean) => { const d = base !== undefined ? v - base : null; const good = d === null ? null : lowerBetter ? d <= 0 : d >= 0; return <div className="mini-stat" key={label}><span className="l">{label}</span><span className="v">{fmt(v)}</span>{d !== null && <span className="d" style={{ color: good ? 'var(--ok-fg)' : 'var(--danger-fg)' }}>{d >= 0 ? '+' : ''}{fmt(d)} vs baseline {fmt(base!)}</span>}</div>; };
  return (
    <div className="col gap3">
      <div className="impact-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0,1fr))' }}>
        {tile('Cost per order', r.costPerOrder, r.baselineCostPerOrder, (n) => fmtUsd(n), true)}
        {tile('Split rate', r.splitRate, r.baselineSplitRate, (n) => fmtPct(n), true)}
        {tile('On-time rate', r.onTimeRate, r.baselineOnTimeRate, (n) => fmtPct(n), false)}
        {tile('Margin ratio', r.marginRatio, r.baselineMarginRatio, (n) => fmtPct(n), false)}
        {tile('Touchless rate', r.touchlessRate, r.baselineTouchlessRate, (n) => fmtPct(n), false)}
        {tile('Escalations', r.escalations, r.baselineEscalations, (n) => String(Math.round(n)), true)}
      </div>
      <div className="xs muted">{r.orders.toLocaleString()} orders simulated · completed {fmtDateTime(j.completedAt)} · <Link to="/analytics">compare with live KPIs →</Link></div>
    </div>
  );
}
