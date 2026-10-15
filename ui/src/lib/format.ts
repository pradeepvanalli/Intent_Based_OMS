import type { Money } from '../api/types';

const moneyFmt = new Map<string, Intl.NumberFormat>();
export function fmtMoney(m?: Money | null, opts: { compact?: boolean } = {}): string {
  if (!m) return '—';
  const key = `${m.currency}|${opts.compact ? 'c' : 'f'}`;
  let f = moneyFmt.get(key);
  if (!f) { f = new Intl.NumberFormat('en-US', { style: 'currency', currency: m.currency || 'USD', notation: opts.compact ? 'compact' : 'standard', maximumFractionDigits: 2 }); moneyFmt.set(key, f); }
  return f.format(m.amount);
}
export const fmtUsd = (n?: number | null, digits = 2): string => (n === undefined || n === null || Number.isNaN(n) ? '—' : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: digits, maximumFractionDigits: Math.max(digits, 4) }).format(n));
export const fmtPct = (n?: number | null, digits = 1): string => (n === undefined || n === null || Number.isNaN(n) ? '—' : `${(n * 100).toFixed(digits)}%`);
export const fmtNum = (n?: number | null, digits = 0): string => (n === undefined || n === null || Number.isNaN(n) ? '—' : new Intl.NumberFormat('en-US', { maximumFractionDigits: digits }).format(n));
export const fmtConf = (n?: number | null): string => (n === undefined || n === null ? '—' : n.toFixed(2));

export function fmtDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso); if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}
export function fmtDate(iso?: string | null): string {
  if (!iso) return '—';
  const d = iso.length <= 10 ? new Date(iso + 'T00:00:00') : new Date(iso); if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: d.getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
}
export function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso); if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' });
}
export function relTime(iso?: string | null, now = Date.now()): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime(); if (Number.isNaN(t)) return iso;
  const diff = t - now; const abs = Math.abs(diff); const sfx = diff < 0 ? ' ago' : '';
  const pfx = diff >= 0 ? 'in ' : '';
  if (abs < 45_000) return diff < 0 ? 'just now' : 'now';
  if (abs < 3_600_000) return `${pfx}${Math.round(abs / 60_000)}m${sfx}`;
  if (abs < 86_400_000) return `${pfx}${Math.round(abs / 3_600_000)}h${sfx}`;
  if (abs < 30 * 86_400_000) return `${pfx}${Math.round(abs / 86_400_000)}d${sfx}`;
  return fmtDate(iso);
}
export function fmtDuration(ms: number): string {
  const abs = Math.abs(ms); const sign = ms < 0 ? '-' : '';
  const h = Math.floor(abs / 3_600_000); const m = Math.floor((abs % 3_600_000) / 60_000); const s = Math.floor((abs % 60_000) / 1000);
  if (h >= 48) return `${sign}${Math.floor(h / 24)}d ${h % 24}h`;
  if (h > 0) return `${sign}${h}h ${String(m).padStart(2, '0')}m`;
  return `${sign}${m}:${String(s).padStart(2, '0')}`;
}
export function fmtMinutes(min: number): string { return fmtDuration(min * 60_000); }
/** ISO-8601 duration (PT4H, P3D, PT30M) → human text */
export function fmtIsoDuration(iso?: string | null): string {
  if (!iso) return '—';
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(iso);
  if (!m) return iso;
  const parts: string[] = []; if (m[1]) parts.push(`${m[1]}d`); if (m[2]) parts.push(`${m[2]}h`); if (m[3]) parts.push(`${m[3]}m`); if (m[4]) parts.push(`${m[4]}s`);
  return parts.join(' ') || iso;
}
export const titleCase = (s?: string | null): string => (s ?? '').replace(/[_.-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
export const humanize = (s?: string | null): string => (s ?? '').replace(/[_]+/g, ' ');
export const shortId = (id?: string | null, n = 10): string => (!id ? '—' : id.length > n + 2 ? `${id.slice(0, n)}…` : id);
export const layerLabel = (l?: string | null): string => ({ L0_RULE: 'L0', L1_SYSTEM_ONE: 'L1', L2_LLM: 'L2', L3_HUMAN: 'L3', DEFAULT: 'DEF' } as Record<string, string>)[l ?? ''] ?? (l ?? '—');
export const layerName = (l?: string | null): string => ({ L0_RULE: 'L0 · Rules', L1_SYSTEM_ONE: 'L1 · Jev', L2_LLM: 'L2 · LLM', L3_HUMAN: 'L3 · Human', DEFAULT: 'Default' } as Record<string, string>)[l ?? ''] ?? (l ?? '—');
export function statusTone(status?: string | null): 'created' | 'active' | 'terminal' | 'cancelled' | 'risk' | 'hold' | 'neutral' {
  const s = (status ?? '').toLowerCase();
  if (['created', 'requested', 'queued', 'open', 'draft'].includes(s)) return 'created';
  if (['delivered', 'picked_up', 'completed', 'refunded', 'resolved', 'closed', 'closed_keep_it', 'live', 'ok', 'converted', 'captured', 'issued', 'active'].includes(s)) return 'terminal';
  if (['cancelled', 'canceled', 'expired', 'retired', 'denied', 'failed', 'rolled_back', 'void', 'revoked'].includes(s)) return 'cancelled';
  if (['backordered', 'stuck', 'at_risk', 'compensating', 'blocked', 'critical', 'high'].includes(s)) return 'risk';
  if (['held', 'hold', 'on_hold', 'waiting_task', 'pending_approval', 'eligibility_review', 'awaiting_confirmation'].includes(s)) return 'hold';
  if (!s) return 'neutral';
  return 'active';
}
export function clamp(n: number, lo: number, hi: number): number { return Math.min(hi, Math.max(lo, n)); }
export function safeJson(v: unknown, indent = 2): string { try { return JSON.stringify(v, null, indent) ?? ''; } catch { return String(v); } }
export function valueLabel(v: unknown): string { if (v === null || v === undefined) return '—'; if (typeof v === 'boolean') return v ? 'true' : 'false'; if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(2); if (typeof v === 'object') return safeJson(v, 0).slice(0, 60); return String(v); }
