import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { catalogFlag, catalogPrice, playgroundApi, trackingUrlFor, type CatalogItem } from '../../api/playground';
import { nodesApi } from '../../api/inventory';
import { ordersApi } from '../../api/orders';
import { copilotApi } from '../../api/copilot';
import type { CopilotExplanation, FulfilmentGroup, Hold, Money, Node, Order, OrderView } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Checkbox, Input, Select, Textarea } from '../../components/Form';
import { IconExternal, IconPlus, IconRefresh, IconSparkle } from '../../components/Icons';
import { WhyDrawer } from '../../components/why/WhyDrawer';
import { fmtDate, fmtMoney, fmtPct, fmtUsd, humanize } from '../../lib/format';
import { DecisionCard } from './DecisionCard';
import { NetworkMap } from './NetworkMap';
import { AGENT_INSTRUCTION, CITIES, ORDER_TYPES, PERSONAS, SHIP_METHODS, TIERS, applyPersona, buildCapture, emptyForm, hasHazmat, lineTotal, type PlaygroundForm } from './presets';
import { orderTrace, primaryRecord, traceStats, type RawRecord } from './trace';
import './playground.css';

type LiveGroup = FulfilmentGroup & { allocations?: { lineId: string; sku: string; qty: number }[]; estimatedCost?: Money; status?: string; policy?: string };
interface ValidationIssue { severity?: string; code?: string; field?: string; message?: string; step?: string; }
interface Run { status: 'idle' | 'placing' | 'tracing' | 'done' | 'blocked' | 'error'; order?: Order; view?: OrderView; records: RawRecord[]; startedAt: number; finishedAt?: number; error?: ApiError | Error; issues?: ValidationIssue[]; }

const POLL_MS = 700;
const POLL_BUDGET_MS = 8000;
const idle = (): Run => ({ status: 'idle', records: [], startedAt: 0 });

