import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes, useState, type KeyboardEvent } from 'react';
import { IconClose } from './Icons';

interface FieldWrap { label?: ReactNode; hint?: ReactNode; error?: ReactNode; className?: string; }
export function Field({ label, hint, error, className, children, id }: FieldWrap & { children: ReactNode; id?: string }) {
  return <div className={['field', className].filter(Boolean).join(' ')}>{label && <label htmlFor={id}>{label}</label>}{children}{hint && !error && <span className="hint">{hint}</span>}{error && <span className="error" role="alert">{error}</span>}</div>;
}
export function Input({ label, hint, error, className, mono, sm, ...rest }: FieldWrap & InputHTMLAttributes<HTMLInputElement> & { mono?: boolean; sm?: boolean }) {
  const id = useId();
  return <Field label={label} hint={hint} error={error} className={className} id={id}><input id={id} className={['input', mono && 'mono', sm && 'input-sm'].filter(Boolean).join(' ')} aria-invalid={error ? true : undefined} {...rest} /></Field>;
}
export function Select({ label, hint, error, className, children, sm, ...rest }: FieldWrap & SelectHTMLAttributes<HTMLSelectElement> & { sm?: boolean }) {
  const id = useId();
  return <Field label={label} hint={hint} error={error} className={className} id={id}><select id={id} className={['select', sm && 'select-sm'].filter(Boolean).join(' ')} aria-invalid={error ? true : undefined} {...rest}>{children}</select></Field>;
}
export function Textarea({ label, hint, error, className, mono, ...rest }: FieldWrap & TextareaHTMLAttributes<HTMLTextAreaElement> & { mono?: boolean }) {
  const id = useId();
  return <Field label={label} hint={hint} error={error} className={className} id={id}><textarea id={id} className={['textarea', mono && 'mono'].filter(Boolean).join(' ')} aria-invalid={error ? true : undefined} {...rest} /></Field>;
}
export function Checkbox({ label, ...rest }: { label: ReactNode } & InputHTMLAttributes<HTMLInputElement>) {
  return <label className="checkbox"><input type="checkbox" {...rest} /><span>{label}</span></label>;
}
export function Switch({ label, checked, onChange, disabled }: { label: ReactNode; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return <label className="switch"><input type="checkbox" role="switch" aria-checked={checked} checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} /><span className="track" aria-hidden /><span>{label}</span></label>;
}
export function ChipInput({ values, onChange, placeholder, label, hint, validate }: { values: string[]; onChange: (v: string[]) => void; placeholder?: string; label?: ReactNode; hint?: ReactNode; validate?: (v: string) => string | null }) {
  const [draft, setDraft] = useState(''); const [err, setErr] = useState<string | null>(null); const id = useId();
  const add = () => { const v = draft.trim(); if (!v) return; const e = validate?.(v) ?? null; if (e) { setErr(e); return; } if (!values.includes(v)) onChange([...values, v]); setDraft(''); setErr(null); };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add(); } if (e.key === 'Backspace' && !draft && values.length) onChange(values.slice(0, -1)); };
  return (
    <Field label={label} hint={hint} error={err} id={id}>
      <div className="chip-input" onClick={(e) => (e.currentTarget.querySelector('input') as HTMLInputElement | null)?.focus()}>
        {values.map((v) => <span key={v} className="chip mono">{v}<button type="button" aria-label={`Remove ${v}`} onClick={() => onChange(values.filter((x) => x !== v))}><IconClose size={11} /></button></span>)}
        <input id={id} value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey} onBlur={add} placeholder={placeholder ?? 'Add and press Enter'} aria-label={typeof label === 'string' ? label : 'Add value'} />
      </div>
    </Field>
  );
}
export function Range({ label, value, min = 0, max = 1, step = 0.01, onChange, format, hint }: { label: ReactNode; value: number; min?: number; max?: number; step?: number; onChange: (v: number) => void; format?: (v: number) => string; hint?: ReactNode }) {
  const id = useId();
  return <div className="field"><div className="row-between"><label htmlFor={id}>{label}</label><span className="mono small">{format ? format(value) : value}</span></div><input id={id} type="range" className="range" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} aria-valuetext={format ? format(value) : String(value)} />{hint && <span className="hint">{hint}</span>}</div>;
}
