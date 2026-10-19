import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../../api/client';
import { friendlyError } from '../../api/friendly';
import { learningApi } from '../../api/learning';
import type { ChangeCard, ExperimentMode, KeyDiff, Proposal } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { JsonTree } from '../../components/Misc';
import { Drawer } from '../../components/Overlay';
import { ErrorState, Loading } from '../../components/States';
import { fmtDateTime, fmtNum, fmtPct, humanize, relTime, valueLabel } from '../../lib/format';
import { toast } from '../../lib/toast';
import { useSession } from '../../auth/session';
import { DemoBadge, InfoTip, ReasonDialog, Technical } from './Bits';
import { METHOD_WORDS, POSTAL_REASON_CODES, STATUS_TONE, STATUS_WORDS, TIER_WORDS, VERDICT, authorName, changeSentence, decisionLabel, familyName, isPostalScoped, proposalTitle, replayPhrase } from './copy';

type Dialogs = 'approve' | 'reject' | 'rollback' | 'override' | null;

/** The server's minimum for a written override reason (PromotionGuard.MIN_OVERRIDE_REASON). */
const MIN_OVERRIDE_REASON = 10;

/** XP-02 change card: what would change, why, what replay showed, the second opinion, and what changed since the last approved version. */
export function ChangeDrawer({ proposalId, onClose, canAdmin, names }: { proposalId: string | null; onClose: () => void; canAdmin: boolean; names: Map<string, string> }) {
  const card = useQuery({ queryKey: ['learning', 'card', proposalId], queryFn: () => learningApi.card(proposalId!), enabled: !!proposalId });
  const p = card.data?.proposal;
  return (
    <Drawer open={!!proposalId} onClose={onClose} width={720}
      title={p ? proposalTitle(p) : 'Proposed change'}
      subtitle={p ? <span className="row wrap gap2"><Badge tone={STATUS_TONE[p.status]}>{STATUS_WORDS[p.status]}</Badge><Badge tone={p.tier} title={`Risk tier ${p.tier}`}>{TIER_WORDS[p.tier]}</Badge><DemoBadge by={p.createdBy} id={p.proposalId} /></span> : undefined}
      footer={card.data && canAdmin ? <Actions card={card.data} onDone={() => card.refetch()} /> : <Button onClick={onClose}>Close</Button>}>
      {card.isLoading && <Loading label="Loading change…" />}
      {card.isError && <ErrorState error={card.error} retry={() => card.refetch()} />}
      {card.data && <CardBody card={card.data} names={names} />}
    </Drawer>
  );
}

