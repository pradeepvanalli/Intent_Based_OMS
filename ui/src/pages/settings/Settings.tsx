import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/admin';
import { authApi } from '../../api/auth';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ChipInput, Input, Select, Switch, Textarea } from '../../components/Form';
import { Dialog } from '../../components/Overlay';
import { CopyButton } from '../../components/Misc';
import { Table } from '../../components/Table';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { Tabs, TabPanel } from '../../components/Tabs';
import { fmtDateTime, safeJson } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { Link } from 'react-router-dom';
import { IconSettings, IconSparkle } from '../../components/Icons';
import { toast } from '../../lib/toast';
import { restartTour } from '../../shell/tourSteps';

const KNOWN: { key: string; desc: string; kind: 'number' | 'boolean' | 'string' | 'json' | 'secret' }[] = [
  { key: 'sourcing.margin_floor', desc: 'Contribution margin ratio below which a plan needs approval (GR-01)', kind: 'number' },
  { key: 'sourcing.max_split_packages', desc: 'Maximum packages per order when splitting', kind: 'number' },
  { key: 'sourcing.split_penalty_usd', desc: 'Cost added per extra package in plan ranking', kind: 'number' },
  { key: 'sourcing.candidate_limit', desc: 'Candidate nodes evaluated per order (default 100)', kind: 'number' },
  { key: 'sourcing.channel_default_policy', desc: 'Fulfilment policy per channel when intent is unclear', kind: 'json' },
  { key: 'ai.budget_usd_per_day', desc: 'Daily AI spend budget; L1/L2 degrade to L0 when exceeded (GR-17)', kind: 'number' },
  { key: 'ai.kill_switch', desc: 'Disable all L1/L2 decisions tenant-wide (GR-06)', kind: 'boolean' },
  { key: 'provider.kill_switch.jev', desc: 'Disable the Jev provider; fall back to fallbackProvider / default', kind: 'boolean' },
  { key: 'provider.kill_switch.llm.anthropic', desc: 'Disable the Anthropic LLM provider', kind: 'boolean' },
  { key: 'guardrail.refund_velocity_usd_per_hour', desc: 'Circuit breaker on aggregate refund spend (GR-05)', kind: 'number' },
  { key: 'guardrail.appeasement_velocity_usd_per_hour', desc: 'Circuit breaker on appeasement spend (GR-05)', kind: 'number' },
  { key: 'returns.window_days', desc: 'Return window for the standard order type', kind: 'number' },
  { key: 'returns.instant_refund_cap_usd', desc: 'Maximum value for instant refunds', kind: 'number' },
  { key: 'inventory.channel_buffer', desc: 'ATP buffer per channel', kind: 'json' },
  { key: 'capture.validation_steps', desc: 'Ordered validation pipeline for capture (FR-CAP-05)', kind: 'json' },
  { key: 'retention.years', desc: 'Audit retention (FR-XAI-02)', kind: 'number' },
  { key: 'alerts.slack_webhook', desc: 'Slack incoming webhook for alerts (FR-MON-06)', kind: 'secret' },
  { key: 'alerts.pagerduty_routing_key', desc: 'PagerDuty Events v2 routing key', kind: 'secret' },
  { key: 'alerts.email', desc: 'Alert email recipient', kind: 'string' },
];

export default function Settings() {
  const [tab, setTab] = useQueryState('tab', 'config');
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">Tenant administration</div>
          <h1><span className="icon-badge"><IconSettings size={20} /></span> Settings</h1>
          <div className="sub">The knobs that control how this tenant runs — margin floors, AI spend budgets and kill switches, API keys, webhooks — and the audit log of every change made here.</div>
        </div>
        <div className="page-actions"><Button variant="ghost" onClick={() => restartTour()} title="Show the six-step first-run tour again">Restart tour</Button><Link to="/copilot/bootstrap" className="btn" title="Describe your business and get a reviewable configuration bundle"><IconSparkle size={14} /> Bootstrap with copilot</Link></div>
      </div>
      <Tabs ariaLabel="Settings sections" active={tab} onChange={setTab} tabs={[{ id: 'config', label: 'Tenant config' }, { id: 'keys', label: 'API keys' }, { id: 'webhooks', label: 'Webhooks' }, { id: 'audit', label: 'Audit log' }]} />
      <TabPanel id="config" active={tab}><ConfigEditor /></TabPanel>
      <TabPanel id="keys" active={tab}><ApiKeys /></TabPanel>
      <TabPanel id="webhooks" active={tab}><Webhooks /></TabPanel>
      <TabPanel id="audit" active={tab}><Audit /></TabPanel>
    </div>
  );
}