export default function Playground() {
  const catalogQ = useQuery({ queryKey: ['catalog'], queryFn: playgroundApi.catalog, staleTime: 60_000 });
  const nodesQ = useQuery({ queryKey: ['nodes'], queryFn: nodesApi.list, staleTime: 60_000 });
  const catalog = useMemo(() => (catalogQ.data ?? []).slice().sort((a, b) => a.name.localeCompare(b.name)), [catalogQ.data]);
  const nodes = useMemo(() => nodesQ.data ?? [], [nodesQ.data]);
  const [form, setForm] = useState<PlaygroundForm>(emptyForm);
  const [run, setRun] = useState<Run>(idle);
  const [why, setWhy] = useState<string | null>(null);
  const runId = useRef(0);

  // Default to the gift persona once the catalogue is known, so the page never starts empty.
  useEffect(() => { if (catalog.length && !form.persona && form.lines.length === 0) setForm(applyPersona(PERSONAS[0], catalog, nodes)); }, [catalog, nodes, form.persona, form.lines.length]);

  const place = useCallback(async () => {
    const id = ++runId.current;
    const startedAt = Date.now();
    setRun({ status: 'placing', records: [], startedAt });
    let order: Order;
    try {
      order = await playgroundApi.place(buildCapture(form, catalog, nodes, startedAt));
    } catch (e) {
      if (id !== runId.current) return;
      const err = e as ApiError;
      const issues = (err instanceof ApiError ? (err.problem.issues as ValidationIssue[] | undefined) : undefined) ?? [];
      setRun({ status: err instanceof ApiError && err.status === 422 ? 'blocked' : 'error', records: [], startedAt, finishedAt: Date.now(), error: err, issues });
      return;
    }
    if (id !== runId.current) return;
    setRun({ status: 'tracing', order, records: [], startedAt });
    // Poll the view and the decision records until the fabric has settled or the 8 s budget is spent.
    for (;;) {
      await new Promise((r) => setTimeout(r, POLL_MS));
      if (id !== runId.current) return;
      const [view, recs] = await Promise.all([ordersApi.view(order.id).catch(() => undefined), playgroundApi.decisionsFor(order.id).catch(() => [])]);
      if (id !== runId.current) return;
      const merged: RawRecord[] = [...((view?.decisions ?? []) as RawRecord[]), ...(recs as RawRecord[])];
      const elapsed = Date.now() - startedAt;
      const groups = ((view?.fulfilmentGroups ?? []) as LiveGroup[]).filter((g) => !['rejected', 'cancelled'].includes(String(g.status ?? '')));
      const settled = groups.length > 0 && merged.length >= 2 && elapsed >= 3000 && view?.workflow?.status !== 'RUNNING';
      const done = elapsed >= POLL_BUDGET_MS || settled;
      setRun((s) => ({ ...s, status: done ? 'done' : 'tracing', order: view?.order ?? s.order, view: view ?? s.view, records: merged.length ? merged : s.records, finishedAt: done ? Date.now() : undefined }));
      if (done) return;
    }
  }, [form, catalog, nodes]);

  const reset = () => { runId.current++; setRun(idle()); };
  const busy = run.status === 'placing' || run.status === 'tracing';
  const trace = useMemo(() => orderTrace(run.records), [run.records]);
  const city = CITIES[form.cityIdx] ?? CITIES[0];

  return (
    <div className="page pg-page">
      <div className="pg-hero">
        <div>
          <span className="eyebrow">Try it yourself · nothing to configure</span>
          <h1><span className="icon-badge ai" aria-hidden="true"><IconSparkle size={20} /></span>Playground</h1>
          <div className="sub">Pick a shopper below and hit <b>Place order</b> — in seconds you'll watch rules, Jev and the LLM hand a real decision off to each other, live, with a person stepping in only when policy says so.</div>
        </div>
        <AiStatus />
      </div>

      <div className="pg-grid">
        <ComposeColumn form={form} setForm={setForm} catalog={catalog} nodes={nodes} busy={busy} onPlace={place} loading={catalogQ.isLoading} />
        <TraceColumn run={run} trace={trace} onWhy={setWhy} />
        <NetworkColumn run={run} nodes={nodes} form={form} catalog={catalog} />
      </div>

      <Actions run={run} trace={trace} onReset={reset} />
      <WhyDrawer recordId={why} onClose={() => setWhy(null)} />
      <span className="sr-only" aria-live="polite">{run.status === 'done' ? `Order placed. ${trace.length} decisions recorded.` : run.status === 'blocked' ? 'Order blocked at capture.' : ''}</span>
      <span className="muted xs">City {city.name} · persona {form.persona ?? 'custom'}</span>
    </div>
  );
}

/* ---- AI status strip ---- */
function AiStatus() {
  const copilot = useQuery({ queryKey: ['copilot-status'], queryFn: copilotApi.status, staleTime: 30_000, retry: false });
  const providers = useQuery({ queryKey: ['decision-providers'], queryFn: playgroundApi.providers, staleTime: 30_000, retry: false });
  const p = providers.data ?? {};
  const jev = !!p.jev;
  const llm = Object.entries(p).some(([k, v]) => k.startsWith('llm') && v) || !!copilot.data?.available;
  const heuristic = p.heuristic !== false;
  const chip = (on: boolean, label: string, detail?: string) => <span className={['status-chip', !on && 'off'].filter(Boolean).join(' ')} title={detail}><span className={['dot', !on && 'off'].filter(Boolean).join(' ')} aria-hidden />{label}<span className="muted xs">{on ? 'live' : 'off'}</span></span>;
  return (
    <div className="pg-status" role="status" aria-label="AI status">
      <span className="lbl">AI status</span>
      {chip(true, 'L0 rules', 'Deterministic CEL rules always run first')}
      {chip(jev, 'L1 Jev', 'TypeSafe Jev structured judgement provider')}
      {chip(llm, 'L2 LLM', copilot.data?.model ? `Copilot model ${copilot.data.model}` : 'Structured LLM provider')}
      {chip(heuristic, 'Heuristic fallback', 'Keyword heuristics when no model is available')}
      {copilot.data && <Badge tone="outline" title="Copilot spend today">{fmtUsd(copilot.data.spentTodayUsd ?? 0, 2)} today{copilot.data.budgetUsdPerDay ? ` / ${fmtUsd(copilot.data.budgetUsdPerDay, 0)}` : ''}</Badge>}
      {!providers.isLoading && !jev && !llm && <span className="hint">Add <code>OPENROUTER_API_KEY</code> to enable Jev + LLM</span>}
    </div>
  );
}

