import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { copilotApi } from '../../api/copilot';
import type { ChangeSet, CopilotScope, CopilotStatus } from '../../api/types';
import { hasAnyPermission, useSession } from '../../auth/session';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select, Textarea } from '../../components/Form';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { Tabs, TabPanel } from '../../components/Tabs';
import { IconLock, IconSend, IconSparkle } from '../../components/Icons';
import { fmtUsd, relTime } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { toast } from '../../lib/toast';
import { ChangeSetView } from './ChangeSetView';
import { Artifacts } from './Artifacts';
import { COPILOT_PERMISSIONS } from './shared';

const EXAMPLES = [
  'Gold customers with a receipt get an instant refund under $250',
  'Never split orders containing hazmat items',
  'Agent orders over $500 to California need a risk review',
  'Add a pre-order order type with a 30-day backorder window',
  'Raise the margin floor for marketplace orders to 15%',
];
const SCOPES: { value: CopilotScope; label: string }[] = [{ value: 'all', label: 'Anything' }, { value: 'decisions', label: 'Decisions only' }, { value: 'state_models', label: 'State models only' }, { value: 'order_types', label: 'Order types only' }, { value: 'policies', label: 'Policies & config only' }];

export default function Copilot() {
  const { principal } = useSession(); const qc = useQueryClient();
  const allowed = hasAnyPermission(principal, COPILOT_PERMISSIONS);
  const [tab, setTab] = useQueryState('tab', 'changes');
  const [csId, setCsId] = useQueryState('cs');
  const status = useQuery({ queryKey: ['copilot-status'], queryFn: copilotApi.status, refetchInterval: 60000 });
  const history = useQuery({ queryKey: ['copilot-changes'], queryFn: () => copilotApi.changes({ limit: 50 }), enabled: allowed });
  const current = useQuery({ queryKey: ['copilot-change', csId], queryFn: () => copilotApi.change(csId), enabled: allowed && !!csId });
  const artifactCount = useQuery({ queryKey: ['copilot-artifacts', 'draft'], queryFn: () => copilotApi.artifacts('draft'), enabled: allowed });
  const [sp, setSp] = useSearchParams();
  const [text, setText] = useState(() => sp.get('prompt') ?? ''); const [scope, setScope] = useState<CopilotScope>('all');
  // `/copilot?prompt=…` (e.g. from a Margin Guardian finding) pre-fills the composer, then the param is dropped from the URL.
  useEffect(() => { const prompt = sp.get('prompt'); if (prompt !== null) { setText(prompt); setSp((prev) => { const n = new URLSearchParams(prev); n.delete('prompt'); n.delete('cs'); return n; }, { replace: true }); } }, [sp, setSp]);
  const onCreated = (cs: ChangeSet) => { qc.setQueryData(['copilot-change', cs.id], cs); qc.invalidateQueries({ queryKey: ['copilot-changes'] }); setCsId(cs.id); };
  const propose = useMutation({ mutationFn: (req: { text: string; scope: CopilotScope; context?: Record<string, unknown> }) => copilotApi.propose(req), onSuccess: (cs) => { onCreated(cs); toast.ok('Proposal ready', `${cs.changes.length} change${cs.changes.length === 1 ? '' : 's'}${cs.questions.length ? ` · ${cs.questions.length} question${cs.questions.length === 1 ? '' : 's'}` : ''}`); } });
  const onUpdated = (cs: ChangeSet) => { qc.setQueryData(['copilot-change', cs.id], cs); qc.invalidateQueries({ queryKey: ['copilot-changes'] }); qc.invalidateQueries({ queryKey: ['copilot-artifacts'] }); qc.invalidateQueries({ queryKey: ['decisions'] }); };
  const available = status.data?.available ?? true;
  if (!allowed) return <div className="page"><div className="page-head"><div className="row gap3"><span className="icon-badge ai" aria-hidden="true"><IconSparkle size={20} /></span><h1>Copilot</h1></div></div><EmptyState icon={<IconLock size={18} />} title="Copilot needs an editing role" hint={<>Your role cannot propose or stage configuration changes. One of <code>decision.edit</code>, <code>statemodel.edit</code> or <code>config.admin</code> is required.</>} /></div>;
  return (
    <div className="page">
      <div className="page-head">
        <div className="row gap3" style={{ alignItems: 'flex-start' }}>
          <span className="icon-badge ai" aria-hidden="true"><IconSparkle size={20} /></span>
          <div><div className="eyebrow">AI configuration assistant</div><h1>Copilot</h1><div className="sub">Tell it what should change, in plain English. It turns your sentence into a reviewable change set — diff, generated tests and a risk tier included — and nothing ships until a person approves it.</div></div>
        </div>
        <div className="page-actions"><StatusChip status={status.data} loading={status.isLoading} /><Link to="/copilot/bootstrap" className="btn">Bootstrap a tenant</Link></div>
      </div>
      {status.data && !available && <div className="callout warn" role="status"><IconSparkle size={14} /><span>Connect an LLM key to enable the copilot; deterministic fallbacks still work for <Link to="/copilot/bootstrap">Bootstrap</Link>. Model configured: <span className="mono">{status.data.model}</span>.</span></div>}
      <Tabs ariaLabel="Copilot sections" active={tab} onChange={setTab} tabs={[{ id: 'changes', label: 'Changes', count: history.data?.total }, { id: 'artifacts', label: 'Artifacts', count: artifactCount.data?.items.length }]} />
      <TabPanel id="changes" active={tab}>
        <div className="copilot-layout">
          <Section title="History" className="copilot-rail" tight actions={history.data && <span className="xs muted">{history.data.total}</span>}>
            {history.isLoading && <Loading />}
            {history.isError && <ErrorState error={history.error} retry={() => history.refetch()} />}
            {history.data && history.data.items.length === 0 && <EmptyState title="No proposals yet" hint="Describe a change on the right — each request becomes a change set here: a diff, generated tests and a risk tier, ready for review." />}
            {history.data && history.data.items.map((cs) => <button key={cs.id} type="button" className="cs-item" aria-current={cs.id === csId || undefined} onClick={() => setCsId(cs.id)}><div className="row gap3"><StatusBadge status={cs.status} /><span className="grow" /><span className="xs muted">{relTime(cs.createdAt)}</span></div><div className="sum">{cs.summary}</div><div className="xs muted">{cs.changes.length} change{cs.changes.length === 1 ? '' : 's'} · {cs.createdBy}{cs.questions.length ? ` · ${cs.questions.length} open question${cs.questions.length === 1 ? '' : 's'}` : ''}</div></button>)}
          </Section>
          <div className="col gap4">
            <Section title={<span className="row gap3"><IconSparkle size={14} style={{ color: 'var(--accent)' }} /> Describe the change</span>} actions={<Select aria-label="Scope" sm value={scope} onChange={(e) => setScope(e.target.value as CopilotScope)}>{SCOPES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}</Select>}>
              <form className="col gap3 composer" onSubmit={(e) => { e.preventDefault(); if (text.trim().length >= 8) propose.mutate({ text: text.trim(), scope }); }}>
                <Textarea aria-label="Describe the change in plain English" rows={3} value={text} onChange={(e) => setText(e.target.value)} placeholder="Describe the change in plain English…" disabled={!available} onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter' && text.trim().length >= 8 && available) propose.mutate({ text: text.trim(), scope }); }} />
                <div className="example-chips" aria-label="Example requests">{EXAMPLES.map((ex) => <button key={ex} type="button" className="chip chip-btn" aria-pressed={text === ex} onClick={() => setText(ex)} disabled={!available}>{ex}</button>)}</div>
                <div className="row wrap gap3"><span className="xs muted grow">The copilot proposes; people approve. Nothing changes until a draft is staged, reviewed and published. ⌘/Ctrl+Enter to submit.</span><Button type="submit" variant="primary" icon={<IconSend size={14} />} loading={propose.isPending} disabled={!available || text.trim().length < 8}>Propose changes</Button></div>
              </form>
              {propose.isError && <div className="mt3"><ErrorState error={propose.error} /></div>}
            </Section>
            {!csId && !propose.isPending && <EmptyState icon={<IconSparkle size={18} />} title="No change set selected" hint="Describe a change above — Copilot drafts a reviewable change set in seconds — or pick a past one from the history rail." />}
            {propose.isPending && <Loading label="Drafting the proposal…" />}
            {csId && current.isLoading && !propose.isPending && <Loading label="Loading change set…" />}
            {csId && current.isError && <ErrorState error={current.error} retry={() => current.refetch()} />}
            {current.data && !propose.isPending && <ChangeSetView key={current.data.id} cs={current.data} onUpdated={onUpdated} canStage={allowed} resubmitting={propose.isPending} onResubmit={(answers) => propose.mutate({ text: current.data!.request.text, scope: current.data!.request.scope as CopilotScope, context: { answers, previousChangeSetId: current.data!.id } })} />}
          </div>
        </div>
      </TabPanel>
      <TabPanel id="artifacts" active={tab}><Artifacts /></TabPanel>
    </div>
  );
}

