import { useMemo, useState, type ReactNode } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { copilotApi } from '../../api/copilot';
import type { ChangeSet, ChangeTestCase, ProposedChange } from '../../api/types';
import { Badge, StatusBadge, TierBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { DiffViewer } from '../../components/DiffViewer';
import { Checkbox, Textarea } from '../../components/Form';
import { Dialog } from '../../components/Overlay';
import { Table } from '../../components/Table';
import { IconCheck, IconClose, IconExternal, IconSparkle } from '../../components/Icons';
import { fmtDateTime, fmtUsd, humanize, safeJson, valueLabel } from '../../lib/format';
import { toast } from '../../lib/toast';
import { stagedLink } from './shared';

const KIND_TONE: Record<string, string> = { decision_rule: 'accent', decision_criteria: 'accent', decision_bands: 'accent', decision_new: 'accent', state_model: 'info', order_type: 'active', sla: 'warn', playbook: 'hold', tenant_config: 'neutral', compliance_rule: 'risk' };
export function KindBadge({ kind }: { kind: string }) { return <Badge tone={KIND_TONE[kind] ?? 'neutral'} mono aria-label={`kind ${humanize(kind)}`}>{humanize(kind)}</Badge>; }

export function ChangeSetView({ cs, onUpdated, onResubmit, resubmitting, canStage, headerActions }: { cs: ChangeSet; onUpdated: (cs: ChangeSet) => void; onResubmit?: (answers: Record<string, string>) => void; resubmitting?: boolean; canStage: boolean; headerActions?: ReactNode }) {
  const stageable = useMemo(() => cs.changes.filter((c) => !c.staged && c.validation.ok).map((c) => c.id), [cs]);
  const [selected, setSelected] = useState<Set<string>>(() => new Set(stageable));
  const [rejectOpen, setRejectOpen] = useState(false); const [reason, setReason] = useState('');
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const stage = useMutation({ mutationFn: (ids: string[]) => copilotApi.stage(cs.id, ids), onSuccess: (r) => { const n = r.changes.filter((c) => c.staged).length; toast.ok(`Staged ${n} change${n === 1 ? '' : 's'} as drafts`, 'Decision changes are studio drafts; everything else is an artefact awaiting apply.'); onUpdated(r); setSelected(new Set()); } });
  const reject = useMutation({ mutationFn: () => copilotApi.reject(cs.id, reason), onSuccess: (r) => { toast.ok('Change set rejected'); setRejectOpen(false); setReason(''); onUpdated(r); } });
  const toggle = (id: string) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const rejected = cs.status === 'rejected'; const sel = [...selected].filter((id) => stageable.includes(id));
  const staged = cs.changes.filter((c) => c.staged);
  const unanswered = cs.questions.filter((q) => !answers[q]?.trim());
  return (
    <div className="col gap4" aria-label="Proposed change set">
      <section className="card card-pad col gap3">
        <div className="row wrap gap3" style={{ alignItems: 'flex-start' }}>
          <div className="grow col gap1">
            <div className="row wrap gap3"><StatusBadge status={cs.status} lg /><span className="id muted">{cs.id}</span><Badge tone="outline">scope {humanize(cs.request.scope)}</Badge><span className="xs muted">by {cs.createdBy} · {fmtDateTime(cs.createdAt)}</span></div>
            <p style={{ fontSize: 'var(--fs-lg)', lineHeight: 1.5 }}>{cs.summary}</p>
            <div className="xs muted">Request: “{cs.request.text}”{cs.usage && <> · {cs.usage.model} · {cs.usage.inputTokens.toLocaleString()} in / {cs.usage.outputTokens.toLocaleString()} out tokens · {fmtUsd(cs.usage.costUsd, 4)}</>}</div>
          </div>
          <div className="row wrap gap3">{headerActions}{canStage && !rejected && <Button variant="primary" disabled={!sel.length} loading={stage.isPending} onClick={() => stage.mutate(sel)}>Stage selected as drafts{sel.length ? ` (${sel.length})` : ''}</Button>}{canStage && !rejected && <Button variant="danger" onClick={() => setRejectOpen(true)}>Reject…</Button>}</div>
        </div>
        {staged.length > 0 && <div className="callout ok"><IconCheck size={14} /><div className="staged-summary"><b>{staged.length} of {cs.changes.length} changes staged.</b>{staged.map((c) => { const l = stagedLink(c)!; return <span key={c.id}>{c.title} → <Link to={l.to}>{l.label}</Link></span>; })}</div></div>}
        {rejected && <div className="callout danger"><IconClose size={14} /><span>This change set was rejected; nothing was staged from it.</span></div>}
        <div className="grid grid-2">
          <div><div className="section-title">Assumptions</div>{cs.assumptions.length ? <ul className="small" style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 4 }}>{cs.assumptions.map((a, i) => <li key={i}>{a}</li>)}</ul> : <span className="small muted">None stated.</span>}</div>
          <div>
            <div className="section-title">Clarifying questions{cs.questions.length ? <Badge tone="warn" style={{ marginLeft: 8 }}>{cs.questions.length}</Badge> : null}</div>
            {!cs.questions.length && <span className="small muted">No open questions.</span>}
            {cs.questions.length > 0 && <div className="col gap3">
              {cs.questions.map((q) => <div key={q} className="q-answer"><div className="small"><IconSparkle size={12} style={{ verticalAlign: -2, marginRight: 4, color: 'var(--accent)' }} />{q}</div><Textarea aria-label={`Answer: ${q}`} rows={2} placeholder="Your answer…" value={answers[q] ?? ''} onChange={(e) => setAnswers({ ...answers, [q]: e.target.value })} disabled={!onResubmit || rejected} /></div>)}
              {onResubmit && !rejected && <div className="flex-end"><Button size="sm" disabled={unanswered.length > 0} loading={resubmitting} onClick={() => onResubmit(answers)} title={unanswered.length ? 'Answer every question to resubmit' : undefined}>Resubmit with answers</Button></div>}
            </div>}
          </div>
        </div>
      </section>

      <div className="col gap3" role="list" aria-label="Proposed changes">
        {cs.changes.map((c) => <ChangeCard key={c.id} cs={cs} change={c} selectable={canStage && !rejected && !c.staged && c.validation.ok} selected={selected.has(c.id)} onToggle={() => toggle(c.id)} />)}
      </div>

      <Dialog open={rejectOpen} onClose={() => setRejectOpen(false)} title="Reject change set" description="Nothing from this proposal will be staged. The reason is written to the audit log." footer={<><Button onClick={() => setRejectOpen(false)}>Cancel</Button><Button variant="danger" disabled={!reason.trim()} loading={reject.isPending} onClick={() => reject.mutate()}>Reject</Button></>}>
        <Textarea label="Reason (required)" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why this proposal should not proceed" />
      </Dialog>
    </div>
  );
}

