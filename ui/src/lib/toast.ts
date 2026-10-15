import { useSyncExternalStore } from 'react';

export type ToastTone = 'info' | 'ok' | 'warn' | 'danger';
export interface Toast { id: number; title: string; message?: string; tone: ToastTone; ttl: number; }

let toasts: Toast[] = [];
let seq = 1;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

export function pushToast(t: Omit<Toast, 'id' | 'ttl'> & { ttl?: number }): number {
  const id = seq++;
  const toast: Toast = { id, ttl: t.ttl ?? (t.tone === 'danger' ? 8000 : 4500), ...t };
  toasts = [...toasts, toast].slice(-5);
  emit();
  if (toast.ttl > 0) setTimeout(() => dismissToast(id), toast.ttl);
  return id;
}
export const toast = {
  info: (title: string, message?: string) => pushToast({ title, message, tone: 'info' }),
  ok: (title: string, message?: string) => pushToast({ title, message, tone: 'ok' }),
  warn: (title: string, message?: string) => pushToast({ title, message, tone: 'warn' }),
  danger: (title: string, message?: string) => pushToast({ title, message, tone: 'danger' }),
};
export function dismissToast(id: number) { toasts = toasts.filter((t) => t.id !== id); emit(); }
export function useToasts(): Toast[] {
  return useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l); }, () => toasts, () => toasts);
}
