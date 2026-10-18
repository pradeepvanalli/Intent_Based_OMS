import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { RetentionResult } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select } from '../../components/Form';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { fmtDate, fmtNum, fmtPct, humanize, relTime, valueLabel } from '../../lib/format';
import { toast } from '../../lib/toast';
import { InfoTip, Technical } from './Bits';
import { ObjectiveSettings } from './ObjectiveEditor';

const FAIRNESS_DECISIONS = ['fulfilment_intent', 'split_acceptability', 'sourcing_node_score', 'payment_risk', 'return_risk', 'rto_risk'];

/** What counts as a good outcome, test cases to sign off, fairness, weekly summaries and data retention. */
export function SettingsTab({ canAdmin, canApproveDecisions }: { canAdmin: boolean; canApproveDecisions: boolean }) {
  return (
    <div className="col gap4">
      <Section title={<span className="row gap2">What a good outcome means <InfoTip text="Every learner works toward this score. Hard rules (margin floor, compliance holds, refunds and cancellations never automatic) are never traded away for it." /></span>}>
        <ObjectiveSettings canAdmin={canAdmin} />
      </Section>
      <GoldenCandidates canSignOff={canAdmin && canApproveDecisions} />
      <Fairness />
      <Digests canAdmin={canAdmin} />
      {canAdmin && <Retention />}
    </div>
  );
}

function GoldenCandidates({ canSignOff }: { canSignOff: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['learning', 'golden-candidates'], queryFn: learningApi.goldenCandidates });
  const invalidate = () => qc.invalidateQueries({ queryKey: ['learning', 'golden-candidates'] });
  const sign = useMutation({ mutationFn: (id: string) => learningApi.signOffGolden(id), onSuccess: () => { toast.ok('Added to the test cases', 'Every future version of the decision must pass it.'); invalidate(); } });
  const reject = useMutation({ mutationFn: (id: string) => learningApi.rejectGolden(id), onSuccess: () => { toast.ok('Dismissed'); invalidate(); } });
  return (
    <Section title={<span className="row gap2">Suggested test cases <InfoTip text="Situations people keep resolving the same way. Signing one off adds it to the decision's test set, so no future version can get it wrong." /></span>}>
      {q.isLoading && <Loading />}
      {q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
      {q.data && q.data.length === 0 && <EmptyState title="No suggestions right now" hint="They appear when the same situation is resolved the same way several times." />}
      <div className="subtle-list">{(q.data ?? []).map((c) => (
        <div key={c.hypothesisId} className="row-between wrap gap3">
          <div className="col gap1">
            <span className="small"><b>{humanize(c.decision)}</b> should answer <span className="mono">{valueLabel(c.expectedValue)}</span></span>
            <span className="xs muted">{c.agreeingResolutions} matching human decisions · {relTime(c.createdAt)}{c.rationale ? ` · ${c.rationale}` : ''}</span>
            <Technical><span className="xs muted mono">{c.hypothesisId} · records {(c.sourceRecordIds ?? []).slice(0, 3).join(', ')}</span></Technical>
          </div>
          {canSignOff && <span className="row gap2"><Button size="sm" variant="primary" loading={sign.isPending && sign.variables === c.hypothesisId} onClick={() => sign.mutate(c.hypothesisId)}>Sign off</Button><Button size="sm" variant="ghost" loading={reject.isPending && reject.variables === c.hypothesisId} onClick={() => reject.mutate(c.hypothesisId)}>Dismiss</Button></span>}
        </div>
      ))}</div>
      {!canSignOff && (q.data?.length ?? 0) > 0 && <p className="caption mt2">Signing off needs both learning admin and decision approval permissions.</p>}
    </Section>
  );
}

