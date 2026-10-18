import { useMutation, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { PolicyChange, Proposal } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { fmtDateTime, fmtPct, humanize } from '../../lib/format';
import { toast } from '../../lib/toast';

function changeLabel(c: PolicyChange): string {
  switch (c.type) {
    case 'ThresholdChange': return `${c.decision} · ${c.band} band threshold ${c.from.toFixed(2)} → ${c.to.toFixed(2)}`;
    case 'WeightChange': return `${c.target} · ${c.weightKey} ${String(c.from)} → ${String(c.to)}`;
    case 'RuleAdd': return `${c.decision} · add rule: ${c.expression} → ${c.thenValue}`;
    case 'RuleRetire': return `${c.decision} · retire rule ${c.ruleId}`;
    case 'ParameterChange': return `${c.parameter} ${String(c.from)} → ${String(c.to)}`;
    case 'AllowListChange': return `${c.listName} · ${c.add ? 'add' : 'remove'} ${c.entry}`;
    default: return JSON.stringify(c);
  }
}

const verdictTone: Record<string, 'ok' | 'warn' | 'danger'> = { LOOKS_SAFE: 'ok', NEEDS_CHANGES: 'warn', RISKY: 'danger' };
const statusTone: Record<string, string> = { DRAFT: 'neutral', EVALUATED: 'info', APPROVED: 'accent', CANARY: 'ai', PROMOTED: 'ok', REJECTED: 'danger', ROLLED_BACK: 'danger' };

/** XP-02: a proposal's hypothesis, evidence, replay results with confidence intervals, the critic's review and
 * the diff, with approve/reject/evaluate actions gated to learning.admin. */
export function ChangeCard({ proposal, canAdmin }: { proposal: Proposal; canAdmin: boolean }) {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: ['learning', 'proposals'] });

  const evaluate = useMutation({ mutationFn: () => learningApi.evaluate(proposal.proposalId), onSuccess: () => { toast.ok('Replay evaluation complete'); invalidate(); } });
  const approve = useMutation({ mutationFn: () => learningApi.approve(proposal.proposalId), onSuccess: () => { toast.ok('Proposal approved'); invalidate(); } });
  const reject = useMutation({
    mutationFn: () => learningApi.reject(proposal.proposalId, typeof window !== 'undefined' ? (window.prompt('Reason for rejecting this proposal?') ?? undefined) : undefined),
    onSuccess: () => { toast.ok('Proposal rejected'); invalidate(); },
  });
  const promote = useMutation({ mutationFn: () => learningApi.promote(proposal.proposalId), onSuccess: () => { toast.ok('Proposal promoted'); invalidate(); } });

  const r = proposal.evaluationReport;
  const ci = r?.confidenceInterval;
  const liftPositive = ci ? ci.lower > 0 : undefined;

  return (
    <div className="card" style={{ padding: 'var(--s3) var(--s4)' }}>
      <div className="row gap3 wrap">
        <span className="mono small">{proposal.proposalId}</span>
        <Badge tone={statusTone[proposal.status] ?? 'neutral'}>{humanize(proposal.status.toLowerCase())}</Badge>
        <Badge tone={proposal.tier}>{proposal.tier}</Badge>
        <span className="mono xs muted">{proposal.changeset.target.type}:{proposal.changeset.target.name} v{proposal.changeset.baseVersion}</span>
        <span className="grow" />
        <span className="xs muted">{fmtDateTime(proposal.createdAt)}</span>
      </div>

      <div className="small mt2">
        {proposal.changeset.motivatedBy && <div className="xs muted">motivated by <span className="mono">{proposal.changeset.motivatedBy}</span></div>}
        <div className="col gap1 mt1">{proposal.changeset.changes.map((c, i) => <div key={i} className="mono xs" style={{ padding: '2px 0' }}>{changeLabel(c)}</div>)}</div>
      </div>

      {r && (
        <div className="callout mt2" style={{ padding: 'var(--s2) var(--s3)' }}>
          <div className="row wrap gap3 small">
            <span>Replay lift <b style={liftPositive === false ? { color: 'var(--danger)' } : undefined}>{fmtPct(r.liftEstimate, 1)}</b></span>
            {ci && <span className="xs muted">95% CI [{fmtPct(ci.lower, 1)}, {fmtPct(ci.upper, 1)}]{liftPositive === false && ' · crosses or is below zero'}</span>}
            {r.sampleSize !== undefined && <span className="xs muted">{r.sampleSize} logged decisions</span>}
            {!!r.clippedRecords && <span className="xs muted">{r.clippedRecords} propensity-clipped</span>}
          </div>
        </div>
      )}
      {!r && <div className="xs muted mt2">Not evaluated against replay history yet.</div>}

      {proposal.criticReview && (
        <div className="row gap2 mt2 small" style={{ alignItems: 'flex-start' }}>
          <Badge tone={verdictTone[proposal.criticReview.verdict] ?? 'neutral'}>{humanize(proposal.criticReview.verdict.toLowerCase())}</Badge>
          {proposal.criticReview.risks.length > 0 && <ul className="xs muted" style={{ margin: 0, paddingLeft: 16 }}>{proposal.criticReview.risks.map((risk, i) => <li key={i}>{risk}</li>)}</ul>}
        </div>
      )}

      {proposal.rejectedReason && <div className="xs muted mt2">Rejected: {proposal.rejectedReason}</div>}

      {canAdmin && (
        <div className="row gap2 mt3">
          {proposal.status === 'DRAFT' && <Button size="sm" loading={evaluate.isPending} onClick={() => evaluate.mutate()}>Run replay evaluation</Button>}
          {(proposal.status === 'DRAFT' || proposal.status === 'EVALUATED') && (
            <>
              <Button size="sm" variant="primary" loading={approve.isPending} onClick={() => approve.mutate()}>Approve</Button>
              <Button size="sm" variant="subtle" loading={reject.isPending} onClick={() => reject.mutate()}>Reject</Button>
            </>
          )}
          {proposal.status === 'APPROVED' && <Button size="sm" variant="ai" loading={promote.isPending} onClick={() => promote.mutate()}>Promote</Button>}
        </div>
      )}
    </div>
  );
}