export function StatusChip({ status, loading }: { status?: CopilotStatus; loading?: boolean }) {
  if (loading || !status) return <span className="status-chip muted" aria-live="polite"><span className="spinner" aria-hidden /> copilot status…</span>;
  const budget = status.budgetUsdPerDay ? `${fmtUsd(status.spentTodayUsd)} / ${fmtUsd(status.budgetUsdPerDay, 0)} today` : `${fmtUsd(status.spentTodayUsd)} today`;
  const remaining = status.remainingUsd !== undefined && status.remainingUsd !== null ? `${fmtUsd(status.remainingUsd)} left` : null;
  const over = status.budgetExceeded ?? (!!status.budgetUsdPerDay && status.spentTodayUsd >= status.budgetUsdPerDay);
  const title = [remaining, status.copilotUsedTodayUsd !== undefined ? `copilot ${fmtUsd(status.copilotUsedTodayUsd, 2)}` : null, status.copilotCallsToday !== undefined ? `${status.copilotCallsToday} calls today` : null].filter(Boolean).join(' · ');
  return <span className={['status-chip', !status.available && 'off'].filter(Boolean).join(' ')} role="status" title={title || undefined} aria-label={`copilot ${status.available ? 'available' : 'unavailable'}, model ${status.model}, ${budget}${remaining ? `, ${remaining}` : ''}`}><span className="dot" aria-hidden /><span className="mono">{status.model}</span><span className="muted">·</span><span className={over ? 'strong' : 'muted'}>{budget}</span>{remaining && !over && <span className="muted">· {remaining}</span>}{!status.available && <Badge tone="danger">offline</Badge>}{over && <Badge tone="warn">budget reached</Badge>}</span>;
}
