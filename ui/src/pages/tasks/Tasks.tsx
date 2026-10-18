import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { tasksApi } from '../../api/workflows';
import type { HumanTask } from '../../api/types';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select, Textarea } from '../../components/Form';
import { Countdown, JsonTree, KeyValue } from '../../components/Misc';
import { Table, Pagination } from '../../components/Table';
import { EmptyState, ErrorState, Loading } from '../../components/States';
import { fmtDateTime, fmtIsoDuration, fmtPct, fmtUsd, humanize, relTime } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { hasPermission, useSession } from '../../auth/session';
import { toast } from '../../lib/toast';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { IconTasks } from '../../components/Icons';

export default function Tasks() {
  const { principal } = useSession(); const qc = useQueryClient();
  const [queue, setQueue] = useQueryState('queue'); const [status, setStatus] = useQueryState('status', 'OPEN,CLAIMED'); const [sel, setSel] = useQueryState('task'); const [offset, setOffset] = useQueryState('offset', '0');
  const [mine, setMine] = useState(false);
  const queues = useQuery({ queryKey: ['queues'], queryFn: tasksApi.queues, refetchInterval: 10000 });
  const [scope, setScope] = useQueryState('scope', 'mine');
  const myQueues = scope === 'mine' && !queue && !mine;
  const list = useQuery({ queryKey: ['tasks', queue, status, mine, scope, offset], queryFn: () => tasksApi.list({ queue: queue || undefined, status: status || undefined, assignee: mine ? principal?.id : undefined, mine: myQueues || undefined, offset: Number(offset), limit: 25 }), refetchInterval: 10000, placeholderData: (p) => p });
  const detail = useQuery({ queryKey: ['task', sel], queryFn: () => tasksApi.get(sel), enabled: !!sel });
  const [why, setWhy] = useState<string | null>(null);
  const invalidate = () => { qc.invalidateQueries({ queryKey: ['tasks'] }); qc.invalidateQueries({ queryKey: ['queues'] }); qc.invalidateQueries({ queryKey: ['task', sel] }); qc.invalidateQueries({ queryKey: ['nav-counts'] }); };
  const totalOpen = (queues.data ?? []).reduce((a, q) => a + (q.openCount ?? 0), 0);
  return (
    <div className="page">
      <div className="page-head">
        <div className="row gap3" style={{ alignItems: 'flex-start' }}>
          <span className="icon-badge" aria-hidden="true"><IconTasks /></span>
          <div><h1>Tasks</h1><div className="sub">Work routed to a person when the system won't decide on its own — margin approvals, fraud reviews, escalations. Claim one, make the call, and your reason joins the audit trail.</div></div>
        </div>
        <div className="page-actions"><Select aria-label="Which tasks" sm value={scope} onChange={(e) => setScope(e.target.value)} title="My queues shows only the queues your role works"><option value="mine">My queues</option><option value="all">Every queue</option></Select><label className="checkbox"><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Assigned to me</label><Select aria-label="Status" sm value={status} onChange={(e) => setStatus(e.target.value)}><option value="OPEN,CLAIMED">Open & claimed</option><option value="OPEN">Open</option><option value="CLAIMED">Claimed</option><option value="COMPLETED">Completed</option><option value="">All</option></Select></div></div>
      <div className="two-col rev">
        <aside className="card card-pad">
          <div className="section-title">Queues</div>
          <div className="queue-list" role="list">
            <button className="queue-item" role="listitem" aria-current={!queue} onClick={() => setQueue('')}><span>All queues</span><span className="cnt">{totalOpen}</span></button>
            {(queues.data ?? []).map((q) => <button key={q.name} className="queue-item" role="listitem" aria-current={queue === q.name} onClick={() => setQueue(q.name)}><span className="col gap1" style={{ alignItems: 'flex-start' }}><span>{humanize(q.name)}</span><span className="xs muted" style={{ fontWeight: 400 }}>SLA {fmtIsoDuration(q.slaDefault)}{q.overdueCount ? <> · <span style={{ color: 'var(--danger)' }}>{q.overdueCount} overdue</span></> : null}</span></span><span className="cnt">{q.openCount ?? 0}</span></button>)}
          </div>
        </aside>
        <div className="col gap4">
          <div className="card">
            {list.isLoading && <Loading />}
            {list.isError && <div className="card-body"><ErrorState error={list.error} retry={() => list.refetch()} /></div>}
            {list.data && <Table rows={list.data.items} rowKey={(t) => t.id} selectedKey={sel} onRowClick={(t) => setSel(t.id)} empty={<EmptyState icon={<IconTasks size={18} />} title="Nothing waiting here" hint={mine ? 'Nothing is assigned to you in this view — try another queue or turn off "Assigned to me".' : myQueues ? 'Nothing is waiting in the queues your role works. Choose "Every queue" to see the rest.' : 'Tasks appear automatically when a decision needs a person — a margin approval, a fraud hold, or an escalation. Clear or widen the filters above to see more.'} />} caption="Tasks" columns={[
              { key: 'p', header: 'Pri', width: 44, render: (t) => <Badge tone={t.priority <= 1 ? 'danger' : t.priority === 2 ? 'warn' : 'neutral'}>P{t.priority}</Badge> },
              { key: 'title', header: 'Task', render: (t) => <span className="col gap1"><span className="strong small">{t.title}</span><span className="xs muted">{humanize(t.type)} · {humanize(t.queue)} · <Link to={subjectLink(t)} className="mono" onClick={(e) => e.stopPropagation()}>{t.subjectId}</Link></span></span> },
              { key: 'st', header: 'Status', render: (t) => <span className="row gap1"><StatusBadge status={t.status} />{t.assignee && <span className="xs muted">{t.assignee}</span>}</span> },
              { key: 'due', header: 'Due', render: (t) => t.status === 'COMPLETED' ? <span className="muted small">done {relTime(t.completedAt)}</span> : <Countdown due={t.slaDueAt} /> },
              { key: 'age', header: 'Created', render: (t) => relTime(t.createdAt) },
            ]} />}
            {list.data && <Pagination offset={list.data.offset} limit={list.data.limit} total={list.data.total} onChange={(o) => setOffset(String(o))} />}
          </div>
        </div>
      </div>
      {sel && <TaskDetail task={detail.data} loading={detail.isLoading} onClose={() => setSel('')} onChanged={invalidate} onWhy={setWhy} />}
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </div>
  );
}
function subjectLink(t: HumanTask): string { return t.subjectType === 'order' ? `/orders/${t.subjectId}` : t.subjectType === 'return' ? `/returns/${t.subjectId}` : t.subjectType === 'exception' ? `/exceptions?selected=${t.subjectId}` : '#'; }

