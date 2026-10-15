import { useId } from 'react';

export function LineChart({ series, height = 180, yFormat = (v) => String(v), xLabels, ariaLabel, yMin, yMax, target }: { series: { name: string; data: number[]; color?: string }[]; height?: number; yFormat?: (v: number) => string; xLabels?: string[]; ariaLabel: string; yMin?: number; yMax?: number; target?: number }) {
  const id = useId();
  const w = 600; const padL = 44; const padR = 12; const padT = 10; const padB = 22;
  const all = series.flatMap((s) => s.data).concat(target !== undefined ? [target] : []);
  const lo = yMin ?? Math.min(...all); const hi = yMax ?? Math.max(...all); const span = hi - lo || 1;
  const n = Math.max(...series.map((s) => s.data.length));
  const x = (i: number) => padL + (i / Math.max(1, n - 1)) * (w - padL - padR);
  const y = (v: number) => padT + (1 - (v - lo) / span) * (height - padT - padB);
  const ticks = [0, 0.5, 1].map((t) => lo + t * span);
  return (
    <svg className="svg-chart" viewBox={`0 0 ${w} ${height}`} role="img" aria-labelledby={`${id}-t`}>
      <title id={`${id}-t`}>{ariaLabel}</title>
      {ticks.map((t) => <g key={t}><line x1={padL} x2={w - padR} y1={y(t)} y2={y(t)} stroke="var(--grid)" strokeWidth={1} /><text x={padL - 6} y={y(t) + 3} textAnchor="end">{yFormat(t)}</text></g>)}
      {target !== undefined && <line x1={padL} x2={w - padR} y1={y(target)} y2={y(target)} stroke="var(--text-muted)" strokeDasharray="4 4" strokeWidth={1} />}
      {series.map((s, si) => { const d = s.data.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' '); return <path key={s.name} d={d} fill="none" stroke={s.color ?? `var(--c${(si % 6) + 1})`} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />; })}
      {xLabels && xLabels.map((l, i) => (i % Math.ceil(xLabels.length / 6) === 0 || i === xLabels.length - 1) && <text key={i} x={x(i)} y={height - 6} textAnchor="middle">{l}</text>)}
    </svg>
  );
}