function CardBody({ card, names }: { card: ChangeCard; names: Map<string, string> }) {
  const p = card.proposal;
  const motive = p.changeset.motivatedBy && !/^hyp_/.test(p.changeset.motivatedBy) ? p.changeset.motivatedBy : null;
  const { principal } = useSession();
  const mine = card.actions.reason?.includes('author') || (!!principal && p.createdBy === `user:${principal.id}`);
  return (
    <>
      {mine && (p.status === 'DRAFT' || p.status === 'EVALUATED') && (
        <div className="callout info"><span>You suggested this change, so someone else needs to approve it. That keeps every change checked by two people.</span></div>
      )}

      {p.status === 'APPROVED' && card.actions.promotionBlocker && (
        <div className="callout danger" role="status" data-testid="promotion-blocker"><span><b>It cannot go live right now:</b> {card.actions.promotionBlocker}.</span></div>
      )}
      {p.status === 'APPROVED' && !card.actions.promotionBlocker && (card.actions.promotionConcerns?.length ?? 0) > 0 && (
        <div className="callout warn" role="status" data-testid="promotion-concerns">
          <span><b>Going live needs a written reason</b>, because:
            <ul className="small" style={{ margin: '4px 0 0', paddingLeft: 18 }}>{card.actions.promotionConcerns!.map((c, i) => <li key={i}>{c}</li>)}</ul>
          </span>
        </div>
      )}

      <section>
        <div className="section-title">What would change</div>
        <ul style={{ margin: 0, paddingLeft: 18 }}>{p.changeset.changes.map((c, i) => <li key={i}>{changeSentence(c)}</li>)}</ul>
        {card.diff.length > 0 && <DiffTable rows={card.diff} caption="Values" />}
        <div className="xs muted mt2">Suggested by {authorName(p.createdBy, names)} {relTime(p.createdAt)} · {TIER_WORDS[p.tier]}</div>
      </section>

      <section>
        <div className="section-title">Why it was suggested</div>
        {card.hypothesis?.rationale && <p className="small" style={{ margin: 0 }}>{card.hypothesis.rationale}</p>}
        {!card.hypothesis?.rationale && motive && <p className="small" style={{ margin: 0 }}>{motive}</p>}
        {card.evidence.length > 0 && <ul className="small muted mt2" style={{ margin: 0, paddingLeft: 18 }}>{card.evidence.map((e, i) => <li key={i}>{e}</li>)}</ul>}
        {!card.hypothesis?.rationale && !motive && card.evidence.length === 0 && <span className="small muted">No written reason was recorded.</span>}
      </section>

      <Replay card={card} />

      {card.criticReview && (
        <section>
          <div className="section-title">Second opinion <InfoTip text="An independent AI reviewer reads the change and its evidence and lists risks. It cannot approve anything." /></div>
          <div className="row gap2" style={{ alignItems: 'flex-start' }}>
            <Badge tone={VERDICT[card.criticReview.verdict]?.tone ?? 'neutral'}>{VERDICT[card.criticReview.verdict]?.label ?? humanize(card.criticReview.verdict)}</Badge>
            {card.criticReview.risks.length > 0 ? <ul className="small" style={{ margin: 0, paddingLeft: 16 }}>{card.criticReview.risks.map((r, i) => <li key={i}>{r}</li>)}</ul> : <span className="small muted">No risks listed.</span>}
          </div>
        </section>
      )}

      {card.cumulative && (
        <section>
          <div className="section-title">Since the last approved version <InfoTip text="Small automatic changes add up. This shows everything that changed since a person last approved this policy, and where it lands if this change goes live." /></div>
          <p className="small" style={{ margin: 0 }}>
            {card.cumulative.baseline?.approvedBy
              ? <>Last approved by <b>{card.cumulative.baseline.approvedBy.replace(/^user:/, '')}</b> {relTime(card.cumulative.baseline.approvedAt)}.</>
              : <>No change to this policy has been approved yet; the original version is the baseline.</>}
            {' '}{card.cumulative.automaticPromotionsSinceBaseline > 0 ? `${card.cumulative.automaticPromotionsSinceBaseline} automatic change${card.cumulative.automaticPromotionsSinceBaseline === 1 ? '' : 's'} since then.` : 'No automatic changes since then.'}
          </p>
          {card.cumulative.withPending.length > 0 && <DiffTable rows={card.cumulative.withPending} caption="If this goes live" />}
          {card.cumulative.withPending.length === 0 && card.cumulative.sinceBaseline.length > 0 && <DiffTable rows={card.cumulative.sinceBaseline} caption="Changed so far" />}
        </section>
      )}

      <section>
        <div className="section-title">History</div>
        <div className="subtle-list small">
          <div>Suggested {fmtDateTime(p.createdAt)}</div>
          {p.evaluatedAt && <div>Tested on past decisions {fmtDateTime(p.evaluatedAt)}</div>}
          {p.approvedAt && <div>Approved by {p.approvedBy?.replace(/^user:/, '')} {fmtDateTime(p.approvedAt)}</div>}
          {p.promotedAt && <div>Went live {fmtDateTime(p.promotedAt)}</div>}
          {card.promotionNote && (
            <div data-testid="promotion-note">Went live without full evidence: {card.promotionNote.by.replace(/^user:/, '')} wrote "{card.promotionNote.reason}"
              {card.promotionNote.waived.length > 0 && <span className="muted"> (waived: {card.promotionNote.waived.join('; ')})</span>}</div>
          )}
          {p.rejectedAt && <div>Rejected {fmtDateTime(p.rejectedAt)}{p.rejectedReason ? `: ${p.rejectedReason}` : ''}</div>}
          {p.rolledBackAt && <div>Rolled back {fmtDateTime(p.rolledBackAt)}{p.rolledBackReason ? `: ${p.rolledBackReason}` : ''}</div>}
        </div>
      </section>

      <Technical>
        <section>
          <div className="section-title">Technical details</div>
          <div className="code" style={{ whiteSpace: 'normal' }}><JsonTree data={{ proposalId: p.proposalId, target: p.changeset.target, baseVersion: p.changeset.baseVersion, changes: p.changeset.changes, evaluationReport: p.evaluationReport, createdBy: p.createdBy }} defaultOpen={1} /></div>
        </section>
      </Technical>
    </>
  );
}

