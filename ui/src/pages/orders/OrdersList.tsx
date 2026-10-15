import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ordersApi } from '../../api/orders';
import { nodesApi } from '../../api/inventory';
import type { BulkAction, BulkOperation, OrderQuery } from '../../api/types';
import { Table, Pagination } from '../../components/Table';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Select, Textarea } from '../../components/Form';
import { Dialog } from '../../components/Overlay';
import { EmptyState, ErrorState, Loading } from '../../components/States';
import { fmtDateTime, fmtMoney, humanize, relTime } from '../../lib/format';
import { useDebounced } from '../../lib/hooks';
import { hasPermission, useSession } from '../../auth/session';
import { toast } from '../../lib/toast';
import { IconOrders, IconSearch } from '../../components/Icons';

const STATUSES = ['created', 'validated', 'sourcing', 'awaiting_sourcing', 'released', 'picking', 'packed', 'backordered', 'partially_shipped', 'shipped', 'delivered', 'cancelled'];
const CHANNELS = ['web', 'shopify', 'amazon', 'pos', 'agent', 'edi'];
const PAGE = 25;

export default function OrdersList() {
  const [sp, setSp] = useSearchParams(); const navigate = useNavigate(); const { principal } = useSession();
  const get = (k: string) => sp.get(k) ?? '';
  const set = (k: string, v: string) => setSp((prev) => { const n = new URLSearchParams(prev); if (v) n.set(k, v); else n.delete(k); if (k !== 'offset') n.delete('offset'); return n; }, { replace: true });
  const [text, setText] = useState(get('q')); const dq = useDebounced(text, 300);
  const offset = Number(get('offset') || 0);
  const query: OrderQuery & { ageBucket?: string; open?: string } = useMemo(() => ({ q: dq || undefined, status: get('status') || undefined, channel: get('channel') || undefined, orderType: get('orderType') || undefined, nodeId: get('nodeId') || undefined, placedFrom: get('from') || undefined, placedTo: get('to') || undefined, atRisk: get('atRisk') === 'true' || undefined, ageBucket: get('ageBucket') || undefined, offset, limit: PAGE }), [dq, sp, offset]); // eslint-disable-line react-hooks/exhaustive-deps
  const q = useQuery({ queryKey: ['orders', query], queryFn: () => ordersApi.search(query as OrderQuery), placeholderData: (prev) => prev });
  const nodes = useQuery({ queryKey: ['nodes'], queryFn: nodesApi.list, staleTime: 60000 });
  const types = useQuery({ queryKey: ['order-types'], queryFn: ordersApi.types, staleTime: 60000 });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulk, setBulk] = useState<BulkAction | null>(null);
  const canBulk = hasPermission(principal, 'order.bulk');
  const toggle = (id: string) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const toggleAll = (ids: string[]) => setSelected((s) => { const all = ids.every((i) => s.has(i)); const n = new Set(s); ids.forEach((i) => (all ? n.delete(i) : n.add(i))); return n; });
  const activeFilters = ['status', 'channel', 'orderType', 'nodeId', 'from', 'to', 'atRisk', 'ageBucket'].filter((k) => get(k));
  return (
    <div className="page">
      <div className="page-head">
        <div className="row gap3" style={{ alignItems: 'flex-start' }}>
          <span className="icon-badge" aria-hidden="true"><IconOrders /></span>
          <div>
            <h1>Orders</h1>
            <div className="sub">{q.data ? `${q.data.total.toLocaleString()} orders across every channel — filter to narrow down, or open one to act on it.` : 'Every order placed, on any channel, lands here — search, inspect, and act on one order or many at once.'}</div>
          </div>
        </div>
      </div>
      <div className="card">
        <div className="toolbar">
          <div className="search grow" style={{ maxWidth: 360 }}><IconSearch size={14} /><input className="input" placeholder="Order id, channel order id, customer, SKU" value={text} onChange={(e) => { setText(e.target.value); set('q', e.target.value); }} aria-label="Search orders" /></div>
          <Select aria-label="Status" sm value={get('status')} onChange={(e) => set('status', e.target.value)}><option value="">Any status</option>{STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}</Select>
          <Select aria-label="Channel" sm value={get('channel')} onChange={(e) => set('channel', e.target.value)}><option value="">Any channel</option>{CHANNELS.map((s) => <option key={s} value={s}>{s}</option>)}</Select>
          <Select aria-label="Order type" sm value={get('orderType')} onChange={(e) => set('orderType', e.target.value)}><option value="">Any type</option>{(types.data ?? []).map((t) => <option key={t.name} value={t.name}>{humanize(t.name)}</option>)}</Select>
          <Select aria-label="Node" sm value={get('nodeId')} onChange={(e) => set('nodeId', e.target.value)}><option value="">Any node</option>{(nodes.data ?? []).map((n) => <option key={n.id} value={n.id}>{n.id}</option>)}</Select>
          <Input aria-label="Placed from" type="date" sm value={get('from')} onChange={(e) => set('from', e.target.value)} />
          <Input aria-label="Placed to" type="date" sm value={get('to')} onChange={(e) => set('to', e.target.value)} />
          <label className="checkbox small" title="Flagged by the breach-risk decision as likely to miss its delivery promise"><input type="checkbox" checked={get('atRisk') === 'true'} onChange={(e) => set('atRisk', e.target.checked ? 'true' : '')} /> At risk</label>
          {get('ageBucket') && <Badge tone="accent">age {get('ageBucket')} <button className="btn btn-sm btn-ghost" style={{ height: 16, padding: '0 4px' }} onClick={() => set('ageBucket', '')} aria-label="Clear age filter">×</button></Badge>}
          {activeFilters.length > 0 && <Button size="sm" variant="ghost" onClick={() => { setSp(new URLSearchParams(dq ? { q: dq } : {}), { replace: true }); }}>Clear filters</Button>}
        </div>
        {selected.size > 0 && (
          <div className="bulk-bar" role="region" aria-label="Bulk actions">
            <span>{selected.size} selected</span>
            {(['resource', 'repromise', 'hold', 'release', 'cancel'] as BulkAction[]).map((a) => <Button key={a} size="sm" disabled={!canBulk} onClick={() => setBulk(a)} title={canBulk ? undefined : 'Requires order.bulk'}>{a === 'resource' ? 'Re-source' : a === 'repromise' ? 'Re-promise' : humanize(a).replace(/^\w/, (c) => c.toUpperCase())}</Button>)}
            <span className="grow" /><Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>Clear selection</Button>
          </div>
        )}
        {q.isLoading && <Loading />}
        {q.isError && <div className="card-body"><ErrorState error={q.error} retry={() => q.refetch()} /></div>}
        {q.data && <Table rows={q.data.items} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/orders/${encodeURIComponent(r.id)}`)} selectable={{ selected, onToggle: toggle, onToggleAll: toggleAll }} empty={activeFilters.length > 0 || dq
          ? <EmptyState title="No orders match these filters" hint="Try widening the date range, or clear a filter to see more." action={<Button size="sm" variant="ghost" onClick={() => { setSp(new URLSearchParams(dq ? { q: dq } : {}), { replace: true }); }}>Clear filters</Button>} />
          : <EmptyState icon={<IconOrders size={18} />} title="No orders yet" hint="Orders placed on any channel — web, Shopify, Amazon, POS, EDI, or an autonomous agent — show up here the moment they're captured." action={<Link to="/playground" className="btn btn-sm">Place a test order in the Playground</Link>} />
        } caption="Orders" columns={[
          { key: 'id', header: 'Order', render: (r) => <span className="col gap1"><span className="mono">{r.id}</span><span className="xs muted">{r.channelOrderId}</span></span> },
          { key: 'status', header: 'Status', render: (r) => <span className="row gap1 wrap"><StatusBadge status={r.status} />{r.held && <Badge tone="hold">hold</Badge>}{r.atRisk && <Badge tone="risk">at risk</Badge>}</span> },
          { key: 'channel', header: 'Channel', render: (r) => <span className="row gap1">{r.channel}{r.agentId && <Badge tone="L2">agent</Badge>}</span> },
          { key: 'type', header: 'Type', render: (r) => humanize(r.orderType) },
          { key: 'node', header: 'Nodes', render: (r) => <span className="mono small">{r.nodeIds?.join(', ') || '—'}</span> },
          { key: 'lines', header: 'Lines', align: 'right', render: (r) => r.lineCount },
          { key: 'total', header: 'Total', align: 'right', render: (r) => <span className="money">{fmtMoney(r.total)}</span> },
          { key: 'promise', header: 'Promise', render: (r) => r.promiseDate ?? '—' },
          { key: 'placed', header: 'Placed', render: (r) => <span title={fmtDateTime(r.placedAt)}>{relTime(r.placedAt)}</span> },
        ]} />}
        {q.data && <Pagination offset={q.data.offset} limit={q.data.limit} total={q.data.total} onChange={(o) => set('offset', String(o))} />}
      </div>
      {bulk && <BulkDialog action={bulk} ids={[...selected]} onClose={() => setBulk(null)} onDone={() => { setSelected(new Set()); q.refetch(); }} />}
    </div>
  );
}

function BulkDialog({ action, ids, onClose, onDone }: { action: BulkAction; ids: string[]; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState(''); const [holdType, setHoldType] = useState('ops_review'); const [plan, setPlan] = useState<BulkOperation | null>(null); const [result, setResult] = useState<BulkOperation | null>(null);
  const params = action === 'hold' ? { type: holdType, reasonCode: 'bulk', reason } : { reason };
  const dry = useMutation({ mutationFn: () => ordersApi.bulk({ action, subjectIds: ids, params, dryRun: true, perSecond: 20 }), onSuccess: setPlan });
  const run = useMutation({ mutationFn: () => ordersApi.bulk({ action, subjectIds: ids, params, dryRun: false, perSecond: 20 }), onSuccess: (r) => { setResult(r); toast.ok(`Bulk ${action} finished`, `${r.succeeded} succeeded · ${r.failed} failed`); onDone(); } });
  const rollback = useMutation({ mutationFn: () => ordersApi.bulkRollback(result!.id), onSuccess: (r) => { setResult(r); toast.ok('Rolled back'); onDone(); } });
  const label = action === 'resource' ? 'Re-source' : action === 'repromise' ? 'Re-promise' : humanize(action).replace(/^\w/, (c) => c.toUpperCase());
  const current = result ?? plan;
  return (
    <Dialog open onClose={onClose} title={`${label} ${ids.length} order${ids.length > 1 ? 's' : ''}`} width={640} description="Runs a dry run first. Nothing changes until you confirm."
      footer={<>{result ? <><span className="grow" />{result.status !== 'ROLLED_BACK' && <Button onClick={() => rollback.mutate()} loading={rollback.isPending}>Roll back</Button>}<Button variant="primary" onClick={onClose}>Done</Button></> : <><Button onClick={onClose}>Cancel</Button>{!plan ? <Button variant="primary" loading={dry.isPending} onClick={() => dry.mutate()} disabled={action === 'cancel' && !reason.trim()}>Dry run</Button> : <Button variant={action === 'cancel' ? 'danger' : 'primary'} loading={run.isPending} onClick={() => run.mutate()} disabled={plan.succeeded === 0}>Apply to {plan.succeeded} order{plan.succeeded === 1 ? '' : 's'}</Button>}</>}</>}>
      {!current && <>
        {action === 'hold' && <Select label="Hold type" value={holdType} onChange={(e) => setHoldType(e.target.value)}>{['ops_review', 'fraud_review', 'address_verification', 'payment_review', 'customer_request'].map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select>}
        <Textarea label={`Reason${action === 'cancel' ? ' (required)' : ''}`} value={reason} onChange={(e) => setReason(e.target.value)} rows={2} placeholder={action === 'resource' ? 'e.g. Carrier outage in TX; move to DC-NJ' : 'Recorded in the audit log'} />
        <div className="xs muted">Rate limited to 20/s. Terminal and shipped orders are skipped. Every change appends to the ledger with your actor id.</div>
      </>}
      {current && (<>
        <div className="row gap3"><Badge tone={current.dryRun ? 'info' : current.status === 'ROLLED_BACK' ? 'cancelled' : 'ok'} lg>{current.dryRun ? 'Dry run' : current.status}</Badge><span className="small">{current.succeeded} {current.dryRun ? 'would change' : 'changed'} · {current.results.filter((r) => r.status === 'skipped').length} skipped · {current.failed} failed</span><span className="grow" /><span className="mono xs muted">{current.id}</span></div>
        <div className="table-wrap" style={{ maxHeight: 320, border: '1px solid var(--border)' }}>
          <table className="tbl dense"><thead><tr><th>Order</th><th>Result</th><th>Detail</th></tr></thead><tbody>
            {current.results.map((r) => <tr key={r.subjectId}><td className="mono">{r.subjectId}</td><td><Badge tone={r.status === 'ok' || r.status === 'planned' ? 'ok' : r.status === 'skipped' ? 'neutral' : 'danger'}>{r.status}</Badge></td><td className="small muted">{r.message ?? '—'}</td></tr>)}
          </tbody></table>
        </div>
      </>)}
    </Dialog>
  );
}
