import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { AutonomyLevel, ProposalStatus } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select } from '../../components/Form';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { IconDiff, IconSparkle } from '../../components/Icons';
import { hasPermission, useSession } from '../../auth/session';
import { fmtDateTime, humanize, relTime } from '../../lib/format';
import { toast } from '../../lib/toast';
import { ChangeCard } from './ChangeCard';
import { ObjectiveEditor } from './ObjectiveEditor';

const AUTONOMY_LEVELS: AutonomyLevel[] = ['L0_OBSERVE', 'L1_RECOMMEND', 'L2_ACT_WITH_APPROVAL', 'L3_AUTONOMOUS'];
const AUTONOMY_LABEL: Record<AutonomyLevel, string> = { L0_OBSERVE: 'L0 · observe', L1_RECOMMEND: 'L1 · recommend', L2_ACT_WITH_APPROVAL: 'L2 · act with approval', L3_AUTONOMOUS: 'L3 · autonomous' };
const PROPOSAL_TABS: ProposalStatus[] = ['DRAFT', 'EVALUATED', 'APPROVED', 'PROMOTED', 'REJECTED'];
const REFRESH = 30000;

/**
 * XP-01: the Learning Console — active learners and their autonomy level, the proposal queue (XP-02 change
 * cards), running experiments, promoted changes, and lift vs. holdout. XP-04's weekly digest is folded into the
 * "Promoted this week" section below rather than a separate route.
 */
export default function LearningConsole() {
  const { principal } = useSession();
  const canRead = hasPermission(principal, 'learning.read');
  const canAdmin = hasPermission(principal, 'learning.admin');
  const [status, setStatus] = useState<ProposalStatus>('DRAFT');

  const learners = useQuery({ queryKey: ['learning', 'learners'], queryFn: learningApi.learners, refetchInterval: REFRESH, enabled: canRead });
  const proposals = useQuery({ queryKey: ['learning', 'proposals', status], queryFn: () => learningApi.proposals(status), refetchInterval: REFRESH, enabled: canRead });
  const promoted = useQuery({ queryKey: ['learning', 'proposals', 'PROMOTED'], queryFn: () => learningApi.proposals('PROMOTED'), refetchInterval: REFRESH, enabled: canRead });
  const experiments = useQuery({ queryKey: ['learning', 'experiments'], queryFn: learningApi.experiments, refetchInterval: REFRESH, enabled: canRead });
  const objective = useQuery({ queryKey: ['learning', 'objective'], queryFn: learningApi.objective, enabled: canRead });

  const holdout = useMemo(() => experiments.data?.find((e) => e.mode === 'HOLDOUT'), [experiments.data]);

  if (!canRead) {
    return (
      <div className="page">
        <div className="page-head"><div><h1>Learning</h1></div></div>
        <EmptyState title="You don't have access to this" hint="Viewing the learning console needs the learning.read permission." />
      </div>
    );
  }

  const noLearnersYet = learners.data?.length === 0;

  return (
    <div className="page">
      <div className="page-head">
        <div><h1 className="row gap3"><span className="icon-badge ai" aria-hidden><IconSparkle size={18} /></span> Learning <Badge tone="ai">self-optimizing</Badge></h1>
          <div className="sub">What's learning from your order outcomes, what it's proposing, and what's been proven and promoted</div>
        </div>
      </div>

      <Section title="Learners">
        {learners.isLoading && <Loading />}
        {learners.isError && <ErrorState error={learners.error} retry={() => learners.refetch()} />}
        {learners.data && noLearnersYet && (
          <EmptyState title="No learners registered yet" hint="Learners register themselves the first time they run against real order outcomes. Once orders start flowing through the decision fabric, parameter learners (node reliability, lane transit, the sourcing-policy bandit) and the nightly analyst will appear here." />
        )}
        {learners.data && !noLearnersYet && (
          <div className="col gap2">
            {learners.data.map((l) => <LearnerRow key={l.learnerKey} learner={l} canAdmin={canAdmin} />)}
          </div>
        )}
      </Section>

      <Section
        title={<span className="row gap3"><span className="icon-badge ai" aria-hidden><IconDiff size={16} /></span> Proposal queue</span>}
        actions={<div className="row gap2" role="tablist" aria-label="Proposal status">
          {PROPOSAL_TABS.map((s) => <button key={s} type="button" role="tab" aria-selected={status === s} className={`chip chip-btn ${status === s ? 'badge-ai' : ''}`} onClick={() => setStatus(s)}>{humanize(s.toLowerCase())}</button>)}
        </div>}
      >
        {proposals.isLoading && <Loading />}
        {proposals.isError && <ErrorState error={proposals.error} retry={() => proposals.refetch()} />}
        {proposals.data && proposals.data.length === 0 && (
          <EmptyState title={`No ${humanize(status.toLowerCase())} proposals`} hint={status === 'DRAFT'
            ? 'Once the analyst finds a testable pattern in your order outcomes, the author drafts a typed changeset here — every one typed, evidenced and reversible before it ever goes live.'
            : 'Nothing in this status right now.'} />
        )}
        {proposals.data && proposals.data.length > 0 && (
          <div className="col gap3">{proposals.data.map((p) => <ChangeCard key={p.proposalId} proposal={p} canAdmin={canAdmin} />)}</div>
        )}
      </Section>

      <Section title="Running experiments">
        {experiments.isLoading && <Loading />}
        {experiments.isError && <ErrorState error={experiments.error} retry={() => experiments.refetch()} />}
        {experiments.data && experiments.data.length === 0 && (
          <EmptyState title="No experiments running" hint="A promoted change below T0 first runs shadow, then a sticky canary, before it's eligible for full promotion. A persistent holdout keeps a slice of traffic on the baseline policy so cumulative lift always has something real to compare against." />
        )}
        {experiments.data && experiments.data.length > 0 && (
          <div className="col gap2">{experiments.data.map((e) => (
            <div key={e.experimentId} className="row gap3 small" style={{ padding: 'var(--s2) 0', borderBottom: '1px solid var(--border)' }}>
              <Badge tone={e.mode === 'HOLDOUT' ? 'ai' : 'outline'}>{e.mode.toLowerCase()}</Badge>
              <span className="mono xs">{e.proposalId}</span>
              <span className="muted">{(e.trafficShare * 100).toFixed(0)}% of traffic</span>
              <span className="grow" />
              <span className="xs muted">started {relTime(e.startAt)}</span>
            </div>
          ))}</div>
        )}
        {holdout && <div className="xs muted mt2">Holdout kept out of every promotion: {(holdout.trafficShare * 100).toFixed(0)}% of traffic, running since {fmtDateTime(holdout.startAt)}. Lift is measured against this, never against a slide.</div>}
      </Section>

      <Section title="Promoted this week">
        {promoted.isLoading && <Loading />}
        {promoted.data && promoted.data.length === 0 && <EmptyState title="Nothing promoted yet" hint="A promoted change shows up here with its measured lift the moment it goes live — this is the weekly digest, always current rather than a once-a-week email." />}
        {promoted.data && promoted.data.length > 0 && (
          <div className="col gap3">{promoted.data.map((p) => <ChangeCard key={p.proposalId} proposal={p} canAdmin={canAdmin} />)}</div>
        )}
      </Section>

      <Section title="Tenant objective">
        {objective.isLoading && <Loading />}
        {objective.isError && <ErrorState error={objective.error} retry={() => objective.refetch()} />}
        {objective.data && <ObjectiveEditor profile={objective.data} canEdit={canAdmin} />}
      </Section>
    </div>
  );
}