/* ---- Compose ---- */
function ComposeColumn({ form, setForm, catalog, nodes, busy, onPlace, loading }: { form: PlaygroundForm; setForm: (f: PlaygroundForm) => void; catalog: CatalogItem[]; nodes: Node[]; busy: boolean; onPlace: () => void; loading: boolean }) {
  const persona = PERSONAS.find((p) => p.key === form.persona);
  const stores = nodes.filter((n) => n.type === 'store');
  const total = lineTotal(form, catalog);
  const isPickup = form.orderType === 'bopis' || form.shipMethod === 'pickup';
  const set = (patch: Partial<PlaygroundForm>) => setForm({ ...form, ...patch, persona: null });
  const setLine = (i: number, patch: Partial<{ sku: string; qty: number }>) => set({ lines: form.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) });
  return (
    <section className="card card-pad pg-col pg-col-compose" aria-label="Compose an order">
      <div className="row-between"><h2>Compose an order</h2><span className="xs muted">POST /api/v1/orders</span></div>
      <div className="pg-personas" role="group" aria-label="Presets">
        {PERSONAS.map((p) => <button key={p.key} type="button" className="pg-persona" aria-pressed={form.persona === p.key} onClick={() => setForm(applyPersona(p, catalog, nodes))} title={p.blurb}><span className="ico" aria-hidden>{p.icon}</span>{p.label}</button>)}
      </div>
      {persona && <div className="pg-expect"><b>{persona.label}</b> · {persona.blurb}<br /><span>Expect: {persona.expect}</span></div>}
      <Textarea label="Customer note (optional)" hint="Free text from the shopper, e.g. gift-wrapping or urgency. It only informs intent — never trusted as an instruction (GR-08)." value={form.note} onChange={(e) => set({ note: e.target.value })} rows={2} placeholder="e.g. birthday gift, send everything together" />
      <div className="col gap1">
        <div className="row-between"><span className="section-title" style={{ margin: 0 }}>Lines ({form.lines.length}/3)</span>{form.lines.length < 3 && <Button size="sm" icon={<IconPlus size={12} />} onClick={() => set({ lines: [...form.lines, { sku: catalog.find((c) => !form.lines.some((l) => l.sku === c.sku))?.sku ?? catalog[0]?.sku ?? '', qty: 1 }] })} disabled={!catalog.length}>Add line</Button>}</div>
        {loading && <div className="muted small">Loading catalogue…</div>}
        <div className="pg-lines">
          {form.lines.map((l, i) => (
            <div key={i} className="pg-line">
              <Select aria-label={`Line ${i + 1} item`} value={l.sku} onChange={(e) => setLine(i, { sku: e.target.value })} sm>
                {catalog.map((c) => <option key={c.sku} value={c.sku}>{c.name} · {fmtUsd(catalogPrice(c), 0)}{catalogFlag(c, 'hazmat') ? ' · hazmat' : ''}{catalogFlag(c, 'ageRestricted') ? ' · 21+' : ''}{catalogFlag(c, 'oversized') ? ' · oversized' : ''}</option>)}
              </Select>
              <Input aria-label={`Line ${i + 1} quantity`} type="number" min={1} max={20} value={l.qty} onChange={(e) => setLine(i, { qty: Math.max(1, Number(e.target.value) || 1) })} sm />
              <button type="button" className="rm" aria-label={`Remove line ${i + 1}`} onClick={() => set({ lines: form.lines.filter((_, j) => j !== i) })} disabled={form.lines.length <= 1}>×</button>
            </div>
          ))}
        </div>
      </div>
      <div className="form-grid">
        <Select label="Ship to" value={String(form.cityIdx)} onChange={(e) => set({ cityIdx: Number(e.target.value) })}>{CITIES.map((c, i) => <option key={c.name} value={i}>{c.name}, {c.state}</option>)}</Select>
        <Select label="Ship method" value={form.shipMethod} onChange={(e) => set({ shipMethod: e.target.value, orderType: e.target.value === 'pickup' ? 'bopis' : form.orderType === 'bopis' ? 'standard_ship' : form.orderType })}>{SHIP_METHODS.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}</Select>
        <Select label="Customer tier" value={form.tier} onChange={(e) => set({ tier: e.target.value })}>{TIERS.map((t) => <option key={t} value={t}>{t}</option>)}</Select>
        <Select label="Order type" value={form.orderType} onChange={(e) => set({ orderType: e.target.value, shipMethod: e.target.value === 'bopis' ? 'pickup' : form.shipMethod === 'pickup' ? 'ground' : form.shipMethod })}>{ORDER_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select>
        {isPickup && <Select className="span2" label="Pickup store" value={form.pickupNodeId} onChange={(e) => set({ pickupNodeId: e.target.value })}>{stores.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.id})</option>)}</Select>}
      </div>
      <div className="pg-flags">
        <Checkbox label="Flagged customer history" checked={form.flaggedCustomer} onChange={(e) => set({ flaggedCustomer: e.target.checked })} />
        <Checkbox label="Placed by an agent (UCP)" checked={form.agent} onChange={(e) => set({ agent: e.target.checked, channel: e.target.checked ? 'agent' : 'web' })} />
      </div>
      {form.agent && <div className="callout"><span className="small">Instruction (data only): “{AGENT_INSTRUCTION}” · mandate $400</span></div>}
      {hasHazmat(form, catalog) && ['overnight', '2day'].includes(form.shipMethod) && form.shipMethod === 'overnight' && <div className="callout warn"><span>Hazmat on an air service: expect the L0 compliance rule to block this before any model runs.</span></div>}
      <div className="pg-total"><span>{form.lines.reduce((s, l) => s + l.qty, 0)} unit(s) · {CITIES[form.cityIdx]?.name} · {form.shipMethod}</span><b>{fmtUsd(total)}</b></div>
      <Button variant="primary" size="lg" loading={busy} disabled={!form.lines.length || !catalog.length} onClick={onPlace} icon={<IconSparkle size={15} />}>{busy ? 'Deciding…' : 'Place order'}</Button>
      <div className="xs muted" style={{ textAlign: 'center' }}>Places a real order on this sandbox tenant so you can watch the decision trace — safe to try as many times as you like.</div>
    </section>
  );
}

