import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { returnsApi } from '../../api/returns';
import { StatusBadge, Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Select } from '../../components/Form';
import { Table, Pagination } from '../../components/Table';
import { EmptyState, ErrorState, Loading } from '../../components/States';
import { fmtDateTime, fmtMoney, humanize, relTime } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { ScorePips } from '../../components/Misc';
import { IconReturn } from '../../components/Icons';

export default function Returns() {
  const navigate = useNavigate();
  const [status, setStatus] = useQueryState('status'); const [q, setQ] = useQueryState('q'); const [offset, setOffset] = useQueryState('offset', '0');
  const list = useQuery({ queryKey: ['returns', status, q, offset], queryFn: () => returnsApi.list({ status: status || undefined, q: q || undefined, offset: Number(offset), limit: 25 }), placeholderData: (p) => p });
  return (
    <div className="page">
      <div className="page-head">
        <div className="row gap3" style={{ alignItems: 'flex-start' }}>
          <span className="icon-badge" aria-hidden="true"><IconReturn /></span>
          <div><h1>Returns</h1><div className="sub">Every RMA from request to refund — eligibility, abuse risk, refund timing, and disposition are all decided automatically and shown with their reasoning.</div></div>
        </div>
        <div className="page-actions filters"><Input aria-label="Search RMA or order" sm placeholder="RMA or order id" value={q} onChange={(e) => setQ(e.target.value)} /><Select aria-label="Status" sm value={status} onChange={(e) => setStatus(e.target.value)}><option value="">Any status</option>{['requested', 'eligibility_review', 'approved', 'in_transit', 'received', 'inspected', 'refunded', 'denied', 'closed_keep_it'].map((s) => <option key={s} value={s}>{humanize(s)}</option>)}</Select></div>
      </div>
      <div className="card">
        {list.isLoading && <Loading />}
        {list.isError && <div className="card-body"><ErrorState error={list.error} retry={() => list.refetch()} /></div>}
        {list.data && <Table rows={list.data.items} rowKey={(r) => r.rma} onRowClick={(r) => navigate(`/returns/${r.rma}`)} empty={status || q
          ? <EmptyState title="No returns match these filters" hint="Try a different status, or clear the search." action={<Button size="sm" variant="ghost" onClick={() => { setStatus(''); setQ(''); }}>Clear filters</Button>} />
          : <EmptyState icon={<IconReturn size={18} />} title="No returns yet" hint="Returns created from any channel — a customer request, a CS agent, or an autonomous one — show up here with their RMA the moment they're filed." action={<Link to="/orders" className="btn btn-sm">Find an order to return</Link>} />
        } caption="Returns" columns={[
          { key: 'rma', header: 'RMA', render: (r) => <span className="mono">{r.rma}</span> }, { key: 'order', header: 'Order', render: (r) => <span className="mono">{r.orderId}</span> },
          { key: 'st', header: 'Status', render: (r) => <StatusBadge status={r.status} /> }, { key: 'ch', header: 'Channel', render: (r) => <Badge tone="outline">{r.channel}</Badge> },
          { key: 'lines', header: 'Lines', align: 'right', render: (r) => r.lineCount ?? '—' },
          { key: 'risk', header: <span title="Likelihood this return is abusive (e.g. wardrobing, serial returns), scored 0 (none) to 4 (severe).">Abuse risk</span>, render: (r) => r.riskScore !== undefined && r.riskScore !== null ? <span className="row gap1"><ScorePips value={r.riskScore} /><span className="small num">{r.riskScore}/4</span></span> : <span className="muted small">pending</span> },
          { key: 'timing', header: <span title="When the refund is issued: instant, on carrier scan, after inspection, or escalated to a person.">Refund timing</span>, render: (r) => r.refundTiming ? <Badge tone={r.refundTiming === 'instant' ? 'ok' : r.refundTiming === 'escalate' ? 'warn' : 'info'}>{humanize(r.refundTiming)}</Badge> : '—' },
          { key: 'amt', header: 'Refund', align: 'right', render: (r) => fmtMoney(r.refundAmount) }, { key: 'at', header: 'Created', render: (r) => <span title={fmtDateTime(r.createdAt)}>{relTime(r.createdAt)}</span> },
        ]} />}
        {list.data && <Pagination offset={list.data.offset} limit={list.data.limit} total={list.data.total} onChange={(o) => setOffset(String(o))} />}
      </div>
    </div>
  );
}
