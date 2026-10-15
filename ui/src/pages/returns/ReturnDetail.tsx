import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { returnsApi } from '../../api/returns';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select } from '../../components/Form';
import { KeyValue, ScorePips } from '../../components/Misc';
import { Table } from '../../components/Table';
import { ErrorState, Loading, Section } from '../../components/States';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { fmtDateTime, fmtMoney, humanize } from '../../lib/format';
import { hasPermission, useSession } from '../../auth/session';
import { toast } from '../../lib/toast';
import { IconReturn, IconWhy } from '../../components/Icons';

export default function ReturnDetail() {
  const { rma = '' } = useParams(); const qc = useQueryClient(); const { principal } = useSession();
  const q = useQuery({ queryKey: ['return', rma], queryFn: () => returnsApi.get(rma) });
  const [why, setWhy] = useState<string | null>(null); const [condition, setCondition] = useState('like_new');
  const refresh = () => { qc.invalidateQueries({ queryKey: ['return', rma] }); qc.invalidateQueries({ queryKey: ['returns'] }); };
  const useAct = (label: string, fn: () => Promise<unknown>) => useMutation({ mutationFn: fn, onSuccess: () => { toast.ok(label); refresh(); } });
  const decide = useAct('Eligibility and refund timing decided', () => returnsApi.decide(rma));
  const scan = useAct('Carrier scan recorded', () => returnsApi.scan(rma));
  const inspect = useAct('Inspection recorded', () => returnsApi.inspect(rma, condition));
  const dispo = useAct('Disposition decided', () => returnsApi.disposition(rma));
  const refund = useAct('Refund issued', () => returnsApi.refund(rma));
  if (q.isLoading) return <div className="page"><Loading /></div>;
  if (q.isError || !q.data) return <div className="page"><ErrorState error={q.error} retry={() => q.refetch()} /></div>;
  const r = q.data; const canDecide = hasPermission(principal, 'return.decide'); const canRefund = hasPermission(principal, 'refund.issue') || hasPermission(principal, 'refund.approve');
  const terminal = ['refunded', 'denied', 'closed_keep_it'].includes(r.status);
  return (
    <div className="page">
      <nav className="small muted" aria-label="Breadcrumb"><Link to="/returns">Returns</Link> / <span className="mono">{r.rma}</span></nav>
      <div className="page-head"><div>
          <div className="order-title"><span className="icon-badge" aria-hidden="true"><IconReturn /></span><h1 className="mono" style={{ fontSize: 'var(--fs-xl)' }}>{r.rma}</h1><StatusBadge status={r.status} lg /><Badge tone="outline" lg>{r.channel}</Badge><span className="muted">for order <Link to={`/orders/${r.orderId}`} className="mono">{r.orderId}</Link></span></div>
          <div className="sub">This return's full lifecycle — eligibility, abuse risk, refund timing and disposition — each decided automatically and explained below.</div>
        </div>
        <div className="page-actions">
          {!terminal && (r.status === 'requested' || r.status === 'eligibility_review') && <Button variant="primary" disabled={!canDecide} onClick={() => decide.mutate()} loading={decide.isPending} title={canDecide ? undefined : 'Requires return.decide'}>Decide eligibility</Button>}
          {!terminal && r.status === 'approved' && <Button onClick={() => scan.mutate()} loading={scan.isPending}>Record carrier scan</Button>}
          {!terminal && (r.status === 'in_transit' || r.status === 'received') && <span className="row gap1"><Select aria-label="Condition" sm value={condition} onChange={(e) => setCondition(e.target.value)}>{['like_new', 'used', 'damaged', 'defective', 'wrong_item'].map((c) => <option key={c} value={c}>{humanize(c)}</option>)}</Select><Button onClick={() => inspect.mutate()} loading={inspect.isPending}>Record inspection</Button></span>}
          {!terminal && r.status === 'inspected' && !r.dispositionDecisionRecordId && <Button onClick={() => dispo.mutate()} loading={dispo.isPending}>Decide disposition</Button>}
          {!terminal && ['approved', 'in_transit', 'received', 'inspected'].includes(r.status) && <Button variant="primary" disabled={!canRefund} onClick={() => refund.mutate()} loading={refund.isPending} title={canRefund ? undefined : 'Requires refund.issue'}>Issue refund {fmtMoney(r.refundAmount)}</Button>}
        </div></div>
      <div className="order-grid">
        <section className="card card-pad col"><h4>Request</h4><KeyValue tight items={[['Reason', humanize(r.reason)], ['Created', `${fmtDateTime(r.createdAt)} by ${r.createdBy ?? '—'}`], ['Refund value', <b>{fmtMoney(r.refundAmount)}</b>], ['Carrier scan', r.scannedAt ? fmtDateTime(r.scannedAt) : '—'], ['Inspected', r.inspectedAt ? fmtDateTime(r.inspectedAt) : '—']]} /></section>
        <section className="card card-pad col"><div className="row-between"><h4 title="Likelihood this return is abusive — serial returns, mismatched reason, wardrobing — scored 0 (none) to 4 (severe).">Abuse risk</h4>{r.riskDecisionRecordId && <Button size="sm" variant="ghost" icon={<IconWhy size={13} />} onClick={() => setWhy(r.riskDecisionRecordId!)}>Why</Button>}</div>
          {r.riskScore === null || r.riskScore === undefined ? <div className="muted small">Not yet assessed — click "Decide eligibility" above to run the risk check.</div> : <><div className="row gap3"><ScorePips value={r.riskScore} /><b>{r.riskScore}/4</b><span className="muted small">{humanize(r.riskLevel)}</span></div>
            <div className="section-title mt2" title="Nouls are the system's short, auditable reason codes — each tagged with how confident it is that the signal applies.">Reason codes (Nouls)</div><div className="pill-row">{(r.reasonCodes ?? []).length === 0 && <span className="xs muted">none</span>}{(r.reasonCodes ?? []).map((c) => <Badge key={c.code} tone={c.probability >= 0.6 ? 'danger' : c.probability >= 0.4 ? 'warn' : 'outline'} title={`p=${c.probability}`}>{humanize(c.code)} · {(c.probability * 100).toFixed(0)}%</Badge>)}</div></>}
          {r.eligibility && <><div className="divider" /><div className="row gap3">{r.eligibility.eligible ? <Badge tone="ok">eligible</Badge> : <Badge tone="danger">not eligible</Badge>}<span className="xs muted">window {r.eligibility.windowDays}d</span></div><ul className="xs muted" style={{ margin: 0, paddingLeft: 16 }}>{r.eligibility.reasons.map((x) => <li key={x}>{x}</li>)}</ul></>}
        </section>
        <section className="card card-pad col"><div className="row-between"><h4 title="When the refund actually reaches the customer: instantly, on carrier scan, after inspection, or escalated to a person.">Refund timing</h4>{r.refundTimingDecisionRecordId && <Button size="sm" variant="ghost" icon={<IconWhy size={13} />} onClick={() => setWhy(r.refundTimingDecisionRecordId!)}>Why</Button>}</div>
          {r.refundTiming ? <><div style={{ fontSize: 'var(--fs-lg)', fontWeight: 600 }}>{humanize(r.refundTiming)}</div><div className="xs muted">{({ instant: 'Refund issued immediately', on_carrier_scan: 'Refund when the carrier scans the package', on_inspection: 'Refund after warehouse inspection', store_credit_only: 'Store credit instead of refund', escalate: 'A person decides' } as Record<string, string>)[r.refundTiming] ?? ''}</div></> : <div className="muted small">Pending decision — resolved once eligibility is decided.</div>}
          {r.refund && <><div className="divider" /><KeyValue tight items={[['Refunded', fmtMoney(r.refund.amount)], ['Restocking fee', fmtMoney(r.refund.restockingFee)], ['Status', <StatusBadge status={r.refund.status} />], ['Issued', fmtDateTime(r.refund.issuedAt)]]} /></>}
          {r.keepIt?.recommended && <div className="callout ok mt2"><span>Keep-it recommended: return shipping would cost {fmtMoney(r.keepIt.returnCost)} but the item is only worth {fmtMoney(r.keepIt.recovery)} once it's back, and abuse risk is low — so the customer keeps the item and still gets refunded. (FR-RET-05)</span></div>}
        </section>
        <section className="card card-pad col"><div className="row-between"><h4 title="What happens to a returned item after inspection: restock, liquidate, destroy, or return to vendor.">Disposition</h4>{r.dispositionDecisionRecordId && <Button size="sm" variant="ghost" icon={<IconWhy size={13} />} onClick={() => setWhy(r.dispositionDecisionRecordId!)}>Why</Button>}</div>
          {r.lines.some((l) => l.disposition) ? r.lines.map((l) => <div key={l.lineId} className="row gap3 small"><span className="mono">{l.sku}</span><Badge tone="info">{humanize(l.disposition)}</Badge></div>) : <div className="muted small">Decided automatically after inspection — restock, liquidate, destroy, or return to vendor.</div>}
        </section>
      </div>
      <Section title="Lines" tight><Table rows={r.lines} rowKey={(l) => l.lineId} columns={[{ key: 'l', header: 'Line', render: (l) => <span className="mono">{l.lineId}</span> }, { key: 'sku', header: 'SKU', render: (l) => <span className="col gap1"><span className="mono">{l.sku}</span><span className="xs muted">{l.description}</span></span> }, { key: 'qty', header: 'Qty', align: 'right', render: (l) => l.qty }, { key: 'reason', header: 'Reason', render: (l) => humanize(l.reason) }, { key: 'cond', header: 'Condition', render: (l) => l.condition ? humanize(l.condition) : '—' }, { key: 'disp', header: 'Disposition', render: (l) => l.disposition ? humanize(l.disposition) : '—' }]} /></Section>
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </div>
  );
}
