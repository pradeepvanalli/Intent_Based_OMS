import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), intervalMs); return () => clearInterval(t); }, [intervalMs]);
  return now;
}
export function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}
export function useQueryState(key: string, fallback = ''): [string, (v: string) => void] {
  const [sp, setSp] = useSearchParams();
  const value = sp.get(key) ?? fallback;
  const set = useCallback((v: string) => { setSp((prev) => { const n = new URLSearchParams(prev); if (v) n.set(key, v); else n.delete(key); if (key !== 'offset') n.delete('offset'); return n; }, { replace: true }); }, [key, setSp]);
  return [value, set];
}
export function useLocalStorage<T>(key: string, initial: T): [T, (v: T) => void] {
  const [v, setV] = useState<T>(() => { try { const raw = localStorage.getItem(key); return raw ? (JSON.parse(raw) as T) : initial; } catch { return initial; } });
  const set = useCallback((n: T) => { setV(n); try { localStorage.setItem(key, JSON.stringify(n)); } catch { /* ignore */ } }, [key]);
  return [v, set];
}
export function useEscape(onEscape: () => void, active = true) {
  useEffect(() => { if (!active) return; const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onEscape(); }; window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h); }, [onEscape, active]);
}
/** Trap focus within a container and restore on unmount. */
export function useFocusTrap(active: boolean) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!active || !ref.current) return;
    const prev = document.activeElement as HTMLElement | null;
    const el = ref.current;
    const focusables = () => Array.from(el.querySelectorAll<HTMLElement>('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])')).filter((x) => x.offsetParent !== null || x === document.activeElement);
    const first = focusables()[0]; (first ?? el).focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return; const f = focusables(); if (!f.length) return;
      const i = f.indexOf(document.activeElement as HTMLElement);
      if (e.shiftKey && (i <= 0)) { e.preventDefault(); f[f.length - 1].focus(); } else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); }
    };
    el.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden';
    return () => { el.removeEventListener('keydown', onKey); document.body.style.overflow = prevOverflow; prev?.focus?.(); };
  }, [active]);
  return ref;
}
export function useTheme(): [string, (t: string) => void] {
  const [theme, setThemeState] = useState<string>(() => { try { return localStorage.getItem('oms.theme') ?? 'system'; } catch { return 'system'; } });
  const setTheme = useCallback((t: string) => {
    setThemeState(t);
    try { if (t === 'system') localStorage.removeItem('oms.theme'); else localStorage.setItem('oms.theme', t); } catch { /* ignore */ }
    if (t === 'system') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
  }, []);
  return [theme, setTheme];
}
