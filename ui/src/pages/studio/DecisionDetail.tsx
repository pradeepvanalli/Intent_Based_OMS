import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { decisionsApi } from '../../api/decisions';
import { analyticsApi } from '../../api/analytics';
import type { DecisionDefinition, DecisionResult, EvaluationResult, ImpactDiff, LintResult } from '../../api/types';
import { decisionToYaml, parseDecisionYaml, toForm, type DecisionForm } from '../../lib/modelYaml';
import { Badge, BandBadge, LayerBadge, StatusBadge, TierBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Select, Textarea, Range } from '../../components/Form';
import { ConfirmDialog } from '../../components/Overlay';
import { KeyValue, ProbBar, YamlView } from '../../components/Misc';
import { Tabs, TabPanel } from '../../components/Tabs';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { StackedBar } from '../../components/charts/BarChart';
import { CalibrationCurve } from '../../components/charts/CalibrationCurve';
import { StructuredEditor } from './StructuredEditor';
import { SideBySide } from '../stateModels/StateModelDetail';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { fmtConf, fmtDateTime, fmtNum, fmtPct, fmtUsd, humanize, relTime, safeJson, valueLabel } from '../../lib/format';
import { hasPermission, useSession } from '../../auth/session';
import { toast } from '../../lib/toast';
import { useQueryState } from '../../lib/hooks';

const TABS = [{ id: 'editor', label: 'Editor' }, { id: 'yaml', label: 'YAML' }, { id: 'lint', label: 'Lint' }, { id: 'impact', label: 'Impact diff' }, { id: 'evaluation', label: 'Evaluation' }, { id: 'approval', label: 'Approval' }, { id: 'rollout', label: 'Rollout' }, { id: 'diff', label: 'Version diff' }, { id: 'try', label: 'Try it' }];