/* ---- Trace ---- */
function TraceColumn({ run, trace, onWhy }: { run: Run; trace: ReturnType<typeof orderTrace>; onWhy: (id: string) => void }) {
  const [revealed, setRevealed] = useState(0);
  useEffect(() => { if (run.status === 'idle' || run.status === 'placing') setRevealed(0); }, [run.status, run.startedAt]);
  useEffect(() => { if (revealed < trace.length) { const t = setTimeout(() => setRevealed((n) => n + 1), revealed === 0 ? 80 : 340); return () => clearTimeout(t); } }, [revealed, trace.length]);
  const shown = trace.slice(0, revealed);
  const stats = traceStats(trace);
  const elapsed = run.finishedAt && run.startedAt ? run.finishedAt - run.startedAt : null;
  return (
    <section className="card card-pad pg-col pg-col-trace" aria-label="Decision trace">
      <div className="pg-trace-head"><h2>Decision trace</h2>{run.order && <span className="mono xs muted">{run.order.channelOrderId}</span>}{run.status === 'tracing' && <span className="live"><span className="pulse" aria-hidden />deciding</span>}{run.status === 'done' && <Badge tone="ok">settled{elapsed ? ` in ${(elapsed / 1000).toFixed(1)} s` : ''}</Badge>}</div>
      <div className="pg-trace">
        {run.status === 'idle' && <div className="empty"><div className="title">No order yet</div><div className="small">Pick a persona on the left and place the order — each decision the fabric makes (rules, Jev, LLM or a person) appears here the instant it happens, with its confidence and a "Why" behind it.</div></div>}
        {run.status === 'placing' && <div className="pg-skel"><span className="spinner" aria-hidden /> Capturing the order: schema, address, item and price sanity, payment status, compliance rules (all L0, no model)…</div>}
        {run.status === 'blocked' && <BlockedCard issues={run.issues ?? []} error={run.error} />}
        {run.status === 'error' && <div className="callout danger"><span>Could not place the order: {(run.error as Error)?.message ?? 'unknown error'}</span></div>}
        {shown.map((r, i) => <DecisionCard key={r.id} r={r} index={i} onWhy={onWhy} />)}
        {run.status === 'tracing' && revealed >= trace.length && <div className="pg-skel"><span className="spinner" aria-hidden /> {trace.length === 0 ? 'Workflow started · inferring intent…' : 'Waiting for the next decision…'}</div>}
        {run.status === 'done' && trace.length === 0 && <div className="callout warn"><span>No decision records yet. The workflow may be waiting on a hold; open the full order to see why.</span></div>}
      </div>
      {trace.length > 0 && (
        <div className="pg-totals" aria-label="Trace totals">
          <div><span className="k">Decisions</span><span className="v num">{stats.decisions}</span></div>
          <div><span className="k">Human decisions avoided</span><span className="v num">{stats.humanAvoided}{stats.escalated ? <span className="muted small"> · {stats.escalated} to a person</span> : null}</span></div>
          <div><span className="k">AI cost</span><span className="v num">{stats.costUsd > 0 ? fmtUsd(stats.costUsd, 4) : '$0'}</span></div>
          <div><span className="k">Decision latency</span><span className="v num">{stats.latencyMs} ms</span></div>
        </div>
      )}
    </section>
  );
}

