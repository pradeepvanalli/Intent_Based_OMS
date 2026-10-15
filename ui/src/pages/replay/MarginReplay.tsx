import { useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { DEFAULT_REPLAY_OPTIONS, replayApi, type ReplayJob, type ReplayOptions, type ReplayResults, type ReplayRow } from '../../api/replay';
import { nodesApi } from '../../api/inventory';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Checkbox, Input, Select, Switch } from '../../components/Form';
import { StatTile } from '../../components/StatTile';
import { BarChart } from '../../components/charts/BarChart';
import { Pagination, Table } from '../../components/Table';
import { EmptyState, ErrorState, Section } from '../../components/States';
import { IconChart } from '../../components/Icons';
import { fmtDateTime, fmtPct, fmtUsd } from '../../lib/format';
import { toast } from '../../lib/toast';
import { filterRows, fmtSigned, fmtUsd0, headline, intentLabel, paginate, regionBars, sortRows, toMarkdown, type RowSort, type RowSortKey } from './report';

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED'];
const MAX_BYTES = 10 * 1024 * 1024;

export default function MarginReplay() {
  const nodes = useQuery({ queryKey: ['nodes'], queryFn: nodesApi.list });
  const jobs = useQuery({ queryKey: ['replay-jobs'], queryFn: replayApi.list, refetchInterval: 10000 });
  const [opts, setOpts] = useState<ReplayOptions>(DEFAULT_REPLAY_OPTIONS);
  const [file, setFile] = useState<File | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const onQueued = (j: ReplayJob) => { setJobId(j.id); toast.ok('Margin Replay queued', j.label ?? j.id); jobs.refetch(); };
  const upload = useMutation({ mutationFn: () => replayApi.upload(file!, opts), onSuccess: onQueued });
  const sample = useMutation({ mutationFn: () => replayApi.sample(opts), onSuccess: onQueued });
  const job = useQuery({ queryKey: ['replay-job', jobId], queryFn: () => replayApi.get(jobId!), enabled: !!jobId, refetchInterval: (q) => (q.state.data && TERMINAL.includes(q.state.data.status) ? false : 1000) });
  useEffect(() => { if (!jobId && jobs.data?.[0]) setJobId(jobs.data[0].id); }, [jobs.data, jobId]);
  const j = job.data;
  const dcs = (nodes.data ?? []).filter((n) => n.active !== false && (n.type === 'dc' || n.type === '3pl'));
  const busy = upload.isPending || sample.isPending;
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">Testing &amp; forecasting</div>
          <h1><span className="icon-badge"><IconChart size={20} /></span> Margin Replay</h1>
          <div className="sub">Drop a CSV export of real orders and see what Intent OMS would have done differently — and how much margin it would have recovered — compared with a conventional, nearest-node OMS. Nothing is persisted as an order.</div>
        </div>
      </div>
      <div className="two-col rev" style={{ gridTemplateColumns: '360px minmax(0,1fr)' }}>
        <form className="col gap3" onSubmit={(e) => { e.preventDefault(); if (file) upload.mutate(); }}>
          <Section title="1 · Orders file" tight>
            <DropZone file={file} onFile={setFile} />
            <div className="hint mt2">Shopify <em>Orders export</em> (Name, Lineitem sku, Lineitem quantity, Lineitem price, Shipping City/Province/Zip/Country, Shipping Method, Notes, Shipping, Total) or a generic CSV (order_id, placed_at, sku, qty, unit_price, city, state, zip, country, ship_method, note, customer_tier). Rows sharing an order id are one order. Up to 10 MB / 5,000 orders.</div>
          </Section>
          <Section title="2 · Options" tight>
            <div className="col gap3">
              <Switch label={<span>Assume stock everywhere <span className="xs muted">(every node holds every SKU — compares network and policy, not today's inventory)</span></span>} checked={opts.assumeStock} onChange={(v) => setOpts({ ...opts, assumeStock: v })} />
              <Select label="Baseline (the conventional OMS)" value={opts.baseline} onChange={(e) => setOpts({ ...opts, baseline: e.target.value })} hint="Nearest node = ship-as-available from the closest capable node, no margin floor, no intent handling.">
                <option value="nearest_node">Nearest node that can ship</option>
                {dcs.map((n) => <option key={n.id} value={`single_dc:${n.id}`}>Everything from one DC: {n.id}</option>)}
              </Select>
              <Switch label={<span>Use fulfilment-intent decisions <span className="xs muted">(runs fulfilment_intent in simulation mode on orders with notes; keyword rules otherwise)</span></span>} checked={opts.useDecisions} onChange={(v) => setOpts({ ...opts, useDecisions: v })} />
              <div className="row gap3">
                <Input label="COGS for unknown SKUs (share of price)" type="number" min={0} max={1} step={0.05} value={opts.unknownSkuCogsRatio} onChange={(e) => setOpts({ ...opts, unknownSkuCogsRatio: Math.min(1, Math.max(0, Number(e.target.value))) })} />
                <Select label="Format" value={opts.format} onChange={(e) => setOpts({ ...opts, format: e.target.value as ReplayOptions['format'] })}><option value="auto">Auto-detect</option><option value="shopify">Shopify export</option><option value="generic">Generic</option></Select>
              </div>
            </div>
          </Section>
          <div className="row gap2 wrap">
            <Button type="submit" variant="primary" loading={upload.isPending} disabled={!file || busy}>Run replay</Button>
            <Button type="button" loading={sample.isPending} disabled={busy} onClick={() => sample.mutate()}>Run the bundled Shopify sample</Button>
          </div>
          <span className="hint">The sample is 300 orders over 30 days against the demo network (docs/examples/data/shopify-orders-sample.csv).</span>
          {upload.isError && <ErrorState error={upload.error} />}
          {sample.isError && <ErrorState error={sample.error} />}
          {(jobs.data ?? []).length > 0 && <>
            <div className="section-title mt3">Recent replays</div>
            <div className="col gap1">{(jobs.data ?? []).slice(0, 8).map((x) => <button key={x.id} className="sel-row" aria-selected={x.id === jobId} onClick={() => setJobId(x.id)} type="button"><span className="small truncate" title={x.label ?? x.id}>{x.label ?? x.id}</span><StatusBadge status={x.status} /><span className="grow" /><span className="xs muted">{fmtDateTime(x.submittedAt)}</span></button>)}</div>
          </>}
        </form>
        <div className="col gap4">
          {!j && !job.isLoading && <EmptyState title="No replay yet" hint="Drop a CSV and run it, or run the bundled Shopify sample to see the report shape." />}
          {j && <>
            <div className="row gap3 wrap">
              <StatusBadge status={j.status} lg /><span className="small strong">{j.label ?? j.id}</span><span className="xs muted mono">{j.id}</span>
              {j.results?.options && <>
                <Badge tone="outline">{j.results.options.assumeStock ? 'stock assumed' : 'live ATP'}</Badge>
                <Badge tone="outline">baseline {j.results.options.baseline.replace('single_dc:', 'single DC ')}</Badge>
                <Badge tone="outline">{j.results.options.useDecisions ? 'decisions on' : 'keyword intents'}</Badge>
              </>}
            </div>
            {!TERMINAL.includes(j.status) && <div><div className="bar" style={{ height: 8 }} role="progressbar" aria-valuenow={Math.round(j.progress * 100)} aria-valuemin={0} aria-valuemax={100}><span style={{ width: `${j.progress * 100}%` }} /></div><div className="xs muted mt2">Replaying orders through baseline and Intent OMS sourcing… {Math.round(j.progress * 100)}%{j.results?.summary ? ` · ${j.results.summary.planned} replayed so far` : ''}</div></div>}
            {j.status === 'FAILED' && <div className="error-box" role="alert"><span className="title">Replay failed</span><span className="small">{j.error}</span></div>}
            {j.results && <Report results={j.results} job={j} />}
          </>}
        </div>
      </div>
    </div>
  );
}

function DropZone({ file, onFile }: { file: File | null; onFile: (f: File | null) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const accept = (f: File | undefined) => {
    if (!f) return;
    if (!/\.csv$/i.test(f.name) && !f.type.includes('csv') && !f.type.includes('text')) { toast.warn('Not a CSV', `${f.name} does not look like a CSV export.`); return; }
    if (f.size > MAX_BYTES) { toast.warn('File too large', 'The limit is 10 MB (about 5,000 orders).'); return; }
    onFile(f);
  };
  const onDrop = (e: DragEvent<HTMLDivElement>) => { e.preventDefault(); setOver(false); accept(e.dataTransfer.files?.[0]); };
  return (
    <div role="button" tabIndex={0} aria-label="Upload orders CSV" onClick={() => input.current?.click()} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.current?.click(); } }}
      onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)} onDrop={onDrop}
      style={{ border: `2px dashed ${over ? 'var(--accent)' : 'var(--border-strong)'}`, borderRadius: 'var(--r3)', padding: 'var(--s5) var(--s4)', textAlign: 'center', background: over ? 'var(--accent-soft, var(--surface-2))' : 'var(--surface-2)', cursor: 'pointer' }}>
      <input ref={input} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={(e) => { accept(e.target.files?.[0]); e.target.value = ''; }} />
      {file ? <div className="col gap1"><span className="strong">{file.name}</span><span className="xs muted">{(file.size / 1024).toFixed(1)} KB · click to replace</span><span><Button size="sm" variant="ghost" type="button" onClick={(e) => { e.stopPropagation(); onFile(null); }}>Remove</Button></span></div>
        : <div className="col gap1"><span className="strong">Drop your orders CSV here</span><span className="xs muted">or click to choose a file · Shopify “Orders export” or generic layout</span></div>}
    </div>
  );
}