export default function DecisionDetail() {
  const { name = '' } = useParams(); const qc = useQueryClient(); const { principal } = useSession();
  const versions = useQuery({ queryKey: ['decision-versions', name], queryFn: () => decisionsApi.versions(name) });
  const summary = useQuery({ queryKey: ['decisions'], queryFn: decisionsApi.list });
  const [vsel, setVsel] = useQueryState('v'); const [tab, setTab] = useQueryState('tab', 'editor');
  const list = versions.data ?? [];
  const current = list.find((d) => String(d.version) === vsel) ?? list.find((d) => d.status === 'draft') ?? list.find((d) => d.status === 'live') ?? list[list.length - 1];
  const live = list.find((d) => d.status === 'live');
  const [form, setForm] = useState<DecisionForm | null>(null); const [yamlText, setYamlText] = useState(''); const [yamlErr, setYamlErr] = useState<string | null>(null);
  useEffect(() => { if (current) { setForm(toForm(current)); setYamlText(current.yaml); setYamlErr(null); } }, [current]);
  const dirty = !!current && !!form && (yamlText !== current.yaml);
  const editable = current?.status === 'draft' && hasPermission(principal, 'decision.edit');
  const onFormChange = (f: DecisionForm) => { setForm(f); setYamlText(decisionToYaml(f)); setYamlErr(null); };
  const onYamlChange = (t: string) => { setYamlText(t); try { const p = parseDecisionYaml(t); setForm(toForm({ ...current, ...p })); setYamlErr(null); } catch (e) { setYamlErr(e instanceof Error ? e.message : 'Invalid YAML'); } };
  const refresh = () => { qc.invalidateQueries({ queryKey: ['decision-versions', name] }); qc.invalidateQueries({ queryKey: ['decisions'] }); };
  const saveDraft = useMutation({ mutationFn: () => decisionsApi.createDraft(name, yamlText, current?.version), onSuccess: (d) => { toast.ok(`Draft v${d.version} saved`); refresh(); setVsel(String(d.version)); } });
  const newDraft = useMutation({ mutationFn: () => decisionsApi.createDraft(name, current!.yaml, current!.version), onSuccess: (d) => { toast.ok(`Draft v${d.version} created from v${current!.version}`); refresh(); setVsel(String(d.version)); setTab('editor'); } });
  const kill = useMutation({ mutationFn: (on: boolean) => decisionsApi.killSwitch(name, on, on ? 'Emergency kill from Studio' : 'Re-enabled from Studio'), onSuccess: (r) => { toast[r.killSwitch ? 'warn' : 'ok'](r.killSwitch ? 'Kill switch ON' : 'Kill switch off', r.killSwitch ? 'All layers fall back to the L0 default immediately.' : undefined); refresh(); } });
  const [killOpen, setKillOpen] = useState(false);
  const sum = summary.data?.find((d) => d.name === name);
  if (versions.isLoading) return <div className="page"><Loading /></div>;
  if (versions.isError || !current || !form) return <div className="page"><ErrorState error={versions.error ?? new Error('Decision not found')} retry={() => versions.refetch()} /></div>;
  const hasDraft = list.some((d) => d.status === 'draft');
  return (
    <div className="page">
      <nav className="small muted" aria-label="Breadcrumb"><Link to="/studio">Policy Studio</Link> / <span className="mono">{name}</span></nav>
      <div className="page-head">
        <div><div className="order-title"><h1 className="mono" style={{ fontSize: 'var(--fs-xl)' }}>{name}</h1><Badge tone="accent" mono lg>v{current.version}</Badge><StatusBadge status={current.status} lg /><TierBadge tier={current.tier} />{current.status === 'canary' && <Badge tone="warn" lg title="Serving a slice of live traffic before a full rollout">{current.canaryPercent}% canary</Badge>}{current.killSwitch && <Badge tone="danger" lg title="This decision is forced to its safe default and is not calling its provider">kill switch on</Badge>}{dirty && <Badge tone="warn" lg>unsaved</Badge>}</div>
          <div className="sub">Edit this decision's logic, simulate it against past orders, get it approved, then roll it out gradually — using the tabs below.</div>
          <div className="small muted mt2">{current.purpose ?? sum?.purpose} · owner <b>{current.owner}</b> · provider <span className="mono">{current.provider}</span>{live && <> · live v{live.version}</>}</div>
        </div>
        <div className="page-actions">
          {hasPermission(principal, 'decision.edit') && !hasDraft && <Button onClick={() => newDraft.mutate()} loading={newDraft.isPending}>New draft from v{current.version}</Button>}
          {editable && <Button variant="primary" disabled={!dirty || !!yamlErr} loading={saveDraft.isPending} onClick={() => saveDraft.mutate()}>Save draft</Button>}
          {hasPermission(principal, 'decision.publish') && <Button variant={current.killSwitch ? 'default' : 'danger'} onClick={() => (current.killSwitch ? kill.mutate(false) : setKillOpen(true))} loading={kill.isPending}>{current.killSwitch ? 'Disable kill switch' : 'Kill switch'}</Button>}
        </div>
      </div>
      <div className="studio-layout">
        <aside className="card card-pad col">
          <div className="section-title">Versions</div>
          <div className="version-list">{[...list].reverse().map((d) => <button key={d.version} className="version-item" aria-current={d.version === current.version} onClick={() => setVsel(String(d.version))}><span className="v">v{d.version}</span><span className="col gap1" style={{ alignItems: 'flex-start', minWidth: 0 }}><span className="small truncate" style={{ maxWidth: 170 }} title={d.changeReason}>{d.changeReason ?? '—'}</span><span className="xs muted">{relTime(d.createdAt)} · {d.createdBy ?? '—'}{d.ticket && <> · {d.ticket}</>}</span></span><StatusBadge status={d.status} /></button>)}</div>
          <div className="divider" />
          <div className="section-title">Governance</div>
          <KeyValue tight items={[['Tier', <TierBadge tier={current.tier} />], ['Approvers needed', current.tier === 'T2' || current.tier === 'T3' ? '2 (incl. risk owner for T3)' : '1'], ['Simulation', current.tier === 'T0' ? 'recommended' : 'required'], [<span title="A batch of hand-labelled example cases used to grade this version before it can publish">Golden set</span>, current.evaluation?.goldenSet ?? '—'], ['Volume 30d', fmtNum(sum?.volume30d)], ['Override rate', fmtPct(sum?.overrideRate)]]} />
        </aside>
        <div className="col gap4">
          <Tabs ariaLabel="Decision version sections" active={tab} onChange={setTab} tabs={TABS} />
          <TabPanel id="editor" active={tab}>
            {!editable && <div className="callout mb3"><span>{(current.status as string) === 'draft' ? 'Your role cannot edit decisions (needs decision.edit).' : <>Version v{current.version} is <b>{current.status}</b> and read-only. {hasPermission(principal, 'decision.edit') && !hasDraft && <>Create a new draft to change it.</>}{hasDraft && current.status !== 'draft' && <>A draft already exists — select it in the version list.</>}</>}</span></div>}
            <StructuredEditor form={form} onChange={onFormChange} readOnly={!editable} />
          </TabPanel>
          <TabPanel id="yaml" active={tab}>
            <div className="grid grid-2">
              <div className="col gap2"><div className="row-between"><span className="section-title" style={{ margin: 0 }} title="PRD Appendix B format">YAML (the editor tab as text)</span>{yamlErr ? <Badge tone="danger">parse error</Badge> : <Badge tone="ok">in sync with form</Badge>}</div><Textarea aria-label="Decision YAML" mono rows={30} value={yamlText} onChange={(e) => onYamlChange(e.target.value)} readOnly={!editable} spellCheck={false} style={{ fontSize: 'var(--fs-sm)', lineHeight: 1.55 }} />{yamlErr && <div className="error-box"><span className="title">YAML does not parse</span><span className="small mono">{yamlErr}</span></div>}{dirty && editable && <Button size="sm" variant="ghost" onClick={() => { setYamlText(current.yaml); setForm(toForm(current)); setYamlErr(null); }}>Revert</Button>}</div>
              <div className="col gap2"><span className="section-title">Highlighted</span><YamlView text={yamlText} maxHeight={680} /></div>
            </div>
          </TabPanel>
          <TabPanel id="lint" active={tab}><LintPanel name={name} version={current.version} yaml={dirty ? yamlText : undefined} /></TabPanel>
          <TabPanel id="impact" active={tab}><ImpactPanel name={name} version={current.version} /></TabPanel>
          <TabPanel id="evaluation" active={tab}><EvaluationPanel name={name} def={current} /></TabPanel>
          <TabPanel id="approval" active={tab}><ApprovalPanel def={current} onChanged={refresh} /></TabPanel>
          <TabPanel id="rollout" active={tab}><RolloutPanel def={current} live={live} onChanged={refresh} /></TabPanel>
          <TabPanel id="diff" active={tab}><DiffPanel list={list} current={current} yamlText={yamlText} dirty={dirty} /></TabPanel>
          <TabPanel id="try" active={tab}><TryPanel name={name} def={current} /></TabPanel>
        </div>
      </div>
      <ConfirmDialog open={killOpen} onClose={() => setKillOpen(false)} onConfirm={() => { kill.mutate(true); setKillOpen(false); }} title={`Kill switch for ${name}`} danger confirmLabel="Turn kill switch on" body={<div className="callout danger"><span>GR-06: all versions of <b>{name}</b> stop calling providers immediately and return the deterministic default <span className="mono">{valueLabel(current.dflt)}</span>. Open escalations stay open. This is audited and alerts the owner.</span></div>} />
    </div>
  );
}

