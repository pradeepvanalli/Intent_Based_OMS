import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { Digest, LiftMetric, LiftReport } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { StatTile } from '../../components/StatTile';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { ApiError } from '../../api/client';
import { fmtDate, fmtNum, fmtPct, humanize, relTime } from '../../lib/format';
import { toast } from '../../lib/toast';
import { DemoBadge, InfoTip, Meter, Technical } from './Bits';
import { decisionLabel, liftVerdict, metricName, relativeChange, targetName } from './copy';

const REFRESH = 30000;
const COVERAGE_FLOOR = 0.8;

/** "Is learning helping, what changed, what needs me": the three questions a first-time visitor has. */
export function OverviewTab({ canAdmin, goTo }: { canAdmin: boolean; goTo: (tab: string) => void }) {
  const lift = useQuery({ queryKey: ['learning', 'lift'], queryFn: () => learningApi.lift(90), refetchInterval: REFRESH });
  const learners = useQuery({ queryKey: ['learning', 'learners'], queryFn: learningApi.learners, refetchInterval: REFRESH });
  const coverage = useQuery({ queryKey: ['learning', 'coverage'], queryFn: learningApi.coverage, refetchInterval: REFRESH });
  const drafts = useQuery({ queryKey: ['learning', 'proposals', 'DRAFT'], queryFn: () => learningApi.proposals('DRAFT'), refetchInterval: REFRESH });
  const evaluated = useQuery({ queryKey: ['learning', 'proposals', 'EVALUATED'], queryFn: () => learningApi.proposals('EVALUATED'), refetchInterval: REFRESH });
  const approved = useQuery({ queryKey: ['learning', 'proposals', 'APPROVED'], queryFn: () => learningApi.proposals('APPROVED'), refetchInterval: REFRESH });
  const objectiveDrafts = useQuery({ queryKey: ['learning', 'objective', 'drafts'], queryFn: learningApi.objectiveDrafts, refetchInterval: REFRESH });
  const digest = useQuery({ queryKey: ['learning', 'digest', 'latest'], queryFn: learningApi.latestDigest, retry: false });
  const experiments = useQuery({ queryKey: ['learning', 'experiments', 'all'], queryFn: () => learningApi.experiments(true), refetchInterval: REFRESH });
  const holdoutBy = experiments.data?.find((e) => e.experimentId === lift.data?.holdout?.experimentId)?.createdBy ?? null;

  const reward = lift.data?.metrics.find((m) => m.metric === 'reward');
  const verdict = reward ? liftVerdict(reward) : null;
  const rel = reward ? relativeChange(reward.treatedMean, reward.holdoutMean) : null;
  const helpingValue = verdict && reward?.lift != null && reward.significant
    ? (rel !== null ? `${rel >= 0 ? '+' : ''}${fmtPct(rel, 1)}` : `${reward.lift >= 0 ? '+' : ''}${fmtNum(reward.lift, 3)}`)
    : (verdict?.text ?? (lift.data?.holdout ? 'Not enough data yet' : 'Not measured yet'));
  const names = new Map((learners.data ?? []).map((l) => [l.learnerKey, l.displayName]));
  const toReview = (drafts.data?.length ?? 0) + (evaluated.data?.length ?? 0);
  const all = learners.data ?? [];
  const running = all.filter((l) => l.running).length;
  const paused = all.filter((l) => !l.running);
  const cov = Object.entries(coverage.data ?? {});
  const lowest = cov.length ? Math.min(...cov.map(([, v]) => v)) : null;
  const below = cov.filter(([, v]) => v < COVERAGE_FLOOR);

  return (
    <div className="col gap4">
      <div className="learn-tiles">
        <StatTile label="Is learning helping?" loading={lift.isLoading}
          value={helpingValue}
          tone={verdict?.tone === 'ok' ? 'ok' : verdict?.tone === 'danger' ? 'danger' : undefined}
          hint={!lift.data ? undefined : reward?.lift != null && reward.significant ? `${verdict?.text}: outcome score vs the holdout` : lift.data.holdout ? 'Compared with the holdout group' : 'Start a holdout to measure it'} />
        <StatTile label="Changes waiting for you" value={toReview + (approved.data?.length ?? 0)} loading={drafts.isLoading || evaluated.isLoading || approved.isLoading}
          hint={drafts.data && evaluated.data && approved.data ? `${toReview} to review · ${approved.data.length} approved, not live` : undefined} onClick={() => goTo('changes')} tone={toReview > 0 ? 'ai' : undefined} />
        <StatTile label="Learners running" value={learners.data ? `${running} of ${all.length}` : '—'} loading={learners.isLoading}
          hint={!learners.data ? undefined : paused.length ? `${paused.length} paused or switched off` : 'All running'} onClick={() => goTo('learners')} tone={paused.length ? 'warn' : undefined} />
        <StatTile label="Outcomes coming back" value={lowest === null ? '—' : `${cov.length - below.length} of ${cov.length}`} loading={coverage.isLoading}
          hint={!coverage.data ? undefined : lowest === null ? 'Measured once there are enough decisions'
            : below.length ? `decisions on track · ${below.map(([d]) => decisionLabel(d)).join(', ')} below 80%, so the learners that use it pause` : 'decisions have enough outcomes to learn from'}
          tone={below.length ? 'warn' : undefined} />
      </div>

      <div className="learn-two">
        <LiftCard lift={lift.data} isLoading={lift.isLoading} error={lift.error} retry={() => lift.refetch()} canAdmin={canAdmin} holdoutBy={holdoutBy} />
        <div className="col gap4">
          <Section title="Needs you">
            <div className="need-list">
              <NeedItem count={toReview} label="changes to review" onClick={() => goTo('changes')} />
              <NeedItem count={approved.data?.length ?? 0} label="approved changes not live yet" onClick={() => goTo('changes')} />
              <NeedItem count={paused.length} label="learners paused or switched off" onClick={() => goTo('learners')} />
              <NeedItem count={objectiveDrafts.data?.length ?? 0} label="objective drafts to approve" onClick={() => goTo('settings')} />
            </div>
          </Section>
          <Section title={<span className="row gap2">Outcomes coming back <InfoTip text="Share of recent decisions that got a real outcome (delivered, returned, charged back…). Learning that depends on a decision pauses itself below 80%." /></span>}>
            {coverage.isError && <ErrorState error={coverage.error} retry={() => coverage.refetch()} />}
            {coverage.data && cov.length === 0 && <EmptyState title="Not measured yet" hint="Coverage is measured once a decision has at least 20 episodes old enough for an outcome to arrive." />}
            {cov.map(([decision, value]) => (
              <div key={decision} className="coverage-row">
                <span>{decisionLabel(decision)}</span>
                <Meter value={value} target={COVERAGE_FLOOR} label={`${decisionLabel(decision)} outcome coverage`} />
                <span className="num right">{fmtPct(value, 0)}</span>
              </div>
            ))}
          </Section>
        </div>
      </div>

      <DigestCard digest={digest.data} missing={digest.error instanceof ApiError && digest.error.status === 404} isLoading={digest.isLoading} canAdmin={canAdmin} names={names} />
    </div>
  );
}