function TaskDetail({ task, loading, onClose, onChanged, onWhy }: { task?: HumanTask; loading: boolean; onClose: () => void; onChanged: () => void; onWhy: (id: string) => void }) {
  const { principal } = useSession(); const canWork = hasPermission(principal, 'task.work') || hasPermission(principal, 'margin.approve');
  const [outcome, setOutcome] = useState(''); const [reason, setReason] = useState('');
  const claim = useMutation({ mutationFn: () => tasksApi.claim(task!.id, principal!.id), onSuccess: () => { toast.ok('Task claimed'); onChanged(); } });
  const complete = useMutation({ mutationFn: () => tasksApi.complete(task!.id, outcome, reason), onSuccess: () => { toast.ok('Task completed', `Outcome: ${outcome}`); setOutcome(''); setReason(''); onChanged(); } });
  const payload = (task?.payload ?? {}) as Record<string, unknown>;
  const options: string[] = task?.type === 'margin_floor_approval' ? ['approved', 'approved_alternative', 'rejected'] : task?.type === 'fraud_review' ? ['release_hold', 'cancel_order', 'keep_hold'] : task?.type === 'decision_escalation' ? ((payload.options as string[] | undefined) ?? ['accept_proposed', 'other']) : ['done', 'escalated', 'not_applicable'];
  return (
    <div className="drawer-root" role="presentation">
      <div className="drawer-overlay" onClick={onClose} aria-hidden />
      <div className="drawer" role="dialog" aria-modal="true" aria-label="Task detail" style={{ '--drawer-w': '640px' } as React.CSSProperties}>
        <div className="drawer-head"><div className="grow">{loading || !task ? <h2>Loading…</h2> : <><h2>{task.title}</h2><div className="row gap3 mt2 small muted"><StatusBadge status={task.status} /><span>{humanize(task.type)}</span><span>· queue {humanize(task.queue)}</span><span>· P{task.priority}</span>{task.status !== 'COMPLETED' && <span>· due <Countdown due={task.slaDueAt} /></span>}</div></>}</div><Button variant="ghost" onClick={onClose}>Close</Button></div>
        <div className="drawer-body">
          {task && <>
            <KeyValue tight items={[['Task', <span className="mono">{task.id}</span>], ['Subject', <Link to={subjectLink(task)} className="mono">{task.subjectType} / {task.subjectId}</Link>], ['Workflow', <span className="mono small">{task.workflowId ?? '—'}</span>], ['Created', fmtDateTime(task.createdAt)], ['Assignee', task.assignee ?? 'unassigned'], ['Skills', task.requiredSkills?.join(', ') || '—']]} />
            {task.type === 'margin_floor_approval' && <MarginApproval payload={payload} />}
            {task.type === 'decision_escalation' && typeof payload.decisionRecordId === 'string' && <div className="callout"><span>Proposed: <span className="mono">{String(payload.proposed)}</span> at confidence {String(payload.confidence)}. Review the trace before deciding.</span><Button size="sm" onClick={() => onWhy(payload.decisionRecordId as string)}>Why</Button></div>}
            <details><summary className="small strong" style={{ cursor: 'pointer' }}>Payload</summary><div className="code mt2" style={{ whiteSpace: 'normal' }}><JsonTree data={payload} defaultOpen={1} /></div></details>
            {task.status === 'COMPLETED' && <div className="callout ok"><span>Completed by <b>{task.completedBy}</b> {fmtDateTime(task.completedAt)} — outcome <b>{task.outcome}</b>: {task.outcomeReason}</span></div>}
            {task.status !== 'COMPLETED' && task.status !== 'CANCELLED' && (
              <div className="card card-pad col">
                <div className="section-title">Decide</div>
                {task.status === 'OPEN' && <div className="row gap3"><span className="small muted">Claim the task before completing it.</span><Button size="sm" onClick={() => claim.mutate()} loading={claim.isPending} disabled={!canWork} title={canWork ? undefined : 'Requires task.work or margin.approve'}>Claim</Button></div>}
                <Select label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)} disabled={task.status !== 'CLAIMED' || !canWork}><option value="">Select…</option>{options.map((o) => <option key={o} value={o}>{humanize(o)}</option>)}</Select>
                <Textarea label="Reason (required, audited)" hint="Recorded against this task and visible in its history — explain the why, not just the what." value={reason} onChange={(e) => setReason(e.target.value)} rows={3} disabled={task.status !== 'CLAIMED' || !canWork} />
                <div className="flex-end"><Button variant="primary" disabled={task.status !== 'CLAIMED' || !outcome || !reason.trim() || !canWork} loading={complete.isPending} onClick={() => complete.mutate()} title={canWork ? undefined : 'Requires task.work or margin.approve'}>Complete task</Button></div>
                {!canWork && <div className="xs muted">Your role cannot work tasks — this needs task.work or margin.approve.</div>}
              </div>
            )}
          </>}
          {!task && !loading && <EmptyState title="Task not found" hint="It may have been completed, reassigned, or removed from this queue." />}
        </div>
      </div>
    </div>
  );
}
function MarginApproval({ payload }: { payload: Record<string, unknown> }) {
  const plan = payload.plan as Record<string, number | string> | undefined; const alts = (payload.alternatives as Record<string, number | string>[] | undefined) ?? []; const floor = Number(payload.floor ?? 0.12);
  if (!plan) return null;
  const row = (p: Record<string, number | string>, label: string, primary?: boolean) => (
    <div key={label} className={['sel-row'].join(' ')} aria-selected={primary} style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr 1fr 1fr 1fr 1fr', gap: 8, cursor: 'default' }}>
      <span className="col gap1"><b className="small">{label}</b><span className="xs muted mono">{String(p.nodeId)} · {humanize(String(p.policy ?? ''))}</span></span>
      <span className="col gap1"><span className="xs muted">margin</span><b className={Number(p.marginRatio) < floor ? 'num' : 'num'} style={{ color: Number(p.marginRatio) < floor ? 'var(--danger)' : 'var(--ok)' }}>{fmtPct(Number(p.marginRatio))}</b></span>
      <span className="col gap1"><span className="xs muted">contribution</span><span className="num">{fmtUsd(Number(p.contributionMargin))}</span></span>
      <span className="col gap1"><span className="xs muted">fulfil cost</span><span className="num">{fmtUsd(Number(p.totalCost))}</span></span>
      <span className="col gap1"><span className="xs muted">on time</span><span className="num">{fmtPct(Number(p.onTimeProbability), 0)}</span></span>
      <span className="col gap1"><span className="xs muted">ETA</span><span>{String(p.eta ?? '—')}</span></span>
    </div>);
  return <section className="col gap1"><div className="section-title" title="The floor is the lowest margin policy allows before this needs your sign-off.">Plan economics · floor {fmtPct(floor, 0)}</div>{row(plan, 'Proposed plan', true)}{alts.length > 0 && <div className="xs muted mt2">Alternatives — other fulfilment plans the system considered and rejected</div>}{alts.map((a, i) => row(a, `Alternative ${i + 1}`))}</section>;
}