function ChangeCard({ cs, change: c, selectable, selected, onToggle }: { cs: ChangeSet; change: ProposedChange; selectable: boolean; selected: boolean; onToggle: () => void }) {
  const [explanation, setExplanation] = useState<string | null>(null);
  const explain = useMutation({ mutationFn: () => copilotApi.explainChange(cs.id, c.id), onSuccess: (r) => setExplanation(r.text) });
  const addTests = useMutation({ mutationFn: () => copilotApi.addTests(cs.id, c.id), onSuccess: (r) => toast.ok(`${r.added} test${r.added === 1 ? '' : 's'} added`, `Golden set ${r.goldenSet}`) });
  const issues = c.validation.issues.filter((i) => i.severity !== 'info');
  const plainWarnings = c.validation.warnings ?? [];
  const errors = issues.filter((i) => i.severity === 'error').length; const warnings = issues.filter((i) => i.severity === 'warning').length + plainWarnings.length;
  const link = stagedLink(c);
  return (
    <article className={['change-card', c.staged && 'staged', !c.validation.ok && 'invalid'].filter(Boolean).join(' ')} role="listitem" aria-labelledby={`${c.id}-title`}>
      <div className="cc-head">
        {selectable && <Checkbox label={<span className="sr-only">Select {c.title}</span>} checked={selected} onChange={onToggle} />}
        <KindBadge kind={c.kind} />
        <span className="mono small">{c.target}</span>
        <TierBadge tier={c.riskTier} />
        <Badge tone="outline" title="Approvals required before going live">{c.requiresApprovals} approval{c.requiresApprovals === 1 ? '' : 's'}</Badge>
        {c.validation.ok ? <Badge tone={warnings ? 'warn' : 'ok'} dot>{warnings ? `valid · ${warnings} warning${warnings > 1 ? 's' : ''}` : 'valid'}</Badge> : <Badge tone="danger" dot>{errors} error{errors > 1 ? 's' : ''}</Badge>}
        <span className="grow" />
        {link && <Link to={link.to} className="small row gap1"><IconCheck size={12} /> {link.label}{c.staged?.status && c.staged.kind === 'decision_draft' ? ` · ${c.staged.status}` : ''} <IconExternal size={12} /></Link>}
        {c.staged?.merged && <Badge tone="info" title={c.staged.mergedChangeIds?.length ? `Merged with ${c.staged.mergedChangeIds.join(', ')}` : 'Merged into an existing draft'}>merged into existing draft</Badge>}
      </div>
      <div className="cc-body">
        <h3 id={`${c.id}-title`}>{c.title}</h3>
        <p className="small muted" style={{ lineHeight: 1.5 }}>{c.rationale}</p>
        {(issues.length > 0 || plainWarnings.length > 0) && <div className="issue-list" aria-label="Validation findings">{issues.map((i, k) => <div key={k} className={`issue ${i.severity}`}><Badge tone={i.severity === 'error' ? 'danger' : 'warn'}>{i.severity}</Badge><div><div>{i.message}</div><code>{i.code}{i.path ? ` · ${i.path}` : ''}</code></div></div>)}{plainWarnings.map((w, k) => <div key={`w${k}`} className="issue warning"><Badge tone="warn">warning</Badge><div>{w}</div></div>)}</div>}
        <DiffViewer before={c.before} after={c.after} diff={c.diff} leftLabel={`${c.target} (current)`} rightLabel={`${c.target} (proposed)`} maxHeight={420} />
        <TestsTable tests={c.tests} />
        {explanation && <div className="callout"><Badge tone="outline">generated</Badge><span className="small" style={{ lineHeight: 1.5 }}>{explanation}</span></div>}
      </div>
      <div className="cc-foot">
        <Button size="sm" icon={<IconSparkle size={13} />} loading={explain.isPending} onClick={() => (explanation ? setExplanation(null) : explain.mutate())}>{explanation ? 'Hide explanation' : 'Explain'}</Button>
        <Button size="sm" disabled={!c.tests.length} loading={addTests.isPending} onClick={() => addTests.mutate()}>Add tests to golden set</Button>
        <span className="grow" />
        <span className="xs muted">{c.tests.length} generated test{c.tests.length === 1 ? '' : 's'}</span>
      </div>
    </article>
  );
}

function TestsTable({ tests }: { tests: ChangeTestCase[] }) {
  const [open, setOpen] = useState(false);
  if (!tests.length) return <div className="xs muted">No generated tests.</div>;
  return (
    <div>
      <button type="button" className="btn btn-sm btn-ghost" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? 'Hide' : 'Show'} generated tests ({tests.length})</button>
      {open && <div className="mt2"><Table rows={tests} rowKey={(t) => t.caseId} dense caption="Generated test cases" columns={[
        { key: 'id', header: 'Case', render: (t) => <span className="mono small">{t.caseId}</span> },
        { key: 'in', header: 'Input', render: (t) => <code className="xs" style={{ whiteSpace: 'pre-wrap' }}>{safeJson(t.input, 0)}</code> },
        { key: 'exp', header: 'Expected', render: (t) => <span className="mono small">{typeof t.expected === 'object' ? safeJson(t.expected, 0) : valueLabel(t.expected)}</span> },
        { key: 'note', header: 'Note', render: (t) => <span className="small muted">{t.note ?? '—'}</span> },
      ]} /></div>}
    </div>
  );
}
