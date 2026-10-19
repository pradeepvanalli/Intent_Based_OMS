import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { Experiment, ExperimentDetail } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { JsonTree } from '../../components/Misc';
import { Drawer } from '../../components/Overlay';
import { Table } from '../../components/Table';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { fmtDate, fmtNum, fmtPct, humanize, relTime } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { toast } from '../../lib/toast';
import { DemoBadge, InfoTip, Technical } from './Bits';
import { MODE_WORDS, STATUS_WORDS, decisionLabel, familyName, targetName } from './copy';

const REFRESH = 30000;

/** Shadow tests, trials on some customers, and the holdout, with what each has measured so far. */
export function ExperimentsTab({ canAdmin }: { canAdmin: boolean }) {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['learning', 'experiments', 'all'], queryFn: () => learningApi.experiments(true), refetchInterval: REFRESH });
  const [open, setOpen] = useQueryState('experiment');
  const holdout = useMutation({ mutationFn: () => learningApi.ensureHoldout(0.05), onSuccess: () => { toast.ok('Holdout started'); qc.invalidateQueries({ queryKey: ['learning'] }); } });
  const rows = (list.data ?? []).slice().sort((a, b) => (a.status === b.status ? b.startAt.localeCompare(a.startAt) : a.status === 'RUNNING' ? -1 : 1));
  const hasHoldout = rows.some((e) => e.mode === 'HOLDOUT' && e.status === 'RUNNING');

  return (
    <div className="col gap4">
      <Section title={<span className="row gap2">Experiments <InfoTip text="Before a change goes live everywhere it can run in shadow (compared, never acting) or on a small share of customers. A holdout keeps some customers on the original behaviour to measure the overall effect." /></span>}
        actions={canAdmin && !hasHoldout ? <Button size="sm" variant="subtle" loading={holdout.isPending} onClick={() => holdout.mutate()}>Start a 5% holdout</Button> : undefined}>
        <div className="autonomy-legend mb2">{(['SHADOW', 'CANARY', 'HOLDOUT'] as const).map((m) => <span key={m}><b>{MODE_WORDS[m].label}</b> <InfoTip text={MODE_WORDS[m].explain} label={MODE_WORDS[m].label} /></span>)}</div>
        {list.isLoading && <Loading />}
        {list.isError && <ErrorState error={list.error} retry={() => list.refetch()} />}
        {list.data && (
          <Table rows={rows} rowKey={(e) => e.experimentId} onRowClick={(e) => setOpen(e.experimentId)} caption="Experiments" selectedKey={open || null}
            empty={<EmptyState title="No experiments yet" hint="Open a proposed change under Changes and start a shadow test or a trial on some customers." />}
            columns={[
              { key: 'what', header: 'What is being tested', render: (e) => <span className="col gap1"><span className="strong small">{targetWords(e)}</span><Technical><span className="xs muted mono">{e.experimentId} · {e.proposalId}</span></Technical></span> },
              { key: 'kind', header: 'Kind', render: (e) => <span title={MODE_WORDS[e.mode].explain}>{MODE_WORDS[e.mode].label}</span> },
              { key: 'share', header: 'Customers', align: 'right', render: (e) => e.mode === 'SHADOW' ? <span className="muted small">none affected</span> : fmtPct(e.trafficShare, 0) },
              { key: 'status', header: 'Status', render: (e) => <span className="row gap1"><Badge tone={e.status === 'RUNNING' ? 'ai' : e.status === 'COMPLETED' ? 'ok' : 'neutral'} dot>{humanize(e.status.toLowerCase())}</Badge><DemoBadge by={e.createdBy} /></span> },
              { key: 'when', header: 'Started', render: (e) => <span className="small muted">{relTime(e.startAt)}</span> },
            ]} />
        )}
      </Section>
      <ExperimentDrawer id={open || null} onClose={() => setOpen('')} canAdmin={canAdmin} />
    </div>
  );
}

function targetWords(e: Experiment): string {
  if (e.mode === 'HOLDOUT') return 'Everything learning changes (overall effect)';
  const t = e.targetRef ?? '';
  if (t.startsWith('parameter:live:')) return `Using the learned "${familyName(t.slice('parameter:live:'.length))}" live`;
  if (e.decisionName) return `${decisionLabel(e.decisionName)}${e.candidateVersion ? `, new version ${e.candidateVersion}` : ''}`;
  return targetName(t) || 'A proposed change';
}

