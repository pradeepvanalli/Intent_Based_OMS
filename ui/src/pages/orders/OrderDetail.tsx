import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ordersApi } from '../../api/orders';
import { trackingUrlFor } from '../../api/playground';
import { holdsApi } from '../../api/stateModels';
import { agentsApi } from '../../api/agents';
import type { DecisionRecord, EntityStatus, Feasibility, Modification, ModificationType, OrderView, TimelineEntry } from '../../api/types';
import { Badge, BandBadge, LayerBadge, StatusBadge, TierBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Select, Textarea } from '../../components/Form';
import { Dialog, Drawer } from '../../components/Overlay';
import { Tabs, TabPanel } from '../../components/Tabs';
import { Table } from '../../components/Table';
import { ErrorState, Loading, EmptyState } from '../../components/States';
import { JsonTree, KeyValue, Countdown, CopyButton } from '../../components/Misc';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { fmtConf, fmtDateTime, fmtMoney, fmtPct, fmtUsd, humanize, relTime, safeJson, valueLabel, statusTone } from '../../lib/format';
import { toast } from '../../lib/toast';
import { hasPermission, useSession } from '../../auth/session';
import { useQueryState } from '../../lib/hooks';
import { IconCopy, IconOrders, IconWhy } from '../../components/Icons';
import { RiskBar } from '../controlTower/ControlTower';
import { CsDraftTab } from './CsDraftTab';

