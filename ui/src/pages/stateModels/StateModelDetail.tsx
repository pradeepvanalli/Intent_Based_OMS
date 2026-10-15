import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { stateModelsApi } from '../../api/stateModels';
import type { MigrationReport, StateModel, ValidationReport } from '../../api/types';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select, Textarea } from '../../components/Form';
import { Dialog } from '../../components/Overlay';
import { YamlView, KeyValue } from '../../components/Misc';
import { Tabs, TabPanel } from '../../components/Tabs';
import { Table } from '../../components/Table';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { StateDiagram } from './StateDiagram';
import { fmtDateTime, fmtIsoDuration } from '../../lib/format';
import { hasPermission, useSession } from '../../auth/session';
import { toast } from '../../lib/toast';
import { useQueryState } from '../../lib/hooks';
import { lineDiff } from '../../lib/diff';
import { parseStateModelYaml } from '../../lib/modelYaml';

const NEW_TEMPLATE = `stateModel: order_line.my_type
version: 1
entity: order_line
quantityLevel: true
states:
  - { id: created, meta: Created }
  - { id: released, sla: { maxAge: PT4H, onBreach: playbook.store_backlog } }
  - { id: shipped }
  - { id: delivered, meta: Terminal }
  - { id: cancelled, meta: Terminal }
transitions:
  - { from: created, to: released, trigger: decision.sourcing_plan, guards: ["plan.margin >= tenant.margin_floor || approval.present"] }
  - { from: released, to: shipped, trigger: event.shipped, actions: [capture_payment] }
  - { from: shipped, to: delivered, trigger: event.delivered }
  - { from: [created, released], to: cancelled, trigger: api.cancel_line, permission: order.cancel }
`;

