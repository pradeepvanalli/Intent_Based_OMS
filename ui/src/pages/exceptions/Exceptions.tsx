import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { exceptionsApi } from '../../api/monitoring';
import type { ExceptionRecord } from '../../api/types';
import { Badge, SeverityBadge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Select, Textarea } from '../../components/Form';
import { Countdown, KeyValue } from '../../components/Misc';
import { EmptyState, ErrorState, Loading } from '../../components/States';
import { fmtDateTime, humanize, relTime } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { hasPermission, useSession } from '../../auth/session';
import { toast } from '../../lib/toast';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { IconAlert, IconCheck, IconWhy } from '../../components/Icons';

export default function Exceptions() {
  const { principal } = useSession(); const qc = useQueryClient();
  const [group, setGroup] = useQueryState('group', 'severity'); const [status, setStatus] = useQueryState('status', 'active'); const [type, setType] = useQueryState('type'); const [sev, setSev] = useQueryState('severity'); const [text, setText] = useQueryState('q'); const [sel, setSel] = useQueryState('selected');
  const list = useQuery({ queryKey: ['exceptions', status, type, sev, text], queryFn: () => exceptionsApi.list({ status: status || undefined, type: type || undefined, severity: sev || undefined, q: text || undefined, limit: 200 }), refetchInterval: 10000, placeholderData: (p) => p });
  const detail = useQuery({ queryKey: ['exception', sel], queryFn: () => exceptionsApi.get(sel), enabled: !!sel });
  const playbooks = useQuery({ queryKey: ['playbooks'], queryFn: exceptionsApi.playbooks, staleTime: 60000 });
  const [why, setWhy] = useState<string | null>(null); const [resolution, setResolution] = useState('resourced'); const [note, setNote] = useState('');
  const invalidate = () => { qc.invalidateQueries({ queryKey: ['exceptions'] }); qc.invalidateQueries({ queryKey: ['exception', sel] }); qc.invalidateQueries({ queryKey: ['nav-counts'] }); };
  const assign = useMutation({ mutationFn: (id: string) => exceptionsApi.assign(id, principal!.id), onSuccess: () => { toast.ok('Assigned to you'); invalidate(); } });
  const resolve = useMutation({ mutationFn: (id: string) => exceptionsApi.resolve(id, resolution, note), onSuccess: () => { toast.ok('Exception resolved'); setNote(''); invalidate(); } });
  const groups = useMemo(() => { const m = new Map<string, ExceptionRecord[]>(); for (const e of list.data?.items ?? []) { const k = group === 'type' ? e.type : group === 'queue' ? e.queue : e.severity; (m.get(k) ?? m.set(k, []).get(k)!).push(e); } const order = ['critical', 'high', 'medium', 'low']; return [...m.entries()].sort((a, b) => (order.indexOf(a[0]) === -1 ? 99 : order.indexOf(a[0])) - (order.indexOf(b[0]) === -1 ? 99 : order.indexOf(b[0])) || a[0].localeCompare(b[0])); }, [list.data, group]);
  const canWork = hasPermission(principal, 'exception.work');
  const e = detail.data; const pb = e?.playbook ? playbooks.data?.find((p) => p.name === e.playbook) : undefined;
  const types = [...new Set((list.data?.items ?? []).map((x) => x.type))].sort();
  return (
    <div className="page">
      <div className="page-head">
        <div className="row gap3" style={{ alignItems: 'flex-start' }}>
          <span className="icon-badge" aria-hidden="true"><IconAlert /></span>
          <div><h1>Exceptions</h1><div className="sub">{list.data ? `${list.data.total.toLocaleString()} exception${list.data.total === 1 ? '' : 's'} in view — each one auto-classified by type and severity, with a playbook started where one applies.` : "Things the workflow couldn't resolve on its own — a failed payment, a short pick, an undeliverable address — land here already classified, with a playbook started where one applies."}</div></div>
        </div>
        <div className="page-actions filters">
          <Input aria-label="Search" sm placeholder="Search title or order" value={text} onChange={(e) => setText(e.target.value)} />
          <Select aria-label="Group by" sm value={group} onChange={(e) => setGroup(e.target.value)}><option value="severity">Group: severity</option><option value="type">Group: type</option><option value="queue">Group: queue</option></Select>
          <Select aria-label="Status" sm value={status} onChange={(e) => setStatus(e.target.value)}><option value="active">Unresolved</option><option value="OPEN">Open</option><option value="IN_PROGRESS">In progress</option><option value="RESOLVED">Resolved</option><option value="">All</option></Select>
          <Select aria-label="Type" sm value={type} onChange={(e) => setType(e.target.value)}><option value="">Any type</option>{types.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select>
          <Select aria-label="Severity" sm value={sev} onChange={(e) => setSev(e.target.value)}><option value="">Any severity</option>{['critical', 'high', 'medium', 'low'].map((s) => <option key={s} value={s}>{s}</option>)}</Select>
        </div></div>
      <div className="two-col" style={{ gridTemplateColumns: 'minmax(0, 1fr) 460px' }}>
        <div className="col gap4">
          {list.isLoading && <Loading />}
          {list.isError && <ErrorState error={list.error} retry={() => list.refetch()} />}
          {list.data && list.data.items.length === 0 && <div className="card"><EmptyState icon={<IconCheck size={18} />} title={status === 'active' && !type && !sev && !text ? 'Inbox is clear' : 'No exceptions match these filters'} hint={status === 'active' && !type && !sev && !text ? "Nothing needs attention right now — exceptions appear here when the workflow can't resolve something on its own, like a failed payment or a short pick." : 'Try a different status, type, or severity.'} action={(status !== 'active' || type || sev || text) ? <Button size="sm" variant="ghost" onClick={() => { setStatus('active'); setType(''); setSev(''); setText(''); }}>Reset filters</Button> : undefined} /></div>}
          {groups.map(([k, items]) => (
            <section key={k} className="card">
              <div className="card-head"><h2 className="row gap3">{group === 'severity' ? <SeverityBadge severity={k} /> : <Badge tone="outline">{humanize(k)}</Badge>}<span className="muted small" style={{ fontWeight: 400 }}>{items.length}</span></h2></div>
              <div className="subtle-list" style={{ padding: '0 var(--s4)' }}>
                {items.map((x) => <button key={x.id} className="row gap3" style={{ all: 'unset', display: 'flex', width: '100%', boxSizing: 'border-box', cursor: 'pointer', padding: '10px 0', borderBottom: '1px solid var(--border)', background: sel === x.id ? 'var(--accent-soft)' : undefined, alignItems: 'flex-start' }} onClick={() => setSel(x.id)} aria-pressed={sel === x.id}>
                  {group !== 'severity' && <SeverityBadge severity={x.severity} />}
                  <div className="grow col gap1"><span className="small strong">{x.title}</span><span className="xs muted">{humanize(x.type)} · queue {humanize(x.queue)} · <span className="mono">{x.subjectId}</span> · {relTime(x.raisedAt)}</span></div>
                  <span className="col gap1" style={{ alignItems: 'flex-end' }}><StatusBadge status={x.status} />{x.playbook && <PlaybookMini steps={x.playbookSteps ?? []} />}</span>
                </button>)}
              </div>
            </section>))}
        </div>
        <aside className="card sticky-side" aria-live="polite">
          {!sel && <EmptyState title="Select an exception" hint="Pick one on the left to see how it was classified, what its playbook has done so far, and what you can do next." />}
          {sel && detail.isLoading && <Loading />}
          {e && (<>
            <div className="card-head" style={{ alignItems: 'flex-start' }}><div className="col gap1"><h2>{e.title}</h2><div className="row gap1 wrap"><SeverityBadge severity={e.severity} /><Badge tone="outline">{humanize(e.type)}</Badge><StatusBadge status={e.status} /></div></div></div>
            <div className="card-body col gap4">
              <KeyValue tight items={[['Exception', <span className="mono">{e.id}</span>], ['Subject', <Link to={e.subjectType === 'order' ? `/orders/${e.subjectId}` : '#'} className="mono">{e.subjectId}</Link>], ['Queue', humanize(e.queue)], ['Raised', `${fmtDateTime(e.raisedAt)} by ${e.raisedBy ?? '—'}`], ['SLA', e.status === 'RESOLVED' ? 'resolved' : <Countdown due={e.slaDueAt} />], ['Assignee', e.assignee ?? 'unassigned']]} />
              {e.description && <p className="small">{e.description}</p>}
              <div className="callout"><div className="grow small">Type, severity and queue are Jev's best guess from the error details <span className="xs muted" title="decision: exception_classify">(decision exception_classify)</span> — click Why to see the probability behind each guess.</div>{e.classificationRecordId && <Button size="sm" onClick={() => setWhy(e.classificationRecordId!)} icon={<IconWhy size={13} />}>Why</Button>}</div>
              <section><div className="section-title" title="A playbook is a pre-approved sequence of steps the system runs toward a resolution, pausing wherever a step needs confirmation.">Playbook {e.playbook ? <span className="mono" style={{ textTransform: 'none', letterSpacing: 0 }}>· {e.playbook}</span> : null}</div>
                {!e.playbook && <div className="small muted">No playbook configured for this type — resolve it manually below.</div>}
                {e.playbook && <ol className="col gap1" style={{ margin: 0, paddingLeft: 0, listStyle: 'none' }}>{(e.playbookSteps ?? []).map((s, i) => <li key={i} className="row gap3" style={{ alignItems: 'flex-start' }}><StepIcon status={s.status} /><div className="grow col"><span className="small"><b>{humanize(s.action)}</b> <Badge tone={s.status === 'done' ? 'ok' : s.status === 'failed' ? 'danger' : s.status === 'awaiting_confirmation' ? 'warn' : s.status === 'running' ? 'info' : 'neutral'}>{humanize(s.status)}</Badge>{s.gatedBy && <Badge tone="outline" style={{ marginLeft: 4 }} title={`Waiting on ${s.gatedBy} before this step can run`}>gated: {s.gatedBy}</Badge>}</span>{s.detail && <span className="xs muted">{s.detail}</span>}{pb?.steps[i] && (pb.steps[i].minConfidence || pb.steps[i].maxSpendUsd) && <span className="xs faint">{[pb.steps[i].minConfidence ? `min confidence ${pb.steps[i].minConfidence}` : null, pb.steps[i].maxSpendUsd ? `spend ≤ $${pb.steps[i].maxSpendUsd}` : null].filter(Boolean).join(' · ')}</span>}</div></li>)}</ol>}
              </section>
              {e.status !== 'RESOLVED' && e.status !== 'CLOSED' && (
                <section className="col gap3"><div className="section-title">Remediate</div>
                  <div className="row gap3">{!e.assignee && <Button size="sm" disabled={!canWork} onClick={() => assign.mutate(e.id)} loading={assign.isPending}>Assign to me</Button>}<Link to={`/orders/${e.subjectId}?tab=modify`} className="btn btn-sm">Open order</Link></div>
                  <Select label="Resolution" value={resolution} onChange={(ev) => setResolution(ev.target.value)} disabled={!canWork}>{['resourced', 'reshipped', 'refunded', 'cancelled', 'address_fixed', 'customer_contacted', 'false_positive', 'other'].map((r) => <option key={r} value={r}>{humanize(r)}</option>)}</Select>
                  <Textarea label="Note" value={note} onChange={(ev) => setNote(ev.target.value)} rows={2} disabled={!canWork} />
                  <div className="flex-end"><Button variant="primary" size="sm" disabled={!canWork} loading={resolve.isPending} onClick={() => resolve.mutate(e.id)}>Resolve</Button></div>
                  {!canWork && <span className="xs muted">Requires exception.work</span>}
                </section>)}
              {e.status === 'RESOLVED' && <div className="callout ok"><span>Resolved {fmtDateTime(e.resolvedAt)} — {humanize(e.resolution)}</span></div>}
            </div>
          </>)}
        </aside>
      </div>
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </div>
  );
}
function PlaybookMini({ steps }: { steps: { status: string }[] }) {
  return <span className="row gap1" aria-label={`playbook ${steps.filter((s) => s.status === 'done').length} of ${steps.length} steps done`}>{steps.map((s, i) => <span key={i} style={{ width: 8, height: 8, borderRadius: 2, background: s.status === 'done' ? 'var(--ok)' : s.status === 'failed' ? 'var(--danger)' : s.status === 'awaiting_confirmation' ? 'var(--warn)' : s.status === 'running' ? 'var(--info)' : 'var(--border-strong)' }} />)}</span>;
}
function StepIcon({ status }: { status: string }) {
  const bg = status === 'done' ? 'var(--ok)' : status === 'failed' ? 'var(--danger)' : status === 'awaiting_confirmation' ? 'var(--warn)' : status === 'running' ? 'var(--info)' : 'var(--border-strong)';
  return <span aria-hidden style={{ width: 18, height: 18, borderRadius: '50%', background: bg, color: '#fff', display: 'grid', placeItems: 'center', flex: 'none', marginTop: 2 }}>{status === 'done' && <IconCheck size={11} />}</span>;
}
