import { useCallback, useId, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useEscape, useFocusTrap } from '../lib/hooks';
import { IconClose } from './Icons';
import { Button } from './Button';

export function Drawer({ open, onClose, title, subtitle, children, footer, width }: { open: boolean; onClose: () => void; title: ReactNode; subtitle?: ReactNode; children: ReactNode; footer?: ReactNode; width?: number }) {
  const ref = useFocusTrap(open); const id = useId();
  const close = useCallback(() => onClose(), [onClose]);
  useEscape(close, open);
  if (!open) return null;
  return createPortal(
    <div className="drawer-root">
      <div className="drawer-overlay" onClick={close} aria-hidden />
      <div className="drawer" role="dialog" aria-modal="true" aria-labelledby={id} ref={ref} tabIndex={-1} style={width ? ({ '--drawer-w': `${width}px` } as React.CSSProperties) : undefined}>
        <div className="drawer-head">
          <div className="grow"><h2 id={id}>{title}</h2>{subtitle && <div className="muted small mt2">{subtitle}</div>}</div>
          <Button variant="ghost" iconOnly icon={<IconClose />} onClick={close}>Close</Button>
        </div>
        <div className="drawer-body">{children}</div>
        {footer && <div className="drawer-foot">{footer}</div>}
      </div>
    </div>, document.body);
}

export function Dialog({ open, onClose, title, children, footer, width, description }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; footer?: ReactNode; width?: number; description?: ReactNode }) {
  const ref = useFocusTrap(open); const id = useId();
  const close = useCallback(() => onClose(), [onClose]);
  useEscape(close, open);
  if (!open) return null;
  return createPortal(
    <div className="dialog-root">
      <div className="drawer-overlay" onClick={close} aria-hidden />
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={id} ref={ref} tabIndex={-1} style={width ? ({ '--dialog-w': `${width}px` } as React.CSSProperties) : undefined}>
        <div className="dialog-head"><div className="grow"><h2 id={id}>{title}</h2>{description && <div className="muted small mt2">{description}</div>}</div><Button variant="ghost" iconOnly icon={<IconClose />} onClick={close}>Close</Button></div>
        <div className="dialog-body">{children}</div>
        {footer && <div className="dialog-foot">{footer}</div>}
      </div>
    </div>, document.body);
}

export function ConfirmDialog({ open, onClose, onConfirm, title, body, confirmLabel = 'Confirm', danger, loading, requireText }: { open: boolean; onClose: () => void; onConfirm: () => void; title: string; body?: ReactNode; confirmLabel?: string; danger?: boolean; loading?: boolean; requireText?: string }) {
  return (
    <Dialog open={open} onClose={onClose} title={title} footer={<><Button onClick={onClose}>Cancel</Button><Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} loading={loading} form={requireText ? 'confirm-form' : undefined}>{confirmLabel}</Button></>}>
      {body}
      {requireText && <ConfirmTyping expected={requireText} />}
    </Dialog>
  );
}
function ConfirmTyping({ expected }: { expected: string }) {
  return <label className="field"><span className="field-label">Type <code>{expected}</code> to confirm</span><input className="input mono" required pattern={expected.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} aria-label={`Type ${expected} to confirm`} /></label>;
}
