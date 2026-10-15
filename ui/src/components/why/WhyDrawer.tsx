import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { decisionRecordsApi } from '../../api/decisions';
import { copilotApi } from '../../api/copilot';
import type { DecisionTrace, TraceQuestion } from '../../api/types';
import { fmtConf, fmtDateTime, fmtUsd, humanize, valueLabel } from '../../lib/format';
import { toast } from '../../lib/toast';
import { useSession, hasPermission } from '../../auth/session';
import { Drawer } from '../Overlay';
import { Badge, BandBadge, LayerBadge, TierBadge } from '../Badge';
import { Button } from '../Button';
import { Switch, Textarea, Select, Input } from '../Form';
import { JsonTree, KeyValue, LayerStepper, ProbBar, ScorePips } from '../Misc';
import { ErrorState, Loading } from '../States';
import { IconCheck, IconClose, IconSparkle } from '../Icons';

export function WhyDrawer({ recordId, onClose }: { recordId: string | null; onClose: () => void }) {
  const [customer, setCustomer] = useState(false);
  const q = useQuery({ queryKey: ['why', recordId, customer ? 'customer' : 'internal'], queryFn: () => decisionRecordsApi.why(recordId!, customer ? 'customer' : 'internal'), enabled: !!recordId });
  return (
    <Drawer open={!!recordId} onClose={onClose} width={680}
      title={<span className="row gap3">Why<span className="id muted">{recordId}</span></span>}
      subtitle={q.data ? <span className="row wrap gap3"><span>{q.data.definition.name} <span className="mono">v{q.data.definition.version}</span></span><TierBadge tier={q.data.definition.tier} /><BandBadge band={q.data.band} /><LayerBadge layer={q.data.record.layer} /><Badge tone="outline">{q.data.record.mode}</Badge></span> : undefined}
      footer={<><Switch label="Customer-safe view" checked={customer} onChange={setCustomer} /><span className="grow" /><Button onClick={onClose}>Close</Button></>}>
      {q.isLoading && <Loading label="Loading trace…" />}
      {q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
      {q.data && <WhyBody trace={q.data} customer={customer} />}
    </Drawer>
  );
}

export function WhyBody({ trace, customer }: { trace: DecisionTrace; customer: boolean }) {
  const r = trace.record;
  const mainQ = trace.questions[0];
  return (
    <>
      {customer && <div className="callout info"><span>Customer-safe view (FR-XAI-04): internal margin, cost, risk and history fields are hidden. Audience: <b>{trace.audience ?? 'customer'}</b>.</span></div>}
      {customer && trace.customerSafeExplanation && <p>{trace.customerSafeExplanation}</p>}
      {!customer && trace.explanation && <div className="callout"><span><Badge tone="outline" style={{ marginRight: 8 }}>generated</Badge>{trace.explanation}</span></div>}
      <ExplainPanel recordId={r.id} customer={customer} />

      <section>
        <div className="section-title">Outcome</div>
        <div className="row wrap gap3" style={{ alignItems: 'baseline' }}>
          <span style={{ fontSize: 'var(--fs-xl)', fontWeight: 650 }} className="mono">{outcomeLabel(trace)}</span>
          <span className="muted">action <b>{trace.action}</b> · confidence <b className="num">{fmtConf(r.confidence)}</b></span>
        </div>
        <ConfidenceGauge confidence={r.confidence} bands={trace.definition.bands} tier={trace.definition.tier} />
        {trace.override && <div className="callout warn mt3"><span>Overridden by <b>{trace.override.by}</b> {fmtDateTime(trace.override.at)}: <span className="mono">{valueLabel(trace.override.value)}</span> — {trace.override.reason}</span></div>}
      </section>

      <section>
        <div className="section-title">Layer path</div>
        <LayerStepper steps={trace.layers} final={r.layer} />
        <ul className="small muted mt2" style={{ margin: 0, paddingLeft: 18 }}>{trace.layers.filter((l) => l.ran).map((l) => <li key={l.layer}><b style={{ color: 'var(--text)' }}>{l.layer.replace(/_/g, ' ')}</b> — {l.outcome}</li>)}</ul>
        {trace.escalation && <div className="callout mt2"><span>Escalated to <b>{trace.escalation.queue ?? 'human queue'}</b>{trace.escalation.taskId && <> · task <Link to={`/tasks?task=${trace.escalation.taskId}`} className="mono">{trace.escalation.taskId}</Link></>} · {trace.escalation.status}</span></div>}
      </section>

      {!customer && (
        <section>
          <div className="section-title">Definition & provider</div>
          <KeyValue tight items={[
            ['Decision', <span className="mono">{trace.definition.name} v{trace.definition.version}</span>],
            ['Owner', trace.definition.owner], ['Tier', <TierBadge tier={trace.definition.tier} title />],
            ['Provider', <span className="mono">{trace.provider}{trace.modelVersion ? ` · ${trace.modelVersion}` : ''}</span>],
            ['Latency', <span className="num">{trace.latencyMs} ms</span>], ['Cost', <span className="num">{fmtUsd(trace.costUsd, 4)}{trace.inputTokens ? ` · ${trace.inputTokens} in / ${trace.outputTokens} out tokens` : ''}</span>],
            ['Evaluated', fmtDateTime(r.evaluatedAt)], ['Actor', <span className="mono">{trace.actor}</span>],
            ['Subject', <Link to={subjectLink(r.subjectType, r.subjectId)} className="mono">{r.subjectType}/{r.subjectId}</Link>],
            ['Input hash', <span className="mono small">{trace.inputHash ?? '—'}</span>],
          ]} />
        </section>
      )}

      <section>
        <div className="section-title">Questions & answers</div>
        <div className="col gap3">{trace.questions.map((qq) => <QuestionCard key={qq.key} q={qq} main={qq === mainQ} />)}</div>
      </section>

      {trace.rules.length > 0 && !customer && (
        <section>
          <div className="section-title">L0 rules</div>
          <div className="subtle-list small">{trace.rules.map((rule) => <div key={rule.id} className="row gap3" style={{ alignItems: 'flex-start' }}>{rule.matched ? <Badge tone="ok"><IconCheck size={11} /> fired</Badge> : <Badge tone="outline">no match</Badge>}<div className="grow"><div><b>{rule.id}</b> → <span className="mono">{rule.then}</span></div><div className="mono muted xs">when {rule.when}</div>{rule.reason && <div className="muted xs">{rule.reason}</div>}</div></div>)}</div>
        </section>
      )}

      {trace.checks.length > 0 && !customer && (
        <section>
          <div className="section-title">Pre / post checks (GR-01)</div>
          <div className="subtle-list small">{trace.checks.map((c, i) => <div key={i} className="row gap3">{c.passed ? <Badge tone="ok"><IconCheck size={11} /> pass</Badge> : <Badge tone="danger"><IconClose size={11} /> fail</Badge>}<Badge tone="outline">{c.phase}</Badge><span className="mono xs">{c.expression}</span>{c.message && <span className="muted">{c.message}</span>}</div>)}</div>
        </section>
      )}

      <section id="why-raw-trace" tabIndex={-1}>
        <div className="section-title">Inputs passed{!customer && trace.tokenisedFields?.length ? <span className="muted" style={{ fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}> · tokenised: {trace.tokenisedFields.join(', ')}</span> : null}</div>
        <div className="code" style={{ whiteSpace: 'normal' }}><JsonTree data={trace.inputs} tokenised={trace.tokenisedFields ?? []} /></div>
      </section>

      {!customer && <Feedback trace={trace} />}
    </>
  );
}

/** FR-XAI-03: plain-English explanation generated by the copilot from the stored trace; honours the customer-safe audience. */
function ExplainPanel({ recordId, customer }: { recordId: string; customer: boolean }) {
  const [wanted, setWanted] = useState(false);
  const audience = customer ? 'customer' : 'internal';
  const q = useQuery({ queryKey: ['copilot-explain', recordId, audience], queryFn: () => copilotApi.explain(recordId, audience), enabled: wanted });
  return (
    <div className="col gap2">
      <div className="row wrap gap3">
        <Button size="sm" icon={<IconSparkle size={13} />} loading={q.isFetching} onClick={() => { if (wanted) q.refetch(); else setWanted(true); }} aria-expanded={wanted && !!q.data}>Explain in plain English</Button>
        <span className="xs muted">Generated for the <b>{audience}</b> audience from the trace below; the trace is the source of truth.</span>
      </div>
      {wanted && q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
      {wanted && q.data && <div className="callout info" role="region" aria-label="Plain-English explanation">
        <div className="col gap2 grow">
          <div className="row wrap gap3"><Badge tone="outline">generated</Badge><span className="xs muted mono">{q.data.model}</span><Badge tone="outline">{q.data.audience}</Badge><span className="grow" /><a href="#why-raw-trace" className="xs" onClick={(e) => { e.preventDefault(); document.getElementById('why-raw-trace')?.scrollIntoView({ behavior: 'smooth', block: 'start' }); document.getElementById('why-raw-trace')?.focus(); }}>View raw trace ↓</a></div>
          <p className="small" style={{ lineHeight: 1.55, color: 'var(--text)' }}>{q.data.text}</p>
        </div>
      </div>}
    </div>
  );
}

function outcomeLabel(t: DecisionTrace): string {
  const q = t.questions[0];
  if (q?.type === 'score' && q.levels) return `${valueLabel(t.record.value)} · ${q.levels[Number(t.record.value)] ?? ''}`;
  if (q?.type === 'noul') return `${Number(t.record.value) >= 0.5 || t.record.value === true ? 'true' : 'false'} (p=${typeof q.answer.value === 'number' ? q.answer.value.toFixed(2) : valueLabel(q.answer.value)})`;
  return valueLabel(t.record.value);
}
function subjectLink(type: string, id: string): string {
  if (type === 'order') return `/orders/${encodeURIComponent(id)}`;
  if (type === 'return') return `/returns/${encodeURIComponent(id)}`;
  if (type === 'exception') return `/exceptions?selected=${encodeURIComponent(id)}`;
  return '#';
}

export function ConfidenceGauge({ confidence, bands, tier }: { confidence: number; bands: { actGte: number; confirmGte: number }; tier: string }) {
  const act = Math.min(1, bands.actGte); const conf = Math.min(act, bands.confirmGte);
  return (
    <div className="mt3" aria-label={`confidence ${confidence.toFixed(2)}; escalate below ${conf}, confirm from ${conf}, act from ${act}`}>
      <div className="band-viz" style={{ height: 18 }}>
        <span className="esc" style={{ width: `${conf * 100}%` }}>{conf > 0.12 && 'escalate'}</span>
        <span className="conf" style={{ width: `${(act - conf) * 100}%` }}>{act - conf > 0.14 && 'confirm'}</span>
        <span className="act" style={{ width: `${(1 - act) * 100}%` }}>{1 - act > 0.1 ? 'act' : ''}</span>
        <span className="conf-marker" style={{ left: `calc(${Math.min(100, confidence * 100)}% - 1px)`, background: 'var(--text)', width: 3 }} title={`confidence ${confidence.toFixed(2)}`} />
      </div>
      <div className="row-between xs muted mt2"><span>0</span><span>{tier} thresholds: confirm ≥ {conf} · act ≥ {act}</span><span>1</span></div>
    </div>
  );
}

function QuestionCard({ q, main }: { q: TraceQuestion; main: boolean }) {
  const a = q.answer;
  return (
    <div className="q-card">
      <div className="q-head"><Badge tone="accent" mono>{q.type}</Badge><b className="mono">{q.key}</b>{main && <Badge tone="outline">primary</Badge>}{q.escape && <Badge tone="warn">escape option chosen</Badge>}<span className="grow" /><span className="small muted">confidence <b className="num">{fmtConf(a.confidence)}</b></span></div>
      {q.instructions && <div className="small">{q.instructions}</div>}
      {q.statement && <div className="small">“{q.statement}”</div>}
      {q.type === 'choice' && a.probabilities && <div className="col gap1">{Object.entries(a.probabilities).sort((x, y) => y[1] - x[1]).map(([k, p]) => <div key={k}><ProbBar label={k} value={p} chosen={String(a.value) === k} escape={q.escape && String(a.value) === k} />{q.criteria?.[k] && <div className="xs faint" style={{ paddingLeft: 0 }}>{q.criteria[k]}</div>}</div>)}</div>}
      {q.type === 'score' && a.probabilities && <div className="col gap1"><div className="row gap3"><ScorePips value={Number(a.value)} max={(q.levels ?? []).length || 5} /><b>{q.levels?.[Number(a.value)] ?? valueLabel(a.value)}</b></div>{Object.entries(a.probabilities).map(([k, p]) => <ProbBar key={k} label={`${k} · ${(q.levels?.[Number(k)] ?? '').slice(0, 28)}`} value={p} chosen={String(a.value) === k} />)}</div>}
      {q.type === 'noul' && <div className="col gap1"><ProbBar label="true" value={Number(a.probabilities?.true ?? a.value)} chosen={Number(a.value) >= 0.5} tone={Number(a.value) >= 0.5 ? 'warn' : 'ok'} /><ProbBar label="false" value={Number(a.probabilities?.false ?? 1 - Number(a.value))} chosen={Number(a.value) < 0.5} /></div>}
    </div>
  );
}

function Feedback({ trace }: { trace: DecisionTrace }) {
  const qc = useQueryClient(); const { principal } = useSession();
  const canOverride = hasPermission(principal, 'decision.override');
  const r = trace.record; const q = trace.questions[0];
  const options = q?.type === 'choice' ? Object.keys(q.criteria ?? {}) : q?.type === 'score' ? (q.levels ?? []).map((_, i) => String(i)) : ['true', 'false'];
  const [value, setValue] = useState(options.find((o) => o !== String(r.value)) ?? options[0] ?? '');
  const [reason, setReason] = useState(''); const [label, setLabel] = useState(trace.label?.label ?? '');
  const invalidate = () => { qc.invalidateQueries({ queryKey: ['why', r.id] }); qc.invalidateQueries({ queryKey: ['order'] }); qc.invalidateQueries({ queryKey: ['decision-records'] }); };
  const override = useMutation({ mutationFn: () => decisionRecordsApi.override(r.id, q?.type === 'score' ? Number(value) : q?.type === 'noul' ? value === 'true' : value, reason), onSuccess: () => { toast.ok('Override recorded', 'Fed back into evaluation (GR-13)'); setReason(''); invalidate(); } });
  const lab = useMutation({ mutationFn: () => decisionRecordsApi.label(r.id, label), onSuccess: () => { toast.ok('Outcome label saved'); invalidate(); } });
  return (
    <section className="grid grid-2">
      <div className="card card-pad col">
        <div className="section-title">Override (GR-13)</div>
        {!canOverride && <div className="muted small">Your role cannot override decisions.</div>}
        <Select label="New value" value={value} onChange={(e) => setValue(e.target.value)} disabled={!canOverride}>{options.map((o) => <option key={o} value={o}>{o}{q?.type === 'score' && q.levels ? ` · ${q.levels[Number(o)]}` : ''}</option>)}</Select>
        <Textarea label="Reason (required)" value={reason} onChange={(e) => setReason(e.target.value)} rows={2} disabled={!canOverride} placeholder="Why the model was wrong, in one or two sentences" />
        <div className="flex-end"><Button variant="primary" size="sm" disabled={!canOverride || !reason.trim()} loading={override.isPending} onClick={() => override.mutate()}>Record override</Button></div>
      </div>
      <div className="card card-pad col">
        <div className="section-title">Outcome label (FR-DEC-10)</div>
        <Input label="Label" list="outcome-labels" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="e.g. outcome_confirmed" />
        <datalist id="outcome-labels">{['outcome_confirmed', 'outcome_wrong', 'customer_complained', 'delivered_on_time', 'delivered_late', 'return_abusive', 'return_legitimate'].map((o) => <option key={o} value={o} />)}</datalist>
        {trace.label && <div className="xs muted">Current: <b>{trace.label.label}</b> by {trace.label.by}</div>}
        <div className="flex-end"><Button size="sm" disabled={!label.trim()} loading={lab.isPending} onClick={() => lab.mutate()}>Save label</Button></div>
      </div>
      <div className="span2 xs muted" style={{ gridColumn: 'span 2' }}>Record <span className="mono">{r.id}</span> · {humanize(r.decision)} · evaluated {fmtDateTime(r.evaluatedAt)}</div>
    </section>
  );
}