function Replay({ card }: { card: ChangeCard }) {
  const r = card.replay;
  if (!r.evaluated) return (
    <section><div className="section-title">Tested on past decisions?</div><span className="small muted">Not yet. Testing replays this change against decisions already made, before anything goes live.</span></section>
  );
  const phrase = replayPhrase(r.liftEstimate, r.confidenceInterval);
  return (
    <section>
      <div className="section-title">Tested on past decisions? <InfoTip text="The change is replayed against logged decisions and their real outcomes. The range shows how sure the estimate is; it only counts as better when the whole range is above zero." /></div>
      <div className="row wrap gap3" style={{ alignItems: 'baseline' }}>
        <Badge tone={phrase.tone} lg>{phrase.text}</Badge>
        {r.confidenceInterval && <span className="small muted">likely between {fmtPct(r.confidenceInterval.lower, 1)} and {fmtPct(r.confidenceInterval.upper, 1)}</span>}
        {r.sampleSize !== undefined && <span className="small muted">{fmtNum(r.sampleSize)} past decisions</span>}
      </div>
      {r.method && <div className="xs muted mt1">{METHOD_WORDS[r.method] ?? humanize(r.method)}</div>}
      {r.rewards && r.rewards.mature + r.rewards.provisional > 0 && (
        <div className="xs muted mt1">{fmtPct(r.rewards.matureShare, 0)} of these outcomes are final; the rest may still change (returns, late deliveries).</div>
      )}
      {r.method === 'no_counterfactual' && (r.reason || r.next) && <div className="small muted mt1">{r.reason ? `${r.reason}. ` : ''}{r.next ?? ''}</div>}
      {r.caveat && <div className="xs muted mt1">{r.caveat}</div>}
      {r.ips?.confidenceInterval && (
        <Technical><div className="xs muted mono mt1">IPS cross-check: lift {fmtNum(r.ips.liftEstimate ?? 0, 4)} · CI [{fmtNum(r.ips.confidenceInterval.lower, 4)}, {fmtNum(r.ips.confidenceInterval.upper, 4)}]</div></Technical>
      )}
      {r.shadow && (
        <div className={`callout mt2 ${r.shadow.passed ? 'ok' : r.shadow.passed === false ? 'warn' : ''}`}>
          <span><b>Shadow test {r.shadow.passed ? 'passed' : r.shadow.passed === false ? 'did not pass' : 'running'}.</b> {String(r.shadow.reason ?? '')}</span>
        </div>
      )}
    </section>
  );
}

function keyLabel(key: string): string {
  const parts = key.split(':');
  if (parts[0] === 'threshold' && parts.length === 3) return `${decisionLabel(parts[1])}: ${parts[2]} threshold`;
  if (parts[0] === 'weight' && parts.length === 3) return `${humanizeKey(parts[1])}: ${parts[2]} weight`;
  if ((parts[0] === 'rule' || parts[0] === 'retire') && parts.length >= 3) return `${decisionLabel(parts[1])}: rule ${parts.slice(2).join(':')}`;
  if (key.startsWith('bands.')) return `${decisionLabel(key.slice('bands.'.length))} confidence threshold`;
  if (key.startsWith('live:')) return `${familyName(key.slice('live:'.length))} (used live)`;
  return decisionLabel(key.replace(/\./g, ' · '));
}

