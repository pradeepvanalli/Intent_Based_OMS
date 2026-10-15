import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// Node 22+ exposes an unset `localStorage` global that vitest's jsdom environment does not replace; provide an in-memory Storage.
class MemoryStorage implements Storage {
  private m = new Map<string, string>();
  get length() { return this.m.size; }
  clear() { this.m.clear(); }
  getItem(k: string) { return this.m.has(k) ? this.m.get(k)! : null; }
  key(i: number) { return [...this.m.keys()][i] ?? null; }
  removeItem(k: string) { this.m.delete(k); }
  setItem(k: string, v: string) { this.m.set(k, String(v)); }
}
const ls = globalThis.localStorage as Storage | undefined;
if (!ls || typeof ls.getItem !== 'function') {
  Object.defineProperty(globalThis, 'localStorage', { value: new MemoryStorage(), configurable: true, writable: true });
}
const ss = globalThis.sessionStorage as Storage | undefined;
if (!ss || typeof ss.getItem !== 'function') {
  Object.defineProperty(globalThis, 'sessionStorage', { value: new MemoryStorage(), configurable: true, writable: true });
}

afterEach(() => { cleanup(); localStorage.clear(); });

if (!window.matchMedia) {
  window.matchMedia = ((query: string) => ({ matches: false, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn() })) as unknown as typeof window.matchMedia;
}
if (typeof globalThis.structuredClone !== 'function') {
  globalThis.structuredClone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T;
}