function LearnerRow({ learner, canAdmin }: { learner: { learnerKey: string; autonomyLevel: AutonomyLevel; enabled: boolean; lastRunAt?: string | null }; canAdmin: boolean }) {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: ['learning', 'learners'] });
  const setAutonomy = useMutation({
    mutationFn: (level: AutonomyLevel) => learningApi.setAutonomy(learner.learnerKey, level),
    onSuccess: () => { toast.ok('Autonomy level updated'); invalidate(); },
  });
  const kill = useMutation({
    mutationFn: (enabled: boolean) => learningApi.killSwitch(learner.learnerKey, enabled),
    onSuccess: (l) => { toast[l.enabled ? 'ok' : 'warn'](l.enabled ? 'Learner re-enabled' : 'Learner paused'); invalidate(); },
  });

  return (
    <div className="row gap3" style={{ padding: 'var(--s2) var(--s3)', borderRadius: 'var(--r2)', background: 'var(--surface-2)' }}>
      <span className="mono small">{humanize(learner.learnerKey)}</span>
      {!learner.enabled && <Badge tone="warn">paused</Badge>}
      <span className="grow" />
      <span className="xs muted">{learner.lastRunAt ? `last ran ${relTime(learner.lastRunAt)}` : 'has not run yet'}</span>
      <Select sm aria-label={`Autonomy level for ${learner.learnerKey}`} value={learner.autonomyLevel} disabled={!canAdmin || setAutonomy.isPending}
        onChange={(e) => setAutonomy.mutate(e.target.value as AutonomyLevel)} style={{ width: 200 }}>
        {AUTONOMY_LEVELS.map((lv) => <option key={lv} value={lv}>{AUTONOMY_LABEL[lv]}</option>)}
      </Select>
      {canAdmin && (
        <Button size="sm" variant={learner.enabled ? 'subtle' : 'primary'} loading={kill.isPending} onClick={() => kill.mutate(!learner.enabled)}>
          {learner.enabled ? 'Pause' : 'Resume'}
        </Button>
      )}
    </div>
  );
}