export default function OrderDetail() {
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: ['order', id], queryFn: () => ordersApi.view(id), refetchInterval: 10000 });
  const [tab, setTab] = useQueryState('tab', 'lines');
  const [why, setWhy] = useState<string | null>(null);
  const [mandateOpen, setMandateOpen] = useState(false);
  if (q.isLoading) return <div className="page"><Loading label="Loading order…" /></div>;
  if (q.isError || !q.data) return <div className="page"><ErrorState error={q.error} retry={() => q.refetch()} /></div>;
  const v = q.data; const o = v.order;
  const decisions = v.decisions ?? [];
  const activeHolds = v.holds.filter((h) => !h.resolvedAt);
  return (
    <div className="page">
      <nav className="small muted" aria-label="Breadcrumb"><Link to="/orders">Orders</Link> / <span className="mono">{o.id}</span></nav>
      <header className="order-head">
        <div>
          <div className="order-title">
            <span className="icon-badge" aria-hidden="true"><IconOrders /></span>
            <h1>{o.id}</h1><StatusBadge status={o.status} lg />
            {activeHolds.length > 0 && <Badge tone="hold" lg>{activeHolds.length} hold{activeHolds.length > 1 ? 's' : ''}</Badge>}
            {v.risk && v.risk.score >= 3 && <Badge tone="risk" lg>at risk</Badge>}
            {o.provenance && <button className="provenance" onClick={() => setMandateOpen(true)} aria-label="Agent provenance: open mandate and evidence pack">🤖 {o.provenance.protocol.toUpperCase()} · {o.provenance.agentId}</button>}
          </div>
          <div style={{ color: 'var(--text-muted)', marginTop: 'var(--s2)', fontSize: 'var(--fs-lg)', lineHeight: 1.5, maxWidth: '62ch' }}>This order's full record — lines and money, every automated decision with its reasoning, and what you can still do from here.</div>
          <div className="order-meta">
            <span>Type <b>{humanize(o.orderType)}</b></span><span>Channel <b>{o.channel}</b> <span className="mono">{o.channelOrderId}</span></span>
            <span>Customer <b>{o.customer?.name ?? o.customerRef}</b>{o.customer?.tier && <> · <Badge tone="outline">{o.customer.tier}</Badge></>}</span>
            <span>Placed <b title={fmtDateTime(o.placedAt)}>{relTime(o.placedAt)}</b></span>
            <span>Model <span className="mono">{o.stateModelId}@{o.stateModelVersion}</span></span>
            {v.workflow && <span>Workflow <Badge tone={statusTone(v.workflow.status)}>{v.workflow.status.toLowerCase().replace(/_/g, ' ')}</Badge> <span className="mono">{v.workflow.currentStep}</span></span>}
          </div>
        </div>
        <OrderActions view={v} onChanged={() => q.refetch()} />
      </header>

      <div className="order-grid">
        <IntentCard view={v} onWhy={setWhy} />
        <HoldsCard view={v} onChanged={() => q.refetch()} />
        <TotalsCard view={v} />
        <MarginCard view={v} />
      </div>

      <div className="card">
        <div style={{ padding: '0 var(--s4)' }}>
          <Tabs ariaLabel="Order sections" active={tab} onChange={setTab} tabs={[
            { id: 'lines', label: 'Lines', count: o.lines.length }, { id: 'timeline', label: 'Timeline' }, { id: 'decisions', label: 'Decisions', count: decisions.length },
            { id: 'shipments', label: 'Shipments', count: v.shipments.length }, { id: 'payments', label: 'Payments & refunds', count: v.payments.length + v.refunds.length }, { id: 'returns', label: 'Returns', count: v.returns.length },
            { id: 'comms', label: 'Communications', count: (v.communications ?? []).length }, { id: 'reply', label: 'Reply draft' }, { id: 'modify', label: 'Modify' },
          ]} />
        </div>
        <div className="card-body">
          <TabPanel id="lines" active={tab}><LinesTab view={v} /></TabPanel>
          <TabPanel id="timeline" active={tab}><TimelineTab view={v} onWhy={setWhy} /></TabPanel>
          <TabPanel id="decisions" active={tab}><DecisionsTab decisions={decisions} onWhy={setWhy} /></TabPanel>
          <TabPanel id="shipments" active={tab}>{v.shipments.length === 0 ? <EmptyState title="No shipments yet" hint="Shipments appear when the node confirms a carrier handoff." /> : <Table rows={v.shipments} rowKey={(s) => s.id} columns={[{ key: 'id', header: 'Shipment', render: (s) => <span className="mono">{s.id}</span> }, { key: 'node', header: 'Node', render: (s) => <span className="mono">{s.nodeId}</span> }, { key: 'carrier', header: 'Carrier', render: (s) => <>{s.carrier} <span className="muted">{s.service}</span></> }, { key: 'tracking', header: 'Tracking', render: (s) => <span className="mono small">{s.tracking ?? '—'}</span> }, { key: 'lines', header: 'Lines', render: (s) => s.lines.map((l) => `${l.lineId}×${l.qty}`).join(', ') }, { key: 'status', header: 'Status', render: (s) => <StatusBadge status={s.status} /> }, { key: 'shipped', header: 'Shipped', render: (s) => fmtDateTime(s.shippedAt) }, { key: 'eta', header: 'ETA / delivered', render: (s) => s.deliveredAt ? fmtDateTime(s.deliveredAt) : s.eta ?? '—' }]} />}</TabPanel>
          <TabPanel id="payments" active={tab}>
            <div className="section-title">Payments</div>
            {v.payments.length === 0 ? <EmptyState title="No payment records" hint="Authorizations and captures for this order appear here as they happen." /> : <Table rows={v.payments} rowKey={(p) => p.id} dense columns={[{ key: 'id', header: 'Payment', render: (p) => <span className="mono">{p.id}</span> }, { key: 'type', header: 'Type', render: (p) => `${p.type} · ${p.tenderType ?? ''}` }, { key: 'amt', header: 'Amount', align: 'right', render: (p) => fmtMoney(p.amount) }, { key: 'st', header: 'Status', render: (p) => <StatusBadge status={p.status} /> }, { key: 'psp', header: 'PSP ref', render: (p) => <span className="mono small">{p.pspRef}</span> }, { key: 'auth', header: 'Authorized', render: (p) => fmtDateTime(p.authorizedAt) }, { key: 'exp', header: 'Auth expires', render: (p) => p.capturedAt ? <span className="muted">captured {fmtDateTime(p.capturedAt)}</span> : <Countdown due={p.expiresAt} label="authorization expires" /> }]} />}
            <div className="section-title mt4">Refunds</div>
            {v.refunds.length === 0 ? <div className="muted small">No refunds issued.</div> : <Table rows={v.refunds} rowKey={(r) => r.id} dense columns={[{ key: 'id', header: 'Refund', render: (r) => <span className="mono">{r.id}</span> }, { key: 'amt', header: 'Amount', align: 'right', render: (r) => fmtMoney(r.amount) }, { key: 'st', header: 'Status', render: (r) => <StatusBadge status={r.status} /> }, { key: 'reason', header: 'Reason', render: (r) => r.reason ?? '—' }, { key: 'ret', header: 'Return', render: (r) => r.returnId ? <Link to={`/returns/${r.returnId}`} className="mono">{r.returnId}</Link> : '—' }, { key: 'at', header: 'Issued', render: (r) => fmtDateTime(r.issuedAt) }]} />}
          </TabPanel>
          <TabPanel id="returns" active={tab}>{v.returns.length === 0 ? <EmptyState title="No returns" hint="Returns created from any channel appear here with their RMA." /> : <Table rows={v.returns} rowKey={(r) => r.rma} columns={[{ key: 'rma', header: 'RMA', render: (r) => <Link to={`/returns/${r.rma}`} className="mono">{r.rma}</Link> }, { key: 'st', header: 'Status', render: (r) => <StatusBadge status={r.status} /> }, { key: 'ch', header: 'Channel', render: (r) => r.channel }, { key: 'lines', header: 'Lines', align: 'right', render: (r) => r.lineCount ?? '—' }, { key: 'risk', header: 'Risk', render: (r) => r.riskScore !== undefined ? `${r.riskScore}/4` : '—' }, { key: 'timing', header: 'Refund timing', render: (r) => humanize(r.refundTiming) || '—' }, { key: 'amt', header: 'Refund', align: 'right', render: (r) => fmtMoney(r.refundAmount) }, { key: 'at', header: 'Created', render: (r) => fmtDateTime(r.createdAt) }]} />}</TabPanel>
          <TabPanel id="comms" active={tab}>{(v.communications ?? []).length === 0 ? <EmptyState title="No communications recorded" hint={'Outbound notifications and inbound "where is my order" contacts will be listed here.'} /> : <div className="subtle-list">{(v.communications ?? []).map((c) => <div key={c.id} className="row gap3" style={{ alignItems: 'flex-start' }}><Badge tone={c.direction === 'out' ? 'info' : 'warn'}>{c.direction === 'out' ? '→ out' : '← in'}</Badge><Badge tone="outline">{c.channel}</Badge><div className="grow"><div className="strong small">{c.subject}</div><div className="small muted">{c.summary}</div></div><span className="xs muted nowrap">{fmtDateTime(c.at)} · {c.by}</span></div>)}</div>}</TabPanel>
          <TabPanel id="reply" active={tab}><CsDraftTab orderId={o.id} onWhy={setWhy} /></TabPanel>
          <TabPanel id="modify" active={tab}><ModifyTab view={v} onChanged={() => q.refetch()} /></TabPanel>
        </div>
      </div>
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
      {o.provenance && <MandateDrawer open={mandateOpen} onClose={() => setMandateOpen(false)} view={v} onWhy={setWhy} />}
    </div>
  );
}