function BlockedCard({ issues, error }: { issues: ValidationIssue[]; error?: ApiError | Error }) {
  const blocking = issues.filter((i) => (i.severity ?? 'block') === 'block');
  return (
    <article className="pg-card enter blocked" aria-label="Capture blocked">
      <header className="pg-card-head"><span className="pg-step">0</span><span className="pg-card-title">Blocked at capture</span><Badge tone="L0" mono>L0 Rules</Badge><Badge tone="danger">block</Badge></header>
      <div className="small">{blocking.length ? 'A deterministic compliance or validation rule refused the order before any model was called (422 validation_failed).' : (error as ApiError)?.problem?.detail ?? 'The order was refused.'}</div>
      {issues.map((i, k) => <div key={k} className="pg-issue"><Badge tone={i.severity === 'warn' ? 'warn' : 'danger'} mono>{i.code ?? 'issue'}</Badge><span>{i.message}{i.field ? <span className="muted xs"> · {i.field}</span> : null}</span></div>)}
      <dl className="pg-meta"><div><dt>Latency</dt><dd className="num">&lt; 5 ms</dd></div><div><dt>Cost</dt><dd className="num">$0</dd></div><div><dt>Provider</dt><dd className="mono">rules</dd></div></dl>
    </article>
  );
}

/* ---- Network ---- */
function NetworkColumn({ run, nodes, form, catalog }: { run: Run; nodes: Node[]; form: PlaygroundForm; catalog: CatalogItem[] }) {
  const view = run.view;
  const order = view?.order ?? run.order;
  const groups = ((view?.fulfilmentGroups ?? []) as LiveGroup[]).filter((g) => !['rejected', 'cancelled'].includes(String(g.status ?? '')));
  const city = CITIES[form.cityIdx] ?? CITIES[0];
  const isPickup = form.orderType === 'bopis' || form.shipMethod === 'pickup';
  const pickupNode = isPickup ? nodes.find((n) => n.id === form.pickupNodeId) : undefined;
  const chosen = groups.length ? groups.map((g) => g.nodeId) : isPickup && pickupNode ? [pickupNode.id] : [];
  // For pickup orders the destination is the store itself (the customer travels there), otherwise the ship-to city.
  const destination = isPickup && pickupNode ? { name: `${pickupNode.name} · pickup`, lat: pickupNode.lat, lon: pickupNode.lon } : { name: `${city.name}, ${city.state}`, lat: city.lat, lon: city.lon };
  const holds = (view?.holds ?? []).filter((h) => !h.resolvedAt);
  const margin = order?.margin ?? null;
  const shipping = groups.reduce((s, g) => s + (g.estimatedCost?.amount ?? 0), 0);
  const nodeName = (id: string) => nodes.find((n) => n.id === id)?.name ?? id;
  const skuName = (sku: string) => catalog.find((c) => c.sku === sku)?.name ?? sku;
  return (
    <section className="card card-pad pg-col pg-col-network" aria-label="Network">
      <div className="row-between"><h2>Network</h2><span className="xs muted">{nodes.length} nodes · GET /api/v1/nodes</span></div>
      <NetworkMap nodes={nodes} destination={destination} chosen={chosen} pickup={isPickup} />
      {holds.map((h) => <HoldCard key={h.id} hold={h} />)}
      {margin?.belowFloor && !margin.approvalPresent && <div className="pg-hold"><div className="t">Margin-floor hold</div><div className="small">Contribution margin {fmtPct(margin.marginRatio)} is below the tenant floor {fmtPct(margin.floor, 0)}. Nothing ships until a finance controller approves (task queue margin_floor_approvals).</div></div>}
      {margin?.belowFloor && margin.approvalPresent && <div className="pg-hold warn"><div className="t">Below floor · approved</div><div className="small">A recorded approval lets this order ship at {fmtPct(margin.marginRatio)} margin.</div></div>}
      <div className="pg-plan">
        <div className="section-title" style={{ margin: 0 }}>Sourcing plan</div>
        {!order && <div className="muted small">The plan appears once the workflow has sourced the order.</div>}
        {order && groups.length === 0 && run.status !== 'placing' && <div className="muted small">{run.status === 'tracing' ? 'Sourcing…' : holds.length ? 'Sourcing is blocked by a hold.' : 'No fulfilment group yet.'}</div>}
        {groups.map((g, i) => (
          <div key={g.id ?? i} className="pg-pkg">
            <div className="hd"><Badge tone="accent">package {i + 1}</Badge><b>{nodeName(g.nodeId)}</b><span className="mono xs muted">{g.nodeId}</span>{g.status && <Badge tone="outline">{humanize(g.status)}</Badge>}</div>
            <div className="muted">{(g.allocations ?? []).map((a) => `${skuName(a.sku)} × ${a.qty}`).join(', ') || (g.lineIds ?? []).join(', ')}</div>
            <div className="row wrap gap3">
              <span>{humanize(g.shipMethod ?? (order as Order & { shipMethod?: string } | undefined)?.shipMethod ?? '')}</span>
              {g.promiseDate && <span>promise <b>{fmtDate(g.promiseDate)}</b></span>}
              {g.onTimeProbability != null && <span className="row gap1"><span className="bar ok" style={{ width: 60 }}><span style={{ width: `${g.onTimeProbability * 100}%` }} /></span><span className="num">{fmtPct(g.onTimeProbability, 0)} on time</span></span>}
              {g.estimatedCost && <span className="num">ship {fmtMoney(g.estimatedCost)}</span>}
            </div>
          </div>
        ))}
        {order && (
          <dl className="kv tight">
            <dt>Status</dt><dd><Badge tone="active">{humanize(order.status)}</Badge>{order.intent?.policy && <span className="muted small"> · intent {humanize(order.intent.policy)}</span>}</dd>
            {groups.length > 0 && <><dt>Packages</dt><dd className="num">{groups.length}</dd></>}
            {shipping > 0 && <><dt>Shipping cost</dt><dd className="num">{fmtUsd(shipping)}</dd></>}
            {margin && <><dt>Landed margin</dt><dd className="pg-margin"><span className={`num ${margin.belowFloor ? 'strong' : ''}`} style={margin.belowFloor ? { color: 'var(--danger)' } : undefined}>{fmtPct(margin.marginRatio)}</span><span className={`bar ${margin.belowFloor ? 'danger' : 'ok'}`}><span style={{ width: `${Math.min(100, Math.max(0, margin.marginRatio) * 100)}%` }} /></span><span className="muted xs">floor {fmtPct(margin.floor, 0)}</span></dd></>}
            <dt>Order total</dt><dd className="num">{fmtMoney(order.totals?.grandTotal)}</dd>
          </dl>
        )}
      </div>
    </section>
  );
}