function LintPanel({ name, version, yaml }: { name: string; version: number; yaml?: string }) {
  const q = useQuery({ queryKey: ['lint', name, version, yaml], queryFn: () => decisionsApi.lint(name, version, yaml) });
  const errors = (q.data ?? []).filter((l) => l.severity === 'error').length;
  return (
    <Section title={<span className="row gap3"><span title="FR-PRM-08">Automated checks</span> {q.data && (errors ? <Badge tone="danger">{errors} error{errors > 1 ? 's' : ''} block publish</Badge> : <Badge tone="ok">no blocking issues</Badge>)}{yaml && <Badge tone="warn">unsaved edits</Badge>}</span>} actions={<Button size="sm" onClick={() => q.refetch()} loading={q.isFetching}>Re-run</Button>}>
      <p className="small muted mb3" style={{ marginTop: 0 }}>Static checks that catch mistakes before you can publish — like a question with no "I don't know" escape option, or a threshold that contradicts a deterministic rule.</p>
      {q.isLoading && <Loading />}{q.isError && <ErrorState error={q.error} />}
      {q.data && <div className="col">{q.data.map((l: LintResult, i) => <div key={i} className="lint-item"><Badge tone={l.severity === 'error' ? 'danger' : l.severity === 'warning' ? 'warn' : 'ok'}>{l.severity}</Badge><div><b className="mono xs">{l.code}</b> {l.message}{l.path && <div className="xs muted mono">{l.path}</div>}</div></div>)}</div>}
      <div className="xs muted mt3">Checks: overlapping options, missing escape option, vague rubric levels, PII in prompts, contradiction with L0 rules, unknown state paths, protected attributes (GR-16), band thresholds vs tier gate.</div>
    </Section>
  );
}