export default function StateModelDetail() {
  const { id = '' } = useParams(); const isNew = id === 'new'; const navigate = useNavigate(); const qc = useQueryClient(); const { principal } = useSession();
  const versions = useQuery({ queryKey: ['state-model-versions', id], queryFn: () => stateModelsApi.versions(id), enabled: !isNew });
  const [vsel, setVsel] = useQueryState('v'); const [tab, setTab] = useQueryState('tab', 'diagram');
  const list = versions.data ?? []; const current = list.find((m) => String(m.version) === vsel) ?? list[list.length - 1];
  const [yaml, setYaml] = useState(''); const [reason, setReason] = useState(''); const [report, setReport] = useState<ValidationReport | null>(null); const [publishOpen, setPublishOpen] = useState(false);
  useEffect(() => { if (isNew) setYaml(NEW_TEMPLATE); else if (current) setYaml(current.yaml); }, [current, isNew]);
  const validate = useMutation({ mutationFn: () => stateModelsApi.validate(yaml), onSuccess: setReport });
  const publish = useMutation({ mutationFn: () => stateModelsApi.publish(yaml, reason), onSuccess: (m) => { toast.ok(`Published ${m.id} v${m.version}`); setPublishOpen(false); qc.invalidateQueries({ queryKey: ['state-models'] }); qc.invalidateQueries({ queryKey: ['state-model-versions'] }); navigate(`/state-models/${encodeURIComponent(m.id)}?v=${m.version}`); } });
  const canPublish = hasPermission(principal, 'statemodel.publish') || hasPermission(principal, 'statemodel.*');
  const draftModel = useMemo(() => { try { return parseStateModelYaml(yaml); } catch { return null; } }, [yaml]);
  const [diffAgainst, setDiffAgainst] = useState<number | ''>('');
  const [migrateOpen, setMigrateOpen] = useState(false);
  if (!isNew && versions.isLoading) return <div className="page"><Loading /></div>;
  if (!isNew && (versions.isError || !current)) return <div className="page"><ErrorState error={versions.error ?? new Error('Not found')} retry={() => versions.refetch()} /></div>;
  const model: StateModel | null = isNew ? (draftModel ? { ...draftModel } : null) : current!;
  const dirty = !isNew && current && yaml !== current.yaml;
  const otherV = typeof diffAgainst === 'number' ? list.find((m) => m.version === diffAgainst) : undefined;
  return (
    <div className="page">
      <nav className="small muted" aria-label="Breadcrumb"><Link to="/state-models">State models</Link> / <span className="mono">{isNew ? 'new' : id}</span></nav>
      <div className="page-head">
        <div><div className="order-title"><h1 className="mono" style={{ fontSize: 'var(--fs-xl)' }}>{model?.id ?? 'New state model'}</h1>{model && <Badge tone="accent" mono lg>v{model.version}</Badge>}{model && <Badge tone="outline" lg>{model.entity}</Badge>}{model?.quantityLevel && <Badge tone="ok" lg title="Tracked separately for each unit of quantity, not just once per line">quantity-level</Badge>}{dirty && <Badge tone="warn" lg>unsaved edits</Badge>}</div>
          <div className="sub">{isNew ? 'Describe the states an entity can be in and the guarded moves between them, then publish a new version.' : 'The states this entity can be in and the guarded moves between them — view the diagram, inspect the rules, or compare versions below.'}</div>
          {current && !isNew && <div className="small muted" style={{ marginTop: 4 }}>Published {fmtDateTime(current.publishedAt)} by {current.publishedBy} — {current.reason}</div>}
        </div>
        <div className="page-actions">{!isNew && list.length > 1 && <Button onClick={() => setMigrateOpen(true)}>Migrate in-flight</Button>}<Button onClick={() => validate.mutate()} loading={validate.isPending}>Validate</Button><Button variant="primary" disabled={!canPublish} title={canPublish ? undefined : 'Requires statemodel.publish'} onClick={() => setPublishOpen(true)}>Publish…</Button></div>
      </div>
      <div className="studio-layout">
        <aside className="card card-pad col">
          <div className="section-title">Versions</div>
          {isNew && <div className="muted small">Publishing creates version 1 of the id in the YAML.</div>}
          <div className="version-list">{[...list].reverse().map((m) => <button key={m.version} className="version-item" aria-current={m.version === current?.version} onClick={() => { setVsel(String(m.version)); setReport(null); }}><span className="v">v{m.version}</span><span className="col gap1" style={{ alignItems: 'flex-start' }}><span className="small truncate" style={{ maxWidth: 180 }} title={m.reason}>{m.reason}</span><span className="xs muted">{fmtDateTime(m.publishedAt)} · {m.publishedBy}</span></span>{m === list[list.length - 1] && <Badge tone="ok">latest</Badge>}</button>)}</div>
          {model && <><div className="divider" /><div className="section-title">Summary</div><KeyValue tight items={[['States', model.states.length], ['Terminal', model.states.filter((s) => s.meta === 'TERMINAL').map((s) => s.id).join(', ') || '—'], ['Transitions', model.transitions.length], ['SLA timers', model.states.filter((s) => s.sla).length], ['Roll-up rules', model.rollup?.length ?? 0]]} /></>}
        </aside>
        <div className="col gap4">
          <Tabs ariaLabel="Model views" active={tab} onChange={setTab} tabs={[{ id: 'diagram', label: 'Diagram' }, { id: 'yaml', label: 'YAML' }, { id: 'states', label: 'States & transitions' }, { id: 'diff', label: 'Version diff' }]} />
          <TabPanel id="diagram" active={tab}>{model ? <StateDiagram model={model} /> : <EmptyState title="YAML does not parse" hint="Fix the YAML tab to render the diagram." />}</TabPanel>
          <TabPanel id="yaml" active={tab}>
            <div className="grid grid-2">
              <div className="col gap2"><div className="row-between"><span className="section-title" style={{ margin: 0 }}>Editor</span><span className="xs muted">PRD Appendix A format · {yaml.split('\n').length} lines</span></div><Textarea aria-label="State model YAML" mono rows={28} value={yaml} onChange={(e) => { setYaml(e.target.value); setReport(null); }} spellCheck={false} style={{ fontSize: 'var(--fs-sm)', lineHeight: 1.55 }} />{dirty && <Button size="sm" variant="ghost" onClick={() => setYaml(current!.yaml)}>Revert to v{current!.version}</Button>}</div>
              <div className="col gap2"><span className="section-title">Highlighted</span><YamlView text={yaml} maxHeight={640} /></div>
            </div>
          </TabPanel>
          <TabPanel id="states" active={tab}>{model && <div className="grid grid-2">
            <Section title="States" tight><Table rows={model.states} rowKey={(s) => s.id} dense columns={[{ key: 'id', header: 'State', render: (s) => <span className="mono">{s.id}</span> }, { key: 'meta', header: 'Meta', render: (s) => <StatusBadge status={s.meta === 'CREATED' ? 'created' : s.meta === 'TERMINAL' ? 'completed' : 'active'} /> }, { key: 'sla', header: <span title="How long an entity may sit in this state before the given playbook runs">SLA</span>, render: (s) => s.sla ? <span className="small">{fmtIsoDuration(s.sla.maxAge)} → <span className="mono">{s.sla.onBreach}</span></span> : <span className="muted">—</span> }]} /></Section>
            <Section title="Transitions" tight><Table rows={model.transitions} rowKey={(t) => t.id} dense columns={[{ key: 'from', header: 'From → To', render: (t) => <span className="mono small">{t.from.join(' | ')} → <b>{t.to}</b></span> }, { key: 'trig', header: <span title="What causes this move: a decision result, a direct API call, or an event">Trigger</span>, render: (t) => <Badge tone={t.trigger.startsWith('decision.') ? 'L1' : t.trigger.startsWith('api.') ? 'L3' : 'outline'} mono>{t.trigger}</Badge> }, { key: 'g', header: <span title="Guards are conditions that must be true for the move to happen; actions run as a side effect of it">Guards / actions</span>, render: (t) => <span className="col gap1 xs">{(t.guards ?? []).map((g) => <code key={g}>{g}</code>)}{(t.actions ?? []).length > 0 && <span className="muted">actions: {t.actions!.join(', ')}</span>}{t.permission && <span className="muted">permission: {t.permission}</span>}</span> }]} /></Section>
            {model.rollup && model.rollup.length > 0 && <Section title={<span title="FR-SM-05">Roll-up rules</span>} tight><p className="xs muted" style={{ padding: '0 var(--s4)', marginTop: 0 }}>How a parent entity's state is derived from the states of its lines — e.g. an order is "shipped" once every line is.</p><Table rows={model.rollup} rowKey={(r) => r.parentState} dense columns={[{ key: 'p', header: 'Priority', align: 'right', render: (r) => r.priority }, { key: 's', header: 'Parent state', render: (r) => <span className="mono">{r.parentState}</span> }, { key: 'e', header: 'Expression (CEL)', render: (r) => <code className="xs">{r.expression}</code> }]} /></Section>}
          </div>}</TabPanel>
          <TabPanel id="diff" active={tab}>
            <div className="row gap3 mb3"><span className="small">Compare {dirty ? 'editor' : `v${current?.version ?? '?'}`} with</span><Select aria-label="Compare version" sm value={String(diffAgainst)} onChange={(e) => setDiffAgainst(e.target.value ? Number(e.target.value) : '')}><option value="">select version…</option>{list.filter((m) => m.version !== current?.version || dirty).map((m) => <option key={m.version} value={m.version}>v{m.version}</option>)}</Select></div>
            {otherV ? <SideBySide left={otherV.yaml} right={yaml} leftLabel={`v${otherV.version}`} rightLabel={dirty ? 'editor' : `v${current?.version}`} /> : <EmptyState title="Pick a version to compare" hint="Choose an earlier version above to see exactly what changed, line by line." />}
          </TabPanel>
          {report && <Section title={<span className="row gap3">Validation report {report.ok ? <Badge tone="ok">passes static checks</Badge> : <Badge tone="danger">{report.issues.filter((i) => i.severity === 'error').length} error(s)</Badge>}</span>}>
            {report.issues.length === 0 && <div className="small muted">No issues. Reachability, dead-ends, guard syntax and SLA durations all check out.</div>}
            <div className="col gap1">{report.issues.map((i, k) => <div key={k} className="lint-item"><Badge tone={i.severity === 'error' ? 'danger' : 'warn'}>{i.severity}</Badge><div><b className="mono xs">{i.code}</b> {i.message}{i.ref && <span className="muted"> · <span className="mono">{i.ref}</span></span>}</div></div>)}</div>
          </Section>}
        </div>
      </div>
      <Dialog open={publishOpen} onClose={() => setPublishOpen(false)} title="Publish state model" description="Static checks must pass. The version in the YAML must be new. Existing entities keep their pinned version until migrated." footer={<><Button onClick={() => setPublishOpen(false)}>Cancel</Button><Button variant="primary" loading={publish.isPending} disabled={!reason.trim()} onClick={() => publish.mutate()}>Publish</Button></>}>
        <KeyValue tight items={[['Model', <span className="mono">{draftModel?.id ?? '—'}</span>], ['Version', draftModel?.version ?? '—'], ['States / transitions', draftModel ? `${draftModel.states.length} / ${draftModel.transitions.length}` : '—']]} />
        <Textarea label="Change reason (required, audited — GR-10)" value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
      </Dialog>
      {migrateOpen && current && <MigrateDialog model={id} versions={list} current={current.version} onClose={() => setMigrateOpen(false)} />}
    </div>
  );
}

export function SideBySide({ left, right, leftLabel, rightLabel }: { left: string; right: string; leftLabel: string; rightLabel: string }) {
  const rows = useMemo(() => lineDiff(left, right), [left, right]);
  const added = rows.filter((r) => r.kind === 'add').length; const removed = rows.filter((r) => r.kind === 'del').length; const changed = rows.filter((r) => r.kind === 'chg').length;
  return (
    <div className="col gap2">
      <div className="row gap3 small"><Badge tone="ok">+{added + changed}</Badge><Badge tone="danger">−{removed + changed}</Badge><span className="muted">{leftLabel} → {rightLabel}</span></div>
      <div className="diff" role="table" aria-label={`Diff ${leftLabel} to ${rightLabel}`}>
        <div className="side" role="rowgroup"><div className="dl" style={{ background: 'var(--surface-2)', fontWeight: 600, position: 'sticky', top: 0 }}>{leftLabel}</div>{rows.map((r, i) => <div key={i} className={`dl ${r.kind === 'del' || r.kind === 'chg' ? (r.kind === 'chg' ? 'chg' : 'del') : r.kind === 'add' ? 'pad' : ''}`}><span className="ln">{r.ln ?? ''}</span>{r.left ?? ''}</div>)}</div>
        <div className="side" role="rowgroup"><div className="dl" style={{ background: 'var(--surface-2)', fontWeight: 600, position: 'sticky', top: 0 }}>{rightLabel}</div>{rows.map((r, i) => <div key={i} className={`dl ${r.kind === 'add' || r.kind === 'chg' ? (r.kind === 'chg' ? 'chg' : 'add') : r.kind === 'del' ? 'pad' : ''}`}><span className="ln">{r.rn ?? ''}</span>{r.right ?? ''}</div>)}</div>
      </div>
    </div>
  );
}

function MigrateDialog({ model, versions, current, onClose }: { model: string; versions: StateModel[]; current: number; onClose: () => void }) {
  const [from, setFrom] = useState(versions.find((v) => v.version !== current)?.version ?? current); const [to, setTo] = useState(current); const [report, setReport] = useState<MigrationReport | null>(null);
  const qc = useQueryClient();
  const dry = useMutation({ mutationFn: () => stateModelsApi.migrate(model, from, to, true), onSuccess: setReport });
  const run = useMutation({ mutationFn: () => stateModelsApi.migrate(model, from, to, false), onSuccess: (r) => { setReport(r); toast.ok(`Migrated ${r.migrated} entities`, r.blocked ? `${r.blocked} blocked` : undefined); qc.invalidateQueries({ queryKey: ['order'] }); } });
  return (
    <Dialog open onClose={onClose} title="Migrate in-flight entities" width={720} description="Moves entities pinned to one version onto another. Dry run first; blocked entities (state missing in target) are reported per entity." footer={<><Button onClick={onClose}>Close</Button>{!report || report.dryRun ? <><Button loading={dry.isPending} onClick={() => dry.mutate()}>Dry run</Button>{report?.dryRun && <Button variant="primary" loading={run.isPending} disabled={report.migrated === 0} onClick={() => run.mutate()}>Migrate {report.migrated}</Button>}</> : null}</>}>
      <div className="row gap3"><Select label="From version" value={String(from)} onChange={(e) => { setFrom(Number(e.target.value)); setReport(null); }}>{versions.map((v) => <option key={v.version} value={v.version}>v{v.version}</option>)}</Select><Select label="To version" value={String(to)} onChange={(e) => { setTo(Number(e.target.value)); setReport(null); }}>{versions.map((v) => <option key={v.version} value={v.version}>v{v.version}</option>)}</Select></div>
      {report && <>
        <div className="row gap3"><Badge tone={report.dryRun ? 'info' : 'ok'} lg>{report.dryRun ? 'Dry run' : 'Applied'}</Badge><span className="small">{report.total} entities · {report.migrated} ok · {report.blocked} blocked</span></div>
        {report.entities.length === 0 ? <div className="muted small">No entities pinned to v{from}.</div> : <div className="table-wrap" style={{ maxHeight: 300, border: '1px solid var(--border)' }}><table className="tbl dense"><thead><tr><th>Entity</th><th>State</th><th>Result</th></tr></thead><tbody>{report.entities.map((e) => <tr key={e.entityId}><td className="mono small">{e.entityId}</td><td className="mono small">{e.fromState}{e.fromState !== e.toState ? ` → ${e.toState}` : ''}</td><td><Badge tone={e.status === 'ok' ? 'ok' : 'danger'}>{e.status}</Badge> <span className="xs muted">{e.message}</span></td></tr>)}</tbody></table></div>}
      </>}
    </Dialog>
  );
}
