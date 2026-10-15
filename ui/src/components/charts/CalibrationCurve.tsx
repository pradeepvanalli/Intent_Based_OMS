import { useId } from 'react';
import type { CalibrationBin } from '../../api/types';

export function CalibrationCurve({ bins, size = 220, ariaLabel = 'Calibration curve' }: { bins: CalibrationBin[]; size?: number; ariaLabel?: string }) {
  const id = useId();
  const pad = 30; const inner = size - pad * 2;
  const x = (v: number) => pad + v * inner; const y = (v: number) => pad + (1 - v) * inner;
  const pts = bins.filter((b) => b.count > 0);
  const maxCount = Math.max(1, ...pts.map((b) => b.count));
  const d = pts.map((b, i) => `${i ? 'L' : 'M'}${x(b.meanConfidence).toFixed(1)} ${y(b.accuracy).toFixed(1)}`).join(' ');
  const ece = pts.length ? pts.reduce((a, b) => a + b.count * Math.abs(b.meanConfidence - b.accuracy), 0) / pts.reduce((a, b) => a + b.count, 0) : 0;
  return (
    <figure style={{ margin: 0, display: 'flex', gap: 'var(--s4)', alignItems: 'center', flexWrap: 'wrap' }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="svg-chart" style={{ width: size, flex: 'none' }} role="img" aria-labelledby={`${id}-t`}>
        <title id={`${id}-t`}>{ariaLabel}: expected calibration error {ece.toFixed(3)}</title>
        {[0, 0.25, 0.5, 0.75, 1].map((g) => <g key={g}><line x1={x(g)} x2={x(g)} y1={pad} y2={size - pad} stroke="var(--grid)" /><line x1={pad} x2={size - pad} y1={y(g)} y2={y(g)} stroke="var(--grid)" /><text x={x(g)} y={size - pad + 14} textAnchor="middle">{g}</text><text x={pad - 6} y={y(g) + 3} textAnchor="end">{g}</text></g>)}
        <line x1={x(0)} y1={y(0)} x2={x(1)} y2={y(1)} stroke="var(--text-faint)" strokeDasharray="4 4" />
        {pts.map((b) => <polygon key={b.bin} points={`${x(b.lower)},${y(0)} ${x(b.upper)},${y(0)} ${x(b.upper)},${y(0) - (b.count / maxCount) * 20} ${x(b.lower)},${y(0) - (b.count / maxCount) * 20}`} fill="var(--c1)" opacity={0.18} />)}
        <path d={d} fill="none" stroke="var(--c2)" strokeWidth={2} strokeLinejoin="round" />
        {pts.map((b) => <circle key={b.bin} cx={x(b.meanConfidence)} cy={y(b.accuracy)} r={3 + (b.count / maxCount) * 3} fill={Math.abs(b.meanConfidence - b.accuracy) > 0.08 ? 'var(--c5)' : 'var(--c2)'}><title>{`conf ${b.meanConfidence.toFixed(2)} → acc ${b.accuracy.toFixed(2)} (n=${b.count})`}</title></circle>)}
        <text x={size / 2} y={size - 4} textAnchor="middle">stated confidence</text>
        <text x={10} y={size / 2} textAnchor="middle" transform={`rotate(-90 10 ${size / 2})`}>observed accuracy</text>
      </svg>
      <figcaption className="small muted" style={{ maxWidth: 240 }}>
        <div><b className="num" style={{ color: 'var(--text)' }}>ECE {ece.toFixed(3)}</b></div>
        <div className="mt2">Dots on the diagonal are well calibrated. Red dots deviate more than 0.08. Bars show bin volume.</div>
      </figcaption>
    </figure>
  );
}
