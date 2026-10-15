import type { DecisionForm } from '../../lib/modelYaml';
import { TIER_DEFAULT_BANDS } from '../../lib/modelYaml';
import type { QuestionDef, Tier } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ChipInput, Input, Select, Textarea, Checkbox, Range } from '../../components/Form';
import { IconClose, IconPlus } from '../../components/Icons';

const STATE_ROOTS = ['order', 'line', 'plan', 'plan_options', 'tenant', 'approval', 'customer', 'node', 'candidate', 'event', 'carrier', 'return', 'inventory', 'vendor', 'input', 'payment', 'agent', 'mandate'];
const ESCAPE_RE = /unclear|none|not_enough|escalate|other|unknown/;

export function StructuredEditor({ form, onChange, readOnly }: { form: DecisionForm; onChange: (f: DecisionForm) => void; readOnly?: boolean }) {
  const set = <K extends keyof DecisionForm>(k: K, v: DecisionForm[K]) => onChange({ ...form, [k]: v });
  const setQ = (key: string, q: QuestionDef) => onChange({ ...form, questions: { ...form.questions, [key]: q } });
  const renameQ = (oldKey: string, newKey: string) => { if (!newKey || newKey === oldKey || form.questions[newKey]) return; const qs: Record<string, QuestionDef> = {}; for (const k of form.questionOrder) qs[k === oldKey ? newKey : k] = k === oldKey ? { ...form.questions[k], key: newKey } : form.questions[k]; onChange({ ...form, questions: qs, questionOrder: form.questionOrder.map((k) => (k === oldKey ? newKey : k)) }); };
  const removeQ = (key: string) => { const qs = { ...form.questions }; delete qs[key]; onChange({ ...form, questions: qs, questionOrder: form.questionOrder.filter((k) => k !== key) }); };
  const addQ = (type: QuestionDef['type']) => { let key = type === 'choice' ? 'choice' : type === 'score' ? 'score' : 'statement'; let i = 1; while (form.questions[key]) key = `${type}_${i++}`; const q: QuestionDef = type === 'choice' ? { key, type, instructions: '', choiceCriteria: { option_a: '', unclear: 'Not enough information to tell' } } : type === 'score' ? { key, type, instructions: '', scoreLevels: ['', '', ''] } : { key, type, statement: '' }; onChange({ ...form, questions: { ...form.questions, [key]: q }, questionOrder: [...form.questionOrder, key] }); };
  const tierDefaults = TIER_DEFAULT_BANDS[form.tier];
  const ro = readOnly;
  return (
    <div className="col gap3">
      <details className="editor-section" open>
        <summary>Identity & provider <span className="xs muted">{form.name} · {form.tier} · {form.provider}</span></summary>
        <div className="es-body">
          <div className="form-grid">
            <Input label="Decision name" value={form.name} mono disabled hint="Immutable; versions share the name" />
            <Input label="Owner (team)" value={form.owner} onChange={(e) => set('owner', e.target.value)} disabled={ro} hint="Business owner accountable for wording and thresholds" />
            <Select label="Risk tier" value={form.tier} onChange={(e) => { const t = e.target.value as Tier; onChange({ ...form, tier: t, bands: { ...TIER_DEFAULT_BANDS[t] } }); }} disabled={ro} hint="T0 informational · T1 reversible · T2 customer-impacting · T3 money-moving (never autonomous)">{(['T0', 'T1', 'T2', 'T3'] as Tier[]).map((t) => <option key={t} value={t}>{t}</option>)}</Select>
            <Select label="Provider" value={form.provider} onChange={(e) => set('provider', e.target.value)} disabled={ro}>{['jev', 'llm.anthropic', 'llm.openai', 'heuristic'].map((p) => <option key={p} value={p}>{p}</option>)}</Select>
            <Select label="Fallback provider" value={form.fallbackProvider ?? ''} onChange={(e) => set('fallbackProvider', e.target.value || undefined)} disabled={ro} hint="Used on timeout or error (GR-07); then the default value"><option value="">none → default</option>{['heuristic', 'llm.anthropic', 'llm.openai', 'jev'].map((p) => <option key={p} value={p}>{p}</option>)}</Select>
            <Input label="Timeout (ms)" type="number" min={100} max={5000} value={form.timeoutMs} onChange={(e) => set('timeoutMs', Number(e.target.value))} disabled={ro} hint="Budget for the provider call; default 800 ms" />
            <Input label="Default value (L0 fallback)" value={form.dflt === undefined || form.dflt === null ? '' : String(form.dflt)} onChange={(e) => set('dflt', e.target.value)} disabled={ro} mono hint="Returned when the model is unavailable, blocked or killed" />
            <Textarea label="Purpose" className="span2" value={form.purpose ?? ''} onChange={(e) => set('purpose', e.target.value)} rows={2} disabled={ro} hint="One sentence: what this decision chooses and for whom" />
          </div>
        </div>
      </details>

      <details className="editor-section" open>
        <summary>State includes <span className="xs muted">{form.stateInclude.length} paths</span></summary>
        <div className="es-body">
          <div className="guide">Only these paths are passed to the model (GR-09 data minimisation). Paths must start with a known root ({STATE_ROOTS.join(', ')}). Use <code>[]</code> for lists, e.g. <code>plan_options[].eta</code>. PII fields are tokenised automatically.</div>
          {ro ? <div className="pill-row">{form.stateInclude.map((p) => <span key={p} className="chip mono">{p}</span>)}</div> : <ChipInput values={form.stateInclude} onChange={(v) => set('stateInclude', v)} placeholder="order.channel" validate={(v) => (STATE_ROOTS.some((r) => v === r || v.startsWith(r + '.') || v.startsWith(r + '[')) ? null : `Unknown root. Known roots: ${STATE_ROOTS.join(', ')}`)} />}
        </div>
      </details>

      <details className="editor-section" open>
        <summary>Questions <span className="xs muted">{form.questionOrder.length} · first choice question (or composite) drives the band</span></summary>
        <div className="es-body">
          <div className="guide">Choice: pick one option — every choice needs an <b>escape option</b> such as <code>unclear</code> (GR-03). Score: ordered rubric levels, each measurable. Noul: probability a statement is true; mark as reason code to surface it to customers/analysts.</div>
          {form.questionOrder.map((key) => { const q = form.questions[key]; if (!q) return null; return <QuestionEditor key={key} q={q} onChange={(nq) => setQ(key, nq)} onRename={(nk) => renameQ(key, nk)} onRemove={() => removeQ(key)} readOnly={ro} />; })}
          {!ro && <div className="row gap1"><Button size="sm" icon={<IconPlus size={13} />} onClick={() => addQ('choice')}>Choice</Button><Button size="sm" icon={<IconPlus size={13} />} onClick={() => addQ('score')}>Score</Button><Button size="sm" icon={<IconPlus size={13} />} onClick={() => addQ('noul')}>Noul</Button></div>}
        </div>
      </details>

      <details className="editor-section" open>
        <summary>Bands <span className="xs muted">act ≥ {form.bands.actGte} · confirm ≥ {form.bands.confirmGte}</span></summary>
        <div className="es-body">
          <div className="guide">The model's confidence score falls into one of three bands: <b>escalate</b> (too unsure — goes to a human), <b>confirm</b> (asks a quick human nod first) or <b>act</b> (applies the answer automatically). The lowest band never acts on its own (GR-02). Tier defaults: {form.tier} act ≥ {tierDefaults.actGte > 1 ? 'never' : tierDefaults.actGte}{tierDefaults.confirmGte ? `, confirm ≥ ${tierDefaults.confirmGte}` : ''}. Thresholds can only be changed by humans (GR-14).</div>
          <BandViz act={form.bands.actGte} confirm={form.bands.confirmGte} />
          <div className="form-grid">
            <Range label="Act threshold" value={Math.min(1, form.bands.actGte)} min={0.3} max={1} step={0.01} onChange={(v) => !ro && set('bands', { ...form.bands, actGte: v, confirmGte: Math.min(form.bands.confirmGte, v) })} format={(v) => v.toFixed(2)} hint={form.tier === 'T3' ? 'T3 never acts autonomously; act band is advisory' : undefined} />
            <Range label="Confirm threshold" value={form.bands.confirmGte} min={0} max={1} step={0.01} onChange={(v) => !ro && set('bands', { ...form.bands, confirmGte: Math.min(v, form.bands.actGte) })} format={(v) => v.toFixed(2)} />
          </div>
          {form.bands.actGte < tierDefaults.actGte && <div className="callout warn"><span>Act threshold {form.bands.actGte} is below the {form.tier} starting gate ({tierDefaults.actGte}). Lint will block publish unless the owner documents shadow evidence.</span></div>}
          {form.bands.confirmGte > form.bands.actGte && <div className="callout danger"><span>Confirm threshold must be below the act threshold.</span></div>}
        </div>
      </details>

      <details className="editor-section" open>
        <summary>L0 rules <span className="xs muted">{form.rules?.length ?? 0} deterministic rules, first match by priority</span></summary>
        <div className="es-body">
          <div className="guide">A deterministic "if this, then that" check, written in CEL (a small expression language) over the input. <code>then</code> is an option key, <code>escalate</code>, <code>block</code>, or an expression. When a rule matches, it skips the AI model entirely and answers immediately with full confidence.</div>
          <div className="col gap1">
            <div className="rule-row xs muted strong" style={{ textTransform: 'uppercase', letterSpacing: '.05em' }}><span>Id</span><span>When (CEL)</span><span>Then</span><span>Reason</span><span>Prio</span><span /></div>
            {(form.rules ?? []).map((r, i) => <div key={i} className="rule-row">
              <input className="input input-sm mono" aria-label="Rule id" value={r.id} disabled={ro} onChange={(e) => set('rules', form.rules!.map((x, k) => (k === i ? { ...x, id: e.target.value } : x)))} />
              <input className="input input-sm mono" aria-label="Rule condition" value={r.when} disabled={ro} onChange={(e) => set('rules', form.rules!.map((x, k) => (k === i ? { ...x, when: e.target.value } : x)))} />
              <input className="input input-sm mono" aria-label="Rule result" value={r.then} disabled={ro} list="rule-then" onChange={(e) => set('rules', form.rules!.map((x, k) => (k === i ? { ...x, then: e.target.value } : x)))} />
              <input className="input input-sm" aria-label="Rule reason" value={r.reason ?? ''} disabled={ro} onChange={(e) => set('rules', form.rules!.map((x, k) => (k === i ? { ...x, reason: e.target.value } : x)))} />
              <input className="input input-sm num" aria-label="Rule priority" type="number" value={r.priority} disabled={ro} onChange={(e) => set('rules', form.rules!.map((x, k) => (k === i ? { ...x, priority: Number(e.target.value) } : x)))} />
              {!ro && <Button size="sm" variant="ghost" iconOnly icon={<IconClose size={13} />} onClick={() => set('rules', form.rules!.filter((_, k) => k !== i))}>Remove rule</Button>}
            </div>)}
            <datalist id="rule-then">{['escalate', 'block', ...Object.values(form.questions).filter((q) => q.type === 'choice').flatMap((q) => Object.keys(q.choiceCriteria ?? {}))].map((o) => <option key={o} value={o} />)}</datalist>
          </div>
          {!ro && <Button size="sm" icon={<IconPlus size={13} />} onClick={() => set('rules', [...(form.rules ?? []), { id: `rule_${(form.rules?.length ?? 0) + 1}`, when: '', then: 'escalate', reason: '', priority: ((form.rules?.length ?? 0) + 1) * 10 }])}>Add rule</Button>}
        </div>
      </details>

      <details className="editor-section">
        <summary>Composite & top-K <span className="xs muted">{form.composite ? `${Object.keys(form.composite.weights).length} weights` : 'no composite'}{form.topK ? ` · top ${form.topK.k}` : ''}</span></summary>
        <div className="es-body">
          <div className="guide">Composite: weighted sum of score questions into <code>outputKey</code>, used as the band driver when there is no choice question. Top-K: when candidates exceed 255, score each with <code>scoreQuestion</code> then run the choice over the top K.</div>
          <div className="form-grid">
            <div className="field"><span className="field-label">Composite weights</span><div className="col gap1">{Object.values(form.questions).filter((q) => q.type === 'score' || q.type === 'noul').map((q) => <div key={q.key} className="row gap3"><span className="mono small" style={{ width: 160 }}>{q.key}</span><input className="input input-sm" type="number" step={0.05} min={0} max={1} aria-label={`Weight for ${q.key}`} disabled={ro} value={form.composite?.weights[q.key] ?? ''} onChange={(e) => { const w = { ...(form.composite?.weights ?? {}) }; if (e.target.value === '') delete w[q.key]; else w[q.key] = Number(e.target.value); set('composite', Object.keys(w).length ? { weights: w, outputKey: form.composite?.outputKey ?? 'composite' } : null); }} style={{ width: 90 }} /></div>)}{!Object.values(form.questions).some((q) => q.type !== 'choice') && <span className="xs muted">Add a score or noul question to weight it.</span>}</div></div>
            <Input label="Composite output key" value={form.composite?.outputKey ?? ''} disabled={ro || !form.composite} onChange={(e) => set('composite', form.composite ? { ...form.composite, outputKey: e.target.value } : null)} mono />
            <Select label="Top-K score question" value={form.topK?.scoreQuestion ?? ''} disabled={ro} onChange={(e) => set('topK', e.target.value ? { scoreQuestion: e.target.value, k: form.topK?.k ?? 12, candidatesField: form.topK?.candidatesField ?? 'candidates' } : null)}><option value="">disabled</option>{Object.values(form.questions).filter((q) => q.type === 'score').map((q) => <option key={q.key} value={q.key}>{q.key}</option>)}</Select>
            <div className="row gap3"><Input label="K" type="number" min={1} max={255} value={form.topK?.k ?? ''} disabled={ro || !form.topK} onChange={(e) => set('topK', form.topK ? { ...form.topK, k: Number(e.target.value) } : null)} /><Input label="Candidates field" value={form.topK?.candidatesField ?? ''} mono disabled={ro || !form.topK} onChange={(e) => set('topK', form.topK ? { ...form.topK, candidatesField: e.target.value } : null)} /></div>
          </div>
        </div>
      </details>

      <details className="editor-section" open>
        <summary>Pre / post checks & fallback <span className="xs muted">{(form.preChecks?.length ?? 0) + (form.postChecks?.length ?? 0)} hard constraints</span></summary>
        <div className="es-body">
          <div className="guide">Hard constraints evaluated before and after every model call (GR-01). Post-checks can reference <code>answers</code>, <code>chosen_plan</code> and <code>input</code>. A failing post-check discards the answer and falls back to the default.</div>
          <div className="grid grid-2">
            <ChipInput label="Pre-checks (CEL)" values={form.preChecks ?? []} onChange={(v) => !ro && set('preChecks', v)} placeholder="order.lines.size() > 0" />
            <ChipInput label="Post-checks (CEL)" values={form.postChecks ?? []} onChange={(v) => !ro && set('postChecks', v)} placeholder="chosen_plan.margin >= tenant.margin_floor" />
          </div>
        </div>
      </details>

      <details className="editor-section">
        <summary>Evaluation gate <span className="xs muted">{form.evaluation ? `${form.evaluation.goldenSet} · acc ≥ ${form.evaluation.minAccuracyInActBand} · cal ≤ ${form.evaluation.maxCalibrationError}` : 'none (publish gate cannot run)'}</span></summary>
        <div className="es-body">
          <div className="guide">Measured on a <b>golden set</b> — a labelled batch of real cases with known-correct answers (200+ recommended). Publishing is blocked if accuracy in the act band falls below the minimum, or calibration error rises above the maximum (GR-11).</div>
          <Checkbox label="Require evaluation before publish" checked={!!form.evaluation} disabled={ro} onChange={(e) => set('evaluation', e.target.checked ? { goldenSet: `${form.name}_gold_v${form.version}`, minAccuracyInActBand: 0.9, maxCalibrationError: 0.05 } : null)} />
          {form.evaluation && <div className="form-grid"><Input label="Golden set" value={form.evaluation.goldenSet} mono disabled={ro} onChange={(e) => set('evaluation', { ...form.evaluation!, goldenSet: e.target.value })} /><div className="row gap3"><Input label="Min accuracy (act band)" type="number" step={0.01} min={0} max={1} value={form.evaluation.minAccuracyInActBand} disabled={ro} onChange={(e) => set('evaluation', { ...form.evaluation!, minAccuracyInActBand: Number(e.target.value) })} /><Input label="Max calibration error" type="number" step={0.005} min={0} max={0.5} value={form.evaluation.maxCalibrationError} disabled={ro} onChange={(e) => set('evaluation', { ...form.evaluation!, maxCalibrationError: Number(e.target.value) })} /></div></div>}
        </div>
      </details>
    </div>
  );
}

