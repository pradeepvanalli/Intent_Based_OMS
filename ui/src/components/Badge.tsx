import type { HTMLAttributes, ReactNode } from 'react';
import { humanize, layerLabel, statusTone } from '../lib/format';

type Tone = 'neutral' | 'ok' | 'warn' | 'danger' | 'info' | 'accent' | 'ai' | 'outline' | 'created' | 'active' | 'terminal' | 'cancelled' | 'risk' | 'hold';
export function Badge({ tone = 'neutral', dot, mono, lg, className, children, ...rest }: { tone?: Tone | string; dot?: boolean; mono?: boolean; lg?: boolean; children: ReactNode } & HTMLAttributes<HTMLSpanElement>) {
  return <span className={['badge', `badge-${tone}`, mono && 'badge-mono', lg && 'badge-lg', className].filter(Boolean).join(' ')} {...rest}>{dot && <span className="dot" aria-hidden />}{children}</span>;
}
export function StatusBadge({ status, lg }: { status?: string | null; lg?: boolean }) {
  if (!status) return <Badge tone="neutral">—</Badge>;
  return <Badge tone={statusTone(status)} dot lg={lg} aria-label={`status ${humanize(status)}`}>{humanize(status)}</Badge>;
}
export function TierBadge({ tier, title }: { tier?: string | null; title?: boolean }) {
  if (!tier) return null;
  const names: Record<string, string> = { T0: 'Informational', T1: 'Reversible operational', T2: 'Customer-impacting', T3: 'Money-moving / irreversible' };
  return <Badge tone={tier} mono title={names[tier]} aria-label={`tier ${tier} ${names[tier] ?? ''}`}>{tier}{title && <span style={{ fontFamily: 'var(--font-sans)', fontWeight: 500 }}> · {names[tier]}</span>}</Badge>;
}
export function BandBadge({ band, lg }: { band?: string | null; lg?: boolean }) {
  if (!band) return null;
  return <Badge tone={band} lg={lg} aria-label={`band ${band.toLowerCase()}`}>{band.toLowerCase()}</Badge>;
}
export function LayerBadge({ layer, full }: { layer?: string | null; full?: boolean }) {
  if (!layer) return null;
  const short = layerLabel(layer);
  const names: Record<string, string> = { L0: 'Rules', L1: 'Jev', L2: 'LLM', L3: 'Human', DEF: 'Default' };
  return <Badge tone={short} mono title={`${short} ${names[short] ?? ''}`} aria-label={`layer ${short} ${names[short] ?? ''}`}>{short}{(full || short === 'L1' || short === 'L2' || short === 'L3') && <span style={{ fontFamily: 'var(--font-sans)', fontWeight: 500 }}>{names[short] ? ` ${names[short]}` : ''}</span>}</Badge>;
}
export function SeverityBadge({ severity }: { severity?: string | null }) {
  const tone = severity === 'critical' ? 'danger' : severity === 'high' ? 'risk' : severity === 'medium' ? 'warn' : 'neutral';
  return <Badge tone={tone} dot>{severity ?? '—'}</Badge>;
}
export function TrustBadge({ tier }: { tier: number }) {
  const labels = ['unknown', 'verified', 'trusted partner', 'internal'];
  const tones = ['cancelled', 'info', 'ok', 'accent'];
  return <Badge tone={tones[tier] ?? 'neutral'}>trust {tier} · {labels[tier] ?? '—'}</Badge>;
}