function ConfigEditor() {
  const qc = useQueryClient();
  const cfg = useQuery({ queryKey: ['config'], queryFn: () => adminApi.config() });
  const [editing, setEditing] = useState<{ key: string; value: string; reason: string; kind: string } | null>(null);
  const save = useMutation({ mutationFn: (e: { key: string; value: unknown; reason: string }) => adminApi.setConfig(e.key, e.value, e.reason), onSuccess: (r) => { toast.ok('Config saved', r.key); setEditing(null); qc.invalidateQueries({ queryKey: ['config'] }); } });
  const toggle = useMutation({ mutationFn: (e: { key: string; value: boolean }) => adminApi.setConfig(e.key, e.value, `Toggled from Settings`), onSuccess: () => qc.invalidateQueries({ queryKey: ['config'] }) });
  if (cfg.isLoading) return <Loading />; if (cfg.isError) return <ErrorState error={cfg.error} retry={() => cfg.refetch()} />;
  const data = cfg.data ?? {}; const extra = Object.keys(data).filter((k) => !KNOWN.some((x) => x.key === k)).map((k) => ({ key: k, desc: 'Custom key', kind: typeof data[k] === 'boolean' ? 'boolean' as const : typeof data[k] === 'number' ? 'number' as const : typeof data[k] === 'object' ? 'json' as const : 'string' as const }));
  const groups = [['Sourcing & margin', (k: string) => k.startsWith('sourcing.')], ['AI budget & kill switches', (k: string) => k.startsWith('ai.') || k.startsWith('provider.') || k.startsWith('decision.kill')], ['Guardrails & returns', (k: string) => k.startsWith('guardrail.') || k.startsWith('returns.')], ['Alerts', (k: string) => k.startsWith('alerts.')], ['Other', () => true]] as const;
  const all = [...KNOWN, ...extra]; const used = new Set<string>();
  const parse = (kind: string, v: string): unknown => kind === 'number' ? Number(v) : kind === 'boolean' ? v === 'true' : kind === 'json' ? JSON.parse(v) : v;
  return (
    <div className="col gap4">
      <div className="callout warn"><span>Kill switches take effect immediately and fall back to the deterministic default (GR-06). Every change requires a reason and is written to the audit log.</span></div>
      {groups.map(([title, pred]) => { const rows = all.filter((k) => !used.has(k.key) && pred(k.key)); rows.forEach((r) => used.add(r.key)); if (!rows.length) return null; return (
        <Section key={title} title={title} tight>
          <div style={{ padding: '0 var(--s4)' }}>{rows.map((k) => { const v = data[k.key]; return (
            <div key={k.key} className="config-row">
              <div><div className="k">{k.key}</div><div className="desc">{k.desc}</div></div>
              <div className="small">{k.kind === 'boolean' ? <Switch label={v ? 'on' : 'off'} checked={!!v} onChange={(nv) => toggle.mutate({ key: k.key, value: nv })} /> : k.kind === 'secret' ? <span className="mono">{v ? String(v).replace(/.(?=.{4})/g, '•') : <span className="muted">not set</span>}</span> : k.kind === 'json' ? <code className="xs">{safeJson(v, 0)}</code> : <span className="mono">{v === undefined ? <span className="muted">not set</span> : String(v)}</span>}</div>
              <Button size="sm" onClick={() => setEditing({ key: k.key, value: k.kind === 'json' ? safeJson(v) : String(v ?? ''), reason: '', kind: k.kind })}>Edit</Button>
            </div>); })}</div>
        </Section>); })}
      {editing && <Dialog open onClose={() => setEditing(null)} title={<span className="mono">{editing.key}</span>} footer={<><Button onClick={() => setEditing(null)}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!editing.reason.trim()} onClick={() => { try { save.mutate({ key: editing.key, value: parse(editing.kind, editing.value), reason: editing.reason }); } catch (e) { toast.danger('Invalid JSON', e instanceof Error ? e.message : ''); } }}>Save</Button></>}>
        {editing.kind === 'json' ? <Textarea label="Value (JSON)" mono rows={6} value={editing.value} onChange={(e) => setEditing({ ...editing, value: e.target.value })} /> : editing.kind === 'boolean' ? <Select label="Value" value={editing.value} onChange={(e) => setEditing({ ...editing, value: e.target.value })}><option value="true">true</option><option value="false">false</option></Select> : <Input label="Value" mono type={editing.kind === 'number' ? 'number' : 'text'} step="any" value={editing.value} onChange={(e) => setEditing({ ...editing, value: e.target.value })} />}
        <Textarea label="Change reason (required, audited)" rows={2} value={editing.reason} onChange={(e) => setEditing({ ...editing, reason: e.target.value })} />
      </Dialog>}
    </div>
  );
}