function ImpactPanel({ name, version }: { name: string; version: number }) {
  const [days, setDays] = useState(30); const [result, setResult] = useState<ImpactDiff | null>(null); const [why, setWhy] = useState<string | null>(null);
  const run = useMutation({ mutationFn: () => decisionsApi.impact(name, version, days), onSuccess: setResult });
  const bands = (m: Record<string, number>) => [{ label: 'act', value: m.ACT, color: 'var(--band-act)' }, { label: 'confirm', value: m.CONFIRM, color: 'var(--band-confirm)' }, { label: 'escalate', value: m.ESCALATE, color: 'var(--band-escalate)' }];
  return (
    <Section title={<span title="FR-PRM-03">Impact diff</span>} actions={<form className="row gap3" onSubmit={(e) => { e.preventDefault(); run.mutate(); }}><Select aria-label="Days" sm value={String(days)} onChange={(e) => setDays(Number(e.target.value))}>{[7, 14, 30, 60, 90].map((d) => <option key={d} value={d}>last {d} days</option>)}</Select><Button type="submit" size="sm" variant="primary" loading={run.isPending}>Re-run stored inputs</Button></form>}>
      {!result && !run.isPending && <EmptyState title="No simulation yet" hint="Re-runs the live version's recorded inputs through this version in SIMULATION mode and reports what would flip." />}
      {run.isPending && <Loading label="Replaying recorded decisions…" />}
      {run.isError && <ErrorState error={run.error} />}
      {result && <div className="col gap4">
        <div className="impact-grid">
          <div className="mini-stat"><span className="l">Evaluated</span><span className="v">{fmtNum(result.evaluated)}</span><span className="d">v{result.baseVersion} → v{result.draftVersion}, {result.days} days</span></div>
          <div className="mini-stat"><span className="l">Flips</span><span className="v" style={{ color: result.flipRate > 0.08 ? 'var(--danger-fg)' : undefined }}>{fmtNum(result.flips)}</span><span className="d">{fmtPct(result.flipRate)} of decisions change value</span></div>
          <div className="mini-stat"><span className="l">Escalations</span><span className="v">{result.escalationDelta >= 0 ? '+' : ''}{result.escalationDelta}</span><span className="d">human queue load delta</span></div>
          <div className="mini-stat"><span className="l">Cost / latency</span><span className="v">{result.costDeltaUsd >= 0 ? '+' : ''}{fmtUsd(result.costDeltaUsd)}</span><span className="d">{result.latencyDeltaMs >= 0 ? '+' : ''}{result.latencyDeltaMs} ms mean</span></div>
          {result.marginDeltaUsd !== undefined && <div className="mini-stat"><span className="l">Margin</span><span className="v">{result.marginDeltaUsd >= 0 ? '+' : ''}{fmtUsd(result.marginDeltaUsd)}</span><span className="d">projected contribution delta</span></div>}
          {result.slaBreachDelta !== undefined && <div className="mini-stat"><span className="l">SLA breaches</span><span className="v">{result.slaBreachDelta >= 0 ? '+' : ''}{result.slaBreachDelta}</span><span className="d">predicted</span></div>}
        </div>
        <div className="xs muted">Band = what happens with the answer: <b>act</b> applies it automatically, <b>confirm</b> asks a quick human nod, <b>escalate</b> sends it to a human queue.</div>
        <div className="grid grid-2"><div><div className="section-title">Band mix before (v{result.baseVersion})</div><StackedBar ariaLabel="Band mix before" segments={bands(result.bandMixBefore)} /></div><div><div className="section-title">Band mix after (v{result.draftVersion})</div><StackedBar ariaLabel="Band mix after" segments={bands(result.bandMixAfter)} /></div></div>
        {result.layerMixBefore && result.layerMixAfter && <div className="grid grid-2"><div><div className="section-title">Layer mix before</div><StackedBar ariaLabel="Layer mix before" segments={layerSegs(result.layerMixBefore)} /></div><div><div className="section-title">Layer mix after</div><StackedBar ariaLabel="Layer mix after" segments={layerSegs(result.layerMixAfter)} /></div></div>}
        <div><div className="section-title">Sample flipped records</div>{result.samples.length === 0 ? <div className="muted small">No flips in the sample.</div> : <div className="table-wrap" style={{ border: '1px solid var(--border)' }}><table className="tbl dense"><thead><tr><th>Record</th><th>Subject</th><th>Before</th><th>After</th><th></th></tr></thead><tbody>{result.samples.map((s) => <tr key={s.recordId}><td className="mono small">{s.recordId}</td><td><Link to={s.subjectType === 'order' ? `/orders/${s.subjectId}` : '#'} className="mono small">{s.subjectId}</Link></td><td><span className="row gap1"><span className="mono small">{valueLabel(s.before)}</span><BandBadge band={s.bandBefore} /><span className="xs muted num">{fmtConf(s.confidenceBefore)}</span></span></td><td><span className="row gap1"><span className="mono small">{valueLabel(s.after)}</span><BandBadge band={s.bandAfter} /><span className="xs muted num">{fmtConf(s.confidenceAfter)}</span></span></td><td><Button size="sm" variant="ghost" onClick={() => setWhy(s.recordId)}>Why</Button></td></tr>)}</tbody></table></div>}</div>
      </div>}
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </Section>
  );
}
function layerSegs(m: Record<string, number>) { const colors: Record<string, string> = { L0_RULE: 'var(--l0)', L1_SYSTEM_ONE: 'var(--l1)', L2_LLM: 'var(--l2)', L3_HUMAN: 'var(--l3)' }; const labels: Record<string, string> = { L0_RULE: 'L0 rules', L1_SYSTEM_ONE: 'L1 Jev', L2_LLM: 'L2 LLM', L3_HUMAN: 'L3 human' }; return Object.entries(m).map(([k, v]) => ({ label: labels[k] ?? k, value: Math.max(0, v), color: colors[k] ?? 'var(--c6)' })); }