function QuestionEditor({ q, onChange, onRename, onRemove, readOnly }: { q: QuestionDef; onChange: (q: QuestionDef) => void; onRename: (k: string) => void; onRemove: () => void; readOnly?: boolean }) {
  const ro = readOnly;
  const criteria = Object.entries(q.choiceCriteria ?? {});
  const hasEscape = criteria.some(([k]) => ESCAPE_RE.test(k));
  const setCriteria = (entries: [string, string][]) => onChange({ ...q, choiceCriteria: Object.fromEntries(entries) });
  return (
    <div className="q-card">
      <div className="q-head"><Badge tone="accent" mono>{q.type}</Badge><input className="input input-sm mono" aria-label="Question key" style={{ width: 200 }} defaultValue={q.key} disabled={ro} onBlur={(e) => onRename(e.target.value.trim())} />{q.type === 'choice' && !hasEscape && <Badge tone="danger">missing escape option</Badge>}{q.type === 'noul' && <Checkbox label={<span className="small">reason code</span>} checked={!!q.reasonCode} disabled={ro} onChange={(e) => onChange({ ...q, reasonCode: e.target.checked })} />}<span className="grow" />{!ro && <Button size="sm" variant="ghost" iconOnly icon={<IconClose size={13} />} onClick={onRemove}>Remove question</Button>}</div>
      {q.type !== 'noul' && <Textarea label="Instructions" value={q.instructions ?? ''} rows={2} disabled={ro} onChange={(e) => onChange({ ...q, instructions: e.target.value })} hint="The question the model answers. Reference typed fields, never ask for free text." />}
      {q.type === 'choice' && <div className="col gap1">
        <div className="opt-row xs muted strong" style={{ textTransform: 'uppercase', letterSpacing: '.05em' }}><span>Option key</span><span>Description (what makes this the right pick)</span><span /></div>
        {criteria.map(([k, d], i) => <div key={i} className={['opt-row', ESCAPE_RE.test(k) && 'escape'].filter(Boolean).join(' ')}>
          <input className="input input-sm mono" aria-label="Option key" value={k} disabled={ro} onChange={(e) => setCriteria(criteria.map(([kk, dd], j) => (j === i ? [e.target.value, dd] : [kk, dd])))} title={ESCAPE_RE.test(k) ? 'Escape option (GR-03)' : undefined} />
          <input className="input input-sm" aria-label="Option description" value={d} disabled={ro} onChange={(e) => setCriteria(criteria.map(([kk, dd], j) => (j === i ? [kk, e.target.value] : [kk, dd])))} />
          {!ro && <Button size="sm" variant="ghost" iconOnly icon={<IconClose size={13} />} onClick={() => setCriteria(criteria.filter((_, j) => j !== i))}>Remove option</Button>}
        </div>)}
        {!ro && <div className="row gap1"><Button size="sm" icon={<IconPlus size={13} />} onClick={() => setCriteria([...criteria, [`option_${criteria.length + 1}`, '']])}>Add option</Button>{!hasEscape && <Button size="sm" variant="subtle" onClick={() => setCriteria([...criteria, ['unclear', 'Not enough information to tell']])}>Add escape option</Button>}</div>}
      </div>}
      {q.type === 'score' && <div className="col gap1">
        <span className="xs muted strong" style={{ textTransform: 'uppercase', letterSpacing: '.05em' }}>Levels, lowest first (score = index)</span>
        {(q.scoreLevels ?? []).map((l, i) => <div key={i} className="row gap3"><Badge tone="outline" mono>{i}</Badge><input className="input input-sm grow" aria-label={`Level ${i}`} value={l} disabled={ro} onChange={(e) => onChange({ ...q, scoreLevels: q.scoreLevels!.map((x, j) => (j === i ? e.target.value : x)) })} placeholder="Describe a measurable condition for this level" />{!ro && <Button size="sm" variant="ghost" iconOnly icon={<IconClose size={13} />} onClick={() => onChange({ ...q, scoreLevels: q.scoreLevels!.filter((_, j) => j !== i) })}>Remove level</Button>}</div>)}
        {!ro && <Button size="sm" icon={<IconPlus size={13} />} onClick={() => onChange({ ...q, scoreLevels: [...(q.scoreLevels ?? []), ''] })}>Add level</Button>}
      </div>}
      {q.type === 'noul' && <Textarea label="Statement" value={q.statement ?? ''} rows={2} disabled={ro} onChange={(e) => onChange({ ...q, statement: e.target.value })} hint="A declarative sentence; the model returns the probability it is true. Confidence = |2p − 1|." />}
    </div>
  );
}

export function BandViz({ act, confirm }: { act: number; confirm: number }) {
  const a = Math.min(1, act); const c = Math.min(a, confirm);
  return <div className="band-viz" role="img" aria-label={`Bands: escalate below ${c}, confirm from ${c} to ${a}, act from ${a}`}><span className="esc" style={{ width: `${c * 100}%` }}>{c > 0.1 && `escalate < ${c}`}</span><span className="conf" style={{ width: `${(a - c) * 100}%` }}>{a - c > 0.12 && `confirm ≥ ${c}`}</span><span className="act" style={{ width: `${(1 - a) * 100}%` }}>{1 - a > 0.08 && `act ≥ ${a}`}</span></div>;
}