/** "sourcing.weights" → "Sourcing weights". */
function humanizeKey(k: string): string {
  const t = k.replace(/[._]/g, ' ').trim();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

function DiffTable({ rows, caption }: { rows: KeyDiff[]; caption: string }) {
  return (
    <div className="table-wrap mt2">
      <table className="diff-table" aria-label={caption}>
        <thead><tr><th scope="col">{caption}</th><th scope="col">From</th><th scope="col">To</th></tr></thead>
        <tbody>{rows.map((d) => (
          <tr key={d.key}><td>{keyLabel(d.key)}{d.steps > 1 && <span className="xs muted"> · over {d.steps} changes</span>}</td><td className="mono">{valueLabel(d.from)}</td><td className="mono">{valueLabel(d.to)}</td></tr>
        ))}</tbody>
      </table>
    </div>
  );
}

function Actions({ card, onDone }: { card: ChangeCard; onDone: () => void }) {
  const p: Proposal = card.proposal;
  const qc = useQueryClient();
  const { principal } = useSession();
  const [dialog, setDialog] = useState<Dialogs>(null);
  const done = (title: string, body?: string) => { toast.ok(title, body); setDialog(null); qc.invalidateQueries({ queryKey: ['learning'] }); onDone(); };
  const evaluate = useMutation({ mutationFn: () => learningApi.evaluate(p.proposalId), onSuccess: () => done('Tested on past decisions') });
  const approve = useMutation({ mutationFn: (code?: string) => learningApi.approve(p.proposalId, code), onSuccess: () => done('Approved', 'It goes live when promoted.') });
  const reject = useMutation({ mutationFn: (reason: string) => learningApi.reject(p.proposalId, reason), onSuccess: () => done('Rejected') });
  const [waivable, setWaivable] = useState<string[]>([]);
  const promote = useMutation({
    mutationFn: (overrideReason?: string) => learningApi.promote(p.proposalId, overrideReason),
    onSuccess: (_r, reason) => done('Live now', reason ? 'Your reason is recorded with the change. You can roll it back at any time.' : 'You can roll it back at any time.'),
    onError: (e) => {
      // EX-07: weak evidence or a used-up weekly budget; ask for a written reason instead of failing.
      if (e instanceof ApiError && e.code === 'promotion_needs_override') {
        const w = e.problem.waivable;
        setWaivable(Array.isArray(w) ? w.map(String) : []);
        setDialog('override');
        return;
      }
      const f = friendlyError(e instanceof ApiError ? e.problem : { status: 0, detail: e instanceof Error ? e.message : String(e) });
      toast.danger(f.title, f.message);
    },
  });
  const rollback = useMutation({ mutationFn: (reason: string) => learningApi.rollback(p.proposalId, reason), onSuccess: () => done('Rolled back', 'The previous version is back in use.') });
  const experiment = useMutation({ mutationFn: (mode: ExperimentMode) => learningApi.startExperiment(p.proposalId, mode, mode === 'CANARY' ? 0.1 : 1, {}), onSuccess: (_e, mode) => done(mode === 'SHADOW' ? 'Shadow test started' : 'Trial started on 10% of customers') });

  const a = card.actions;
  const open = p.status === 'DRAFT' || p.status === 'EVALUATED';
  const postal = isPostalScoped(p);
  const target = p.changeset.target;
  const canEvaluate = a.canEvaluate ?? open;
  const canExperiment = a.canExperiment ?? (open || p.status === 'APPROVED');
  const canRollback = a.canRollback ?? (p.status === 'PROMOTED' || p.status === 'CANARY');
  const shadowable = (target.type === 'decision' || target.name.startsWith('live:')) && canExperiment;
  const canaryable = target.type === 'decision' && canExperiment;
  const mine = principal ? `user:${principal.id}` === p.createdBy : false;
  const approveHint = p.status === 'DRAFT' ? 'Test it on past decisions before approving' : !a.canApprove || mine ? 'Someone other than the author must approve' : undefined;
  const concerns = a.promotionConcerns ?? [];
  const blocker = a.promotionBlocker ?? null;
  const goLive = () => { if (concerns.length > 0) { setWaivable(concerns); setDialog('override'); } else promote.mutate(undefined); };

  return (
    <div className="row wrap gap2" style={{ width: '100%' }}>
      {canEvaluate && <Button size="sm" loading={evaluate.isPending} onClick={() => evaluate.mutate()}>{card.replay.evaluated ? 'Test again' : 'Test on past decisions'}</Button>}
      {shadowable && <Button size="sm" variant="subtle" loading={experiment.isPending && experiment.variables === 'SHADOW'} onClick={() => experiment.mutate('SHADOW')}>Start shadow test</Button>}
      {canaryable && <Button size="sm" variant="subtle" loading={experiment.isPending && experiment.variables === 'CANARY'} onClick={() => experiment.mutate('CANARY')}>Try on 10% of customers</Button>}
      <span className="grow" />
      {a.canReject && <Button size="sm" variant="ghost" onClick={() => setDialog('reject')}>Reject</Button>}
      {open && <Button size="sm" variant="primary" disabled={!a.canApprove || mine} title={approveHint}
        loading={approve.isPending} onClick={() => (postal ? setDialog('approve') : approve.mutate(undefined))}>Approve</Button>}
      {p.status === 'APPROVED' && <Button size="sm" variant="ai" disabled={!!blocker} title={blocker ?? (concerns.length ? 'Needs a written reason: the evidence or weekly budget falls short' : undefined)}
        loading={promote.isPending} onClick={goLive}>{concerns.length > 0 ? 'Go live anyway…' : 'Go live'}</Button>}
      {canRollback && <Button size="sm" variant="danger" onClick={() => setDialog('rollback')}>{p.status === 'CANARY' ? 'Stop trial and roll back' : 'Roll back'}</Button>}

      <ReasonDialog open={dialog === 'approve'} title="Approve a postal-code change" confirmLabel="Approve" required={false} codes={POSTAL_REASON_CODES}
        description="This change only affects some postal codes, which can disadvantage a neighbourhood. Say why it is justified."
        loading={approve.isPending} onClose={() => setDialog(null)} onConfirm={(_r, code) => approve.mutate(code)} />
      <ReasonDialog open={dialog === 'reject'} title="Reject this change" confirmLabel="Reject" danger loading={reject.isPending}
        description="The learner will not suggest the same change again without new evidence." onClose={() => setDialog(null)} onConfirm={(r) => reject.mutate(r)} />
      <ReasonDialog open={dialog === 'override'} title="Go live without full evidence?" confirmLabel="Go live anyway" danger loading={promote.isPending}
        label="Why go live anyway?" minLength={MIN_OVERRIDE_REASON}
        description={<>
          <span>This change would normally wait, because:</span>
          <ul className="small" style={{ margin: '4px 0 8px', paddingLeft: 18 }}>{(waivable.length ? waivable : concerns).map((w, i) => <li key={i}>{w}</li>)}</ul>
          <span>Your reason is recorded with the change and in the audit trail.</span>
        </>}
        onClose={() => setDialog(null)} onConfirm={(r) => promote.mutate(r)} />
      <ReasonDialog open={dialog === 'rollback'} title="Roll back this change" confirmLabel="Roll back" danger loading={rollback.isPending}
        description="The exact previous version comes back for every new decision, and live use of any learned value it switched on stops." onClose={() => setDialog(null)} onConfirm={(r) => rollback.mutate(r)} />
    </div>
  );
}
