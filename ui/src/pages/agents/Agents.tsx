import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { agentsApi } from '../../api/agents';
import { ordersApi } from '../../api/orders';
import type { AgentIdentity } from '../../api/types';
import { Badge, StatusBadge, TrustBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ChipInput, Input, Select, Switch } from '../../components/Form';
import { Drawer } from '../../components/Overlay';
import { JsonTree, KeyValue, CopyButton } from '../../components/Misc';
import { Table } from '../../components/Table';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { Tabs, TabPanel } from '../../components/Tabs';
import { IconAgent } from '../../components/Icons';
import { fmtDateTime, fmtMoney, humanize, relTime, safeJson } from '../../lib/format';
import { hasPermission, useSession } from '../../auth/session';
import { toast } from '../../lib/toast';
import { useQueryState } from '../../lib/hooks';

const ACTIONS = ['order_get', 'order_search', 'promise', 'order_modify', 'order_cancel', 'return_create', 'inventory_check', 'decision_why', 'task_list', 'task_complete'];

export default function Agents() {
  const { principal } = useSession(); const qc = useQueryClient(); const canAdmin = hasPermission(principal, 'agent.admin');
  const [tab, setTab] = useQueryState('tab', 'registry');
  const agents = useQuery({ queryKey: ['agents'], queryFn: agentsApi.list });
  const mandates = useQuery({ queryKey: ['mandates'], queryFn: () => agentsApi.mandates() });
  const [edit, setEdit] = useState<Partial<AgentIdentity> | null>(null);
  const [secret, setSecret] = useState<{ agentId: string; secret: string } | null>(null);
  const rotate = useMutation({ mutationFn: (id: string) => agentsApi.rotateSecret(id), onSuccess: (r) => { setSecret(r); toast.ok('Secret rotated'); } });
  const save = useMutation({ mutationFn: (a: Partial<AgentIdentity>) => (agents.data?.some((x) => x.id === a.id) ? agentsApi.update(a.id!, a) : agentsApi.create(a)), onSuccess: () => { toast.ok('Agent saved'); setEdit(null); qc.invalidateQueries({ queryKey: ['agents'] }); } });
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">Access &amp; trust</div>
          <h1><span className="icon-badge"><IconAgent size={20} /></span> Agents</h1>
          <div className="sub">Every external AI agent or integration that can act on orders on a customer's behalf — what it's allowed to do, how much it can spend, and the evidence trail if a dispute comes up.</div>
        </div>
        <div className="page-actions">{canAdmin && <Button variant="primary" onClick={() => setEdit({ id: '', owner: '', protocol: 'mcp', verification: 'api_key', trustTier: 1, active: true, limits: { allowedActions: ['order_get'], ratePerMinute: 60, confirmActions: [] } })}>Register agent</Button>}</div>
      </div>
      <Tabs ariaLabel="Agents sections" active={tab} onChange={setTab} tabs={[{ id: 'registry', label: 'Registry', count: agents.data?.length }, { id: 'mandates', label: 'Mandates', count: mandates.data?.length }, { id: 'evidence', label: 'Evidence pack' }]} />
      <TabPanel id="registry" active={tab}>
        <div className="card">
          {agents.isLoading && <Loading />}{agents.isError && <ErrorState error={agents.error} retry={() => agents.refetch()} />}
          {agents.data && <Table rows={agents.data} rowKey={(a) => a.id} onRowClick={canAdmin ? (a) => setEdit(a) : undefined} caption="Agent registry"
            empty={<EmptyState icon={<IconAgent size={18} />} title="No agents registered yet" hint="Register an agent to let an external AI shopping assistant or integration act on orders within limits you set." action={canAdmin ? <Button size="sm" variant="primary" onClick={() => setEdit({ id: '', owner: '', protocol: 'mcp', verification: 'api_key', trustTier: 1, active: true, limits: { allowedActions: ['order_get'], ratePerMinute: 60, confirmActions: [] } })}>Register agent</Button> : undefined} />}
            columns={[
            { key: 'id', header: 'Agent', render: (a) => <span className="col gap1"><span className="mono strong">{a.id}</span><span className="xs muted">{a.owner}</span></span> },
            { key: 'proto', header: <span title="How the agent connects: MCP (Model Context Protocol), ACP or UCP">Protocol</span>, render: (a) => <Badge tone="L2">{a.protocol.toUpperCase()}</Badge> }, { key: 'ver', header: 'Verification', render: (a) => humanize(a.verification) },
            { key: 'tier', header: <span title="How much this agent is trusted: unknown, verified, trusted partner, or internal">Trust</span>, render: (a) => <TrustBadge tier={a.trustTier} /> },
            { key: 'limits', header: 'Limits', render: (a) => <span className="col gap1 small"><span>{a.limits.allowedActions.length} actions · {a.limits.ratePerMinute ?? '—'}/min</span><span className="xs muted">max order {fmtMoney(a.limits.maxOrderValue)} · max refund {fmtMoney(a.limits.maxRefund)}{a.limits.confirmActions?.length ? ` · confirm: ${a.limits.confirmActions.join(', ')}` : ''}</span></span> },
            { key: 'vol', header: 'Orders 30d', align: 'right', render: (a) => a.orders30d ?? 0 }, { key: 'seen', header: 'Last seen', render: (a) => relTime(a.lastSeenAt) },
            { key: 'active', header: 'Status', render: (a) => <StatusBadge status={a.active ? 'active' : 'disabled'} /> },
            { key: 'act', header: '', render: (a) => canAdmin ? <Button size="sm" onClick={(e) => { e.stopPropagation(); rotate.mutate(a.id); }}>Rotate secret</Button> : null },
          ]} />}
        </div>
      </TabPanel>
      <TabPanel id="mandates" active={tab}>
        <p className="small muted mb3">A <b>mandate</b> is a scoped, time-limited permission a buyer grants an agent — what it may do, how much it may spend, and until when.</p>
        <div className="card">{mandates.isLoading && <Loading />}{mandates.data && <Table rows={mandates.data} rowKey={(m) => m.id} caption="Mandates" empty={<EmptyState title="No mandates yet" hint="A mandate is created when a buyer authorizes an agent to act on their behalf within a spend limit and scope." />} columns={[
          { key: 'id', header: 'Mandate', render: (m) => <span className="mono">{m.id}</span> }, { key: 'agent', header: 'Agent', render: (m) => <span className="mono">{m.agentId}</span> }, { key: 'buyer', header: 'Buyer', render: (m) => <span className="mono">{m.buyerRef}</span> },
          { key: 'scope', header: 'Scope', render: (m) => <span className="pill-row">{m.scope.map((s) => <Badge key={s} tone="outline">{s}</Badge>)}</span> }, { key: 'limit', header: 'Spend limit', align: 'right', render: (m) => fmtMoney(m.spendLimit) },
          { key: 'instr', header: 'Instruction', render: (m) => <span className="small muted truncate" style={{ maxWidth: 320, display: 'inline-block' }} title={m.instructionText}>“{m.instructionText}”</span> },
          { key: 'st', header: 'Status', render: (m) => <StatusBadge status={m.status} /> }, { key: 'exp', header: 'Expires', render: (m) => fmtDateTime(m.expiresAt) }, { key: 'orders', header: 'Orders', render: (m) => (m.usedOrderIds ?? []).map((o) => <Link key={o} to={`/orders/${o}`} className="mono small">{o}</Link>) },
        ]} />}</div>
      </TabPanel>
      <TabPanel id="evidence" active={tab}><EvidenceViewer initial={mandates.data?.[0]?.usedOrderIds?.[0] ?? ''} /></TabPanel>

      {edit && <Drawer open onClose={() => setEdit(null)} title={edit.id && agents.data?.some((a) => a.id === edit.id) ? `Edit ${edit.id}` : 'Register agent'} footer={<><Button onClick={() => setEdit(null)}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!edit.id || !edit.owner} onClick={() => save.mutate(edit)}>Save</Button></>}>
        <div className="form-grid">
          <Input label="Agent id" value={edit.id ?? ''} onChange={(e) => setEdit({ ...edit, id: e.target.value })} mono disabled={agents.data?.some((a) => a.id === edit.id) && !!edit.createdAt} />
          <Input label="Owner" value={edit.owner ?? ''} onChange={(e) => setEdit({ ...edit, owner: e.target.value })} />
          <Select label="Protocol" value={edit.protocol} onChange={(e) => setEdit({ ...edit, protocol: e.target.value })}>{['ucp', 'acp', 'mcp'].map((p) => <option key={p} value={p}>{p.toUpperCase()}</option>)}</Select>
          <Select label="Verification" value={edit.verification} onChange={(e) => setEdit({ ...edit, verification: e.target.value })}>{['hmac', 'public_key', 'api_key'].map((p) => <option key={p} value={p}>{humanize(p)}</option>)}</Select>
          <Select label="Trust tier" value={String(edit.trustTier ?? 1)} onChange={(e) => setEdit({ ...edit, trustTier: Number(e.target.value) })} hint="0 unknown (read-only on own orders) · 1 verified · 2 trusted partner · 3 internal">{[0, 1, 2, 3].map((t) => <option key={t} value={t}>{t}</option>)}</Select>
          <Input label="Callback URL" value={edit.callbackUrl ?? ''} onChange={(e) => setEdit({ ...edit, callbackUrl: e.target.value })} placeholder="https://…/oms/callback" />
          <Input label="Max order value (USD)" type="number" value={edit.limits?.maxOrderValue?.amount ?? ''} onChange={(e) => setEdit({ ...edit, limits: { ...edit.limits!, maxOrderValue: e.target.value ? { amount: Number(e.target.value), currency: 'USD' } : undefined } })} />
          <Input label="Max refund (USD)" type="number" value={edit.limits?.maxRefund?.amount ?? ''} onChange={(e) => setEdit({ ...edit, limits: { ...edit.limits!, maxRefund: e.target.value ? { amount: Number(e.target.value), currency: 'USD' } : undefined } })} />
          <Input label="Rate limit (calls/min)" type="number" value={edit.limits?.ratePerMinute ?? ''} onChange={(e) => setEdit({ ...edit, limits: { ...edit.limits!, ratePerMinute: Number(e.target.value) } })} />
          <div className="field"><span className="field-label">Active</span><Switch label={edit.active ? 'enabled' : 'disabled'} checked={!!edit.active} onChange={(v) => setEdit({ ...edit, active: v })} /></div>
        </div>
        <ChipInput label="Allowed actions (MCP tools)" values={edit.limits?.allowedActions ?? []} onChange={(v) => setEdit({ ...edit, limits: { ...edit.limits!, allowedActions: v } })} validate={(v) => (ACTIONS.includes(v) ? null : `Unknown tool. Known: ${ACTIONS.join(', ')}`)} hint={`Known tools: ${ACTIONS.join(', ')}`} />
        <ChipInput label="Actions requiring confirmation" values={edit.limits?.confirmActions ?? []} onChange={(v) => setEdit({ ...edit, limits: { ...edit.limits!, confirmActions: v } })} validate={(v) => (ACTIONS.includes(v) ? null : 'Unknown tool')} hint="These tools return a confirmation request instead of acting (GR-08 read-only by default)." />
      </Drawer>}
      {secret && <Drawer open onClose={() => setSecret(null)} title="New secret" footer={<Button variant="primary" onClick={() => setSecret(null)}>Done</Button>}>
        <div className="callout warn"><span>Share this with the owner of <b className="mono">{secret.agentId}</b> now. It is not retrievable later; the previous secret stops working immediately.</span></div>
        <pre className="code">{secret.secret}</pre><CopyButton text={secret.secret} label="Copy secret" />
      </Drawer>}
    </div>
  );
}

