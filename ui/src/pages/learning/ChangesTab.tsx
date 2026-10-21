import { useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { LearnerView, LiveFamilyDomain, LiveParameter, Proposal, ProposalStatus } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Select, Textarea } from '../../components/Form';
import { ConfirmDialog, Dialog } from '../../components/Overlay';
import { Tabs } from '../../components/Tabs';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { fmtDateTime, relTime } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { toast } from '../../lib/toast';
import { ChangeDrawer } from './ChangeDrawer';
import { DemoBadge, InfoTip, Technical } from './Bits';
import { STATUS_TONE, STATUS_WORDS, TIER_WORDS, VERDICT, authorName, familyName, proposalTitle, replayPhrase } from './copy';
import { rangeProblems } from './liveDomains';

const REFRESH = 30000;
const GROUPS: { id: string; label: string; statuses: ProposalStatus[]; empty: string }[] = [
  { id: 'review', label: 'To review', statuses: ['DRAFT', 'EVALUATED'], empty: 'Nothing to review. Learners draft changes here when they find something worth trying.' },
  { id: 'approved', label: 'Approved', statuses: ['APPROVED'], empty: 'No approved changes waiting to go live.' },
  { id: 'trial', label: 'Being tried', statuses: ['CANARY'], empty: 'No change is being tried on customers right now.' },
  { id: 'live', label: 'Live', statuses: ['PROMOTED'], empty: 'No learned change has gone live yet.' },
  { id: 'closed', label: 'Rejected or rolled back', statuses: ['REJECTED', 'ROLLED_BACK'], empty: 'Nothing rejected or rolled back.' },
];
const ALL_STATUSES: ProposalStatus[] = ['DRAFT', 'EVALUATED', 'APPROVED', 'CANARY', 'PROMOTED', 'REJECTED', 'ROLLED_BACK'];

/** Every proposed change by where it stands, the change card behind each, and which learned values are live. */
export function ChangesTab({ canAdmin, learners }: { canAdmin: boolean; learners: LearnerView[] }) {
  const [group, setGroup] = useQueryState('changes', 'review');
  const [open, setOpen] = useQueryState('change');
  const lists = useQueries({ queries: ALL_STATUSES.map((s) => ({ queryKey: ['learning', 'proposals', s], queryFn: () => learningApi.proposals(s), refetchInterval: REFRESH })) });
  const byStatus = new Map(ALL_STATUSES.map((s, i) => [s, lists[i]]));
  const names = useMemo(() => new Map(learners.map((l) => [l.learnerKey, l.displayName])), [learners]);
  const g = GROUPS.find((x) => x.id === group) ?? GROUPS[0];
  const rows = g.statuses.flatMap((s) => byStatus.get(s)?.data ?? []).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  const loading = g.statuses.some((s) => byStatus.get(s)?.isLoading);
  const failed = g.statuses.map((s) => byStatus.get(s)).find((q) => q?.isError);

  return (
    <div className="col gap4">
      <Section title={<span className="row gap2">Proposed changes <InfoTip text="Every change a learner suggests is tested against past decisions, reviewed by someone other than its author, and can be rolled back." /></span>} tight>
        <div style={{ padding: 'var(--s2) var(--s4) 0' }}>
          <Tabs pills ariaLabel="Change status" active={g.id} onChange={setGroup}
            tabs={GROUPS.map((x) => ({ id: x.id, label: x.label, count: x.statuses.every((s) => byStatus.get(s)?.data) ? x.statuses.reduce((n, s) => n + (byStatus.get(s)?.data?.length ?? 0), 0) : undefined }))} />
        </div>
        <div className="col gap2" style={{ padding: 'var(--s3) var(--s4) var(--s4)' }}>
          {loading && <Loading />}
          {failed && <ErrorState error={failed.error} retry={() => failed.refetch()} />}
          {!loading && !failed && rows.length === 0 && <EmptyState title="Nothing here" hint={g.empty} />}
          {rows.map((p) => <ChangeRow key={p.proposalId} p={p} names={names} onOpen={() => setOpen(p.proposalId)} />)}
        </div>
      </Section>

      <LiveUsePanel canAdmin={canAdmin} learners={learners} />

      <ChangeDrawer proposalId={open || null} onClose={() => setOpen('')} canAdmin={canAdmin} names={names} />
    </div>
  );
}

