import { useState, type ReactNode } from 'react';
import { fmtDuration } from '../lib/format';
import { useNow } from '../lib/hooks';
import { tokenizeYamlLine } from '../lib/yaml';
import { IconChevronDown, IconChevronRight } from './Icons';

export function KeyValue({ items, tight }: { items: [ReactNode, ReactNode][]; tight?: boolean }) {
  return <dl className={['kv', tight && 'tight'].filter(Boolean).join(' ')}>{items.map(([k, v], i) => <div key={i} style={{ display: 'contents' }}><dt>{k}</dt><dd>{v ?? '—'}</dd></div>)}</dl>;
}
export function Countdown({ due, label }: { due?: string | null; label?: string }) {
  const now = useNow(1000);
  if (!due) return <span className="muted">—</span>;
  const ms = new Date(due).getTime() - now;
  const cls = ms < 0 ? 'late' : ms < 30 * 60_000 ? 'soon' : '';
  return <span className={`countdown ${cls}`} title={new Date(due).toLocaleString()} aria-label={`${label ?? 'due'} ${ms < 0 ? 'overdue by' : 'in'} ${fmtDuration(ms)}`}>{ms < 0 ? `−${fmtDuration(ms).replace('-', '')}` : fmtDuration(ms)}</span>;
}
export function LiveIndicator({ updatedAt, intervalMs = 5000, label = 'Live' }: { updatedAt?: number; intervalMs?: number; label?: string }) {
  const now = useNow(1000);
  const age = updatedAt ? now - updatedAt : Infinity;
  const stale = age > intervalMs * 3;
  return <span className={['live', stale && 'stale'].filter(Boolean).join(' ')} role="status" aria-live="off"><span className="pulse" aria-hidden />{stale ? 'Stale' : label}{updatedAt && <span className="num" style={{ fontWeight: 400, textTransform: 'none' }}>· {Math.max(0, Math.round(age / 1000))}s</span>}</span>;
}
export function ProbBar({ label, value, chosen, escape, tone }: { label: string; value: number; chosen?: boolean; escape?: boolean; tone?: 'ok' | 'warn' | 'danger' }) {
  return <div className={['prob', chosen && 'chosen', escape && 'escape'].filter(Boolean).join(' ')}><span className="k" title={label}>{label}</span><div className={['bar', tone].filter(Boolean).join(' ')} role="meter" aria-valuemin={0} aria-valuemax={1} aria-valuenow={value} aria-label={`${label} probability`}><span style={{ width: `${Math.round(value * 100)}%` }} /></div><span className="v">{(value * 100).toFixed(0)}%</span></div>;
}
export function ScorePips({ value, max = 5 }: { value: number; max?: number }) {
  return <span className={`score-pips lvl${value}`} role="img" aria-label={`score ${value} of ${max - 1}`}>{Array.from({ length: max }, (_, i) => <span key={i} className={i <= value ? 'on' : ''} />)}</span>;
}
export function YamlView({ text, maxHeight, lineNumbers = true }: { text: string; maxHeight?: number; lineNumbers?: boolean }) {
  const lines = text.replace(/\n$/, '').split('\n');
  return <pre className="code" style={maxHeight ? ({ '--code-max': `${maxHeight}px` } as React.CSSProperties) : undefined}>{lines.map((l, i) => <div key={i}>{lineNumbers && <span className="ln">{i + 1}</span>}{tokenizeYamlLine(l).map((t, k) => (t.cls ? <span key={k} className={t.cls}>{t.text}</span> : t.text))}</div>)}</pre>;
}
export function JsonTree({ data, tokenised = [], depth = 0, path = '', defaultOpen = 2 }: { data: unknown; tokenised?: string[]; depth?: number; path?: string; defaultOpen?: number }) {
  if (data === null || data === undefined) return <span className="jnull">null</span>;
  if (typeof data === 'string') return <span className={tokenised.some((t) => path.endsWith(t) || path === t) || /^tok_|^\[redacted|^••/.test(data) ? 'jtok' : 'js'}>"{data}"</span>;
  if (typeof data === 'number') return <span className="jn">{data}</span>;
  if (typeof data === 'boolean') return <span className="jb">{String(data)}</span>;
  const entries = Array.isArray(data) ? data.map((v, i) => [String(i), v] as [string, unknown]) : Object.entries(data as Record<string, unknown>);
  return <JsonBranch entries={entries} isArray={Array.isArray(data)} tokenised={tokenised} depth={depth} path={path} defaultOpen={defaultOpen} />;
}
function JsonBranch({ entries, isArray, tokenised, depth, path, defaultOpen }: { entries: [string, unknown][]; isArray: boolean; tokenised: string[]; depth: number; path: string; defaultOpen: number }) {
  const [open, setOpen] = useState(depth < defaultOpen);
  if (!entries.length) return <span className="jnull">{isArray ? '[]' : '{}'}</span>;
  return (
    <span className={depth === 0 ? 'jtree' : undefined}>
      <button className="jtoggle" aria-expanded={open} aria-label={open ? 'Collapse' : 'Expand'} onClick={() => setOpen(!open)}>{open ? <IconChevronDown size={11} /> : <IconChevronRight size={11} />}</button>
      {!open && <span className="jnull">{isArray ? `[${entries.length}]` : `{${entries.length}}`}</span>}
      {open && <ul>{entries.map(([k, v]) => <li key={k}>{!isArray && <><span className="jk">{k}</span>: </>}<JsonTree data={v} tokenised={tokenised} depth={depth + 1} path={path ? `${path}.${k}` : k} defaultOpen={defaultOpen} /></li>)}</ul>}
    </span>
  );
}
export function LayerStepper({ steps, final }: { steps: { layer: string; ran: boolean; outcome?: string; latencyMs?: number }[]; final?: string }) {
  const meta: Record<string, { label: string; color: string; soft: string }> = { L0_RULE: { label: 'L0 Rules', color: 'var(--l0)', soft: 'var(--l0-soft)' }, L1_SYSTEM_ONE: { label: 'L1 Jev', color: 'var(--l1)', soft: 'var(--l1-soft)' }, L2_LLM: { label: 'L2 LLM', color: 'var(--l2)', soft: 'var(--l2-soft)' }, L3_HUMAN: { label: 'L3 Human', color: 'var(--l3)', soft: 'var(--l3-soft)' } };
  return (
    <ol className="stepper" aria-label="Layer path" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
      {steps.map((s, i) => { const m = meta[s.layer] ?? { label: s.layer, color: 'var(--neutral)', soft: 'var(--neutral-soft)' }; const style = { '--step-color': m.color, '--step-soft': m.soft } as React.CSSProperties; return (
        <li key={s.layer} style={{ display: 'contents' }}>
          {i > 0 && <span className={['step-line', s.ran && 'ran'].filter(Boolean).join(' ')} style={style} aria-hidden />}
          <div className={['step', s.ran && 'ran', final === s.layer && 'final'].filter(Boolean).join(' ')} style={style} title={s.outcome} aria-label={`${m.label}: ${s.ran ? 'ran' : 'skipped'}${s.outcome ? `. ${s.outcome}` : ''}`}>
            <span className="node">{s.layer.slice(0, 2)}</span><span className="lbl">{m.label}</span>{s.ran && s.latencyMs !== undefined && <span className="xs faint num">{s.latencyMs} ms</span>}
          </div>
        </li>); })}
    </ol>
  );
}
export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return <button className="btn btn-sm" onClick={async () => { try { await navigator.clipboard.writeText(text); setDone(true); setTimeout(() => setDone(false), 1500); } catch { /* clipboard unavailable */ } }}>{done ? 'Copied' : label}</button>;
}
