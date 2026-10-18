/* Small shared pieces for the learning screens. */
import { useState, type ReactNode } from 'react';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select, Switch, Textarea } from '../../components/Form';
import { IconInfo } from '../../components/Icons';
import { Dialog } from '../../components/Overlay';
import { useTechnical } from '../../lib/technical';
import { isDemo } from './copy';

/** Marks seeded reference data so nobody mistakes it for real activity. */
export function DemoBadge({ by }: { by?: string | null }) {
  if (!isDemo(by)) return null;
  return <Badge tone="outline" title="Seeded reference data for trying the product, not real activity" data-testid="demo-badge">Demo data</Badge>;
}

/** A one-line explanation on hover or focus, instead of a paragraph on the page. */
export function InfoTip({ text, label }: { text: string; label?: string }) {
  return (
    <span className="info-tip" tabIndex={0} title={text} aria-label={label ? `${label}: ${text}` : text} role="note">
      <IconInfo size={13} aria-hidden />
    </span>
  );
}

/** Renders children only while "Show technical details" is on. */
export function Technical({ children }: { children: ReactNode }) {
  const [on] = useTechnical();
  return on ? <>{children}</> : null;
}

export function TechnicalToggle() {
  const [on, setOn] = useTechnical();
  return <Switch label="Show technical details" checked={on} onChange={setOn} />;
}

/** Asks for a reason inside the page (the app never uses window.prompt). */
export function ReasonDialog({ open, title, description, label = 'Reason', confirmLabel, danger, required = true, loading, onClose, onConfirm, codes }: {
  open: boolean; title: string; description?: ReactNode; label?: string; confirmLabel: string; danger?: boolean; required?: boolean; loading?: boolean;
  onClose: () => void; onConfirm: (reason: string, code?: string) => void; codes?: { code: string; label: string }[];
}) {
  const [reason, setReason] = useState('');
  const [code, setCode] = useState('');
  const missing = (required && !reason.trim()) || (!!codes && !code);
  const close = () => { setReason(''); setCode(''); onClose(); };
  return (
    <Dialog open={open} onClose={close} title={title} description={description}
      footer={<><Button onClick={close}>Cancel</Button><Button variant={danger ? 'danger' : 'primary'} disabled={missing} loading={loading} onClick={() => onConfirm(reason.trim(), code || undefined)}>{confirmLabel}</Button></>}>
      {codes && (
        <Select label="Reason code" value={code} onChange={(e) => setCode(e.target.value)} hint="Changes limited to certain postal codes need a stated reason.">
          <option value="">Choose a reason…</option>
          {codes.map((c) => <option key={c.code} value={c.code}>{c.label}</option>)}
        </Select>
      )}
      {(required || !codes) && <Textarea label={label} value={reason} onChange={(e) => setReason(e.target.value)} rows={3} placeholder="One or two sentences; this goes into the audit trail" />}
    </Dialog>
  );
}

/** A small "label: value" figure for summary rows. */
export function Figure({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="mini-stat">
      <span className="xs muted">{label}{hint && <> <InfoTip text={hint} /></>}</span>
      <span className="strong num">{value}</span>
    </div>
  );
}

/** A horizontal bar with a target marker, e.g. coverage against its floor. */
export function Meter({ value, target, label }: { value: number; target?: number; label: string }) {
  const pct = Math.max(0, Math.min(1, value));
  const tone = target === undefined ? '' : value >= target ? 'ok' : 'danger';
  return (
    <div className={`bar ${tone}`} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={1} aria-valuenow={Number(pct.toFixed(3))}>
      <span style={{ width: `${pct * 100}%` }} />
      {target !== undefined && <span className="marker" style={{ left: `${target * 100}%` }} aria-hidden />}
    </div>
  );
}