function EvidenceViewer({ initial }: { initial: string }) {
  const [id, setId] = useState(initial); const [orderId, setOrderId] = useState(initial);
  const pack = useQuery({ queryKey: ['evidence', orderId], queryFn: () => ordersApi.evidencePack(orderId), enabled: !!orderId });
  return (
    <div className="two-col rev" style={{ gridTemplateColumns: '320px minmax(0,1fr)' }}>
      <Section title="Order"><form className="col gap3" onSubmit={(e) => { e.preventDefault(); setOrderId(id); }}><Input label="Order id with agent provenance" value={id} onChange={(e) => setId(e.target.value)} mono placeholder="ORD-100244" /><Button type="submit" variant="primary">Build evidence pack</Button><div className="xs muted">Bundles the mandate, agent identity, instruction text, a prompt-injection screen, the full decision trail and delivery proof — hashed, for dispute evidence (FR-AGT-09).</div></form></Section>
      <Section title={<span className="row gap3">Evidence pack {pack.data && <span className="mono small muted">{pack.data.hash}</span>}</span>} actions={pack.data && <CopyButton text={safeJson(pack.data)} label="Copy JSON" />}>
        {!orderId && <EmptyState title="Enter an order id" hint="Builds a single hashed bundle of everything an agent did on this order — useful for a chargeback or buyer dispute." />}
        {pack.isLoading && <Loading />}
        {pack.isError && <ErrorState error={pack.error} />}
        {pack.data && <div className="col gap4">
          <KeyValue tight items={[['Order', <Link to={`/orders/${pack.data.orderId}`} className="mono">{pack.data.orderId}</Link>], ['Agent', <span className="mono">{pack.data.identity?.id ?? '—'}</span>], ['Mandate', <span className="mono">{pack.data.mandate?.id ?? '—'}</span>], ['Spend limit', fmtMoney(pack.data.mandate?.spendLimit)], ['Injection screen', pack.data.injectionScreen?.flagged ? <Badge tone="danger">flagged</Badge> : <Badge tone="ok">clean</Badge>], ['Decisions', pack.data.decisionTrail.length], ['Generated', fmtDateTime(pack.data.generatedAt)]]} />
          <div><div className="section-title">Tree</div><div className="code" style={{ whiteSpace: 'normal' }}><JsonTree data={pack.data} defaultOpen={1} /></div></div>
          <div><div className="section-title">Raw JSON (select all to download)</div><pre className="code" style={{ maxHeight: 420 }} tabIndex={0}>{safeJson(pack.data)}</pre></div>
        </div>}
      </Section>
    </div>
  );
}
