import { api, idem } from './client';
import type { DecisionRecord, Money, Order } from './types';

/* ---- Playground (showcase) and public tracking ---- */

/** One SKU of the tenant catalogue as `GET /api/v1/catalog` returns it (price, cogs and flags live in `attributes`). */
export interface CatalogItem { sku: string; name: string; attributes: Record<string, unknown>; }
export const catalogPrice = (c: CatalogItem): number => { const p = c.attributes?.price; return typeof p === 'number' ? p : Number(p ?? 0) || 0; };
export const catalogFlag = (c: CatalogItem, key: string): boolean => { const v = c.attributes?.[key]; return v === true || v === 'true'; };

/** `GET /api/v1/decisions/providers` → provider id → available (e.g. `{jev: true, "llm.openai": true, heuristic: true}`). */
export type ProviderAvailability = Record<string, boolean>;

/** Body of `POST /api/v1/orders` (oms-order `CaptureRequest`, see docs/MODULE_NOTES.md). */
export interface CaptureRequestBody {
  channel: string; channelOrderId: string; orderType: string; customerRef: string;
  customer: { ref: string; name: string; email: string; phone?: string; tier: string; ageVerified?: boolean; history: Record<string, unknown> };
  lines: { lineId: string; sku: string; qty: number; unitPrice: Money; attributes?: Record<string, unknown> }[];
  addresses: { type: string; name: string; line1: string; line2?: string; city: string; state: string; zip: string; country: string; addressType?: string }[];
  charges?: { type: string; code?: string; amount: Money; description?: string }[];
  tenders: { type: string; token: string; amount: Money }[];
  notes: { source: string; author?: string; text: string }[];
  intentChoices: string[]; shipMethod: string; currency?: string; extensions?: Record<string, unknown>;
  provenance?: { agentId: string; protocol: string; mandateId?: string; spendLimit?: Money; instructionText?: string; buyerRef?: string } | null;
}

export interface TrackingLink { url: string; token: string; expiresAt: string; }
/** Customer-safe order view behind a signed token (`GET /public/track/{token}`). Never carries costs, nodes or decisions. */
export interface TrackingView {
  orderNumber: string; status: string; statusLabel: string; stage: 'placed' | 'confirmed' | 'shipped' | 'delivered' | 'cancelled' | string; pickup: boolean; placedAt: string;
  promise?: { earliest: string; latest: string; shipMethod?: string } | null;
  items: { name: string; qty: number; status: string }[];
  shipments: { carrier?: string; service?: string; tracking?: string; trackingUrl?: string | null; status: string; shippedAt?: string | null; deliveredAt?: string | null; eta?: string | null; items: string[] }[];
  pickupLocation?: { storeName: string; city?: string; state?: string } | null;
  narrative: string; narrativeGenerated: boolean; nextStep: string; expiresAt: string; generatedAt: string;
}

export const playgroundApi = {
  catalog: () => api.get<CatalogItem[]>('/api/v1/catalog'),
  providers: () => api.get<ProviderAvailability>('/api/v1/decisions/providers', { silent: true }),
  place: (body: CaptureRequestBody) => api.post<Order>('/api/v1/orders', body, { idempotencyKey: idem(), silent: true }),
  decisionsFor: (orderId: string) => api.get<DecisionRecord[]>('/api/v1/decision-records', { query: { subjectType: 'order', subjectId: orderId }, silent: true }),
};

export const trackingApi = {
  /** Operator side (order.read): mint a 30-day link for an order. */
  link: (orderId: string) => api.post<TrackingLink>(`/api/v1/orders/${encodeURIComponent(orderId)}/tracking-link`, {}),
  /** Customer side (no session needed): resolve a token. The token alone identifies tenant and order. */
  view: (token: string) => api.get<TrackingView>(`/public/track/${encodeURIComponent(token)}`, { silent: true }),
};

/** The customer-facing URL for a token on this UI's own origin (the backend's `url` points at the API host). */
export function trackingUrlFor(token: string): string {
  const origin = typeof window !== 'undefined' && window.location ? window.location.origin : '';
  return `${origin}/track/${encodeURIComponent(token)}`;
}