function HoldCard({ hold }: { hold: Hold }) {
  const severe = ['compliance', 'margin_floor', 'agent_verification', 'fraud_review'].includes(hold.type);
  return (
    <div className={['pg-hold', !severe && 'warn'].filter(Boolean).join(' ')} role="alert">
      <div className="t">{humanize(hold.type)} hold <Badge tone="outline" mono>{hold.reasonCode}</Badge></div>
      <div className="small">{hold.note || `Blocks ${hold.blocksTransitions.join(', ')}`}</div>
    </div>
  );
}

/* ---- Bottom actions ---- */
function Actions({ run, trace, onReset }: { run: Run; trace: ReturnType<typeof orderTrace>; onReset: () => void }) {
  const order = run.order;
  const primary = primaryRecord(trace);
  const [wantExplain, setWantExplain] = useState(false);
  useEffect(() => { setWantExplain(false); }, [run.startedAt]);
  const explain = useQuery<CopilotExplanation & { fallbackReason?: string | null }>({ queryKey: ['pg-explain', primary?.id], queryFn: () => copilotApi.explain(primary!.id, 'internal'), enabled: wantExplain && !!primary, retry: false });
  const link = useQuery({ queryKey: ['pg-tracking', order?.id], queryFn: () => ordersApi.trackingLink(order!.id), enabled: !!order && run.status === 'done', retry: false });
  if (run.status === 'idle') return null;
  const explainErr = explain.error as ApiError | undefined;
  const needsKey = explainErr instanceof ApiError && (explainErr.status === 503 || /unavailable|no model|key/i.test(explainErr.problem.detail ?? ''));
  return (
    <>
      <div className="pg-actions">
        <Button icon={<IconSparkle size={14} />} disabled={!primary} loading={explain.isFetching} onClick={() => (wantExplain ? explain.refetch() : setWantExplain(true))}>Explain this order</Button>
        {order && <Link className="btn" to={`/orders/${encodeURIComponent(order.id)}`}>Open full order <IconExternal size={13} /></Link>}
        {link.data && <a className="btn" href={trackingUrlFor(link.data.token)} target="_blank" rel="noreferrer">Customer view <IconExternal size={13} /></a>}
        {order && link.isLoading && <span className="muted small">minting customer link…</span>}
        <span className="spacer" />
        <Button variant="ghost" icon={<IconRefresh size={14} />} onClick={onReset}>Try another</Button>
      </div>
      {wantExplain && explain.isError && <div className="callout warn"><span>{needsKey ? 'Explain needs an LLM key: set OPENROUTER_API_KEY (or ANTHROPIC_API_KEY) and restart.' : `Explain failed: ${explainErr?.message ?? 'unknown error'}`}</span></div>}
      {wantExplain && explain.data && (
        <div className="pg-explain" role="region" aria-label="Plain-English explanation">
          <div className="meta"><Badge tone={explain.data.generated ? 'accent' : 'outline'}>{explain.data.generated ? 'generated' : 'template (no LLM key)'}</Badge>{explain.data.model && <span className="xs muted mono">{explain.data.model}</span>}<span className="xs muted">about {primary ? humanize(primary.decision) : ''}</span>{!explain.data.generated && <span className="xs muted">· add OPENROUTER_API_KEY for a model-written explanation</span>}</div>
          <p>{explain.data.text}</p>
        </div>
      )}
    </>
  );
}