function EvaluationPanel({ name, def }: { name: string; def: DecisionDefinition }) {
  const [result, setResult] = useState<EvaluationResult | null>(null);
  const run = useMutation({ mutationFn: () => decisionsApi.evaluate(name, def.version), onSuccess: setResult });
  return (
    <Section title={<span title="GR-11">Evaluation gate</span>} actions={<Button size="sm" variant="primary" loading={run.isPending} onClick={() => run.mutate()} disabled={!def.evaluation}>Run golden set</Button>}>
      <p className="small muted mb3" style={{ marginTop: 0 }}>Scores this version against a <b title="A batch of hand-labelled example cases with known-correct answers">golden set</b> — real cases with known-correct answers — so you can see its accuracy before anyone sees it live.</p>
      {!def.evaluation && <div className="callout warn"><span>No <code>evaluation</code> block on this version. Add a golden set in the editor; without it the publish gate cannot run.</span></div>}
      {def.evaluation && !result && !run.isPending && <KeyValue tight items={[['Golden set', <span className="mono">{def.evaluation.goldenSet}</span>], ['Min accuracy in act band', fmtPct(def.evaluation.minAccuracyInActBand)], ['Max calibration error', def.evaluation.maxCalibrationError.toFixed(3)]]} />}
      {run.isPending && <Loading label="Scoring labelled cases…" />}
      {result && <div className="col gap4">
        <div className="row gap3">{result.passed ? <Badge tone="ok" lg>PASS — publish allowed</Badge> : <Badge tone="danger" lg>FAIL — publish blocked</Badge>}<span className="small muted">{result.cases} cases · {fmtDateTime(result.evaluatedAt)}</span></div>
        <div className="impact-grid">
          <div className="mini-stat"><span className="l">Accuracy (act band)</span><span className="v" style={{ color: result.accuracyInActBand >= result.minAccuracyInActBand ? 'var(--ok-fg)' : 'var(--danger-fg)' }}>{fmtPct(result.accuracyInActBand)}</span><span className="d">gate ≥ {fmtPct(result.minAccuracyInActBand)}</span></div>
          <div className="mini-stat"><span className="l">Calibration error</span><span className="v" style={{ color: result.calibrationError <= result.maxCalibrationError ? 'var(--ok-fg)' : 'var(--danger-fg)' }}>{result.calibrationError.toFixed(3)}</span><span className="d">gate ≤ {result.maxCalibrationError.toFixed(3)}</span></div>
          <div className="mini-stat"><span className="l">Act-band share</span><span className="v">{fmtPct(result.actBandShare)}</span><span className="d">cases the model would act on</span></div>
          <div className="mini-stat"><span className="l">Golden set</span><span className="v" style={{ fontSize: 'var(--fs-md)' }}>{result.goldenSet}</span><span className="d">{result.cases} labelled cases</span></div>
        </div>
        <div className="grid grid-2"><div className="card card-pad"><div className="section-title">Calibration curve</div><CalibrationCurve bins={result.bins} /></div><div className="card card-pad"><div className="section-title">Failing cases (sample)</div>{(result.failures ?? []).length === 0 ? <div className="muted small">None</div> : <div className="table-wrap"><table className="tbl dense"><thead><tr><th>Case</th><th>Expected</th><th>Got</th><th className="num">Conf</th></tr></thead><tbody>{result.failures!.map((f) => <tr key={f.caseId}><td className="mono small">{f.caseId}</td><td className="mono small">{valueLabel(f.expected)}</td><td className="mono small" style={{ color: 'var(--danger-fg)' }}>{valueLabel(f.got)}</td><td className="num">{fmtConf(f.confidence)}</td></tr>)}</tbody></table></div>}</div></div>
      </div>}
    </Section>
  );
}

