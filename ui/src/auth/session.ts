import { useSyncExternalStore } from 'react';
import type { Principal } from '../api/types';

const TOKEN_KEY = 'oms.token';
const PRINCIPAL_KEY = 'oms.principal';

export interface Session { token: string | null; principal: Principal | null; }

function read(): Session {
  try {
    const token = localStorage.getItem(TOKEN_KEY);
    const raw = localStorage.getItem(PRINCIPAL_KEY);
    return { token, principal: raw ? (JSON.parse(raw) as Principal) : null };
  } catch { return { token: null, principal: null }; }
}

let session: Session = read();
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

export function getSession(): Session { return session; }
export function setSession(token: string, principal: Principal) {
  try { localStorage.setItem(TOKEN_KEY, token); localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(principal)); } catch { /* storage unavailable */ }
  session = { token, principal };
  emit();
}
export function clearSession() {
  try { localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(PRINCIPAL_KEY); } catch { /* storage unavailable */ }
  session = { token: null, principal: null };
  emit();
}
export function useSession(): Session {
  return useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l); }, () => session, () => session);
}
export function hasPermission(p: Principal | null, permission: string): boolean {
  if (!p) return false;
  const perms = p.permissions ?? [];
  if (perms.includes('*') || perms.includes(permission)) return true;
  const dot = permission.indexOf('.');
  return dot > 0 && perms.includes(permission.slice(0, dot) + '.*');
}
/** True when the principal holds at least one of the permissions. */
export function hasAnyPermission(p: Principal | null, permissions: string[]): boolean {
  return permissions.some((x) => hasPermission(p, x));
}
