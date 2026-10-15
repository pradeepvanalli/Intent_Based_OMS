import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { copilotApi } from '../../api/copilot';
import type { BootstrapNode, BootstrapRequest, ChangeSet } from '../../api/types';
import { hasAnyPermission, useSession } from '../../auth/session';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ChipInput, Input, Select, Textarea } from '../../components/Form';
import { KeyValue } from '../../components/Misc';
import { EmptyState, ErrorState, Section } from '../../components/States';
import { IconClose, IconLock, IconPlus, IconSparkle } from '../../components/Icons';
import { fmtPct } from '../../lib/format';
import { toast } from '../../lib/toast';
import { ChangeSetView, KindBadge } from './ChangeSetView';
import { StatusChip } from './Copilot';
import { COPILOT_PERMISSIONS } from './shared';

const STEPS = ['Describe the business', 'Fulfilment nodes', 'Review & generate'];
const EXAMPLE = 'We sell outdoor apparel and camping gear online and through 12 US stores. Web and Amazon are our main channels; stores offer pickup. Most orders ship from two DCs; stores ship from store for local demand. Returns are generous but we lose money on serial returners.';

export default function Bootstrap() {
  const { principal } = useSession(); const qc = useQueryClient();
  const allowed = hasAnyPermission(principal, COPILOT_PERMISSIONS);
  const status = useQuery({ queryKey: ['copilot-status'], queryFn: copilotApi.status });
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<Required<Omit<BootstrapRequest, 'nodes'>> & { nodes: BootstrapNode[] }>({ text: '', channels: ['web', 'amazon'], categories: ['apparel', 'camping gear'], marginFloor: 0.12, returnWindowDays: 30, nodes: [] });
  const [result, setResult] = useState<ChangeSet | null>(null);
  const gen = useMutation({ mutationFn: (req: BootstrapRequest) => copilotApi.bootstrap(req), onSuccess: (cs) => { setResult(cs); qc.invalidateQueries({ queryKey: ['copilot-changes'] }); toast.ok('Blueprint ready', `${cs.changes.length} artefacts to review`); } });
  const stageAll = useMutation({ mutationFn: (cs: ChangeSet) => copilotApi.stage(cs.id, cs.changes.filter((c) => !c.staged && c.validation.ok).map((c) => c.id)), onSuccess: (cs) => { setResult(cs); qc.invalidateQueries({ queryKey: ['copilot-artifacts'] }); qc.invalidateQueries({ queryKey: ['decisions'] }); toast.ok('Blueprint staged', 'Decisions are studio drafts; the rest are artefacts in the Copilot → Artifacts tab.'); } });
  const onUpdated = (cs: ChangeSet) => { setResult(cs); qc.invalidateQueries({ queryKey: ['copilot-artifacts'] }); };
  const valid1 = form.text.trim().length >= 12 && form.channels.length > 0;
  const setNode = (i: number, patch: Partial<BootstrapNode>) => setForm({ ...form, nodes: form.nodes.map((n, k) => (k === i ? { ...n, ...patch } : n)) });
  if (!allowed) return <div className="page"><div className="page-head"><div><h1>Bootstrap</h1></div></div><EmptyState icon={<IconLock size={18} />} title="Bootstrap needs an editing role" hint={<>One of <code>decision.edit</code>, <code>statemodel.edit</code> or <code>config.admin</code> is required.</>} /></div>;
  return (
    <div className="page">
      <nav className="small muted" aria-label="Breadcrumb"><Link to="/copilot">Copilot</Link> / Bootstrap</nav>
      <div className="page-head">
        <div className="row gap3" style={{ alignItems: 'flex-start' }}>
          <span className="icon-badge ai" aria-hidden="true"><IconSparkle size={20} /></span>
          <div><div className="eyebrow">AI configuration assistant</div><h1>Bootstrap a tenant</h1><div className="sub">Describe your business and get a reviewable configuration bundle: order types, state models, SLAs, playbooks, config and starter decisions. Everything stages as drafts — nothing goes live until someone approves it.</div></div>
        </div>
        <div className="page-actions"><StatusChip status={status.data} loading={status.isLoading} /></div>
      </div>
      {status.data && !status.data.available && <div className="callout info"><span>The LLM is offline; Bootstrap uses the deterministic blueprint generator, so you still get a full bundle to review.</span></div>}
      {!result && <>
        <ol className="wizard-steps" aria-label="Bootstrap steps">{STEPS.map((s, i) => <li key={s} className={i < step ? 'done' : ''} aria-current={i === step ? 'step' : undefined}><span className="n" aria-hidden>{i < step ? '✓' : i + 1}</span>{s}</li>)}</ol>
        {step === 0 && <Section title="1 · Describe the business">
          <div className="col gap3" style={{ maxWidth: 860 }}>
            <Textarea label="What do you sell, through which channels, and how do you fulfil?" rows={5} value={form.text} onChange={(e) => setForm({ ...form, text: e.target.value })} placeholder={EXAMPLE} hint={<>A couple of sentences is enough — you'll get order types, state models, SLAs, playbooks and starter decisions to review, never a published change. <button type="button" className="btn btn-sm btn-ghost" onClick={() => setForm({ ...form, text: EXAMPLE })}>Use the example</button></>} />
            <div className="grid grid-2">
              <ChipInput label="Sales channels" values={form.channels} onChange={(v) => setForm({ ...form, channels: v })} hint="web, amazon, shopify, pos, agent, edi…" />
              <ChipInput label="Product categories" values={form.categories} onChange={(v) => setForm({ ...form, categories: v })} hint="Categories drive compliance rules (e.g. batteries → hazmat)" />
              <Input label="Return window (days)" type="number" min={0} max={365} value={form.returnWindowDays} onChange={(e) => setForm({ ...form, returnWindowDays: Number(e.target.value) })} />
              <Input label="Margin floor (%)" type="number" min={0} max={90} step={0.5} value={Math.round(form.marginFloor * 1000) / 10} onChange={(e) => setForm({ ...form, marginFloor: Number(e.target.value) / 100 })} hint="Contribution margin below which a sourcing plan needs approval" />
            </div>
            <div className="flex-end"><Button variant="primary" disabled={!valid1} onClick={() => setStep(1)}>Next: nodes</Button></div>
          </div>
        </Section>}
        {step === 1 && <Section title="2 · Fulfilment nodes (optional)" actions={<Button size="sm" icon={<IconPlus size={13} />} onClick={() => setForm({ ...form, nodes: [...form.nodes, { id: '', type: 'dc', city: '', state: '' }] })}>Add node</Button>}>
          <div className="col gap3" style={{ maxWidth: 860 }}>
            <p className="small muted">DCs, stores, 3PLs or vendors you ship from. Skip this step and the blueprint asks for them as a clarifying question.</p>
            {form.nodes.length > 0 && <div className="nodes-grid" role="table" aria-label="Fulfilment nodes">
              <span className="hdr" role="columnheader">Id</span><span className="hdr" role="columnheader">Type</span><span className="hdr" role="columnheader">City</span><span className="hdr" role="columnheader">State</span><span />
              {form.nodes.map((n, i) => <div key={i} style={{ display: 'contents' }} role="row">
                <Input aria-label={`Node ${i + 1} id`} mono sm placeholder="DC-NJ" value={n.id} onChange={(e) => setNode(i, { id: e.target.value.toUpperCase() })} />
                <Select aria-label={`Node ${i + 1} type`} sm value={n.type} onChange={(e) => setNode(i, { type: e.target.value })}><option value="dc">DC</option><option value="store">Store</option><option value="3pl">3PL</option><option value="vendor">Vendor</option></Select>
                <Input aria-label={`Node ${i + 1} city`} sm placeholder="Edison" value={n.city} onChange={(e) => setNode(i, { city: e.target.value })} />
                <Input aria-label={`Node ${i + 1} state`} sm placeholder="NJ" maxLength={2} value={n.state} onChange={(e) => setNode(i, { state: e.target.value.toUpperCase() })} />
                <Button variant="ghost" iconOnly size="sm" icon={<IconClose size={13} />} onClick={() => setForm({ ...form, nodes: form.nodes.filter((_, k) => k !== i) })}>Remove node {i + 1}</Button>
              </div>)}
            </div>}
            {form.nodes.length === 0 && <div className="row gap3 wrap"><Button size="sm" onClick={() => setForm({ ...form, nodes: [{ id: 'DC-NJ', type: 'dc', city: 'Edison', state: 'NJ' }, { id: 'DC-TX', type: 'dc', city: 'Dallas', state: 'TX' }, { id: 'ST-CHI-114', type: 'store', city: 'Chicago', state: 'IL' }] })}>Use example nodes</Button><span className="xs muted">2 DCs + 1 store</span></div>}
            <div className="row-between"><Button onClick={() => setStep(0)}>Back</Button><Button variant="primary" onClick={() => setStep(2)}>Next: review</Button></div>
          </div>
        </Section>}
        {step === 2 && <Section title="3 · Review & generate">
          <div className="col gap4" style={{ maxWidth: 860 }}>
            <KeyValue items={[['Business', <span style={{ whiteSpace: 'pre-wrap' }}>{form.text}</span>], ['Channels', <span className="pill-row">{form.channels.map((c) => <Badge key={c} tone="outline">{c}</Badge>)}</span>], ['Categories', form.categories.length ? <span className="pill-row">{form.categories.map((c) => <Badge key={c} tone="outline">{c}</Badge>)}</span> : '—'], ['Return window', `${form.returnWindowDays} days`], ['Margin floor', fmtPct(form.marginFloor, 1)], ['Nodes', form.nodes.filter((n) => n.id).length ? <span className="pill-row">{form.nodes.filter((n) => n.id).map((n) => <Badge key={n.id} tone="outline" mono>{n.id} · {n.type}</Badge>)}</span> : <span className="muted">none (will be asked)</span>]]} />
            <div className="callout"><span>You will get a change set of {form.nodes.length ? '8–11' : '8–10'} artefacts: <KindBadge kind="order_type" /> <KindBadge kind="state_model" /> <KindBadge kind="tenant_config" /> <KindBadge kind="sla" /> <KindBadge kind="playbook" /> <KindBadge kind="decision_new" />. Review each diff, then stage all or a selection.</span></div>
            {gen.isError && <ErrorState error={gen.error} />}
            <div className="row-between"><Button onClick={() => setStep(1)}>Back</Button><Button variant="primary" icon={<IconSparkle size={14} />} loading={gen.isPending} onClick={() => gen.mutate({ ...form, nodes: form.nodes.filter((n) => n.id) })}>Generate blueprint</Button></div>
          </div>
        </Section>}
      </>}
      {result && <>
        <div className="row wrap gap3"><Button onClick={() => { setResult(null); setStep(2); }}>← Adjust inputs</Button><span className="xs muted">Blueprint {result.id}</span></div>
        <ChangeSetView key={result.id} cs={result} onUpdated={onUpdated} canStage={allowed} headerActions={result.changes.some((c) => !c.staged && c.validation.ok) && result.status !== 'rejected' ? <Button variant="primary" loading={stageAll.isPending} onClick={() => stageAll.mutate(result)}>Stage all</Button> : undefined} onResubmit={(answers) => gen.mutate({ ...form, nodes: form.nodes.filter((n) => n.id), text: `${form.text}\n\n${Object.entries(answers).map(([q, a]) => `${q} ${a}`).join('\n')}` })} resubmitting={gen.isPending} />
        {result.changes.some((c) => c.staged) && <div className="callout ok"><span>Staged. Decisions: <Link to="/studio">Policy Studio drafts</Link> · Everything else: <Link to="/copilot?tab=artifacts">Copilot → Artifacts</Link> (apply each with a reason).</span></div>}
      </>}
    </div>
  );
}
