import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { autonomyApi } from '../../api/autonomy';
import type { Anomaly, AutonomousAction } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { IconRefresh, IconSparkle, IconWhy } from '../../components/Icons';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { StatTile } from '../../components/StatTile';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { hasPermission, useSession } from '../../auth/session';
import { fmtPct, fmtUsd, humanize, relTime } from '../../lib/format';
import { toast } from '../../lib/toast';
import { GuardrailsPanel } from './GuardrailsPanel';

const REFRESH = 10000;

/** Agentic Control Tower: header stats, guardrails, and the live anomaly -> diagnosis -> outcome feed. */
export function AgenticOps() {
  const { principal } = useSession();
  const canAdmin = hasPermission(principal, 'config.admin');
  const canRead = hasPermission(principal, 'exception.read') || hasPermission(principal, 'exception.work');
  const [why, setWhy] = useState<string | null>(null);
  const qc = useQueryClient();

  const stats = useQuery({ queryKey: ['autonomy', 'stats'], queryFn: autonomyApi.stats, refetchInterval: REFRESH, enabled: canRead });
  const guardrails = useQuery({ queryKey: ['autonomy', 'guardrails'], queryFn: autonomyApi.guardrails, enabled: canRead });
  const anomalies = useQuery({ queryKey: ['autonomy', 'anomalies'], queryFn: () => autonomyApi.anomalies({ limit: 20 }), refetchInterval: REFRESH, enabled: canRead });
  const actions = useQuery({ queryKey: ['autonomy', 'actions'], queryFn: () => autonomyApi.actions({ limit: 50 }), refetchInterval: REFRESH, enabled: canRead });
  const actionsById = useMemo(() => new Map((actions.data?.items ?? []).map((a) => [a.id, a])), [actions.data]);

  const scanNow = useMutation({
    mutationFn: autonomyApi.scanNow,
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ['autonomy'] });
      toast.ok('Scan complete', r.anomaliesFound === 0 ? 'No new anomalies found.' : `${r.anomaliesFound} anomaly${r.anomaliesFound === 1 ? '' : 'ies'} found.`);
    },
  });

  if (!canRead) return null;
  const everSeen = (anomalies.data?.total ?? 0) > 0;

  return (
    <Section
      title={<span className="row gap3"><span className="icon-badge ai" aria-hidden><IconSparkle size={16} /></span> Autonomous operations <Badge tone="ai">agentic</Badge></span>}
      actions={<Button variant="ai" icon={<IconRefresh size={14} />} loading={scanNow.isPending} onClick={() => scanNow.mutate()} disabled={!canAdmin} title={canAdmin ? undefined : 'Needs config.admin'}>Run a scan now</Button>}
    >
      <div className="grid grid-4">
        <StatTile label="Autonomous actions today" value={stats.data ? `${stats.data.actionsToday} / ${stats.data.maxActionsPerDay}` : '—'} tone="ai" loading={stats.isLoading}
          hint={stats.data && stats.data.actionsToday >= stats.data.maxActionsPerDay ? 'daily cap reached' : undefined} />
        <StatTile label="$ impact resolved autonomously" value={fmtUsd(stats.data?.usdImpactResolvedToday)} tone="ai" loading={stats.isLoading} hint={stats.data ? `cap ${fmtUsd(stats.data.maxUsdImpactPerAction)}/action` : undefined} />
        <StatTile label="Human escalations" value={stats.data?.humanEscalations ?? '—'} loading={stats.isLoading} hint="from this capability" />
        <StatTile label="Automation rate" value={fmtPct(stats.data?.automationRate)} tone="ai" loading={stats.isLoading} hint="executed / (executed + escalated)" />
      </div>

      {guardrails.data && <GuardrailsPanel guardrails={guardrails.data} actionsToday={stats.data?.actionsToday ?? 0} canEdit={canAdmin} />}

      <div className="col gap3 mt4">
        <div className="row gap3"><h3 style={{ margin: 0 }}>Live feed</h3><span className="live ai" role="status" aria-live="off"><span className="pulse" aria-hidden />live</span></div>
        {anomalies.isLoading && <Loading />}
        {anomalies.isError && <ErrorState error={anomalies.error} retry={() => anomalies.refetch()} />}
        {anomalies.data && !everSeen && (
          <EmptyState
            title="No anomalies seen yet"
            hint="Once the scanner finds a stuck order — stalled with no progress, a high breach-risk score, a margin-floor approval sitting open, or an overdue exception — it will diagnose the cause here and either act on it automatically or hand it to a person with the diagnosis attached. Try “Run a scan now”."
            action={canAdmin ? <Button variant="ai" size="sm" loading={scanNow.isPending} onClick={() => scanNow.mutate()}>Run a scan now</Button> : undefined}
          />
        )}
        {anomalies.data && everSeen && anomalies.data.items.length === 0 && <EmptyState title="No open anomalies right now" hint="Every stuck order the scanner has found so far has been resolved or escalated." />}
        {anomalies.data && anomalies.data.items.map((a) => <AnomalyCard key={a.id} anomaly={a} action={a.actionId ? actionsById.get(a.actionId) : undefined} onWhy={setWhy} />)}
      </div>
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </Section>
  );
}