function ApprovalPanel({ def, onChanged }: { def: DecisionDefinition; onChanged: () => void }) {
  const { principal } = useSession(); const me = principal?.id;
  const [reason, setReason] = useState(def.changeReason ?? ''); const [ticket, setTicket] = useState(def.ticket ?? ''); const [comment, setComment] = useState('');
  const need = def.tier === 'T2' || def.tier === 'T3' ? 2 : 1; const have = def.approvals?.length ?? 0;
  const submit = useMutation({ mutationFn: () => decisionsApi.submit(def.name, def.version, reason, ticket), onSuccess: () => { toast.ok('Submitted for approval'); onChanged(); } });
  const approve = useMutation({ mutationFn: () => decisionsApi.approve(def.name, def.version, comment), onSuccess: () => { toast.ok('Approved'); setComment(''); onChanged(); } });
  const isSubmitter = def.submittedBy === me; const already = def.approvals?.some((a) => a.approver === me);
  return (
    <Section title={<span title="FR-PRM-02 · FR-PRM-06 · GR-14">Approval</span>}>
      <div className="grid grid-2">
        <div className="col gap3">
          <div className="section-title">Submit</div>
          {def.status !== 'draft' && <div className="callout"><span>Version is <b>{def.status}</b>{def.submittedBy && <> · submitted by <b>{def.submittedBy}</b></>}{def.ticket && <> · ticket <span className="mono">{def.ticket}</span></>}.</span></div>}
          <Textarea label="Change reason (required)" value={reason} onChange={(e) => setReason(e.target.value)} rows={3} disabled={def.status !== 'draft'} />
          <Input label="Linked ticket (required)" value={ticket} onChange={(e) => setTicket(e.target.value)} mono placeholder="OMS-1234" disabled={def.status !== 'draft'} />
          <div className="flex-end"><Button variant="primary" disabled={def.status !== 'draft' || !reason.trim() || !ticket.trim() || !hasPermission(principal, 'decision.edit')} loading={submit.isPending} onClick={() => submit.mutate()}>Submit for approval</Button></div>
          <div className="xs muted">Publishing needs: lint clean, passing golden set, impact simulation on historical orders, and sign-off by tier.</div>
        </div>
        <div className="col gap3">
          <div className="section-title">Approvals · {have}/{need} <TierBadge tier={def.tier} /></div>
          <div className="row gap1">{Array.from({ length: need }, (_, i) => <span key={i} style={{ width: 28, height: 28, borderRadius: '50%', display: 'grid', placeItems: 'center', background: i < have ? 'var(--ok)' : 'var(--surface-3)', color: i < have ? '#fff' : 'var(--text-faint)', border: '1px solid var(--border-strong)', fontSize: 12, fontWeight: 700 }} aria-label={i < have ? `approval ${i + 1} given` : `approval ${i + 1} pending`}>{i < have ? '✓' : i + 1}</span>)}<span className="small muted" style={{ marginLeft: 8 }}>{def.tier === 'T3' ? 'two approvers including the risk owner' : def.tier === 'T2' ? 'two distinct approvers + simulation' : 'one approver' + (def.tier === 'T1' ? ' + simulation' : '')}</span></div>
          {(def.approvals ?? []).map((a) => <div key={a.approver} className="approver"><span className="avatar" aria-hidden>{a.approver.slice(0, 2)}</span><span><b>{a.approver}</b> approved {relTime(a.at)}{a.comment && <span className="muted"> — {a.comment}</span>}</span></div>)}
          {def.status === 'pending_approval' && (<>
            {isSubmitter && <div className="callout warn"><span>Separation of duties (GR-14): you submitted this change, so you cannot approve it. Ask another approver.</span></div>}
            {!isSubmitter && already && <div className="callout ok"><span>You have approved this version.</span></div>}
            {!isSubmitter && !already && hasPermission(principal, 'decision.approve') && <><Textarea label="Comment (optional)" value={comment} onChange={(e) => setComment(e.target.value)} rows={2} /><div className="flex-end"><Button variant="primary" loading={approve.isPending} onClick={() => approve.mutate()}>Approve v{def.version}</Button></div></>}
            {!isSubmitter && !already && !hasPermission(principal, 'decision.approve') && <div className="muted small">Your role cannot approve decision changes.</div>}
          </>)}
          {def.status === 'draft' && <div className="muted small">Submit the draft to start collecting approvals.</div>}
        </div>
      </div>
    </Section>
  );
}