function Fairness() {
  const [decision, setDecision] = useState(FAIRNESS_DECISIONS[0]);
  const q = useQuery({ queryKey: ['learning', 'fairness', decision], queryFn: () => learningApi.fairness(decision) });
  const r = q.data;
  return (
    <Section title={<span className="row gap2">Fairness check <InfoTip text="Compares how often each customer group is decided automatically and how often people override it. Learners never use protected traits such as age, gender or ethnicity." /></span>}
      actions={<Select sm aria-label="Decision to check" value={decision} onChange={(e) => setDecision(e.target.value)}>{FAIRNESS_DECISIONS.map((d) => <option key={d} value={d}>{humanize(d)}</option>)}</Select>}>
      {q.isLoading && <Loading />}
      {q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
      {r && (
        <>
          {r.flags.length > 0 ? <div className="callout warn mb2"><span>{r.flags.join(' ')}</span></div> : <p className="caption">No group stands out for this decision.</p>}
          {r.groups.length === 0 ? <EmptyState title="Not enough decisions yet" /> : (
            <div className="table-wrap">
              <table className="tbl dense" aria-label="Fairness by customer group">
                <thead><tr><th scope="col">Customer group</th><th scope="col" className="num">Customers</th><th scope="col" className="num">Decided automatically</th><th scope="col" className="num">Overridden by people</th></tr></thead>
                <tbody>
                  {r.groups.map((g) => <tr key={g.group}><td>{g.label ?? humanize(g.group)}</td><td className="num">{fmtNum(g.customers)}</td><td className="num">{fmtPct(g.actBandRate, 0)}</td><td className="num">{fmtPct(g.overrideRate, 1)}</td></tr>)}
                  <tr><td className="muted">All customers</td><td /><td className="num muted">{fmtPct(r.tenantActBandRate, 0)}</td><td className="num muted">{fmtPct(r.tenantOverrideRate, 1)}</td></tr>
                </tbody>
              </table>
            </div>
          )}
          <Technical>
            <div className="xs muted mt2">Grouped by {r.grouping ?? 'segment'}.{(r.featureAudit ?? []).some((a) => a.protectedFeatures.length) ? ` Protected inputs found: ${(r.featureAudit ?? []).filter((a) => a.protectedFeatures.length).map((a) => `${a.learner} (${a.protectedFeatures.join(', ')})`).join('; ')}` : ' No learner declares a protected input.'}</div>
          </Technical>
        </>
      )}
    </Section>
  );
}

function Digests({ canAdmin }: { canAdmin: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['learning', 'digest', 'list'], queryFn: () => learningApi.digests(8) });
  const run = useMutation({ mutationFn: () => learningApi.runDigest(7), onSuccess: () => { toast.ok('Weekly summary written'); qc.invalidateQueries({ queryKey: ['learning', 'digest'] }); } });
  return (
    <Section title="Weekly summaries" actions={canAdmin ? <Button size="sm" variant="subtle" loading={run.isPending} onClick={() => run.mutate()}>Write one now</Button> : undefined}>
      {q.isLoading && <Loading />}
      {q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
      {q.data && q.data.length === 0 && <EmptyState title="No summaries yet" hint="One is written every Monday morning and emailed to the addresses in learning.digest.recipients." />}
      <div className="subtle-list small">{(q.data ?? []).map((d) => (
        <div key={d.digestId} className="col gap1">
          <span className="row-between wrap gap2"><b>{fmtDate(d.periodStart)} – {fmtDate(d.periodEnd)}</b><Badge tone={d.delivery === 'sent' ? 'ok' : 'outline'}>{d.delivery === 'stored_only' ? 'Not emailed' : humanize(d.delivery)}</Badge></span>
          {d.summary.headline && <span>{d.summary.headline}</span>}
        </div>
      ))}</div>
    </Section>
  );
}

function Retention() {
  const [result, setResult] = useState<RetentionResult | null>(null);
  const dry = useMutation({ mutationFn: () => learningApi.retention(true), onSuccess: setResult });
  return (
    <Section title={<span className="row gap2">Data retention <InfoTip text="Learning keeps outcomes and episodes for 400 days by default, then removes them. The audit ledger is never pruned." /></span>}
      actions={<Button size="sm" variant="subtle" loading={dry.isPending} onClick={() => dry.mutate()}>Check what would be removed</Button>}>
      {!result && <p className="caption">Nothing is removed by checking; it only counts.</p>}
      {result && (
        <p className="small" style={{ margin: 0 }}>
          Keeping {result.retentionDays} days (everything before {fmtDate(result.cutoff)}). A run now would remove {fmtNum(result.outcomes)} outcomes, {fmtNum(result.episodes)} episodes, {fmtNum(result.supersededFacts)} outdated learned values and {fmtNum(result.digests)} old summaries.
        </p>
      )}
    </Section>
  );
}