function Report({ results: r, job }: { results: ReplayResults; job: ReplayJob }) {
  const s = r.summary;
  const tiles = headline(s);
  const md = toMarkdown(r);
  const copy = async (what: string) => { try { await navigator.clipboard.writeText(md); toast.ok(what, 'Markdown report copied to the clipboard.'); } catch { toast.warn('Clipboard unavailable', 'Select the report text and copy it manually.'); } };
  const bars = regionBars(r.byRegion ?? [], 10);
  return (
    <div className="col gap4">
      <div className="grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 'var(--s3)' }}>
        {tiles.map((t) => <StatTile key={t.key} label={t.label} value={t.value} tone={t.tone} hint={t.hint} />)}
      </div>
      <div className="xs muted">{s.planned.toLocaleString()} of {s.orders.toLocaleString()} orders replayed{s.skipped ? ` · ${s.skipped} skipped (${Object.entries(s.skippedReasons ?? {}).map(([k, v]) => `${v} ${k}`).join(', ')})` : ''}{s.dateFrom ? ` · ${String(s.dateFrom).slice(0, 10)} → ${String(s.dateTo).slice(0, 10)} (${s.annualisationDays} days)` : ''}{r.partial ? ' · partial' : job.completedAt ? ` · completed ${fmtDateTime(job.completedAt)}` : ''}{r.file?.warnings?.length ? ` · ${r.file.warnings.join('; ')}` : ''}</div>
      <Section title="Top findings" actions={<><Button size="sm" onClick={() => copy('Report copied')}>Copy report as Markdown</Button><Button size="sm" variant="primary" onClick={() => copy('Ready to share')}>Share</Button></>}>
        {r.topFindings.length === 0 ? <EmptyState title="No findings" hint="Findings call out the orders and patterns that moved the most margin or on-time percentage in this batch." /> : <ol className="col gap2" style={{ margin: 0, paddingLeft: '1.2em' }}>{r.topFindings.map((f, i) => <li key={i} className="small">{f}</li>)}</ol>}
      </Section>
      <div className="two-col" style={{ gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)' }}>
        <Section title="Margin recovered by state" tight>
          {bars.length === 0 ? <EmptyState title="No regional data" hint="Shown once replayed orders carry a destination state." /> : <BarChart data={bars} height={200} format={(v) => fmtUsd0(v)} ariaLabel="Contribution margin recovered by destination state" />}
          <div className="xs muted mt2">Top {bars.length} states by margin recovered · on-time {fmtPct(s.avgOnTimeBefore)} → {fmtPct(s.avgOnTimeAfter)} · {s.storeToDc} store-sourced orders re-routed to DCs</div>
        </Section>
        <div className="col gap3">
          <div className="callout info" role="note">
            <details style={{ width: '100%' }}>
              <summary className="strong" style={{ cursor: 'pointer' }}>Assumptions ({r.assumptions.length}) — read before quoting the numbers</summary>
              <ul className="col gap1 mt2 small" style={{ margin: 0, paddingLeft: '1.2em' }}>{r.assumptions.map((a, i) => <li key={i}>{a}</li>)}</ul>
            </details>
          </div>
          <details className="callout">
            <summary className="strong" style={{ cursor: 'pointer' }}>How this works</summary>
            <div className="col gap2 mt2 small">
              <p style={{ margin: 0 }}><b>Baseline</b> is the conventional OMS: every order ships as available from the nearest node that is capable and stocked (or everything from the single DC you picked), with no margin floor, no split penalty in the choice and no reading of the customer's notes.</p>
              <p style={{ margin: 0 }}><b>Intent OMS</b> runs the same orders through the tenant's live sourcing policy: the real rate card and node cost profiles, the split penalty and margin floor, plan weights, and the fulfilment intent read from notes (ship together, urgent item, gift, pickup) via the <code>fulfilment_intent</code> decision in simulation mode.</p>
              <p style={{ margin: 0 }}>Both arms are costed with the same economics, so the delta is purely the decision. Each order's own timestamp is “now” for cutoffs and ETAs. The annualised figure scales the file's date range to 365 days and is an estimate.</p>
              <p style={{ margin: 0 }}><b>Nothing is persisted as an order.</b> The upload lives only in this job record; decision records, when enabled, are flagged SIMULATION and never create tasks.</p>
            </div>
          </details>
        </div>
      </div>
      <OrdersTable rows={r.perOrder ?? []} truncated={!!r.perOrderTruncated} />
    </div>
  );
}

const SORT_KEYS: { key: RowSortKey; label: string }[] = [{ key: 'saved', label: 'Margin recovered' }, { key: 'costBefore', label: 'Baseline cost' }, { key: 'costAfter', label: 'Intent cost' }, { key: 'packages', label: 'Package change' }, { key: 'onTime', label: 'On-time change' }, { key: 'state', label: 'State / city' }, { key: 'intent', label: 'Intent' }, { key: 'placedAt', label: 'Placed at' }, { key: 'orderId', label: 'Order id' }];

function OrdersTable({ rows, truncated }: { rows: ReplayRow[]; truncated: boolean }) {
  const [changedOnly, setChangedOnly] = useState(false); const [intentOnly, setIntentOnly] = useState(false); const [floorOnly, setFloorOnly] = useState(false); const [includeSkipped, setIncludeSkipped] = useState(false);
  const [query, setQuery] = useState(''); const [sort, setSort] = useState<RowSort>({ key: 'saved', dir: 'desc' }); const [offset, setOffset] = useState(0);
  const limit = 25;
  const visible = useMemo(() => sortRows(filterRows(rows, { changedOnly, intentOnly, belowFloorOnly: floorOnly, includeSkipped, query }), sort), [rows, changedOnly, intentOnly, floorOnly, includeSkipped, query, sort]);
  useEffect(() => { setOffset(0); }, [changedOnly, intentOnly, floorOnly, includeSkipped, query, sort]);
  const pageRows = paginate(visible, offset, limit);
  const nodes = (ns?: string[]) => (ns && ns.length ? ns.join(' + ') : '—');
  return (
    <Section title={`Per order (${visible.length.toLocaleString()} of ${rows.length.toLocaleString()})`} actions={<span className="xs muted">{truncated ? 'showing the first rows while the job runs' : 'sortable · filter to what changed'}</span>}>
      <div className="row gap3 wrap mb3" style={{ alignItems: 'flex-end' }}>
        <Checkbox label="Changed only" checked={changedOnly} onChange={(e) => setChangedOnly(e.target.checked)} />
        <Checkbox label="With intent" checked={intentOnly} onChange={(e) => setIntentOnly(e.target.checked)} />
        <Checkbox label="Below margin floor" checked={floorOnly} onChange={(e) => setFloorOnly(e.target.checked)} />
        <Checkbox label="Include skipped" checked={includeSkipped} onChange={(e) => setIncludeSkipped(e.target.checked)} />
        <Input aria-label="Filter orders" placeholder="Filter by order, city, state, node…" value={query} onChange={(e) => setQuery(e.target.value)} sm />
        <Select aria-label="Sort by" sm value={sort.key} onChange={(e) => setSort({ ...sort, key: e.target.value as RowSortKey })}>{SORT_KEYS.map((k) => <option key={k.key} value={k.key}>{k.label}</option>)}</Select>
        <Button size="sm" type="button" onClick={() => setSort({ ...sort, dir: sort.dir === 'asc' ? 'desc' : 'asc' })} aria-label={`Sort ${sort.dir === 'asc' ? 'ascending' : 'descending'}`}>{sort.dir === 'asc' ? '↑ asc' : '↓ desc'}</Button>
      </div>
      <Table rows={pageRows} rowKey={(r) => r.orderId} dense caption="Per-order replay" emptyTitle="No orders match the filters" columns={[
        { key: 'order', header: 'Order', render: (r) => <span className="col gap1"><span className="mono small strong">{r.orderId}</span><span className="xs muted">{r.skipped ? r.error : `${r.city ?? ''}${r.city ? ', ' : ''}${r.state ?? ''}${r.placedAt ? ` · ${String(r.placedAt).slice(0, 10)}` : ''}`}</span></span> },
        { key: 'intent', header: 'Intent', render: (r) => r.skipped ? <span className="muted">—</span> : <span className="row gap1 wrap">{r.intent && r.intent !== 'none' ? <Badge tone="warn">{intentLabel(r.intent)}</Badge> : <span className="muted xs">none</span>}{r.isGift && r.intent !== 'gift' && <Badge tone="outline">gift</Badge>}</span> },
        { key: 'nodes', header: 'Nodes before → after', render: (r) => r.skipped ? '' : <span className={['mono', 'small', r.changed && 'strong'].filter(Boolean).join(' ')}>{nodes(r.nodesBefore)} → {nodes(r.nodesAfter)}</span> },
        { key: 'pk', header: 'Pkgs', align: 'right', render: (r) => r.skipped ? '' : <span className="num small">{r.packagesBefore} → {r.packagesAfter}</span> },
        { key: 'cost', header: 'Cost before → after', align: 'right', render: (r) => r.skipped ? '' : <span className="num small">{fmtUsd(r.costBefore)} → {fmtUsd(r.costAfter)}</span> },
        { key: 'margin', header: 'Margin Δ', align: 'right', render: (r) => { if (r.skipped) return ''; const d = r.marginDeltaUsd ?? 0; return <span className="num small strong" style={{ color: d > 0 ? 'var(--ok-fg)' : d < 0 ? 'var(--danger-fg)' : undefined }}>{fmtSigned(d, (v) => fmtUsd(v))}</span>; } },
        { key: 'ontime', header: 'On-time', align: 'right', render: (r) => r.skipped ? '' : <span className="num small">{fmtPct(r.onTimeBefore, 0)} → {fmtPct(r.onTimeAfter, 0)}</span> },
        { key: 'flags', header: 'Flags', render: (r) => <span className="row gap1 wrap">{r.belowFloor && <Badge tone="warn">below floor</Badge>}{r.poBox && <Badge tone="outline">PO box</Badge>}{!!r.unknownSkus && <Badge tone="outline">{r.unknownSkus} unknown SKU</Badge>}{r.hazmat && <Badge tone="outline">hazmat</Badge>}{r.skipped && <Badge tone="outline">skipped</Badge>}</span> },
        { key: 'why', header: 'Why', render: (r) => <span className="xs muted" title={r.reason} style={{ display: 'inline-block', maxWidth: 260 }}>{r.skipped ? '' : (r.reason ?? '').split(';')[0]}</span> },
      ]} />
      <Pagination offset={offset} limit={limit} total={visible.length} onChange={setOffset} />
    </Section>
  );
}
