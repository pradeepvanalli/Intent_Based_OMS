import { useRef, type ReactNode, type KeyboardEvent } from 'react';

export interface TabDef { id: string; label: ReactNode; count?: number; }
export function Tabs({ tabs, active, onChange, pills, ariaLabel }: { tabs: TabDef[]; active: string; onChange: (id: string) => void; pills?: boolean; ariaLabel?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const i = tabs.findIndex((t) => t.id === active); if (i < 0) return;
    let n = i; if (e.key === 'ArrowRight') n = (i + 1) % tabs.length; else if (e.key === 'ArrowLeft') n = (i - 1 + tabs.length) % tabs.length; else if (e.key === 'Home') n = 0; else if (e.key === 'End') n = tabs.length - 1; else return;
    e.preventDefault(); onChange(tabs[n].id); (ref.current?.children[n] as HTMLElement | undefined)?.focus();
  };
  return (
    <div className={['tabs', pills && 'pills'].filter(Boolean).join(' ')} role="tablist" aria-label={ariaLabel} ref={ref} onKeyDown={onKey}>
      {tabs.map((t) => <button key={t.id} role="tab" className="tab" aria-selected={t.id === active} tabIndex={t.id === active ? 0 : -1} id={`tab-${t.id}`} aria-controls={`panel-${t.id}`} onClick={() => onChange(t.id)}>{t.label}{t.count !== undefined && <span className="count">{t.count}</span>}</button>)}
    </div>
  );
}
export function TabPanel({ id, active, children }: { id: string; active: string; children: ReactNode }) {
  if (id !== active) return null;
  return <div role="tabpanel" id={`panel-${id}`} aria-labelledby={`tab-${id}`} className="tab-panel">{children}</div>;
}