function RolloutPanel({ def, live, onChanged }: { def: DecisionDefinition; live?: DecisionDefinition; onChanged: () => void }) {
  const { principal } = useSession(); const canPublish = hasPermission(principal, 'decision.publish');
  const [pct, setPct] = useState(def.canaryPercent || 10); const [confirm, setConfirm] = useState<'live' | 'rollback' | null>(null);
  const publish = useMutation({ mutationFn: (rollout: string) => decisionsApi.publish(def.name, def.version, rollout), onSuccess: (d) => { toast.ok(`v${d.version} is now ${d.status}${d.status === 'canary' ? ` at ${d.canaryPercent}%` : ''}`); onChanged(); } });
  const rollback = useMutation({ mutationFn: () => decisionsApi.rollback(def.name, 'Rollback from Studio'), onSuccess: (d) => { toast.ok(`Rolled back to v${d.version}`); onChanged(); } });
  const need = def.tier === 'T2' || def.tier === 'T3' ? 2 : 1; const approved = (def.approvals?.length ?? 0) >= need || ['shadow', 'canary', 'live'].includes(def.status);
  const steps = ['shadow', 'canary', 'live']; const at = def.status === 'live' ? 2 : def.status === 'canary' ? 1 : def.status === 'shadow' ? 0 : -1;
  return (
    <Section title={<span title="FR-PRM-04">Rollout</span>}>
      <div className="col gap4">
        <p className="small muted" style={{ marginTop: 0 }}>Move this version live in three safe steps: watch it quietly in <b>shadow</b>, try it on a slice of traffic in <b>canary</b>, then promote it to <b>live</b> for everyone.</p>
        <div className="row gap3">{steps.map((s, i) => <span key={s} className="row gap1"><span className="badge badge-lg" style={{ background: i <= at ? 'var(--accent)' : 'var(--surface-3)', color: i <= at ? 'var(--accent-fg)' : 'var(--text-muted)' }}>{i + 1}. {s}{s === 'canary' && def.status === 'canary' ? ` ${def.canaryPercent}%` : ''}</span>{i < 2 && <span className="muted">→</span>}</span>)}<span className="grow" />{live && live.version !== def.version && <span className="small muted">live today: v{live.version}</span>}</div>
        {!approved && <div className="callout warn"><span>Not yet approved ({def.approvals?.length ?? 0}/{need}). Rollout controls unlock after approval.</span></div>}
        {def.status === 'retired' && <div className="callout"><span>This version is retired. Roll back from the live version to restore it.</span></div>}
        <div className="grid grid-3">
          <div className="card card-pad col"><div className="section-title">Shadow</div><p className="small muted">Runs alongside the live version, records SHADOW decisions, takes no action. Use it to collect calibration data.</p><Button disabled={!canPublish || !approved || def.status === 'shadow' || def.status === 'live'} loading={publish.isPending && publish.variables === 'shadow'} onClick={() => publish.mutate('shadow')}>Start shadow</Button></div>
          <div className="card card-pad col"><div className="section-title">Canary</div><p className="small muted">Serves a percentage of subjects (by hash of subject id). Expand gradually; roll back with one click.</p><Range label="Traffic" value={pct} min={1} max={50} step={1} onChange={setPct} format={(v) => `${v}%`} /><Button disabled={!canPublish || !approved || def.status === 'live'} loading={publish.isPending && String(publish.variables).startsWith('canary')} onClick={() => publish.mutate(`canary:${pct}`)}>{def.status === 'canary' ? `Set canary to ${pct}%` : `Start canary at ${pct}%`}</Button></div>
          <div className="card card-pad col"><div className="section-title">Live</div><p className="small muted">Replaces the live version for 100% of traffic. The previous version is retired but kept for rollback.</p><Button variant="primary" disabled={!canPublish || !approved || def.status === 'live'} onClick={() => setConfirm('live')}>Promote to live</Button></div>
        </div>
        <div className="row gap3"><Button variant="danger" disabled={!canPublish || !live} onClick={() => setConfirm('rollback')} loading={rollback.isPending}>Roll back live version</Button><span className="small muted">Restores the previous retired version instantly; canaries are retired.</span></div>
      </div>
      <ConfirmDialog open={confirm === 'live'} onClose={() => setConfirm(null)} onConfirm={() => { publish.mutate('live'); setConfirm(null); }} title={`Promote ${def.name} v${def.version} to live`} confirmLabel="Promote" body={<span className="small">All traffic moves to v{def.version}. v{live?.version ?? '—'} is retired and remains available for rollback. Audited with your actor id.</span>} />
      <ConfirmDialog open={confirm === 'rollback'} onClose={() => setConfirm(null)} onConfirm={() => { rollback.mutate(); setConfirm(null); }} title={`Roll back ${def.name}`} danger confirmLabel="Roll back" body={<span className="small">Live v{live?.version} will be retired and the previous version restored. Use this when a canary or live version misbehaves; a retro review is required within 48 hours (GR-10).</span>} />
    </Section>
  );
}

function DiffPanel({ list, current, yamlText, dirty }: { list: DecisionDefinition[]; current: DecisionDefinition; yamlText: string; dirty: boolean }) {
  const others = list.filter((d) => d.version !== current.version || dirty);
  const [sel, setSel] = useState<number>(() => (list.find((d) => d.status === 'live' && d.version !== current.version) ?? others[others.length - 1])?.version ?? -1);
  const other = list.find((d) => d.version === sel);
  return (
    <Section title="Version diff" actions={<Select aria-label="Compare with" sm value={String(sel)} onChange={(e) => setSel(Number(e.target.value))}><option value="-1">select version…</option>{others.map((d) => <option key={d.version} value={d.version}>v{d.version} · {d.status}</option>)}</Select>}>
      {other ? <SideBySide left={other.yaml} right={yamlText} leftLabel={`v${other.version} (${other.status})`} rightLabel={dirty ? `v${current.version} editor (unsaved)` : `v${current.version} (${current.status})`} /> : <EmptyState title="Pick a version to compare" hint="Choose an earlier version from the dropdown above to see exactly what changed, line by line." />}
    </Section>
  );
}

