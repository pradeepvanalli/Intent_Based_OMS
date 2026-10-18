import { useCallback, useSyncExternalStore } from 'react';

/**
 * "Show technical details": off by default, remembered per browser, shared by every screen that has the toggle
 * (the Learning console and the Why drawer), so switching it in one place updates the other at once.
 */
const KEY = 'oms.ui.technical';
const EVENT = 'oms:technical-changed';

function read(): boolean {
  try { return localStorage.getItem(KEY) === '1'; } catch { return false; }
}
function subscribe(cb: () => void): () => void {
  window.addEventListener(EVENT, cb);
  window.addEventListener('storage', cb);
  return () => { window.removeEventListener(EVENT, cb); window.removeEventListener('storage', cb); };
}

export function useTechnical(): [boolean, (v: boolean) => void] {
  const value = useSyncExternalStore(subscribe, read, () => false);
  const set = useCallback((v: boolean) => {
    try { localStorage.setItem(KEY, v ? '1' : '0'); } catch { /* storage unavailable */ }
    window.dispatchEvent(new Event(EVENT));
  }, []);
  return [value, set];
}
