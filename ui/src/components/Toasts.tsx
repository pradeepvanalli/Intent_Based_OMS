import { dismissToast, useToasts } from '../lib/toast';
import { IconClose } from './Icons';

export function Toasts() {
  const toasts = useToasts();
  if (!toasts.length) return null;
  return (
    <div className="toasts" aria-live="polite" aria-relevant="additions">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.tone}`} role={t.tone === 'danger' ? 'alert' : 'status'}>
          <div className="grow"><div className="title">{t.title}</div>{t.message && <div className="msg">{t.message}</div>}</div>
          <button className="close" aria-label="Dismiss notification" onClick={() => dismissToast(t.id)}><IconClose size={14} /></button>
        </div>
      ))}
    </div>
  );
}