function OrderActions({ view, onChanged }: { view: OrderView; onChanged: () => void }) {
  const { principal } = useSession(); const o = view.order;
  const [cancelOpen, setCancelOpen] = useState(false); const [reason, setReason] = useState('');
  const cancel = useMutation({ mutationFn: () => ordersApi.cancel(o.id, reason), onSuccess: () => { toast.ok('Order cancelled'); setCancelOpen(false); onChanged(); } });
  const resource = useMutation({ mutationFn: () => ordersApi.resource(o.id, 'Manual re-source from order view'), onSuccess: () => { toast.ok('Re-source requested', 'The workflow will re-run sourcing and re-promise.'); onChanged(); } });
  const trackingLink = useMutation({ mutationFn: () => ordersApi.trackingLink(o.id), onSuccess: async (l) => { const url = trackingUrlFor(l.token); try { await navigator.clipboard.writeText(url); toast.ok('Tracking link copied', `Customer page, valid until ${fmtDateTime(l.expiresAt)}`); } catch { toast.ok('Tracking link ready', url); } } });
  const terminal = ['delivered', 'cancelled'].includes(o.status);
  return (
    <div className="page-actions">
      {hasPermission(principal, 'order.read') && <Button icon={<IconCopy size={14} />} loading={trackingLink.isPending} onClick={() => trackingLink.mutate()} title="Copy a 30-day customer tracking link (no login needed)">Copy tracking link</Button>}
      {hasPermission(principal, 'order.modify') && !terminal && <Button onClick={() => resource.mutate()} loading={resource.isPending}>Re-source</Button>}
      {hasPermission(principal, 'order.cancel') && !terminal && <Button variant="danger" onClick={() => setCancelOpen(true)}>Cancel order</Button>}
      <Dialog open={cancelOpen} onClose={() => setCancelOpen(false)} title={`Cancel ${o.id}`} footer={<><Button onClick={() => setCancelOpen(false)}>Keep order</Button><Button variant="danger" disabled={!reason.trim()} loading={cancel.isPending} onClick={() => cancel.mutate()}>Cancel order</Button></>}>
        <div className="callout warn"><span>Cancelling a whole order is a <b>T3</b> action: it voids the authorization and releases reservations. Lines already shipped cannot be cancelled — create a return instead.</span></div>
        <Select label="Reason" value={reason} onChange={(e) => setReason(e.target.value)}><option value="">Select a reason…</option>{['customer_request', 'fraud_confirmed', 'out_of_stock', 'duplicate_order', 'pricing_error', 'other'].map((r) => <option key={r} value={r}>{humanize(r)}</option>)}</Select>
      </Dialog>
    </div>
  );
}

function IntentCard({ view, onWhy }: { view: OrderView; onWhy: (id: string) => void }) {
  const it = view.order.intent;
  return (
    <section className="card card-pad col">
      <div className="row-between"><h4 title="What the system believes the customer wants — e.g. fastest delivery or cheapest — inferred from their behaviour or explicit choices.">Intent profile</h4>{it?.decisionRecordId && <button className="btn btn-sm btn-ghost" onClick={() => onWhy(it.decisionRecordId!)}><IconWhy size={13} /> Why</button>}</div>
      {!it ? <div className="muted small">Not inferred yet — this fills in once the order's fulfilment intent is evaluated.</div> : <>
        <div style={{ fontSize: 'var(--fs-lg)', fontWeight: 600 }}>{humanize(it.policy)}</div>
        <div className="row gap3 small"><Badge tone={it.source === 'explicit' ? 'ok' : it.source === 'inferred' ? 'L1' : 'neutral'}>{it.source.replace('_', ' ')}</Badge><span className="muted">confidence</span><div className="bar" style={{ width: 80 }}><span style={{ width: `${it.confidence * 100}%` }} /></div><span className="num">{fmtConf(it.confidence)}</span></div>
        {it.explicitChoices && it.explicitChoices.length > 0 && <div className="xs muted">Buyer chose: {it.explicitChoices.join(', ')}</div>}
      </>}
      {view.risk && <><div className="divider" /><div className="row-between"><h4 title="How likely this order is to miss its delivery promise, scored 0 (none) to 4 (severe).">Breach risk</h4>{view.risk.decisionRecordId && <button className="btn btn-sm btn-ghost" onClick={() => onWhy(view.risk!.decisionRecordId!)}><IconWhy size={13} /> Why</button>}</div><RiskBar score={view.risk.score} level={view.risk.level} />{view.risk.reasons.length > 0 && <ul className="xs muted" style={{ margin: 0, paddingLeft: 16 }}>{view.risk.reasons.map((r) => <li key={r}>{r}</li>)}</ul>}</>}
    </section>
  );
}

