import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { copilotApi } from '../../api/copilot';
import type { OpsAnswer } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { KeyValue } from '../../components/Misc';
import { ErrorState, Section } from '../../components/States';
import { Pagination, Table } from '../../components/Table';
import { IconSearch, IconSparkle } from '../../components/Icons';
import { fmtDateTime, humanize, safeJson } from '../../lib/format';

const PAGE = 10;
const ORDER_COL = /^(order[_ ]?id|orderid|order)$/i;
const ORDER_VAL = /^ORD-[\w-]+$/;

/** "Ask the OMS": natural-language question → templated read-only query (FR-ANA / copilot ops). */
export function OpsCopilot() {
  const templates = useQuery({ queryKey: ['copilot-ops-templates'], queryFn: copilotApi.opsTemplates, staleTime: 300000 });
  const [question, setQuestion] = useState(''); const [offset, setOffset] = useState(0);
  const ask = useMutation({ mutationFn: (q: string) => copilotApi.opsAsk(q), onSuccess: () => setOffset(0) });
  const a = ask.data;
  const suggestions = useMemo(() => (templates.data?.templates ?? []).slice(0, 8), [templates.data]);
  return (
    <Section title={<span className="row gap3"><IconSparkle size={14} style={{ color: 'var(--accent)' }} /> Ask the OMS <span className="muted small" style={{ fontWeight: 400 }}>read-only, templated queries</span></span>} actions={a && <span className="xs muted">generated {fmtDateTime(a.generatedAt)}</span>}>
      <form className="col gap3" onSubmit={(e) => { e.preventDefault(); if (question.trim()) ask.mutate(question.trim()); }}>
        <div className="search" style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'center' }}>
          <div className="grow" style={{ position: 'relative' }}><IconSearch size={15} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-faint)' }} /><input className="input" style={{ paddingLeft: 32 }} aria-label="Ask the OMS" placeholder="e.g. Which orders are at risk of missing their promise date?" value={question} onChange={(e) => setQuestion(e.target.value)} /></div>
          <Button type="submit" variant="primary" loading={ask.isPending} disabled={!question.trim()}>Ask</Button>
        </div>
        {suggestions.length > 0 && <div className="example-chips" aria-label="Suggested questions">{suggestions.map((t) => <button key={t.name} type="button" className="chip chip-btn" title={`${t.name}(${t.parameters.map((p) => `${p.name}${p.required ? '' : '?'}: ${p.type}`).join(', ')})`} onClick={() => { setQuestion(t.description); ask.mutate(t.description); }}>{t.description}</button>)}</div>}
      </form>
      {ask.isError && <div className="mt3"><ErrorState error={ask.error} /></div>}
      {a && <div className="col gap3 mt4">
        <p className="ops-answer"><Badge tone="outline" style={{ marginRight: 8 }}>generated</Badge>{a.answer}</p>
        <details className="ops-query"><summary>Query used: <span className="mono">{a.queryName}</span>{Object.keys(a.parameters).length ? ` · ${Object.entries(a.parameters).map(([k, v]) => `${k}=${v === null ? 'null' : String(v)}`).join(', ')}` : ''}</summary>
          <div className="col gap3 mt2">
            {Object.keys(a.parameters).length > 0 && <KeyValue tight items={Object.entries(a.parameters).map(([k, v]) => [<span className="mono">{k}</span>, <span className="mono">{v === null || v === undefined ? '—' : typeof v === 'object' ? safeJson(v, 0) : String(v)}</span>])} />}
            <pre className="code" aria-label="SQL">{a.sql}</pre>
          </div>
        </details>
        <ResultTable a={a} offset={offset} onPage={setOffset} />
      </div>}
    </Section>
  );
}

function ResultTable({ a, offset, onPage }: { a: OpsAnswer; offset: number; onPage: (o: number) => void }) {
  const rows = a.rows.slice(offset, offset + PAGE);
  const columns = a.columns.length ? a.columns : Object.keys(a.rows[0] ?? {});
  const cell = (col: string, v: unknown) => {
    if (v === null || v === undefined) return <span className="muted">—</span>;
    if (typeof v === 'string' && (ORDER_COL.test(col) || ORDER_VAL.test(v))) return <Link to={`/orders/${encodeURIComponent(v)}`} className="mono">{v}</Link>;
    if (typeof v === 'string' && /^dr_/.test(v)) return <span className="mono small">{v}</span>;
    if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v)) return <span title={v}>{fmtDateTime(v)}</span>;
    if (typeof v === 'number') return <span className="num">{Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2)}</span>;
    if (typeof v === 'boolean') return <Badge tone={v ? 'ok' : 'neutral'}>{String(v)}</Badge>;
    if (typeof v === 'object') return <code className="xs">{safeJson(v, 0)}</code>;
    return String(v);
  };
  return (
    <div className="col gap2">
      <div className="row gap3 small muted"><span>{a.rowCount.toLocaleString()} row{a.rowCount === 1 ? '' : 's'}</span>{a.truncated && <Badge tone="warn">truncated</Badge>}</div>
      <Table rows={rows} rowKey={(r) => safeJson(r, 0)} dense caption={`Results for ${a.queryName}`} emptyTitle="No rows" columns={columns.map((c) => ({ key: c, header: humanize(c.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()), align: a.rows.some((r) => typeof r[c] === 'number') ? 'right' as const : 'left' as const, render: (r: Record<string, unknown>) => cell(c, r[c]) }))} />
      <Pagination offset={offset} limit={PAGE} total={a.rows.length} onChange={onPage} />
    </div>
  );
}