function TryPanel({ name, def }: { name: string; def: DecisionDefinition }) {
  const sample = useMemo(() => safeJson(sampleInput(def)), [def]);
  const [input, setInput] = useState(sample); const [err, setErr] = useState<string | null>(null); const [result, setResult] = useState<DecisionResult | null>(null); const [why, setWhy] = useState<string | null>(null);
  useEffect(() => { setInput(sample); setResult(null); }, [sample]);
  const run = useMutation({ mutationFn: (inp: Record<string, unknown>) => decisionsApi.evaluateNow(name, inp, 'SIMULATION', def.version), onSuccess: setResult });
  const analytics = useQuery({ queryKey: ['decision-analytics', name], queryFn: () => analyticsApi.decision(name) });
  const q = Object.values(def.questions)[0];
  return (
    <Section title="Try it (evaluate-now, SIMULATION mode)" actions={<span className="xs muted">No side effects · records stored with mode SIMULATION · version v{def.version}</span>}>
      <div className="two-col rev" style={{ gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)' }}>
        <div className="col gap3">
          <Textarea label="Input (JSON, state roots)" mono rows={18} value={input} onChange={(e) => { setInput(e.target.value); setErr(null); }} spellCheck={false} error={err} />
          <div className="row gap3"><Button variant="primary" loading={run.isPending} onClick={() => { try { run.mutate(JSON.parse(input)); } catch (e) { setErr(e instanceof Error ? e.message : 'Invalid JSON'); } }}>Evaluate</Button><Button variant="ghost" onClick={() => setInput(sample)}>Reset sample</Button>{analytics.data && <span className="xs muted">live p50 latency {analytics.data.versions.at(-1)?.latencyP50Ms} ms</span>}</div>
          {run.isError && <ErrorState error={run.error} />}
        </div>
        <div className="col gap3">
          {!result && <EmptyState title="No result yet" hint="Edit the sample input on the left and press Evaluate to see what this decision would answer. Deterministic rules fire first if any match; otherwise the provider answers each question." />}
          {result && <div className="card card-pad col gap3">
            <div className="row gap3 wrap"><span className="mono" style={{ fontSize: 'var(--fs-xl)', fontWeight: 650 }}>{q?.type === 'score' ? `${valueLabel(result.value)} · ${q.scoreLevels?.[Number(result.value)] ?? ''}` : valueLabel(result.value)}</span><LayerBadge layer={result.layer} /><BandBadge band={result.band} /><Badge tone="outline">{result.action}</Badge></div>
            <KeyValue tight items={[['Confidence', <span className="num">{fmtConf(result.confidence)}</span>], ['Provider', <span className="mono">{result.provider}{result.modelVersion ? ` · ${result.modelVersion}` : ''}</span>], ['Latency', `${result.latencyMs} ms`], ['Cost', fmtUsd(result.costUsd, 5)], ['Rules fired', result.rulesFired.length ? result.rulesFired.join(', ') : 'none'], ['Reason codes', result.reasonCodes?.length ? result.reasonCodes.map(humanize).join(', ') : '—'], ['Requires human', result.requiresHuman ? 'yes' : 'no'], ['Record', <span className="mono small">{result.recordId}</span>]]} />
            {Object.entries(result.answers).map(([k, a]) => <div key={k} className="q-card"><div className="q-head"><Badge tone="accent" mono>{a.type}</Badge><b className="mono">{k}</b><span className="grow" /><span className="small muted">confidence {fmtConf(a.confidence)}</span></div>{a.probabilities && <div className="col gap1">{Object.entries(a.probabilities).sort((x, y) => y[1] - x[1]).map(([opt, p]) => <ProbBar key={opt} label={a.type === 'score' ? `${opt} · ${(def.questions[k]?.scoreLevels?.[Number(opt)] ?? '').slice(0, 30)}` : opt} value={p} chosen={String(a.value) === opt} />)}</div>}</div>)}
            <div className="flex-end"><Button onClick={() => setWhy(result.recordId)}>Open full Why</Button></div>
          </div>}
        </div>
      </div>
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
    </Section>
  );
}
function sampleInput(def: DecisionDefinition): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const defaults: Record<string, unknown> = { 'order.channel': 'web', 'order.notes': ['Please ship everything together, it is a gift.'], 'order.intent_choices': [], 'order.has_hazmat': false, 'order.status': 'released', 'order.age_hours': 6, 'order.value': 184.5, 'customer.tier': 'gold', 'customer.history.split_acceptance_rate': 0.4, 'customer.history.returns_90d': 1, 'node.id': 'DC-TX', 'node.type': 'dc', 'node.health.backlog': 3920, 'event.type': 'wms.short_pick', 'event.message': 'Short pick on APP-TEE-BLK-M', 'return.reason': 'too_small', 'return.receipt_present': true, 'input.text': 'Buy a black tee for Ava, deliver before Friday.', 'input.source': 'agent', 'line.sku': 'APP-HOOD-GRY-L', 'line.days_backordered': 4 };
  for (const path of def.stateInclude) {
    const clean = path.replace(/\[\]/g, ''); const parts = clean.split('.'); let cur = out;
    for (let i = 0; i < parts.length - 1; i++) { cur = (cur[parts[i]] as Record<string, unknown>) ?? (cur[parts[i]] = {}); if (Array.isArray(cur)) break; }
    const leaf = parts[parts.length - 1]; if (cur[leaf] === undefined) cur[leaf] = defaults[clean] ?? (path.includes('[]') ? [] : /rate|ratio|prob/.test(leaf) ? 0.5 : /count|days|hours|qty|value|backlog|minutes/.test(leaf) ? 2 : `sample_${leaf}`);
  }
  return out;
}
