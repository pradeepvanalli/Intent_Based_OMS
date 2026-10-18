import type { ReactNode } from 'react';
import { IconInbox } from './Icons';
import { ApiError } from '../api/client';
import { friendlyError } from '../api/friendly';

export function Loading({ label = 'Loading…', inline }: { label?: string; inline?: boolean }) {
  return <div className={inline ? 'row muted small' : 'loading'} role="status" aria-live="polite"><span className="spinner" aria-hidden /> {label}</div>;
}
export function Skeleton({ lines = 3, h = 14 }: { lines?: number; h?: number }) {
  return <div className="col" aria-hidden>{Array.from({ length: lines }, (_, i) => <div key={i} className="skeleton" style={{ height: h, width: `${90 - (i % 3) * 18}%` }} />)}</div>;
}
export function EmptyState({ title, hint, action, icon }: { title: string; hint?: ReactNode; action?: ReactNode; icon?: ReactNode }) {
  return <div className="empty" role="status"><div className="icon">{icon ?? <IconInbox size={18} />}</div><div className="title">{title}</div>{hint && <div className="small">{hint}</div>}{action && <div className="mt2">{action}</div>}</div>;
}
/** A failed load, in plain words. The raw server message stays available under "Technical details". */
export function ErrorState({ error, retry, title }: { error: unknown; retry?: () => void; title?: string }) {
  const f = error instanceof ApiError
    ? friendlyError(error.problem)
    : { title: 'Something went wrong', message: 'Try again in a moment.', technical: (error as { message?: string })?.message ?? String(error) };
  return (
    <div className="error-box" role="alert">
      <div className="title">{title ?? f.title}</div>
      <div className="small">{f.message}</div>
      {f.technical && <details className="xs muted"><summary>Technical details</summary><code className="mono" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{f.technical}</code></details>}
      {retry && <div><button className="btn btn-sm" onClick={retry}>Try again</button></div>}
    </div>
  );
}
export function Section({ title, actions, children, className, tight }: { title: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; tight?: boolean }) {
  return <section className={['card', className].filter(Boolean).join(' ')}><div className="card-head"><h2>{title}</h2>{actions && <div className="row">{actions}</div>}</div><div className={['card-body', tight && 'tight'].filter(Boolean).join(' ')}>{children}</div></section>;
}
