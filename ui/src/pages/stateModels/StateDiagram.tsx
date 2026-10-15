import { useMemo, useState } from 'react';
import type { StateModel } from '../../api/types';
import { layoutGraph } from '../../lib/layout';
import { fmtIsoDuration } from '../../lib/format';

export function StateDiagram({ model, highlight }: { model: Pick<StateModel, 'states' | 'transitions'>; highlight?: string[] }) {
  const [hover, setHover] = useState<string | null>(null);
  const layout = useMemo(() => layoutGraph(
    model.states.map((s) => ({ id: s.id, terminal: s.meta === 'TERMINAL', initial: s.meta === 'CREATED' })),
    model.transitions.flatMap((t) => t.from.map((f) => ({ id: `${t.id}@${f}`, from: f, to: t.to, label: t.trigger }))),
  ), [model]);
  const byId = useMemo(() => Object.fromEntries(model.states.map((s) => [s.id, s])), [model.states]);
  if (!model.states.length) return <div className="empty"><div className="title">No states to draw</div></div>;
  const active = (id: string) => !hover || hover === id || layout.edges.some((e) => (e.from === hover && e.to === id) || (e.to === hover && e.from === id));
  return (
    <div className="state-diagram" role="img" aria-label={`State diagram with ${model.states.length} states and ${model.transitions.length} transitions`}>
      <svg viewBox={`0 0 ${layout.width} ${layout.height}`} preserveAspectRatio="xMinYMin meet" style={{ display: 'block', width: '100%', maxWidth: layout.width * 1.5, height: 'auto', minWidth: Math.round(layout.width * 0.8) }}>
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--text-muted)" /></marker>
          <marker id="arrow-hi" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--accent)" /></marker>
        </defs>
        {layout.edges.map((e) => { const hi = hover && (e.from === hover || e.to === hover); const dim = hover && !hi; return (
          <g key={e.id} opacity={dim ? 0.18 : 1}>
            <path d={e.d} fill="none" stroke={hi ? 'var(--accent)' : e.back ? 'var(--warn)' : 'var(--text-muted)'} strokeWidth={hi ? 2 : 1.3} strokeDasharray={e.back ? '5 4' : undefined} markerEnd={hi ? 'url(#arrow-hi)' : 'url(#arrow)'} />
            <g transform={`translate(${e.lx} ${e.ly})`}><rect x={-(e.label.length * 5.6) / 2 - 4} y={-9} width={e.label.length * 5.6 + 8} height={14} rx={3} fill="var(--surface)" stroke="var(--border)" /><text textAnchor="middle" y={2} style={{ fontSize: 9.5, fill: hi ? 'var(--accent)' : 'var(--text-muted)' }}>{e.label}</text></g>
          </g>); })}
        {layout.nodes.map((n) => { const s = byId[n.id]; const isHi = highlight?.includes(n.id); return (
          <g key={n.id} transform={`translate(${n.x} ${n.y})`} opacity={active(n.id) ? 1 : 0.3} onMouseEnter={() => setHover(n.id)} onMouseLeave={() => setHover(null)} onFocus={() => setHover(n.id)} onBlur={() => setHover(null)} tabIndex={0} role="button" aria-label={`${n.id}${n.terminal ? ' (terminal)' : ''}${n.initial ? ' (initial)' : ''}${s?.sla ? `, SLA ${fmtIsoDuration(s.sla.maxAge)}` : ''}`} style={{ cursor: 'default' }}>
            <rect width={n.w} height={n.h} rx={n.terminal ? n.h / 2 : 6} fill={n.terminal ? 'var(--st-terminal-soft)' : n.initial ? 'var(--st-created-soft)' : isHi ? 'var(--accent-soft)' : 'var(--surface)'} stroke={isHi ? 'var(--accent)' : n.terminal ? 'var(--st-terminal)' : n.initial ? 'var(--st-created)' : 'var(--border-strong)'} strokeWidth={n.terminal ? 2 : 1.4} />
            {n.terminal && <rect x={3} y={3} width={n.w - 6} height={n.h - 6} rx={(n.h - 6) / 2} fill="none" stroke="var(--st-terminal)" strokeWidth={0.8} />}
            <text x={n.w / 2} y={n.h / 2 + 4} textAnchor="middle" style={{ fontSize: 11.5, fill: 'var(--text)', fontWeight: 600 }}>{n.id}</text>
            {s?.sla && <g transform={`translate(${n.w - 8} -6)`}><circle r={7} fill="var(--warn)" /><text textAnchor="middle" y={3} style={{ fontSize: 8, fill: '#fff', fontWeight: 700 }}>⏱</text><title>{`SLA ${fmtIsoDuration(s.sla.maxAge)} → ${s.sla.onBreach}`}</title></g>}
          </g>); })}
      </svg>
      <div className="legend" style={{ padding: 'var(--s2) var(--s3)', borderTop: '1px solid var(--border)' }}><span><i style={{ background: 'var(--st-created-soft)', border: '1px solid var(--st-created)' }} />initial</span><span><i style={{ background: 'var(--st-terminal-soft)', border: '1px solid var(--st-terminal)', borderRadius: 5 }} />terminal</span><span><i style={{ background: 'var(--warn)', borderRadius: 5 }} />SLA timer</span><span><i style={{ borderTop: '2px dashed var(--warn)', height: 0, background: 'none' }} />loop back</span><span className="faint">hover a state to focus its transitions</span></div>
    </div>
  );
}
