import type { ProblemDetail } from './types';
import { clearSession, getSession } from '../auth/session';
import { toast } from '../lib/toast';

export const API_BASE: string = import.meta.env.VITE_API_BASE ?? '';
export const MOCK: boolean = import.meta.env.VITE_MOCK === '1' || import.meta.env.VITE_MOCK === 'true';

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;
  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.name = 'ApiError';
    this.status = problem.status;
    this.problem = problem;
  }
  get code(): string | undefined { return this.problem.code ?? this.problem.title; }
  get fields(): Record<string, string> | undefined { return this.problem.fields; }
}

export type Query = Record<string, string | number | boolean | undefined | null | string[]>;
export interface RequestOptions { query?: Query; body?: unknown; headers?: Record<string, string>; idempotencyKey?: string; silent?: boolean; signal?: AbortSignal; }

export function buildQuery(q?: Query): string {
  if (!q) return '';
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(q)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) v.forEach((x) => sp.append(k, x)); else sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

let onUnauthorized: () => void = () => {
  clearSession();
  if (!location.pathname.startsWith('/login')) {
    const next = encodeURIComponent(location.pathname + location.search);
    location.assign(`/login?next=${next}`);
  }
};
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn; }

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const url = `${API_BASE}${path}${buildQuery(opts.query)}`;
  if (MOCK) {
    const { mockRequest } = await import('./mock');
    return mockRequest<T>(method, url, opts.body);
  }
  const { token, principal } = getSession();
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json', ...opts.headers };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  if (principal?.tenantId) headers['X-Tenant-Id'] = principal.tenantId;
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;

  let res: Response;
  try {
    res = await fetch(url, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body), signal: opts.signal });
  } catch (e) {
    const problem: ProblemDetail = { status: 0, title: 'network_error', detail: e instanceof Error ? e.message : 'Network error' };
    if (!opts.silent) toast.danger('Network error', 'The API could not be reached.');
    throw new ApiError(problem);
  }
  if (res.status === 401 && !path.startsWith('/api/v1/auth/login')) {
    onUnauthorized();
  }
  if (!res.ok) {
    let problem: ProblemDetail = { status: res.status, title: res.statusText || `HTTP ${res.status}` };
    const ct = res.headers.get('content-type') ?? '';
    try {
      if (ct.includes('json')) problem = { ...problem, ...(await res.json()) } as ProblemDetail;
      else { const text = await res.text(); if (text) problem.detail = text.slice(0, 500); }
    } catch { /* unparseable body */ }
    const err = new ApiError(problem);
    if (!opts.silent && res.status !== 401) toast.danger(humanTitle(problem), problem.detail);
    throw err;
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

function humanTitle(p: ProblemDetail): string {
  const t = (p.code ?? p.title ?? '').toString();
  if (!t) return `Request failed (${p.status})`;
  return t.replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
}

export const api = {
  get: <T>(path: string, opts?: RequestOptions) => request<T>('GET', path, opts),
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) => request<T>('POST', path, { ...opts, body }),
  put: <T>(path: string, body?: unknown, opts?: RequestOptions) => request<T>('PUT', path, { ...opts, body }),
  del: <T>(path: string, opts?: RequestOptions) => request<T>('DELETE', path, opts),
};

export function idem(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