function ExperimentDrawer({ id, onClose, canAdmin }: { id: string | null; onClose: () => void; canAdmin: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['learning', 'experiment', id], queryFn: () => learningApi.experiment(id!), enabled: !!id, refetchInterval: REFRESH });
  const refresh = () => { qc.invalidateQueries({ queryKey: ['learning'] }); };
  const complete = useMutation({ mutationFn: () => learningApi.completeExperiment(id!), onSuccess: () => { toast.ok('Experiment completed', 'Its result is saved on the proposed change.'); refresh(); } });
  const stop = useMutation({ mutationFn: () => learningApi.stopExperiment(id!), onSuccess: () => { toast.warn('Experiment stopped'); refresh(); } });
  const e = q.data?.experiment;
  return (
    <Drawer open={!!id} onClose={onClose} width={640} title={e ? targetWords(e) : 'Experiment'}
      subtitle={e ? <span className="row gap2"><Badge tone="outline">{MODE_WORDS[e.mode].label}</Badge><Badge tone={e.status === 'RUNNING' ? 'ai' : 'neutral'} dot>{humanize(e.status.toLowerCase())}</Badge><DemoBadge by={e.createdBy} /></span> : undefined}
      footer={<>{canAdmin && e?.status === 'RUNNING' && e.mode !== 'HOLDOUT' && <><Button size="sm" variant="primary" loading={complete.isPending} disabled={!!tooEarly(e)} title={tooEarly(e) ?? undefined} onClick={() => complete.mutate()}>Complete and save result</Button><Button size="sm" variant="ghost" loading={stop.isPending} onClick={() => stop.mutate()}>Stop</Button></>}<span className="grow" /><Button onClick={onClose}>Close</Button></>}>
      {q.isLoading && <Loading />}
      {q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
      {q.data && <ExperimentBody d={q.data} />}
    </Drawer>
  );
}

function ExperimentBody({ d }: { d: ExperimentDetail }) {
  const e = d.experiment;
  const m = (d.measurement ?? {}) as Record<string, unknown>;
  const n = (k: string) => (typeof m[k] === 'number' ? (m[k] as number) : undefined);
  return (
    <>
      <p className="small" style={{ margin: 0 }}>{MODE_WORDS[e.mode].explain}</p>
      <section>
        <div className="section-title">So far</div>
        {Object.keys(m).length === 0 && <span className="small muted">Nothing measured yet.</span>}
        {typeof m.passed === 'boolean' && <div className={`callout ${m.passed ? 'ok' : 'warn'}`}><span><b>{m.passed ? 'Passing' : 'Not passing yet'}.</b> {String(m.reason ?? '')}</span></div>}
        <div className="grid grid-3 mt2">
          {n('agreementRate') !== undefined && <Fig label="Agreed with live" value={fmtPct(n('agreementRate'), 0)} hint={`over ${fmtNum(n('paired'))} decisions`} />}
          {n('shadowEscalationRate') !== undefined && <Fig label="Sent to a person" value={`${fmtPct(n('shadowEscalationRate'), 1)} vs ${fmtPct(n('liveEscalationRate'), 1)}`} hint="new vs live" />}
          {n('shadowLatencyP95Ms') !== undefined && <Fig label="Slowest 5% response" value={`${fmtNum(n('shadowLatencyP95Ms'))} vs ${fmtNum(n('liveLatencyP95Ms'))} ms`} hint="new vs live" />}
          {n('decisionsAffected') !== undefined && <Fig label="Decisions it would affect" value={fmtNum(n('decisionsAffected'))} hint={`${fmtNum(n('factsHeldBack'))} learned values held back`} />}
          {n('meanShift') !== undefined && <Fig label="Average change in value" value={fmtNum(n('meanShift'), 3)} />}
          {n('liftEstimate') !== undefined && <Fig label="Estimated effect" value={`${(n('liftEstimate') ?? 0) >= 0 ? '+' : ''}${fmtNum(n('liftEstimate'), 3)}`} hint="on the outcome score" />}
        </div>
      </section>
      <section>
        <div className="section-title">Details</div>
        <div className="subtle-list small">
          <div>Started {fmtDate(e.startAt)}{e.endAt ? ` · planned end ${fmtDate(e.endAt)}` : ''}</div>
          {e.baselineVersion != null && <div>Compared with live version {e.baselineVersion}{e.candidateVersion != null ? `; testing version ${e.candidateVersion}` : ''}</div>}
          {d.proposal && <div>The change being tested is: {STATUS_WORDS[d.proposal.status]?.toLowerCase() ?? humanize(d.proposal.status.toLowerCase())}</div>}
        </div>
      </section>
      <Technical>
        <section><div className="section-title">Technical details</div><div className="code" style={{ whiteSpace: 'normal' }}><JsonTree data={d} defaultOpen={1} /></div></section>
      </Technical>
    </>
  );
}

function Fig({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return <div className="mini-stat"><span className="l">{label}</span><span className="v">{value}</span>{hint && <span className="d">{hint}</span>}</div>;
}

/** EX-03: a trial on customers runs at least its minimum duration (default 7 days) before it can be completed. */
function tooEarly(e: Experiment): string | null {
  if (e.mode !== 'CANARY') return null;
  const days = typeof e.stopRule?.minDurationDays === 'number' ? e.stopRule.minDurationDays : 7;
  const ready = new Date(e.startAt).getTime() + days * 86_400_000;
  return Date.now() < ready ? `A trial runs at least ${days} days before its result counts; it can complete ${new Date(ready).toLocaleDateString()}. Stop it if it has to end now.` : null;
}
