export function Sparkline({ data, width = 100, height = 28, stroke = 'var(--accent)', fill = true, ariaLabel }: { data: number[]; width?: number; height?: number; stroke?: string; fill?: boolean; ariaLabel?: string }) {
  if (!data.length) return null;
  const min = Math.min(...data); const max = Math.max(...data); const span = max - min || 1;
  const pad = 2;
  const pts = data.map((v, i) => [pad + (i / Math.max(1, data.length - 1)) * (width - pad * 2), height - pad - ((v - min) / span) * (height - pad * 2)] as const);
  const d = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');
  const area = `${d} L${pts[pts.length - 1][0].toFixed(1)} ${height} L${pts[0][0].toFixed(1)} ${height} Z`;
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={ariaLabel ?? `trend of ${data.length} points, latest ${data[data.length - 1]}`}>
      {fill && <path d={area} fill={stroke} opacity={0.12} />}
      <path d={d} fill="none" stroke={stroke} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={pts[pts.length - 1][0]} cy={pts[pts.length - 1][1]} r={2} fill={stroke} />
    </svg>
  );
}