function AnomalyCard({ anomaly, action, onWhy }: { anomaly: Anomaly; action?: AutonomousAction; onWhy: (id: string) => void }) {
  const qc = useQueryClient();
  const undo = useMutation({
    mutationFn: () => autonomyApi.undo(action!.id, 'reverted from the Control Tower'),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['autonomy'] }); toast.ok('Action undone', `${humanize(action!.actionTaken)} on ${anomaly.orderId} was reversed.`); },
  });
  const executed = anomaly.outcome === 'executed';
  const canUndo = executed && action?.undoable && !action.undoneAt;
  return (
    <div className="row gap3" style={{ alignItems: 'stretch' }}>
      <div className={`autonomy-rail ${executed ? 'ai' : 'human'}`} aria-hidden />
      <div className="card" style={{ flex: 1, padding: 'var(--s3) var(--s4)' }}>
        <div className="row gap3">
          <Link to={`/orders/${encodeURIComponent(anomaly.orderId)}`} className="mono small">{anomaly.orderId}</Link>
          <Badge tone="outline">{humanize(anomaly.anomalyType)}</Badge>
          {anomaly.diagnosisValue && <Badge tone={executed ? 'ai' : 'outline'}>{humanize(anomaly.diagnosisValue)}</Badge>}
          {anomaly.confidence != null && <span className="xs muted">confidence {anomaly.confidence.toFixed(2)}</span>}
          <span className="grow" />
          <span className="xs muted">{relTime(anomaly.detectedAt)}</span>
        </div>
        <div className="small mt2">
          {executed
            ? <span>⚡ Acted automatically: <strong>{humanize(action?.actionTaken ?? '')}</strong>{action && <>, {relTime(action.executedAt)}</>}{action?.detail && <span className="muted"> — {action.detail}</span>}</span>
            : <span>⏸ Escalated to <span className="mono">{anomaly.escalationQueue ?? 'a queue'}</span>{anomaly.escalationReason && <span className="muted">: {anomaly.escalationReason}</span>}</span>}
        </div>
        <div className="row gap3 mt2">
          {anomaly.decisionRecordId && <button className="btn btn-sm btn-ghost" onClick={() => onWhy(anomaly.decisionRecordId!)}><IconWhy size={13} /> Why</button>}
          {!executed && anomaly.escalationTaskId && <Link to={`/tasks?selected=${encodeURIComponent(anomaly.escalationTaskId)}`} className="small">Open task →</Link>}
          {canUndo && <Button size="sm" variant="subtle" loading={undo.isPending} onClick={() => undo.mutate()}>Undo</Button>}
          {executed && action?.undoneAt && <Badge tone="neutral">undone {relTime(action.undoneAt)}</Badge>}
        </div>
      </div>
    </div>
  );
}