function NeedItem({ count, label, onClick }: { count: number; label: string; onClick: () => void }) {
  return (
    <button type="button" className="need-item" onClick={onClick} aria-label={`${count} ${label}`}>
      <span className="count" style={count === 0 ? { color: 'var(--text-faint)' } : undefined}>{count}</span>
      <span className={count === 0 ? 'muted' : undefined}>{label}</span>
    </button>
  );
}

function LiftCard({ lift, isLoading, error, retry, canAdmin, holdoutBy }: { lift?: LiftReport; isLoading: boolean; error: unknown; retry: () => void; canAdmin: boolean; holdoutBy?: string | null }) {
  const qc = useQueryClient();
  const holdout = useMutation({
    mutationFn: () => learningApi.ensureHoldout(0.05),
    onSuccess: () => { toast.ok('Holdout started', '5% of customers stay on the original behaviour so the effect of learning can be measured.'); qc.invalidateQueries({ queryKey: ['learning'] }); },
  });
  return (
    <Section title={<span className="row gap2">Is learning helping? <InfoTip text="Customers with learning on are compared with a small holdout group kept on the original behaviour. A difference only counts when it is clearly bigger than chance." /></span>}
      actions={lift?.holdout ? <span className="xs muted">last {lift.windowDays} days</span> : undefined}>
      {isLoading && <Loading />}
      {!!error && <ErrorState error={error} retry={retry} />}
      {lift && !lift.holdout && (
        <EmptyState title="Not measured yet" hint="Keep a small share of customers on the original behaviour to see what learning is really worth."
          action={canAdmin ? <Button size="sm" variant="primary" loading={holdout.isPending} onClick={() => holdout.mutate()}>Start a 5% holdout</Button> : undefined} />
      )}
      {lift?.holdout && (
        <>
          <p className="caption row gap2">{fmtPct(lift.holdout.trafficShare, 0)} of customers have been in the holdout since {fmtDate(lift.holdout.startAt)}.<DemoBadge by={holdoutBy} /></p>
          <div className="table-wrap">
            <table className="tbl dense" aria-label="Lift by measure">
              <thead><tr><th scope="col">Measure</th><th scope="col" className="num">With learning</th><th scope="col" className="num">Holdout</th><th scope="col">Result</th></tr></thead>
              <tbody>
                {lift.metrics.map((m) => <LiftRow key={m.metric} m={m} />)}
                {lift.metrics.length === 0 && <tr><td colSpan={4} className="muted small">No outcomes have matured in this window yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Section>
  );
}

function LiftRow({ m }: { m: LiftMetric }) {
  const v = liftVerdict(m);
  return (
    <tr>
      <td>{metricName(m.metric)}<Technical><div className="xs muted mono">{m.metric} · n {m.nTreated} vs {m.nHoldout}{m.ciLower !== undefined ? ` · CI [${fmtNum(m.ciLower, 3)}, ${fmtNum(m.ciUpper, 3)}]` : ''}</div></Technical></td>
      <td className="num">{m.treatedMean === undefined ? '—' : fmtNum(m.treatedMean, 3)}</td>
      <td className="num">{m.holdoutMean === undefined ? '—' : fmtNum(m.holdoutMean, 3)}</td>
      <td><Badge tone={v.tone} title={m.reason ?? undefined}>{v.text}</Badge></td>
    </tr>
  );
}

const EVENT_WORDS: Record<string, string> = { autonomy_changed: 'autonomy changed', switched: 'switched on or off', paused: 'paused', resumed: 'resumed', kill_switch_changed: 'all learning paused or resumed' };

function DigestCard({ digest, missing, isLoading, canAdmin, names }: { digest?: Digest; missing: boolean; isLoading: boolean; canAdmin: boolean; names: Map<string, string> }) {
  const qc = useQueryClient();
  const run = useMutation({ mutationFn: () => learningApi.runDigest(7), onSuccess: () => { toast.ok('Weekly summary updated'); qc.invalidateQueries({ queryKey: ['learning', 'digest'] }); } });
  const s = digest?.summary;
  const promoted = s?.changed?.promoted ?? [];
  const rolledBack = s?.changed?.rolledBack ?? [];
  const learnerChanges = s?.changed?.learnerChanges ?? [];
  const rewards = s?.impact?.rewardByDecision ?? [];
  return (
    <Section title="This week" actions={canAdmin ? <Button size="sm" variant="subtle" loading={run.isPending} onClick={() => run.mutate()}>Refresh summary</Button> : undefined}>
      {isLoading && <Loading />}
      {missing && <EmptyState title="No weekly summary yet" hint="A summary of what changed and what it did is written every Monday." />}
      {digest && (
        <div className="col gap3">
          {s?.headline && <div className="digest-head">{s.headline}</div>}
          <div className="grid grid-3">
            <DigestList title="Went live" rows={promoted} empty="Nothing went live" />
            <DigestList title="Rolled back" rows={rolledBack} empty="Nothing rolled back" />
            <DigestList title="Learner changes" rows={learnerChanges} empty="No learner settings changed"
              format={(r) => {
                const key = String(r.learner ?? r.aggregate_id ?? '');
                const ev = String(r.type ?? '').split('.').pop() ?? '';
                return `${names.get(key) ?? (key ? decisionLabel(key) : 'Learning')}: ${EVENT_WORDS[ev] ?? humanize(ev)}`;
              }} />
          </div>
          {rewards.length > 0 && (
            <div className="small">
              <div className="section-title">Outcome score by decision</div>
              <div className="subtle-list">{rewards.map((r) => (
                <div key={r.decision} className="row-between"><span>{decisionLabel(r.decision)}</span>
                  <span className="num">{fmtNum(r.meanReward, 3)}{r.change != null && <span style={{ color: r.change >= 0 ? 'var(--ok-fg)' : 'var(--danger-fg)', marginLeft: 8 }}>{r.change >= 0 ? '▲' : '▼'} {fmtNum(Math.abs(r.change), 3)}</span>}
                    <span className="xs muted" style={{ marginLeft: 8 }}>{fmtNum(r.maturedEpisodes)} outcomes</span></span>
                </div>))}</div>
            </div>
          )}
          <div className="xs muted">Covers {fmtDate(digest.periodStart)} to {fmtDate(digest.periodEnd)} · written {relTime(digest.createdAt)}{digest.deliveredTo.length ? ` · emailed to ${digest.deliveredTo.length}` : ''}</div>
        </div>
      )}
    </Section>
  );
}

function DigestList({ title, rows, empty, format }: { title: string; rows: Record<string, unknown>[]; empty: string; format?: (r: Record<string, unknown>) => string }) {
  const label = format ?? ((r: Record<string, unknown>) => targetName(String(r.target_ref ?? r.target ?? r.proposal_id ?? '')));
  return (
    <div className="finding">
      <span className="title">{title} <span className="muted">· {rows.length}</span></span>
      {rows.length === 0 ? <span className="xs muted">{empty}</span> : (
        <ul className="small" style={{ margin: 0, paddingLeft: 18 }}>{rows.slice(0, 5).map((r, i) => <li key={i}>{label(r)}</li>)}</ul>
      )}
    </div>
  );
}