function ApiKeys() {
  const [subject, setSubject] = useState(''); const [type, setType] = useState<'USER' | 'AGENT' | 'SYSTEM'>('SYSTEM'); const [roles, setRoles] = useState<string[]>(['agent_readonly']); const [label, setLabel] = useState(''); const [issued, setIssued] = useState<{ keyId: string; apiKey: string } | null>(null);
  const issue = useMutation({ mutationFn: () => authApi.issueKey({ subjectId: subject, type, roles, label }), onSuccess: (r) => { setIssued(r); toast.ok('API key issued'); } });
  return (
    <div className="two-col rev" style={{ gridTemplateColumns: '420px minmax(0,1fr)' }}>
      <Section title="Issue API key"><form className="col gap3" onSubmit={(e) => { e.preventDefault(); issue.mutate(); }}>
        <Input label="Subject id" value={subject} onChange={(e) => setSubject(e.target.value)} mono required placeholder="erp-sync or agt_shopassist" />
        <Select label="Principal type" value={type} onChange={(e) => setType(e.target.value as typeof type)}><option value="SYSTEM">SYSTEM (integration)</option><option value="AGENT">AGENT</option><option value="USER">USER</option></Select>
        <ChipInput label="Roles" values={roles} onChange={setRoles} hint="e.g. agent_readonly, agent_shopper, ops_analyst, platform_engineer" />
        <Input label="Label" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="What this key is for" />
        <Button type="submit" variant="primary" loading={issue.isPending} disabled={!subject}>Issue key</Button>
      </form></Section>
      <Section title="Issued key">{!issued ? <div className="muted small">Keys are shown once, right after issuance. Revoke via <code>DELETE /api/v1/auth/api-keys/{'{keyId}'}</code>.</div> : <div className="col gap3"><div className="callout warn"><span>Store this key now; it is not retrievable later.</span></div><div className="small">Key id <span className="mono">{issued.keyId}</span></div><pre className="code">{issued.apiKey}</pre><div className="row"><CopyButton text={issued.apiKey} label="Copy key" /><Button size="sm" variant="ghost" onClick={() => setIssued(null)}>Dismiss</Button></div></div>}</Section>
    </div>
  );
}

function Webhooks() {
  const qc = useQueryClient(); const hooks = useQuery({ queryKey: ['webhooks'], queryFn: adminApi.webhooks });
  const [url, setUrl] = useState(''); const [filter, setFilter] = useState<string[]>(['order.*']); const [created, setCreated] = useState<{ id: string; secret: string } | null>(null);
  const create = useMutation({ mutationFn: () => adminApi.createWebhook(url, filter), onSuccess: (r) => { setCreated(r); setUrl(''); toast.ok('Webhook created'); qc.invalidateQueries({ queryKey: ['webhooks'] }); } });
  return (
    <div className="two-col rev" style={{ gridTemplateColumns: '420px minmax(0,1fr)' }}>
      <Section title="Add webhook"><form className="col gap3" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
        <Input label="URL" type="url" value={url} onChange={(e) => setUrl(e.target.value)} required placeholder="https://…" />
        <ChipInput label="Event filter" values={filter} onChange={setFilter} hint="Which events to deliver, e.g. order.created, order.* for every order event, or * for everything." />
        <Button type="submit" variant="primary" loading={create.isPending} disabled={!url}>Create</Button>
        {created && <div className="callout warn"><div className="col gap1"><span>Signing secret for <span className="mono">{created.id}</span> (shown once):</span><code className="small">{created.secret}</code><CopyButton text={created.secret} label="Copy secret" /></div></div>}
      </form></Section>
      <Section title="Subscriptions" tight>{hooks.isLoading && <Loading />}{hooks.data && <Table rows={hooks.data} rowKey={(w) => w.id} empty={<EmptyState title="No webhooks yet" hint="Add one on the left to get a signed HTTP POST whenever matching events happen." />} columns={[{ key: 'id', header: 'Id', render: (w) => <span className="mono">{w.id}</span> }, { key: 'url', header: 'URL', render: (w) => <span className="mono small">{w.url}</span> }, { key: 'f', header: 'Events', render: (w) => <span className="pill-row">{(Array.isArray(w.event_filter) ? w.event_filter : typeof w.event_filter === 'string' ? safeParse(w.event_filter) : ['*']).map((f) => <Badge key={f} tone="outline">{f}</Badge>)}</span> }, { key: 'a', header: 'Status', render: (w) => <StatusBadge status={w.active ? 'active' : 'disabled'} /> }, { key: 'c', header: 'Created', render: (w) => fmtDateTime(w.created_at) }]} />}</Section>
    </div>
  );
}
function safeParse(s: string): string[] { try { const v = JSON.parse(s); return Array.isArray(v) ? v.map(String) : [s]; } catch { return [s]; } }

function Audit() {
  const audit = useQuery({ queryKey: ['audit'], queryFn: () => adminApi.audit({ limit: 100 }) });
  return <Section title="Audit log (who · what · when)" tight>
    <p className="xs muted" style={{ padding: '0 var(--s4)', marginTop: 0 }}>Every change made on this page — and every kill switch, approval and config edit elsewhere — is recorded here with a reason.</p>
    {audit.isLoading && <Loading />}{audit.isError && <ErrorState error={audit.error} />}{audit.data && <Table rows={audit.data} rowKey={(a) => String(a.id)} dense empty={<EmptyState title="No audit entries yet" hint="Changes to config, decisions, state models and kill switches will show up here." />} columns={[{ key: 'at', header: 'When', render: (a) => fmtDateTime(a.at) }, { key: 'actor', header: 'Actor', render: (a) => <span className="mono small">{a.actor}</span> }, { key: 'action', header: 'Action', render: (a) => <Badge tone="outline">{a.action}</Badge> }, { key: 'obj', header: 'Object', render: (a) => <span className="mono small">{a.object_type} / {a.object_id}</span> }, { key: 'reason', header: 'Reason', render: (a) => a.reason ?? '—' }]} />}
  </Section>;
}
