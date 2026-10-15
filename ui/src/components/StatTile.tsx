import type { ReactNode } from 'react';
import { Sparkline } from './charts/Sparkline';

export function StatTile({ label, value, delta, deltaLabel, tone, onClick, trend, target, hint, loading }: { label: string; value: ReactNode; delta?: number | null; deltaLabel?: string; tone?: 'ok' | 'warn' | 'danger' | 'ai'; onClick?: () => void; trend?: number[]; target?: ReactNode; hint?: string; loading?: boolean }) {
  const cls = ['stat', tone && `tone-${tone}`, onClick && 'stat-link'].filter(Boolean).join(' ');
  const inner = (<>
    <span className="label">{label}</span>
    {loading ? <span className="skeleton" style={{ height: 26, width: '50%' }} aria-hidden /> : <span className="value">{value}</span>}
    {(delta !== undefined && delta !== null) ? <span className={['delta', delta > 0 ? 'up' : delta < 0 ? 'down' : ''].join(' ')}>{delta > 0 ? '▲' : delta < 0 ? '▼' : '•'} {deltaLabel ?? Math.abs(delta)}</span> : target ? <span className="target">{target}</span> : hint ? <span className="delta">{hint}</span> : null}
    {trend && trend.length > 1 && <span className="spark"><Sparkline data={trend} width={88} height={26} /></span>}
  </>);
  if (onClick) return <button className={cls} onClick={onClick} style={{ textAlign: 'left', font: 'inherit', color: 'inherit' }}>{inner}</button>;
  return <div className={cls}>{inner}</div>;
}