export function ChangeRow({ p, names, onOpen }: { p: Proposal; names: Map<string, string>; onOpen: () => void }) {
  const r = p.evaluationReport;
  const replay = r ? replayPhrase(r.liftEstimate, r.confidenceInterval) : { text: 'Not tested yet', tone: 'neutral' };
  // D5-5: the automatic critic's second opinion is otherwise only visible in the drawer; flag a RISKY or
  // NEEDS_CHANGES verdict here too, reusing the drawer's own badge/tone convention (VERDICT, from ./copy).
  const verdict = p.criticReview?.verdict;
  const flagged = verdict === 'RISKY' || verdict === 'NEEDS_CHANGES';
  return (
    <button type="button" className="change-row" onClick={onOpen} data-testid={`change-${p.proposalId}`}>
      <div className="col gap1" style={{ minWidth: 0 }}>
        <span className="title">{proposalTitle(p)}</span>
        <span className="meta">
          <span>Suggested by {authorName(p.createdBy, names)}</span><span>·</span><span>{relTime(p.createdAt)}</span>
          <DemoBadge by={p.createdBy} id={p.proposalId} />
          <Technical><span className="mono">{p.proposalId} · {p.changeset.target.type}:{p.changeset.target.name}</span></Technical>
        </span>
      </div>
      <div className="side">
        <Badge tone={STATUS_TONE[p.status]}>{STATUS_WORDS[p.status]}</Badge>
        <span className="row gap2">
          <Badge tone={p.tier} title={`Risk tier ${p.tier}`}>{TIER_WORDS[p.tier]}<Technical> · {p.tier}</Technical></Badge>
          <Badge tone={replay.tone}>{replay.text}</Badge>
          {flagged && (
            <Badge tone={VERDICT[verdict!]?.tone ?? 'danger'} title="The automatic critic's second opinion" data-testid="critic-flag">
              {VERDICT[verdict!]?.label ?? verdict}
            </Badge>
          )}
        </span>
      </div>
    </button>
  );
}

/** Which learned values are switched on for live decisions; proposing and revoking live use. */
function LiveUsePanel({ canAdmin, learners }: { canAdmin: boolean; learners: LearnerView[] }) {
  const qc = useQueryClient();
  const live = useQuery({ queryKey: ['learning', 'live-parameters'], queryFn: learningApi.liveParameters, refetchInterval: REFRESH });
  const domains = useQuery({ queryKey: ['learning', 'live-domains'], queryFn: learningApi.liveDomains, staleTime: Infinity });
  const units = useMemo(() => new Map((domains.data ?? []).map((d) => [d.family, d])), [domains.data]);
  const [proposing, setProposing] = useState(false);
  const [revoking, setRevoking] = useState<LiveParameter | null>(null);
  const names = useMemo(() => new Map(learners.map((l) => [l.learnerKey, l.displayName])), [learners]);
  const revoke = useMutation({
    mutationFn: (family: string) => learningApi.revokeLiveUse(family),
    onSuccess: () => { setRevoking(null); toast.warn('Switched off', 'Live decisions use the standard value again.'); qc.invalidateQueries({ queryKey: ['learning', 'live-parameters'] }); },
  });
  const rows = (live.data ?? []).slice().sort((a, b) => (a.status === b.status ? a.parameter.localeCompare(b.parameter) : a.status === 'LIVE' ? -1 : 1));
  return (
    <Section title={<span className="row gap2">Learned values used in live decisions <InfoTip text="A learned value only affects real orders once someone approves using it, within a set range and step. Switching one off takes effect immediately." /></span>}
      actions={canAdmin ? <Button size="sm" variant="primary" onClick={() => setProposing(true)}>Propose live use</Button> : undefined}>
      {live.isLoading && <Loading />}
      {live.isError && <ErrorState error={live.error} retry={() => live.refetch()} />}
      {live.data && rows.length === 0 && <EmptyState title="No learned values are live" hint="Until one is approved, live decisions use their standard values and learning only shows what it would change." />}
      {rows.length > 0 && (
        <div className="subtle-list">
          {rows.map((p) => (
            <div key={p.parameter} className="row-between wrap gap3">
              <div className="col gap1">
                <span className="strong">{familyName(p.parameter)}</span>
                <span className="xs muted">From {names.get(p.learnerKey) ?? p.learnerKey}{p.min != null || p.max != null ? ` · allowed ${p.min ?? '…'} to ${p.max ?? '…'}${units.get(p.parameter)?.kind === 'NUMBER' ? ` (${units.get(p.parameter)!.units})` : ''}` : ''}{p.maxStep != null ? ` · at most ${p.maxStep} from standard` : ''} · {p.status === 'LIVE' ? `since ${fmtDateTime(p.promotedAt)}` : `switched off ${fmtDateTime(p.revokedAt)}`}</span>
                <Technical><span className="xs muted mono">{p.parameter} · proposal {p.proposalId ?? '—'} · by {p.status === 'LIVE' ? p.promotedBy : p.revokedBy}</span></Technical>
              </div>
              <span className="row gap2">
                <DemoBadge by={p.promotedBy} id={p.proposalId} />
                <Badge tone={p.status === 'LIVE' ? 'ok' : 'neutral'} dot>{p.status === 'LIVE' ? 'In use' : 'Switched off'}</Badge>
                {canAdmin && p.status === 'LIVE' && <Button size="sm" variant="ghost" onClick={() => setRevoking(p)} aria-label={`Switch off ${familyName(p.parameter)}`}>Switch off</Button>}
              </span>
            </div>
          ))}
        </div>
      )}
      <ProposeLiveUse open={proposing} onClose={() => setProposing(false)} learners={learners} domains={units} />
      <ConfirmDialog open={!!revoking} onClose={() => setRevoking(null)} onConfirm={() => revoking && revoke.mutate(revoking.parameter)} loading={revoke.isPending} danger
        title="Stop using this learned value?" confirmLabel="Switch off"
        body={<p className="small">{revoking ? familyName(revoking.parameter) : ''} goes back to its standard value for every new decision, straight away. Using it again needs a new approval.</p>} />
    </Section>
  );
}