function HoldsCard({ view, onChanged }: { view: OrderView; onChanged: () => void }) {
  const { principal } = useSession(); const [open, setOpen] = useState(false); const [type, setType] = useState('ops_review'); const [reasonCode, setReasonCode] = useState('manual'); const [note, setNote] = useState(''); const [blocks, setBlocks] = useState('release');
  const [releasing, setReleasing] = useState<string | null>(null); const [resolution, setResolution] = useState('');
  const place = useMutation({ mutationFn: () => ordersApi.placeHold(view.order.id, { type, blocks: blocks.split(',').map((s) => s.trim()).filter(Boolean), reasonCode, note, ttl: 'PT24H' }), onSuccess: () => { toast.ok('Hold placed'); setOpen(false); setNote(''); onChanged(); } });
  const release = useMutation({ mutationFn: (id: string) => holdsApi.release(id, resolution || 'released'), onSuccess: () => { toast.ok('Hold released'); setReleasing(null); setResolution(''); onChanged(); } });
  const active = view.holds.filter((h) => !h.resolvedAt); const resolved = view.holds.filter((h) => h.resolvedAt);
  const canHold = hasPermission(principal, 'order.hold'); const canRelease = hasPermission(principal, 'order.release');
  return (
    <section className="card card-pad col">
      <div className="row-between"><h4 title="A hold blocks specific transitions (like release or shipping) until someone resolves it.">Holds</h4>{canHold && !['delivered', 'cancelled'].includes(view.order.status) && <Button size="sm" onClick={() => setOpen(true)}>Place hold</Button>}</div>
      {active.length === 0 && <div className="muted small">No active holds — nothing is blocking this order from progressing{resolved.length ? ` · ${resolved.length} released` : ''}</div>}
      {active.map((h) => <div key={h.id} className="callout" style={{ borderColor: 'var(--st-hold)', background: 'var(--st-hold-soft)' }}><div className="col gap1 grow"><div className="row gap3"><Badge tone="hold">{humanize(h.type)}</Badge><span className="xs muted">blocks {h.blocksTransitions.join(', ')}</span></div><div className="small">{h.note || h.reasonCode}</div><div className="xs muted">by {h.placedBy} · {relTime(h.placedAt)}{h.expiresAt && <> · expires {relTime(h.expiresAt)}</>}</div></div>{canRelease && <Button size="sm" onClick={() => setReleasing(h.id)}>Release</Button>}</div>)}
      <Dialog open={open} onClose={() => setOpen(false)} title="Place hold" footer={<><Button onClick={() => setOpen(false)}>Cancel</Button><Button variant="primary" loading={place.isPending} onClick={() => place.mutate()}>Place hold</Button></>}>
        <Select label="Hold type" value={type} onChange={(e) => setType(e.target.value)}>{['ops_review', 'fraud_review', 'address_verification', 'payment_review', 'customer_request', 'compliance'].map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select>
        <Input label="Blocks (triggers or states, comma separated)" value={blocks} onChange={(e) => setBlocks(e.target.value)} mono hint="e.g. release, event.shipped" />
        <Input label="Reason code" value={reasonCode} onChange={(e) => setReasonCode(e.target.value)} mono />
        <Textarea label="Note" value={note} onChange={(e) => setNote(e.target.value)} rows={2} />
      </Dialog>
      <Dialog open={!!releasing} onClose={() => setReleasing(null)} title="Release hold" footer={<><Button onClick={() => setReleasing(null)}>Cancel</Button><Button variant="primary" loading={release.isPending} disabled={!resolution.trim()} onClick={() => release.mutate(releasing!)}>Release</Button></>}>
        <Textarea label="Resolution (required)" value={resolution} onChange={(e) => setResolution(e.target.value)} rows={2} placeholder="What was verified or decided" />
      </Dialog>
    </section>
  );
}

function TotalsCard({ view }: { view: OrderView }) {
  const t = view.order.totals;
  return <section className="card card-pad col"><h4>Totals</h4><KeyValue tight items={[['Subtotal', fmtMoney(t.merchandise)], ['Discounts', `−${fmtMoney(t.discounts)}`], ['Shipping', fmtMoney(t.shipping)], ['Tax', fmtMoney(t.tax)], [<b>Total</b>, <b>{fmtMoney(t.grandTotal)}</b>], ['Tender', (view.payments?.length ? view.payments.map((p) => `${p.tenderType ?? p.type ?? 'payment'} ${String(p.status ?? '').toLowerCase()}`) : view.order.tenders?.map((x) => x.type))?.join(', ') || '—']]} /></section>;
}

function MarginCard({ view }: { view: OrderView }) {
  const m = view.order.margin;
  if (!m) return <section className="card card-pad col"><h4>Margin</h4><div className="muted small">No margin snapshot yet — this is calculated once the order is sourced and priced.</div></section>;
  const scale = Math.max(0.4, m.marginRatio + 0.1, m.floor + 0.1);
  return (
    <section className="card card-pad col">
      <div className="row-between"><h4 title="Contribution margin after fulfilment costs, versus the floor policy requires.">Margin snapshot</h4>{!m.belowFloor ? <Badge tone="ok">above floor</Badge> : m.approvalPresent ? <Badge tone="warn">below floor · approved{m.approvalTaskId ? ` (task ${m.approvalTaskId})` : ""}</Badge> : <Badge tone="danger">below floor · needs approval</Badge>}</div>
      <div className="row gap3" style={{ alignItems: 'baseline' }}><span style={{ fontSize: 'var(--fs-xl)', fontWeight: 650 }} className="num">{fmtPct(m.marginRatio)}</span><span className="muted small">contribution margin {fmtUsd(m.contributionMargin)}</span></div>
      <div className="margin-viz" role="img" aria-label={`contribution margin ${fmtPct(m.marginRatio)} versus floor ${fmtPct(m.floor)}`}>
        <div className={['fill', !!m.belowFloor && 'below'].filter(Boolean).join(' ')} style={{ width: `${Math.max(0, Math.min(100, (m.marginRatio / scale) * 100))}%` }} />
        <div className="floor" style={{ left: `${(m.floor / scale) * 100}%` }} /><div className="floor-lbl" style={{ left: `${(m.floor / scale) * 100}%` }}>floor {fmtPct(m.floor, 0)}</div>
      </div>
      <span className="xs faint">The floor is the lowest margin policy allows before this order needs a human sign-off.</span>
      {m.pnl && <KeyValue tight items={[['Revenue', fmtUsd(m.pnl.revenue)], ['COGS', `−${fmtUsd(m.pnl.cogs)}`], ['Shipping', `−${fmtUsd(m.pnl.shipping)}`], ['Labour + pack', `−${fmtUsd(m.pnl.labour + m.pnl.packaging)}`], ['Fees + reserve', `−${fmtUsd(m.pnl.paymentFees + m.pnl.returnsReserve)}`]]} />}
    </section>
  );
}

function LinesTab({ view }: { view: OrderView }) {
  const o = view.order; const groups = view.fulfilmentGroups ?? [];
  const statusFor = (lineId: string): EntityStatus | undefined => view.lineStatuses.find((ls) => ls.entityId.endsWith(`:${lineId}`));
  return (
    <div className="col gap3">
      {o.lines.map((l) => { const st = statusFor(l.lineId); const g = groups.find((x) => x.id === l.fulfilmentGroupId); const attrs = l.attributes ?? {}; return (
        <div key={l.lineId} className="line-card">
          <div className="col gap1"><div className="row gap3"><span className="mono strong">{l.lineId}</span><span className="mono small">{l.sku}</span></div><div className="small">{l.description}</div><div className="pill-row">{attrs.hazmat ? <Badge tone="warn">hazmat</Badge> : null}{attrs.age_restricted ? <Badge tone="warn">age restricted</Badge> : null}{attrs.oversize ? <Badge tone="outline">oversize</Badge> : null}</div></div>
          <div className="col gap1"><span className="xs muted">Quantity status · {l.qty} unit{l.qty > 1 ? 's' : ''}</span><div className="line-status">{st ? st.quantities.map((qb) => <Badge key={qb.state + qb.since} tone={statusTone(qb.state)} dot title={`since ${fmtDateTime(qb.since)}`}>{qb.qty} {humanize(qb.state)}</Badge>) : <Badge tone="neutral">{l.qty} created</Badge>}</div>{st && <span className="xs muted mono">{st.modelId}@{st.modelVersion}</span>}</div>
          <div className="col gap1"><span className="xs muted">Fulfilment</span>{g ? <><span className="small"><span className="mono">{g.id}</span> · <Link to={`/orders?nodeId=${g.nodeId}`} className="mono">{g.nodeId}</Link></span><span className="xs muted">{g.shipMethod}</span></> : <span className="small muted">not sourced</span>}</div>
          <div className="col gap1"><span className="xs muted">Promise</span>{g?.promiseDate ? <><span className="small strong">{g.promiseDate}</span><span className="row gap1 xs"><span className="bar" style={{ width: 54 }}><span style={{ width: `${(g.onTimeProbability ?? 0) * 100}%` }} /></span><span className="num muted">{fmtPct(g.onTimeProbability, 0)} on time</span></span></> : <span className="small muted">—</span>}<span className="xs muted">{fmtMoney(l.unitPrice)} × {l.qty}</span></div>
        </div>); })}
      {o.notes && o.notes.length > 0 && <div className="callout"><div className="col gap1"><span className="xs strong muted" title="Shown to you as reference text only — the workflow never treats customer notes as instructions (GR-08).">CUSTOMER NOTES · shown as data, never acted on automatically</span>{o.notes.map((n, i) => <div key={i} className="small">“{n.text}” <span className="xs muted">— {n.by} via {n.source ?? o.channel}</span></div>)}</div></div>}
    </div>
  );
}

type Merged = { at: string; kind: 'ledger' | 'workflow' | 'decision' | 'error' | 'task' | 'timer' | 'pending'; name: string; detail?: string; status?: string; recordId?: string; data?: unknown };
function TimelineTab({ view, onWhy }: { view: OrderView; onWhy: (id: string) => void }) {
  const [filter, setFilter] = useState<Set<string>>(new Set(['ledger', 'workflow', 'decision', 'task', 'error', 'timer', 'pending']));
  const items = useMemo<Merged[]>(() => {
    const out: Merged[] = [];
    for (const e of view.ledger ?? []) out.push({ at: e.at, kind: 'ledger', name: e.type, detail: e.actor, data: e.data });
    for (const t of (view.timeline ?? []) as TimelineEntry[]) { if (t.kind === 'activity' && String(t.name).startsWith('decision:')) continue; out.push({ at: t.at, kind: t.kind === 'error' ? 'error' : t.kind === 'task' ? 'task' : t.kind === 'timer' ? 'timer' : 'workflow', name: `${t.kind} · ${t.name}`, status: t.status, data: t.details }); }
    for (const d of view.decisions ?? []) out.push({ at: d.evaluatedAt, kind: 'decision', name: `decision · ${d.decision}`, detail: `${valueLabel(d.value)} · ${d.band.toLowerCase()} · ${fmtConf(d.confidence)}`, recordId: d.id, status: d.layer });
    out.sort((a, b) => a.at.localeCompare(b.at));
    if (view.nextExpectedEvent) out.push({ at: view.nextExpectedEvent.expectedBy, kind: 'pending', name: `awaiting ${view.nextExpectedEvent.name}`, detail: `expected by ${fmtDateTime(view.nextExpectedEvent.expectedBy)}`, status: 'pending' });
    return out;
  }, [view]);
  const toggle = (k: string) => setFilter((s) => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const visible = items.filter((i) => filter.has(i.kind));
  return (
    <div className="col gap3">
      <div className="row wrap gap1" role="group" aria-label="Timeline filters">{[['ledger', 'Ledger events'], ['workflow', 'Workflow'], ['decision', 'Decisions'], ['task', 'Tasks'], ['timer', 'Timers'], ['error', 'Errors'], ['pending', 'Pending']].map(([k, l]) => <button key={k} className="btn btn-sm" aria-pressed={filter.has(k)} style={filter.has(k) ? { background: 'var(--accent-soft)', color: 'var(--accent)', borderColor: 'var(--accent)' } : undefined} onClick={() => toggle(k)}>{l}</button>)}<span className="grow" />{view.workflow?.nextWakeAt && <span className="small muted">Next timer <Countdown due={view.workflow.nextWakeAt} /></span>}</div>
      {visible.length === 0 && <EmptyState title="Nothing to show" hint="Adjust the filters above." />}
      <ol className="timeline" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {visible.map((it, i) => <li key={i} className={`tl-item k-${it.kind} ${it.kind === 'pending' ? 'pending' : ''}`}>
          <span className="when" title={it.at}>{fmtDateTime(it.at)}</span>
          <span className="rail"><span className="dotp" aria-hidden /></span>
          <div className="body">
            <div className="name"><span className="mono">{it.name}</span>{it.kind === 'decision' && <LayerBadge layer={it.status} />}{it.kind === 'pending' && <Badge tone="warn">next expected</Badge>}{it.status && it.kind !== 'decision' && it.kind !== 'pending' && <Badge tone={statusTone(it.status)}>{it.status}</Badge>}{it.recordId && <button className="btn btn-sm btn-ghost" onClick={() => onWhy(it.recordId!)}><IconWhy size={13} /> Why</button>}</div>
            {it.detail && <div className="det">{it.detail}</div>}
            {!!it.data && Object.keys(it.data as object).length > 0 && <details className="det"><summary style={{ cursor: 'pointer' }}>details</summary><JsonTree data={it.data} defaultOpen={1} /></details>}
          </div>
        </li>)}
      </ol>
    </div>
  );
}

function DecisionsTab({ decisions, onWhy }: { decisions: DecisionRecord[]; onWhy: (id: string) => void }) {
  if (!decisions.length) return <EmptyState title="No decisions recorded yet" hint="Every automated choice the workflow makes — intent, sourcing, risk, pricing — is recorded here with its reasoning as it runs." />;
  return <Table rows={[...decisions].reverse()} rowKey={(d) => d.id} onRowClick={(d) => onWhy(d.id)} caption="Decision records" columns={[
    { key: 'd', header: 'Decision', render: (d) => <span className="col gap1"><span className="strong">{humanize(d.decision)}</span><span className="xs muted mono">v{d.version} · {d.id}</span></span> },
    { key: 'v', header: 'Value', render: (d) => <span className="mono">{valueLabel(d.value)}{d.overridden && <Badge tone="warn" style={{ marginLeft: 6 }} title="A person or a later decision changed this from its original value.">overridden → {valueLabel(d.overrideValue)}</Badge>}</span> },
    { key: 'layer', header: <span title="Which part of the system decided: L0 rules, L1 Jev, L2 an LLM, or L3 a person.">Layer</span>, render: (d) => <LayerBadge layer={d.layer} /> },
    { key: 'band', header: <span title="How confidently it could act: ACT = applied automatically, CONFIRM = a quick human check first, ESCALATE = routed to a person.">Band</span>, render: (d) => <BandBadge band={d.band} /> },
    { key: 'conf', header: 'Confidence', width: 150, render: (d) => <span className="row gap1"><span className="bar" style={{ width: 70 }}><span style={{ width: `${d.confidence * 100}%` }} /></span><span className="num small">{fmtConf(d.confidence)}</span></span> },
    { key: 'prov', header: 'Provider', render: (d) => <span className="small">{d.provider}{d.modelVersion && <span className="muted"> · {d.modelVersion}</span>}</span> },
    { key: 'lat', header: 'Latency', align: 'right', render: (d) => <span className="num">{d.latencyMs} ms</span> }, { key: 'cost', header: 'Cost', align: 'right', render: (d) => <span className="num">{fmtUsd(d.costUsd, 4)}</span> },
    { key: 'at', header: 'When', render: (d) => relTime(d.evaluatedAt) }, { key: 'why', header: '', width: 70, render: (d) => <button className="btn btn-sm btn-ghost" onClick={(e) => { e.stopPropagation(); onWhy(d.id); }}><IconWhy size={13} /> Why</button> },
  ]} />;
}

function ModifyTab({ view, onChanged }: { view: OrderView; onChanged: () => void }) {
  const { principal } = useSession(); const qc = useQueryClient(); const o = view.order;
  const [type, setType] = useState<ModificationType>('ChangeAddress'); const [lineId, setLineId] = useState(o.lines[0]?.lineId ?? ''); const [qty, setQty] = useState(o.lines[0]?.qty ?? 1); const [shipMethod, setShipMethod] = useState('2day'); const [newSku, setNewSku] = useState('');
  const [addr, setAddr] = useState({ line1: o.addresses?.[0]?.line1 ?? '', city: o.addresses?.[0]?.city ?? '', state: o.addresses?.[0]?.state ?? '', zip: o.addresses?.[0]?.zip ?? '' });
  const [reason, setReason] = useState(''); const [feas, setFeas] = useState<Feasibility | null>(null);
  const mod = (): Modification => ({ type, lineId: ['ChangeQty', 'CancelLine', 'ChangeItem'].includes(type) ? lineId : undefined, qty: type === 'ChangeQty' ? qty : undefined, shipMethod: type === 'ChangeShipMethod' ? shipMethod : undefined, newSku: type === 'ChangeItem' ? newSku : undefined, address: type === 'ChangeAddress' ? { type: 'ship_to', ...addr, country: 'US' } : undefined });
  const check = useMutation({ mutationFn: () => ordersApi.checkModification(o.id, mod()), onSuccess: setFeas });
  const apply = useMutation({ mutationFn: () => ordersApi.applyModification(o.id, mod(), reason), onSuccess: () => { toast.ok('Modification applied', 'The workflow was signalled with order.modified'); setFeas(null); setReason(''); qc.invalidateQueries({ queryKey: ['order', o.id] }); onChanged(); } });
  const canModify = hasPermission(principal, 'order.modify'); const terminal = ['delivered', 'cancelled'].includes(o.status);
  if (terminal) return <EmptyState title={`Order is ${humanize(o.status)} — no further changes`} hint="A delivered or cancelled order can't be modified." action={o.status === 'delivered' ? <Link to="/returns" className="btn btn-sm">Start a return instead</Link> : undefined} />;
  return (
    <div className="two-col">
      <div className="col gap3">
        <div className="callout info"><span>Every change is checked against the order's current state before anything happens, showing its cost and approval needs first — nothing is applied until you confirm. Changes after release may need a carrier intercept or approval. (FR-CS-02)</span></div>
        <div className="form-grid">
          <Select label="Modification" value={type} onChange={(e) => { setType(e.target.value as ModificationType); setFeas(null); }}>{(['ChangeAddress', 'ChangeQty', 'CancelLine', 'ChangeShipMethod', 'ChangeItem', 'CancelOrder'] as ModificationType[]).map((t) => <option key={t} value={t}>{t.replace(/([A-Z])/g, ' $1').trim()}</option>)}</Select>
          {['ChangeQty', 'CancelLine', 'ChangeItem'].includes(type) && <Select label="Line" value={lineId} onChange={(e) => { setLineId(e.target.value); setFeas(null); }}>{o.lines.map((l) => <option key={l.lineId} value={l.lineId}>{l.lineId} · {l.sku} ×{l.qty}</option>)}</Select>}
          {type === 'ChangeQty' && <Input label="New quantity" type="number" min={0} value={qty} onChange={(e) => { setQty(Number(e.target.value)); setFeas(null); }} />}
          {type === 'ChangeShipMethod' && <Select label="Ship method" value={shipMethod} onChange={(e) => { setShipMethod(e.target.value); setFeas(null); }}>{['ground', '2day', 'overnight'].map((m) => <option key={m} value={m}>{m}</option>)}</Select>}
          {type === 'ChangeItem' && <Input label="Replacement SKU" value={newSku} onChange={(e) => { setNewSku(e.target.value); setFeas(null); }} mono />}
          {type === 'ChangeAddress' && <><Input label="Address line 1" className="span2" value={addr.line1} onChange={(e) => setAddr({ ...addr, line1: e.target.value })} /><Input label="City" value={addr.city} onChange={(e) => setAddr({ ...addr, city: e.target.value })} /><div className="row gap3"><Input label="State" value={addr.state} onChange={(e) => setAddr({ ...addr, state: e.target.value })} maxLength={2} /><Input label="ZIP" value={addr.zip} onChange={(e) => setAddr({ ...addr, zip: e.target.value })} /></div></>}
        </div>
        <div className="row gap3"><Button variant="primary" onClick={() => check.mutate()} loading={check.isPending} disabled={!canModify}>Check feasibility</Button>{!canModify && <span className="small muted">Requires order.modify</span>}</div>
      </div>
      <div className="card card-pad col sticky-side">
        <div className="section-title">Feasibility & cost of change</div>
        {!feas && <div className="muted small">Run a check to see whether the change is allowed from the current state, what it costs, and whether it needs approval.</div>}
        {feas && <>
          <div className="row gap3">{feas.allowed ? <Badge tone="ok" lg>allowed</Badge> : <Badge tone="danger" lg>not allowed</Badge>}{feas.requiresApproval && <Badge tone="warn" lg>needs approval</Badge>}{feas.carrierIntercept && <Badge tone="info" lg>carrier intercept</Badge>}</div>
          <KeyValue tight items={[['Cost of change', feas.costDelta ? <b className={feas.costDelta.amount > 0 ? '' : 'muted'}>{feas.costDelta.amount >= 0 ? '+' : ''}{fmtMoney(feas.costDelta)}</b> : '—'], ['New promise', feas.newPromise ? `${feas.newPromise.date} · ${fmtPct(feas.newPromise.onTimeProbability, 0)} on time` : 'unchanged'], ['Affected lines', feas.affectedLines?.join(', ') || '—']]} />
          <ul className="small" style={{ margin: 0, paddingLeft: 16 }}>{feas.reasons.map((r) => <li key={r}>{r}</li>)}</ul>
          {feas.allowed && <><Textarea label="Reason (required)" value={reason} onChange={(e) => setReason(e.target.value)} rows={2} /><Button variant={type.startsWith('Cancel') ? 'danger' : 'primary'} disabled={!reason.trim()} loading={apply.isPending} onClick={() => apply.mutate()}>Apply change</Button></>}
        </>}
      </div>
    </div>
  );
}

function MandateDrawer({ open, onClose, view, onWhy }: { open: boolean; onClose: () => void; view: OrderView; onWhy: (id: string) => void }) {
  const p = view.order.provenance!;
  const mandate = useQuery({ queryKey: ['mandate', p.mandateId], queryFn: () => agentsApi.mandate(p.mandateId!), enabled: open && !!p.mandateId });
  const pack = useQuery({ queryKey: ['evidence', view.order.id], queryFn: () => ordersApi.evidencePack(view.order.id), enabled: open });
  const [showPack, setShowPack] = useState(false);
  return (
    <Drawer open={open} onClose={onClose} title="Agent provenance" subtitle={<span className="row gap3"><Badge tone="L2">{p.protocol.toUpperCase()}</Badge><Link to="/agents" className="mono">{p.agentId}</Link></span>} width={620}>
      <section><div className="section-title">Instruction (untrusted text)</div><div className="callout"><span>“{p.instructionText}”</span></div></section>
      <section><div className="section-title">Mandate</div>
        {mandate.isLoading && <Loading inline />}
        {mandate.data && <KeyValue tight items={[['Mandate', <span className="mono">{mandate.data.id}</span>], ['Buyer', <span className="mono">{mandate.data.buyerRef}</span>], ['Scope', mandate.data.scope.join(', ')], ['Spend limit', <b>{fmtMoney(mandate.data.spendLimit)}</b>], ['Order total', <span className={view.order.totals.grandTotal.amount > mandate.data.spendLimit.amount ? 'strong' : ''} style={view.order.totals.grandTotal.amount > mandate.data.spendLimit.amount ? { color: 'var(--danger)' } : undefined}>{fmtMoney(view.order.totals.grandTotal)}</span>], ['Issued', fmtDateTime(mandate.data.issuedAt)], ['Expires', fmtDateTime(mandate.data.expiresAt)], ['Status', <StatusBadge status={mandate.data.status} />], ['Signature', <span className="mono small">{mandate.data.signature ?? p.signature ?? '—'}</span>]]} />}
        {mandate.isError && <div className="muted small">Mandate not found</div>}
      </section>
      <section><div className="row-between"><div className="section-title" style={{ margin: 0 }}>Evidence pack (FR-AGT-09)</div><div className="row">{pack.data && <CopyButton text={safeJson(pack.data)} label="Copy JSON" />}<Button size="sm" onClick={() => setShowPack((s) => !s)}>{showPack ? 'Hide raw JSON' : 'Show raw JSON'}</Button></div></div>
        {pack.isLoading && <Loading inline />}
        {pack.data && <div className="col gap3 mt3">
          <KeyValue tight items={[['Generated', fmtDateTime(pack.data.generatedAt)], ['Injection screen', pack.data.injectionScreen ? <span className="row gap1">{pack.data.injectionScreen.flagged ? <Badge tone="danger">flagged</Badge> : <Badge tone="ok">clean</Badge>}<span className="muted small">p={pack.data.injectionScreen.probability}</span>{pack.data.injectionScreen.recordId && <button className="btn btn-sm btn-ghost" onClick={() => onWhy(pack.data!.injectionScreen!.recordId!)}>Why</button>}</span> : '—'], ['Delivery proof', pack.data.deliveryProof ? safeJson(pack.data.deliveryProof, 0) : 'not yet delivered'], ['Pack hash', <span className="mono small">{pack.data.hash}</span>]]} />
          <div className="section-title">Decision trail ({pack.data.decisionTrail.length})</div>
          <div className="subtle-list small">{pack.data.decisionTrail.map((d) => <div key={d.id} className="row gap3"><span className="mono">{d.decision}</span><LayerBadge layer={d.layer} /><BandBadge band={d.band} /><span className="mono muted">{valueLabel(d.value)}</span><span className="grow" /><button className="btn btn-sm btn-ghost" onClick={() => onWhy(d.id)}>Why</button></div>)}</div>
          {showPack && <pre className="code" style={{ maxHeight: 360 }}>{safeJson(pack.data)}</pre>}
        </div>}
      </section>
      <section><div className="section-title">Agent identity</div><div className="row gap3"><TierBadge tier={undefined} /><Link to="/agents" className="small">Open agent registry →</Link></div></section>
    </Drawer>
  );
}
