import { useId } from 'react';

export interface BarDatum { label: string; value: number; color?: string; }
export function BarChart({ data, height = 160, format = (v) => String(v), ariaLabel, max }: { data: BarDatum[]; height?: number; format?: (v: number) => string; ariaLabel: string; max?: number }) {
  const id = useId();
  const w = 100; const padL = 0; const padB = 18; const padT = 14;
  const m = max ?? Math.max(1, ...data.map((d) => d.value));
  const bw = (w - padL) / Math.max(1, data.length);
  return (
    <figure style={{ margin: 0 }}>
      <svg className="svg-chart" viewBox={`0 0 ${w} ${height}`} preserveAspectRatio="none" role="img" aria-labelledby={`${id}-t`} style={{ height }}>
        <title id={`${id}-t`}>{ariaLabel}</title>
        {[0.25, 0.5, 0.75, 1].map((g) => <line key={g} x1={0} x2={w} y1={padT + (height - padT - padB) * (1 - g)} y2={padT + (height - padT - padB) * (1 - g)} stroke="var(--grid)" strokeWidth={0.3} vectorEffect="non-scaling-stroke" />)}
        {data.map((d, i) => { const h = (d.value / m) * (height - padT - padB); const x = padL + i * bw + bw * 0.18; return (
          <g key={d.label}>
            <rect x={x} y={height - padB - h} width={bw * 0.64} height={Math.max(0.5, h)} rx={0.6} fill={d.color ?? 'var(--c1)'}><title>{`${d.label}: ${format(d.value)}`}</title></rect>
          </g>); })}
      </svg>
      <div style={{ display: 'grid', gridTemplateColumns: `repeat(${data.length}, 1fr)`, fontSize: 'var(--fs-xs)', color: 'var(--text-muted)', textAlign: 'center', marginTop: -14 }} aria-hidden>
        {data.map((d) => <div key={d.label} className="truncate" title={d.label}><div className="num" style={{ color: 'var(--text)', fontWeight: 600 }}>{format(d.value)}</div><div className="truncate">{d.label}</div></div>)}
      </div>
    </figure>
  );
}

export function StackedBar({ segments, height = 14, ariaLabel, showLegend = true, format = (v: number) => `${Math.round(v * 100)}%` }: { segments: { label: string; value: number; color: string }[]; height?: number; ariaLabel: string; showLegend?: boolean; format?: (v: number) => string }) {
  const total = segments.reduce((a, s) => a + s.value, 0) || 1;
  return (
    <div>
      <div role="img" aria-label={`${ariaLabel}: ${segments.map((s) => `${s.label} ${format(s.value / total)}`).join(', ')}`} style={{ display: 'flex', height, borderRadius: 4, overflow: 'hidden', background: 'var(--surface-3)' }}>
        {segments.map((s) => <div key={s.label} style={{ width: `${(s.value / total) * 100}%`, background: s.color, minWidth: s.value > 0 ? 2 : 0 }} title={`${s.label}: ${format(s.value / total)}`} />)}
      </div>
      {showLegend && <div className="legend mt2">{segments.map((s) => <span key={s.label}><i style={{ background: s.color }} />{s.label} <b className="num">{format(s.value / total)}</b></span>)}</div>}
    </div>
  );
}