function ProposeLiveUse({ open, onClose, learners, domains }: { open: boolean; onClose: () => void; learners: LearnerView[]; domains: Map<string, LiveFamilyDomain> }) {
  const qc = useQueryClient();
  const options = learners.flatMap((l) => l.liveParameters.map((f) => ({ family: f, learnerKey: l.learnerKey, learner: l.displayName })));
  const [choice, setChoice] = useState('');
  const [min, setMin] = useState(''); const [max, setMax] = useState(''); const [step, setStep] = useState(''); const [reason, setReason] = useState('');
  const picked = options.find((o) => `${o.learnerKey}|${o.family}` === choice);
  const n = (v: string) => (v.trim() === '' ? undefined : Number(v));
  const domain = picked ? domains.get(picked.family) : undefined;
  // B-1: the same domain checks the server makes, shown before the request is sent.
  const problems = rangeProblems(domain, n(min), n(max), n(step));
  const invalid = !picked || [min, max, step].some((v) => v.trim() !== '' && Number.isNaN(Number(v))) || problems.length > 0;
  const close = () => { setChoice(''); setMin(''); setMax(''); setStep(''); setReason(''); onClose(); };
  const propose = useMutation({
    mutationFn: () => learningApi.proposeLiveUse({ family: picked!.family, learnerKey: picked!.learnerKey, min: n(min), max: n(max), maxStep: n(step), enabled: true, reason: reason.trim() || undefined }),
    onSuccess: () => { toast.ok('Proposal drafted', 'Someone other than you must approve it before it is used.'); qc.invalidateQueries({ queryKey: ['learning', 'proposals'] }); close(); },
  });
  return (
    <Dialog open={open} onClose={close} title="Propose using a learned value live" description="Drafts a change that goes through testing and approval like any other."
      footer={<><Button onClick={close}>Cancel</Button><Button variant="primary" disabled={invalid} loading={propose.isPending} onClick={() => propose.mutate()}>Draft proposal</Button></>}>
      <Select label="Learned value" value={choice} onChange={(e) => setChoice(e.target.value)}>
        <option value="">Choose…</option>
        {options.map((o) => <option key={`${o.learnerKey}|${o.family}`} value={`${o.learnerKey}|${o.family}`}>{familyName(o.family)} — from {o.learner}</option>)}
      </Select>
      {domain && (
        <p className="xs muted" data-testid="live-domain" style={{ margin: 0 }}>
          Values: {domain.description}{domain.maxStepLimit != null ? ` · one change may move it at most ${domain.maxStepLimit}` : ''}
          {domain.affectedDecisions.length > 0 ? ` · read by ${domain.affectedDecisions.join(', ')}` : ''}
        </p>
      )}
      <div className="grid grid-3">
        <Input label="Lowest allowed" inputMode="decimal" value={min} onChange={(e) => setMin(e.target.value)} placeholder="optional" />
        <Input label="Highest allowed" inputMode="decimal" value={max} onChange={(e) => setMax(e.target.value)} placeholder="optional" />
        <Input label="Biggest step from standard" inputMode="decimal" value={step} onChange={(e) => setStep(e.target.value)} placeholder="optional" />
      </div>
      {problems.length > 0 && (
        <div className="callout warn" role="alert" data-testid="live-range-problems"><span>This range cannot be used: <ul className="small" style={{ margin: '4px 0 0', paddingLeft: 18 }}>{problems.map((x, i) => <li key={i}>{x}</li>)}</ul></span></div>
      )}
      <Textarea label="Why (optional)" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Node reliability has 3 months of data and replay shows fewer late deliveries" />
    </Dialog>
  );
}
